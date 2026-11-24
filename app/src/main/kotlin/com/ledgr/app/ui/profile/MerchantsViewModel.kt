package com.ledgr.app.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.profile.AliasAddResult
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.profile.MerchantSaveResult
import com.ledgr.app.domain.model.CategoryKind
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A category a merchant can default to. */
data class CategoryOption(val id: Long, val name: String, val icon: String, val color: Int)

data class MerchantRowUi(
    val id: Long,
    val name: String,
    val nameColor: Int,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: Int?,
    /** `LETTER`, `CATEGORY` or an `IMG:` value. */
    val icon: String,
    val image: File?,
    val aliasCount: Int,
    val isShipped: Boolean,
)

enum class AvatarMode { Letter, CategoryIcon, Picture }

enum class MerchantNameError { Blank, Duplicate }

/** Why an alias was not added. */
sealed interface AliasError {
    data object Invalid : AliasError
    data class AlreadyMapped(val owner: String, val sameMerchant: Boolean) : AliasError
}

data class MerchantEditorUi(
    val isNew: Boolean,
    /** The saved merchant (null while adding a new one). */
    val merchant: MerchantRowUi?,
    val nameDraft: String,
    val nameError: MerchantNameError?,
    val categoryId: Long?,
    val avatarMode: AvatarMode,
    val aliases: List<String>,
    val aliasInput: String,
    val aliasError: AliasError?,
    val imageError: Boolean,
    val confirmDelete: Boolean,
)

data class MerchantsUiState(
    val merchants: List<MerchantRowUi> = emptyList(),
    val categories: List<CategoryOption> = emptyList(),
    val editor: MerchantEditorUi? = null,
    val failed: Boolean = false,
)

sealed interface MerchantsIntent {
    data object OpenAdd : MerchantsIntent
    data class OpenEdit(val id: Long) : MerchantsIntent
    data object CloseEditor : MerchantsIntent
    data class SetName(val text: String) : MerchantsIntent
    data class SetCategory(val id: Long?) : MerchantsIntent
    data class SetAvatarMode(val mode: AvatarMode) : MerchantsIntent
    data class PickedImage(val uri: Uri) : MerchantsIntent
    data class SetAliasInput(val text: String) : MerchantsIntent
    data object AddAlias : MerchantsIntent
    data class RemoveAlias(val key: String) : MerchantsIntent
    data object Save : MerchantsIntent
    data object RequestDelete : MerchantsIntent
    data object CancelDelete : MerchantsIntent
    data object ConfirmDelete : MerchantsIntent
    data object FailureShown : MerchantsIntent
}

private data class MerchantEditorDraft(
    /** 0 while adding a new merchant. */
    val id: Long = 0,
    val name: String = "",
    val newCategoryId: Long? = null,
    val nameError: MerchantNameError? = null,
    val aliasInput: String = "",
    val aliasError: AliasError? = null,
    val imageError: Boolean = false,
    val confirmDelete: Boolean = false,
)

private data class Flags(val editor: MerchantEditorDraft? = null, val failed: Boolean = false)

