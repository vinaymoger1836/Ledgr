package com.ledgr.app.ui.reconcile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.data.statement.CommitResult
import com.ledgr.app.data.statement.DraftView
import com.ledgr.app.data.statement.ReconcileRepository
import com.ledgr.app.domain.statement.AmountStyle
import com.ledgr.app.domain.statement.ColumnMap
import com.ledgr.app.domain.statement.ColumnMaps
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.PdfLine
import com.ledgr.app.domain.statement.StatementField
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One parsed line shown in the mapping preview (paise; 0 means "no amount on this side"). */
data class PreviewLine(val date: LocalDate, val description: String, val debit: Long, val credit: Long, val balance: Long?)

data class MappingUiState(
    val loading: Boolean = true,
    /** The picked file is gone (the app was restarted): go back and pick it again. */
    val lost: Boolean = false,
    val fileName: String = "",
    val type: FileType? = null,
    /** Header texts by column index. */
    val headerCells: List<String> = emptyList(),
    val choices: Map<StatementField, Int> = emptyMap(),
    val style: AmountStyle = AmountStyle.DEBIT_CREDIT_COLUMNS,
    val dateFormat: String? = null,
    val dateFormats: List<String> = emptyList(),
    val dateExample: String? = null,
    val preview: List<PreviewLine> = emptyList(),
    /** When the map cannot yet read lines: the first rows as plain cells. */
    val rawPreview: List<List<String>> = emptyList(),
    val missing: List<StatementField> = emptyList(),
    val warningCount: Int = 0,
    val lineCount: Int = 0,
    val pdfLines: List<PdfLine> = emptyList(),
    val dividers: List<Int> = emptyList(),
    val remember: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = !loading && !saving && missing.isEmpty() && dateFormat != null && lineCount > 0
}

sealed interface MappingIntent {
    data class SetField(val field: StatementField, val column: Int?) : MappingIntent
    data class SetStyle(val style: AmountStyle) : MappingIntent
    data class SetDateFormat(val pattern: String) : MappingIntent
    data class SetDividers(val dividers: List<Int>) : MappingIntent
    data object ResetDividers : MappingIntent
    data object ToggleRemember : MappingIntent
    data object Save : MappingIntent
    data object Cancel : MappingIntent
    data object DismissError : MappingIntent
}

sealed interface MappingEvent {
    data class Saved(val importId: Long) : MappingEvent
    data object Closed : MappingEvent
}

/**
 * The column-map screen (docs/06 §1.6): shown once per bank and header layout. Starts from the automatic guess, shows five
 * parsed lines so a wrong column is visible at once, and for a PDF lets the dividers be dragged. The map is saved on confirm.
 */
