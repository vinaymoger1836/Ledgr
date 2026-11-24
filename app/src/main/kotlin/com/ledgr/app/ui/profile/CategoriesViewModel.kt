package com.ledgr.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.profile.CategoryChangeResult
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.domain.model.CategoryKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CategoryRowUi(
    val id: Long,
    val key: String,
    val name: String,
    val icon: String,
    val color: Int,
    val kind: CategoryKind,
    val hidden: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    /** Name of the category this one was merged into, if any. */
    val mergedInto: String?,
    /** The "Uncategorised" fallback: cannot be hidden or merged away. */
    val isProtected: Boolean,
)

enum class CategoryNameError { Blank, Duplicate }

data class CategoryEditorUi(
    val category: CategoryRowUi,
    val nameDraft: String,
    val nameError: CategoryNameError?,
    /** Non-null while the merge dialog is open: the possible targets and the chosen one. */
    val merge: MergeUi?,
)

data class MergeUi(val targets: List<CategoryRowUi>, val selectedId: Long?)

/** One-off messages for the snackbar. */
sealed interface CategoryMessage {
    data class Merged(val source: String, val target: String) : CategoryMessage
    data class Protected(val name: String) : CategoryMessage
    data object Failed : CategoryMessage
}

data class CategoriesUiState(
    val expense: List<CategoryRowUi> = emptyList(),
    val income: List<CategoryRowUi> = emptyList(),
    val transfer: List<CategoryRowUi> = emptyList(),
    val hidden: List<CategoryRowUi> = emptyList(),
    val reorderMode: Boolean = false,
    val showHidden: Boolean = false,
    val editor: CategoryEditorUi? = null,
    val message: CategoryMessage? = null,
)

sealed interface CategoriesIntent {
    data class OpenEditor(val id: Long) : CategoriesIntent
    data object CloseEditor : CategoriesIntent
    data class SetName(val text: String) : CategoriesIntent
    data object SaveAndClose : CategoriesIntent
    data class PickColor(val index: Int) : CategoriesIntent
    data class PickIcon(val key: String) : CategoriesIntent
    data object ToggleHidden : CategoriesIntent
    data object StartMerge : CategoriesIntent
    data class PickMergeTarget(val id: Long) : CategoriesIntent
    data object CancelMerge : CategoriesIntent
    data object ConfirmMerge : CategoriesIntent
    data class Move(val id: Long, val delta: Int) : CategoriesIntent
    data object ToggleReorder : CategoriesIntent
    data object ToggleShowHidden : CategoriesIntent
    data object MessageShown : CategoriesIntent
}

private data class CategoryEditorDraft(
    val id: Long,
    val name: String,
    val nameError: CategoryNameError? = null,
    val mergeOpen: Boolean = false,
    val mergeTarget: Long? = null,
)

private data class ScreenFlags(
    val reorderMode: Boolean = false,
    val showHidden: Boolean = false,
    val editor: CategoryEditorDraft? = null,
    val message: CategoryMessage? = null,
)