@HiltViewModel
class MerchantsViewModel @Inject constructor(
    private val repository: MerchantRepository,
    categories: CategoryRepository,
) : ViewModel() {

    private val flags = MutableStateFlow(Flags())

    val state: StateFlow<MerchantsUiState> = combine(
        repository.merchants,
        repository.aliases,
        categories.categories,
        flags,
    ) { merchantRows, aliasRows, categoryRows, f ->
        val byId = categoryRows.associateBy { it.id }
        val options = categoryRows
            .filter { !it.hidden && it.kind != CategoryKind.TRANSFER }
            .map { CategoryOption(it.id, it.name, it.icon, it.color) }
        val aliasCounts = aliasRows.groupingBy { it.counterpartyId }.eachCount()
        val rows = merchantRows.map { m ->
            val category = m.defaultCategoryId?.let(byId::get)
            MerchantRowUi(
                id = m.id,
                name = m.displayName,
                nameColor = m.color,
                categoryId = m.defaultCategoryId,
                categoryName = category?.name,
                categoryIcon = category?.icon,
                categoryColor = category?.color,
                icon = m.icon,
                image = repository.imageFile(m.icon),
                aliasCount = aliasCounts[m.id] ?: 0,
                isShipped = m.merchantKey != null,
            )
        }
        val editor = f.editor?.let { draft ->
            if (draft.id == 0L) {
                MerchantEditorUi(
                    isNew = true,
                    merchant = null,
                    nameDraft = draft.name,
                    nameError = draft.nameError,
                    categoryId = draft.newCategoryId,
                    avatarMode = AvatarMode.Letter,
                    aliases = emptyList(),
                    aliasInput = "",
                    aliasError = null,
                    imageError = false,
                    confirmDelete = false,
                )
            } else {
                rows.firstOrNull { it.id == draft.id }?.let { row ->
                    MerchantEditorUi(
                        isNew = false,
                        merchant = row,
                        nameDraft = draft.name,
                        nameError = draft.nameError,
                        categoryId = row.categoryId,
                        avatarMode = when {
                            row.image != null -> AvatarMode.Picture
                            row.icon == CounterpartyEntity.ICON_CATEGORY -> AvatarMode.CategoryIcon
                            else -> AvatarMode.Letter
                        },
                        aliases = aliasRows.filter { it.counterpartyId == row.id }.map { it.rawKey },
                        aliasInput = draft.aliasInput,
                        aliasError = draft.aliasError,
                        imageError = draft.imageError,
                        confirmDelete = draft.confirmDelete,
                    )
                }
            }
        }
        MerchantsUiState(merchants = rows, categories = options, editor = editor, failed = f.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MerchantsUiState())

    fun onIntent(intent: MerchantsIntent) {
        when (intent) {
            MerchantsIntent.OpenAdd -> flags.update { it.copy(editor = MerchantEditorDraft()) }
            is MerchantsIntent.OpenEdit -> {
                val row = state.value.merchants.firstOrNull { it.id == intent.id } ?: return
                flags.update { it.copy(editor = MerchantEditorDraft(id = row.id, name = row.name)) }
            }
            MerchantsIntent.CloseEditor -> flags.update { it.copy(editor = null) }
            is MerchantsIntent.SetName -> editDraft { it.copy(name = intent.text.take(MerchantRepository.MAX_NAME), nameError = null) }
            is MerchantsIntent.SetCategory -> setCategory(intent.id)
            is MerchantsIntent.SetAvatarMode -> setAvatarMode(intent.mode)
            is MerchantsIntent.PickedImage -> pickedImage(intent.uri)
            is MerchantsIntent.SetAliasInput -> editDraft { it.copy(aliasInput = intent.text.take(MAX_ALIAS_INPUT), aliasError = null) }
            MerchantsIntent.AddAlias -> addAlias()
            is MerchantsIntent.RemoveAlias -> launchEdit { repository.removeAlias(intent.key) }
            MerchantsIntent.Save -> save()
            MerchantsIntent.RequestDelete -> editDraft { it.copy(confirmDelete = true) }
            MerchantsIntent.CancelDelete -> editDraft { it.copy(confirmDelete = false) }
            MerchantsIntent.ConfirmDelete -> delete()
            MerchantsIntent.FailureShown -> flags.update { it.copy(failed = false) }
        }
    }

    private fun editDraft(change: (MerchantEditorDraft) -> MerchantEditorDraft) {
        flags.update { f -> f.copy(editor = f.editor?.let(change)) }
    }

    private fun launchEdit(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { flags.update { f -> f.copy(failed = true) } }
        }
    }

    private fun setCategory(categoryId: Long?) {
        val draft = flags.value.editor ?: return
        if (draft.id == 0L) {
            editDraft { it.copy(newCategoryId = categoryId) }
        } else {
            launchEdit { repository.setDefaultCategory(draft.id, categoryId) }
        }
    }

    private fun setAvatarMode(mode: AvatarMode) {
        val id = flags.value.editor?.id?.takeIf { it != 0L } ?: return
        when (mode) {
            AvatarMode.Letter -> launchEdit { repository.setIconMode(id, CounterpartyEntity.ICON_LETTER) }
            AvatarMode.CategoryIcon -> launchEdit { repository.setIconMode(id, CounterpartyEntity.ICON_CATEGORY) }
            AvatarMode.Picture -> Unit // chosen by picking an image (see PickedImage)
        }
    }

    private fun pickedImage(uri: Uri) {
        val id = flags.value.editor?.id?.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.setImage(id, uri) }.getOrDefault(false)
            editDraft { it.copy(imageError = !ok) }
        }
    }

    private fun addAlias() {
        val draft = flags.value.editor?.takeIf { it.id != 0L } ?: return
        if (draft.aliasInput.isBlank()) return
        viewModelScope.launch {
            when (val result = runCatching { repository.addAlias(draft.id, draft.aliasInput) }.getOrNull()) {
                AliasAddResult.Added -> editDraft { it.copy(aliasInput = "", aliasError = null) }
                AliasAddResult.Invalid -> editDraft { it.copy(aliasError = AliasError.Invalid) }
                is AliasAddResult.AlreadyMapped ->
                    editDraft { it.copy(aliasError = AliasError.AlreadyMapped(result.ownerName, result.sameMerchant)) }
                null -> flags.update { it.copy(failed = true) }
            }
        }
    }

    private fun save() {
        val draft = flags.value.editor ?: return
        viewModelScope.launch {
            val result = runCatching {
                if (draft.id == 0L) repository.add(draft.name, draft.newCategoryId) else repository.rename(draft.id, draft.name)
            }.getOrNull()
            when (result) {
                is MerchantSaveResult.Saved -> flags.update { it.copy(editor = null) }
                MerchantSaveResult.BlankName -> editDraft { it.copy(nameError = MerchantNameError.Blank) }
                MerchantSaveResult.DuplicateName -> editDraft { it.copy(nameError = MerchantNameError.Duplicate) }
                null -> flags.update { it.copy(failed = true) }
            }
        }
    }

    private fun delete() {
        val id = flags.value.editor?.id?.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            val ok = runCatching { repository.delete(id) }.getOrDefault(false)
            flags.update { it.copy(editor = null, failed = !ok) }
        }
    }

    private companion object {
        const val MAX_ALIAS_INPUT = 120
    }
}
