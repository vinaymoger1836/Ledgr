package com.ledgr.app.ui.txn

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.ledger.EditResult
import com.ledgr.app.data.ledger.ManualTxn
import com.ledgr.app.data.ledger.TxnDraft
import com.ledgr.app.data.ledger.TxnRepository
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnKinds
import com.ledgr.app.domain.model.CategoryKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.time.Ist
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountChoice(val id: Long, val label: String)

data class TxnCategoryChoice(val id: Long, val key: String, val name: String, val icon: String, val color: Int, val kind: CategoryKind)

data class TxnMerchantChoice(val id: Long, val name: String, val defaultCategoryId: Long?)

enum class TxnFormError { Amount, Account, Failed }

/** The pickers that open over the form. */
enum class TxnDialog { Category, Merchant, Date, Time, ConfirmDelete }

/** Where a saved row came from, shown read-only (CLAUDE.md rule 4: every row records its origin). */
data class Provenance(
    val source: Source,
    val templateId: String?,
    val rulesVersion: String,
    val confidence: Int,
    val rawCounterparty: String?,
)

data class TxnFormUi(
    val loaded: Boolean = false,
    val isNew: Boolean = false,
    val notFound: Boolean = false,
    val txnId: Long = 0,
    /** Fixed by the bank's message for an existing row; chosen by the person for a new one. */
    val isDebit: Boolean = true,
    val kind: TxnKind = TxnKind.EXPENSE,
    val amountText: String = "",
    val occurredAt: Long = 0,
    val accountId: Long? = null,
    val counterpartyId: Long? = null,
    /** Shown when the merchant is a person or other counterparty that is not in the merchants list. */
    val counterpartyName: String? = null,
    val categoryId: Long? = null,
    val note: String = "",
    val applyToMerchant: Boolean = false,
    val provenance: Provenance? = null,
    val accounts: List<AccountChoice> = emptyList(),
    val categories: List<TxnCategoryChoice> = emptyList(),
    val merchants: List<TxnMerchantChoice> = emptyList(),
    val error: TxnFormError? = null,
    val saving: Boolean = false,
    val dialog: TxnDialog? = null,
    /** Saved or deleted: the screen goes back. */
    val done: Boolean = false,
) {
    val direction: Direction get() = if (isDebit) Direction.DEBIT else Direction.CREDIT
    val amountPaise: Long? get() = Money.parsePaise(amountText)
    val date: LocalDate get() = Ist.localDate(occurredAt)
    val merchantName: String? get() = merchants.firstOrNull { it.id == counterpartyId }?.name ?: counterpartyName
    val category: TxnCategoryChoice? get() = categories.firstOrNull { it.id == categoryId }
}

sealed interface TxnIntent {
    data class SetDirection(val isDebit: Boolean) : TxnIntent
    data class SetKind(val kind: TxnKind) : TxnIntent
    data class SetAmount(val text: String) : TxnIntent
    data class SetAccount(val id: Long) : TxnIntent
    data class SetNote(val text: String) : TxnIntent
    data class SetApply(val on: Boolean) : TxnIntent
    data class PickCategory(val id: Long) : TxnIntent
    data class PickMerchant(val id: Long?) : TxnIntent
    data class PickDate(val date: LocalDate) : TxnIntent
    data class PickTime(val hour: Int, val minute: Int) : TxnIntent
    data class Open(val dialog: TxnDialog) : TxnIntent
    data object CloseDialog : TxnIntent
    data object Save : TxnIntent
    data object ConfirmDelete : TxnIntent
}

/**
 * Transaction detail and manual entry (docs/08 §2.1, docs/12 P4 item 4). With a `txn id` argument it edits that row,
 * without one it adds a new manual row. Whatever the person changes is stored in `user_edited_fields` by
 * [TxnRepository], so a re-scan never undoes it.
 */
