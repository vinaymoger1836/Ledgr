package com.ledgr.app.ui.reconcile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.StatementImportEntity
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.statement.CommitResult
import com.ledgr.app.data.statement.OpenResult
import com.ledgr.app.data.statement.ReconcileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An account the person can import for, shown as a chip. */
data class ImportAccount(val id: Long, val label: String, val color: Int)

/** One entry of the history list. */
data class HistoryRow(
    val id: Long,
    val accountLabel: String,
    val accountColor: Int,
    val fileName: String,
    val periodFrom: String,
    val periodTo: String,
    val lineCount: Int,
    val matchRateBp: Int,
)

data class PasswordPrompt(val draftId: Long, val wrong: Boolean)

data class ReconcileUiState(
    val accounts: List<ImportAccount> = emptyList(),
    val selectedAccountId: Long? = null,
    val history: List<HistoryRow> = emptyList(),
    val busy: Boolean = false,
    val password: PasswordPrompt? = null,
    /** An error code for [errorText]. */
    val error: String? = null,
    val pendingDelete: Long? = null,
) {
    val canImport: Boolean get() = selectedAccountId != null && !busy
}

sealed interface ReconcileIntent {
    data class SelectAccount(val id: Long) : ReconcileIntent
    data class FilePicked(val uri: Uri) : ReconcileIntent
    data class SubmitPassword(val password: String) : ReconcileIntent
    data object CancelPassword : ReconcileIntent
    data object DismissError : ReconcileIntent
    data class AskDelete(val importId: Long) : ReconcileIntent
    data object CancelDelete : ReconcileIntent
    data class ConfirmDelete(val alsoDeleteAdded: Boolean) : ReconcileIntent
    data class Rerun(val importId: Long) : ReconcileIntent
}

sealed interface ReconcileEvent {
    data class OpenMapping(val draftId: Long) : ReconcileEvent
    data class OpenResults(val importId: Long) : ReconcileEvent
}

/** The Reconcile tab's start state (docs/08 §2.3): account chips, the import card and the history. */
@HiltViewModel
class ReconcileViewModel @Inject constructor(
    private val repo: ReconcileRepository,
    accounts: AccountRepository,
) : ViewModel() {

    private val local = MutableStateFlow(ReconcileUiState())
    private val events = Channel<ReconcileEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<ReconcileUiState> = combine(local, accounts.accounts, repo.observeHistory()) { s, accs, history ->
        val active = accs.filter { it.active }
        val byId = accs.associateBy { it.id }
        val selected = s.selectedAccountId?.takeIf { id -> active.any { it.id == id } } ?: active.singleOrNull()?.id
        s.copy(
            accounts = active.map { ImportAccount(it.id, "${it.nickname} ••${it.last4}", it.color) },
            selectedAccountId = selected,
            history = history.map { h -> historyRow(h, byId[h.accountId]?.let { "${it.nickname} ••${it.last4}" }.orEmpty(), byId[h.accountId]?.color ?: 0) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReconcileUiState())

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { repo.purgeStale() }
    }

    private fun historyRow(h: StatementImportEntity, label: String, color: Int) =
        HistoryRow(h.id, label, color, h.fileName, h.periodFrom, h.periodTo, h.lineCount, h.matchRateBp)

    fun onIntent(intent: ReconcileIntent) {
        when (intent) {
            is ReconcileIntent.SelectAccount -> local.update { it.copy(selectedAccountId = intent.id) }
            is ReconcileIntent.FilePicked -> pick(intent.uri)
            is ReconcileIntent.SubmitPassword -> local.value.password?.let { p -> unlock(p.draftId, intent.password) }
            ReconcileIntent.CancelPassword -> {
                local.value.password?.let { repo.cancel(it.draftId) }
                local.update { it.copy(password = null) }
            }
            ReconcileIntent.DismissError -> local.update { it.copy(error = null) }
            is ReconcileIntent.AskDelete -> local.update { it.copy(pendingDelete = intent.importId) }
            ReconcileIntent.CancelDelete -> local.update { it.copy(pendingDelete = null) }
            is ReconcileIntent.ConfirmDelete -> {
                val id = local.value.pendingDelete ?: return
                local.update { it.copy(pendingDelete = null) }
                viewModelScope.launch { repo.deleteImport(id, intent.alsoDeleteAdded) }
            }
            is ReconcileIntent.Rerun -> viewModelScope.launch { repo.rerun(intent.importId) }
        }
    }

    private fun pick(uri: Uri) {
        val account = state.value.selectedAccountId ?: return
        viewModelScope.launch {
            local.update { it.copy(busy = true, error = null) }
            handle(repo.startImport(uri, account))
        }
    }

    private fun unlock(draftId: Long, password: String) {
        viewModelScope.launch {
            local.update { it.copy(busy = true, password = null) }
            handle(repo.unlock(draftId, password))
        }
    }

    private suspend fun handle(result: OpenResult) {
        when (result) {
            is OpenResult.NeedsPassword -> local.update { it.copy(busy = false, password = PasswordPrompt(result.draftId, result.wrong)) }
            is OpenResult.Failed -> local.update { it.copy(busy = false, error = result.code) }
            is OpenResult.Ready -> {
                val view = repo.analyse(result.draftId)
                when {
                    view == null -> fail("draft_lost")
                    view.analysis.header == null -> {
                        repo.cancel(result.draftId)
                        fail(com.ledgr.app.domain.statement.StatementException.NO_HEADER)
                    }
                    view.analysis.needsMapping -> {
                        local.update { it.copy(busy = false) }
                        events.send(ReconcileEvent.OpenMapping(result.draftId))
                    }
                    else -> when (val done = repo.commit(result.draftId, null)) {
                        is CommitResult.Done -> {
                            local.update { it.copy(busy = false) }
                            events.send(ReconcileEvent.OpenResults(done.importId))
                        }
                        is CommitResult.Failed -> fail(done.code)
                    }
                }
            }
        }
    }

    private fun fail(code: String) = local.update { it.copy(busy = false, error = code) }
}