@HiltViewModel
class MappingViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: ReconcileRepository,
    private val rulesLoader: RulesLoader,
) : ViewModel() {
    private val draftId: Long = saved.get<Long>(ARG_DRAFT) ?: -1L
    private val _state = MutableStateFlow(MappingUiState())
    val state: StateFlow<MappingUiState> = _state.asStateFlow()
    private val events = Channel<MappingEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private var autoDividers: List<Int> = emptyList()
    private var job: Job? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val view = repo.analyse(draftId)
        if (view == null) {
            _state.update { it.copy(loading = false, lost = true) }
            return
        }
        val profiles = rulesLoader.bundle().statementProfiles
        val guess = view.analysis.guess
        val map = view.analysis.map
        val fields = map?.fields?.mapNotNull { (k, v) -> StatementField.ofKey(k)?.let { it to v } }?.toMap()
            ?: guess?.fields.orEmpty()
        autoDividers = view.analysis.dividers.orEmpty()
        _state.update {
            it.copy(
                fileName = view.fileName,
                type = view.type,
                style = map?.amountStyle ?: guess?.amountStyle ?: AmountStyle.DEBIT_CREDIT_COLUMNS,
                choices = fields,
                dateFormat = map?.dateFormat ?: guess?.dateFormat ?: profiles.dateFormatsFor(view.bankId).firstOrNull(),
                dateFormats = profiles.dateFormatsFor(view.bankId),
                pdfLines = view.pdfLines,
                dividers = autoDividers,
            )
        }
        apply(view)
    }

    fun onIntent(intent: MappingIntent) {
        when (intent) {
            is MappingIntent.SetField -> {
                _state.update { s -> s.copy(choices = if (intent.column == null) s.choices - intent.field else s.choices + (intent.field to intent.column)) }
                recompute()
            }
            is MappingIntent.SetStyle -> {
                _state.update { it.copy(style = intent.style) }
                recompute()
            }
            is MappingIntent.SetDateFormat -> {
                _state.update { it.copy(dateFormat = intent.pattern) }
                recompute()
            }
            is MappingIntent.SetDividers -> {
                _state.update { it.copy(dividers = intent.dividers) }
                recompute()
            }
            MappingIntent.ResetDividers -> {
                _state.update { it.copy(dividers = autoDividers) }
                recompute()
            }
            MappingIntent.ToggleRemember -> _state.update { it.copy(remember = !it.remember) }
            MappingIntent.Save -> save()
            MappingIntent.Cancel -> {
                repo.cancel(draftId)
                viewModelScope.launch { events.send(MappingEvent.Closed) }
            }
            MappingIntent.DismissError -> _state.update { it.copy(error = null) }
        }
    }

    private fun currentMap(s: MappingUiState): ColumnMap = ColumnMap(
        fields = s.choices.entries.associate { (f, c) -> f.key to c },
        dateFormat = s.dateFormat ?: s.dateFormats.firstOrNull().orEmpty(),
        amountStyle = s.style,
        pdfColumns = if (s.type == FileType.PDF) s.dividers else null,
    )

    private fun recompute() {
        job?.cancel()
        job = viewModelScope.launch {
            val view = repo.analyse(draftId, currentMap(_state.value)) ?: return@launch
            apply(view)
        }
    }

    /** Fold an analysis into the state: header texts, the preview and what is still missing. */
    private fun apply(view: DraftView) {
        val a = view.analysis
        val s = _state.value
        val header = a.header
        val statement = a.statement
        val dateColumn = s.choices[StatementField.DATE]
        val example = if (header != null && dateColumn != null) {
            a.grid.rows.drop(header.position + 1).firstNotNullOfOrNull { it.cells.getOrNull(dateColumn)?.takeIf { c -> c.isNotBlank() } }
        } else {
            null
        }
        _state.update {
            it.copy(
                loading = false,
                headerCells = header?.cells.orEmpty(),
                missing = ColumnMaps.missing(it.choices.keys, it.style),
                preview = statement?.lines?.take(PREVIEW_ROWS)?.map { l -> PreviewLine(l.date, l.description, l.debit, l.credit, l.balance) }.orEmpty(),
                rawPreview = if (statement == null || statement.lines.isEmpty()) {
                    header?.let { h -> a.grid.rows.drop(h.position + 1).take(PREVIEW_ROWS).map { r -> r.cells } }.orEmpty()
                } else {
                    emptyList()
                },
                warningCount = statement?.warnings?.size ?: 0,
                lineCount = statement?.lines?.size ?: 0,
                dateExample = example,
                pdfLines = view.pdfLines,
            )
        }
    }

    private fun save() {
        val s = _state.value
        if (!s.canSave) return
        viewModelScope.launch {
            _state.update { it.copy(saving = true) }
            when (val r = repo.commit(draftId, currentMap(s), s.remember)) {
                is CommitResult.Done -> events.send(MappingEvent.Saved(r.importId))
                is CommitResult.Failed -> _state.update { it.copy(saving = false, error = r.code) }
            }
        }
    }

    /** Leaving the screen any way at all (system Back included) throws the picked file's copy away; a saved import already did. */
    override fun onCleared() {
        repo.cancel(draftId)
    }

    companion object {
        const val ARG_DRAFT = "draftId"
        private const val PREVIEW_ROWS = 5
    }
}