@HiltViewModel
class TxnDetailViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val txns: TxnRepository,
    accounts: AccountRepository,
    categories: CategoryRepository,
    merchants: MerchantRepository,
    private val rulesLoader: RulesLoader,
) : ViewModel() {

    private val txnId: Long? = saved.get<Long>(ARG_ID)?.takeIf { it > 0 }
    private val form = MutableStateFlow(TxnFormUi(isNew = txnId == null, occurredAt = Ist.now()))

    /** Category key per kind from `ledger.yaml` (`categories.by_kind`): where a changed kind moves the category. */
    private var byKind: Map<String, String> = emptyMap()

    private val lookups: Flow<Lookups> = combine(accounts.accounts, categories.categories, merchants.merchants) { a, c, m ->
        Lookups(
            accounts = a.filter { it.active }.map { AccountChoice(it.id, "${it.nickname} ••${it.last4}") },
            categories = c.filter { !it.hidden && it.mergedIntoId == null }
                .sortedBy { it.sortOrder }
                .map { TxnCategoryChoice(it.id, it.key, it.name, it.icon, it.color, it.kind) },
            merchants = m.map { TxnMerchantChoice(it.id, it.displayName, it.defaultCategoryId) },
        )
    }

    private class Lookups(
        val accounts: List<AccountChoice>,
        val categories: List<TxnCategoryChoice>,
        val merchants: List<TxnMerchantChoice>,
    )

    val state: StateFlow<TxnFormUi> = combine(form, lookups) { f, l ->
        f.copy(accounts = l.accounts, categories = l.categories, merchants = l.merchants)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TxnFormUi())

    init {
        viewModelScope.launch {
            byKind = runCatching { rulesLoader.bundle().ledger.categories.byKind }.getOrDefault(emptyMap())
            val id = txnId
            if (id == null) {
                form.update { it.copy(loaded = true) }
            } else {
                val t = txns.get(id)
                form.update { if (t == null) it.copy(loaded = true, notFound = true) else fromRow(t) }
            }
        }
        // A single account is the only possible choice: preselect it for a new entry.
        if (txnId == null) {
            viewModelScope.launch {
                lookups.catch { }.collect { l ->
                    form.update { if (it.accountId == null && l.accounts.size == 1) it.copy(accountId = l.accounts.single().id) else it }
                }
            }
        }
    }

    private fun fromRow(t: TxnEntity) = TxnFormUi(
        loaded = true,
        isNew = false,
        txnId = t.id,
        isDebit = t.direction == Direction.DEBIT,
        kind = t.kind,
        amountText = plainAmount(t.amount),
        occurredAt = t.occurredAt,
        accountId = t.accountId,
        counterpartyId = t.counterpartyId,
        counterpartyName = t.rawCounterparty.takeIf { t.counterpartyId == null },
        categoryId = t.categoryId,
        note = t.note.orEmpty(),
        provenance = Provenance(t.source, t.templateId, t.rulesVersion, t.confidence, t.rawCounterparty),
    )

    fun onIntent(intent: TxnIntent) {
        when (intent) {
            is TxnIntent.SetDirection -> form.update {
                if (!it.isNew) it else it.copy(isDebit = intent.isDebit, kind = TxnKinds.default(if (intent.isDebit) Direction.DEBIT else Direction.CREDIT), categoryId = null, error = null)
            }
            is TxnIntent.SetKind -> form.update { changeKind(it, intent.kind) }
            is TxnIntent.SetAmount -> form.update { it.copy(amountText = filterAmount(intent.text), error = null) }
            is TxnIntent.SetAccount -> form.update { it.copy(accountId = intent.id, error = null) }
            is TxnIntent.SetNote -> form.update { it.copy(note = intent.text.take(MAX_NOTE)) }
            is TxnIntent.SetApply -> form.update { it.copy(applyToMerchant = intent.on) }
            is TxnIntent.PickCategory -> form.update { it.copy(categoryId = intent.id, dialog = null) }
            is TxnIntent.PickMerchant -> form.update { pickMerchant(it, intent.id) }
            is TxnIntent.PickDate -> form.update { it.copy(occurredAt = withDate(it, intent.date), dialog = null) }
            is TxnIntent.PickTime -> form.update { it.copy(occurredAt = withTime(it.occurredAt, intent.hour, intent.minute), dialog = null) }
            is TxnIntent.Open -> form.update { it.copy(dialog = intent.dialog) }
            TxnIntent.CloseDialog -> form.update { it.copy(dialog = null) }
            TxnIntent.Save -> save()
            TxnIntent.ConfirmDelete -> delete()
        }
    }

    private fun changeKind(f: TxnFormUi, kind: TxnKind): TxnFormUi {
        if (kind == f.kind) return f
        // The category must fit the new kind: a transfer uses its transfer category, others keep theirs if it still fits.
        val moved = byKind[kind.name]?.let { key -> state.value.categories.firstOrNull { it.key == key }?.id }
        val keep = f.category?.takeIf { it.kind == categoryKindFor(kind) }?.id
        return f.copy(kind = kind, categoryId = moved ?: keep, applyToMerchant = false)
    }

    private fun pickMerchant(f: TxnFormUi, id: Long?): TxnFormUi {
        val choice = id?.let { m -> f.merchants.firstOrNull { it.id == m } }
        // A merchant brings its usual category, unless this row already has one the person chose.
        val category = if (f.categoryId == null) choice?.defaultCategoryId else f.categoryId
        return f.copy(counterpartyId = id, counterpartyName = null, categoryId = category, dialog = null, applyToMerchant = false)
    }

    private fun withDate(f: TxnFormUi, date: LocalDate): Long {
        val old = Ist.localDate(f.occurredAt)
        if (date == old) return f.occurredAt
        val time = Ist.localTime(f.occurredAt)
        // A new entry on another day has no real time of day: noon, so it never slips across midnight.
        return if (f.isNew) {
            if (date == Ist.today()) Ist.now() else Ist.at(date, NOON)
        } else {
            Ist.at(date, time.hour, time.minute)
        }
    }

    private fun withTime(occurredAt: Long, hour: Int, minute: Int): Long = Ist.at(Ist.localDate(occurredAt), hour, minute)

    private fun save() {
        val f = form.value
        if (f.saving) return
        val amount = f.amountPaise
        val accountId = f.accountId
        when {
            amount == null || amount <= 0L -> form.update { it.copy(error = TxnFormError.Amount) }
            accountId == null -> form.update { it.copy(error = TxnFormError.Account) }
            else -> {
                form.update { it.copy(saving = true, error = null) }
                val draft = TxnDraft(accountId, f.kind, amount, f.occurredAt, f.counterpartyId, f.categoryId, f.note)
                viewModelScope.launch {
                    val result = runCatching {
                        if (f.isNew) txns.addManual(ManualTxn(f.direction, draft)) else txns.edit(f.txnId, draft, f.applyToMerchant)
                    }.getOrNull()
                    form.update {
                        when (result) {
                            EditResult.Saved -> it.copy(saving = false, done = true)
                            EditResult.InvalidAmount -> it.copy(saving = false, error = TxnFormError.Amount)
                            EditResult.NoAccount -> it.copy(saving = false, error = TxnFormError.Account)
                            else -> it.copy(saving = false, error = TxnFormError.Failed)
                        }
                    }
                }
            }
        }
    }

    private fun delete() {
        val id = form.value.txnId
        form.update { it.copy(dialog = null, saving = true) }
        viewModelScope.launch {
            val ok = runCatching { txns.delete(id) }.isSuccess
            form.update { if (ok) it.copy(saving = false, done = true) else it.copy(saving = false, error = TxnFormError.Failed) }
        }
    }

    /** `12345` paise → `"123.45"`, `10000` → `"100"`: what the amount field starts with. No floating point. */
    private fun plainAmount(paise: Long): String {
        val rupees = paise / 100
        val rest = (paise % 100).toInt()
        return if (rest == 0) rupees.toString() else "$rupees.${rest.toString().padStart(2, '0')}"
    }

    /** Digits and one decimal point, at most two decimals: what [Money.parsePaise] accepts. */
    private fun filterAmount(text: String): String {
        val cleaned = text.filter { it.isDigit() || it == '.' }
        val dot = cleaned.indexOf('.')
        if (dot < 0) return cleaned.take(MAX_AMOUNT_DIGITS)
        val whole = cleaned.substring(0, dot).take(MAX_AMOUNT_DIGITS)
        val fraction = cleaned.substring(dot + 1).filter { it.isDigit() }.take(2)
        return "$whole.$fraction"
    }

    companion object {
        const val ARG_ID = "id"
        private const val MAX_AMOUNT_DIGITS = 9
        private const val MAX_NOTE = 200
        private const val NOON = 12
    }
}