@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoryRepository,
) : ViewModel() {

    private val flags = MutableStateFlow(ScreenFlags())

    val state: StateFlow<CategoriesUiState> = combine(repository.categories, flags) { rows, f ->
        val names = rows.associate { it.id to it.name }
        fun neighbourOf(row: CategoryEntity, delta: Int): Boolean {
            val group = rows.filter { it.kind == row.kind && !it.hidden }
            val index = group.indexOfFirst { it.id == row.id }
            return index >= 0 && group.getOrNull(index + delta) != null
        }
        val ui = rows.map { row ->
            CategoryRowUi(
                id = row.id,
                key = row.key,
                name = row.name,
                icon = row.icon,
                color = row.color,
                kind = row.kind,
                hidden = row.hidden,
                canMoveUp = !row.hidden && neighbourOf(row, -1),
                canMoveDown = !row.hidden && neighbourOf(row, +1),
                mergedInto = row.mergedIntoId?.let(names::get),
                isProtected = row.key == CategoryRepository.FALLBACK_KEY,
            )
        }
        val visible = ui.filter { !it.hidden }
        val editor = f.editor?.let { draft ->
            ui.firstOrNull { it.id == draft.id }?.let { current ->
                CategoryEditorUi(
                    category = current,
                    nameDraft = draft.name,
                    nameError = draft.nameError,
                    merge = if (draft.mergeOpen) {
                        MergeUi(
                            targets = visible.filter { it.kind == current.kind && it.id != current.id },
                            selectedId = draft.mergeTarget,
                        )
                    } else {
                        null
                    },
                )
            }
        }
        CategoriesUiState(
            expense = visible.filter { it.kind == CategoryKind.EXPENSE },
            income = visible.filter { it.kind == CategoryKind.INCOME },
            transfer = visible.filter { it.kind == CategoryKind.TRANSFER },
            hidden = ui.filter { it.hidden },
            reorderMode = f.reorderMode,
            showHidden = f.showHidden,
            editor = editor,
            message = f.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoriesUiState())

    fun onIntent(intent: CategoriesIntent) {
        when (intent) {
            is CategoriesIntent.OpenEditor -> {
                val current = state.value.let { s ->
                    (s.expense + s.income + s.transfer + s.hidden).firstOrNull { it.id == intent.id }
                } ?: return
                flags.update { it.copy(editor = CategoryEditorDraft(id = intent.id, name = current.name)) }
            }
            CategoriesIntent.CloseEditor -> flags.update { it.copy(editor = null) }
            is CategoriesIntent.SetName -> editDraft { it.copy(name = intent.text.take(CategoryRepository.MAX_NAME), nameError = null) }
            CategoriesIntent.SaveAndClose -> saveAndClose()
            is CategoriesIntent.PickColor -> withEditor { repository.setColor(it, intent.index) }
            is CategoriesIntent.PickIcon -> withEditor { repository.setIcon(it, intent.key) }
            CategoriesIntent.ToggleHidden -> toggleHidden()
            CategoriesIntent.StartMerge -> editDraft { it.copy(mergeOpen = true, mergeTarget = null) }
            is CategoriesIntent.PickMergeTarget -> editDraft { it.copy(mergeTarget = intent.id) }
            CategoriesIntent.CancelMerge -> editDraft { it.copy(mergeOpen = false, mergeTarget = null) }
            CategoriesIntent.ConfirmMerge -> confirmMerge()
            is CategoriesIntent.Move -> viewModelScope.launch { runCatching { repository.move(intent.id, intent.delta) } }
            CategoriesIntent.ToggleReorder -> flags.update { it.copy(reorderMode = !it.reorderMode) }
            CategoriesIntent.ToggleShowHidden -> flags.update { it.copy(showHidden = !it.showHidden) }
            CategoriesIntent.MessageShown -> flags.update { it.copy(message = null) }
        }
    }

    private fun editDraft(change: (CategoryEditorDraft) -> CategoryEditorDraft) {
        flags.update { f -> f.copy(editor = f.editor?.let(change)) }
    }

    private fun withEditor(block: suspend (Long) -> Unit) {
        val id = flags.value.editor?.id ?: return
        viewModelScope.launch { runCatching { block(id) }.onFailure { flags.update { f -> f.copy(message = CategoryMessage.Failed) } } }
    }

    private fun saveAndClose() {
        val draft = flags.value.editor ?: return
        val unchanged = state.value.editor?.category?.name == draft.name
        if (unchanged) {
            flags.update { it.copy(editor = null) }
            return
        }
        viewModelScope.launch {
            when (runCatching { repository.rename(draft.id, draft.name) }.getOrNull()) {
                CategoryChangeResult.Done -> flags.update { it.copy(editor = null) }
                CategoryChangeResult.BlankName -> editDraft { it.copy(nameError = CategoryNameError.Blank) }
                CategoryChangeResult.DuplicateName -> editDraft { it.copy(nameError = CategoryNameError.Duplicate) }
                else -> flags.update { it.copy(message = CategoryMessage.Failed) }
            }
        }
    }

    private fun toggleHidden() {
        val current = state.value.editor?.category ?: return
        viewModelScope.launch {
            when (runCatching { repository.setHidden(current.id, !current.hidden) }.getOrNull()) {
                CategoryChangeResult.Done -> flags.update { it.copy(editor = null) }
                CategoryChangeResult.Protected ->
                    flags.update { it.copy(editor = null, message = CategoryMessage.Protected(current.name)) }
                else -> flags.update { it.copy(message = CategoryMessage.Failed) }
            }
        }
    }

    private fun confirmMerge() {
        val editor = state.value.editor ?: return
        val targetId = flags.value.editor?.mergeTarget ?: return
        val target = editor.merge?.targets?.firstOrNull { it.id == targetId } ?: return
        viewModelScope.launch {
            when (runCatching { repository.merge(editor.category.id, targetId) }.getOrNull()) {
                CategoryChangeResult.Done -> flags.update {
                    it.copy(editor = null, message = CategoryMessage.Merged(editor.category.name, target.name))
                }
                CategoryChangeResult.Protected ->
                    flags.update { it.copy(editor = null, message = CategoryMessage.Protected(editor.category.name)) }
                else -> flags.update { it.copy(message = CategoryMessage.Failed) }
            }
        }
    }
}
