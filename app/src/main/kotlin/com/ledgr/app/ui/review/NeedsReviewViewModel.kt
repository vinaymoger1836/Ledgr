package com.ledgr.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.CategoryEntity
import com.ledgr.app.data.db.CounterpartyEntity
import com.ledgr.app.data.db.TxnEntity
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.profile.CategoryRepository
import com.ledgr.app.data.profile.MerchantRepository
import com.ledgr.app.data.review.ManualResult
import com.ledgr.app.data.review.RecordResult
import com.ledgr.app.data.review.ReviewCounts
import com.ledgr.app.data.review.ReviewRepository
import com.ledgr.app.domain.ledger.Direction
import com.ledgr.app.domain.ledger.ProposalType
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.sms.Reasons
import com.ledgr.app.domain.time.Ist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The four tabs of Needs review (docs/08 §2.5). */
enum class ReviewTab { Unparsed, Check, Suspicious, Links }

/** One transaction as a line in a list. Amounts are paise; the sign comes from [isDebit]. */
data class TxnLineUi(
    val id: Long,
    val amountPaise: Long,
    val isDebit: Boolean,
    val occurredAt: Long,
    val title: String,
    val categoryName: String?,
    val categoryIcon: String?,
    val categoryColor: Int?,
    val accountName: String,
    val kind: TxnKind,
)

data class UnparsedUi(val id: Long, val header: String, val receivedAt: Long, val reason: String, val body: String?)

data class CheckUi(val line: TxnLineUi, val reason: String)

data class SuspiciousUi(
    val id: Long,
    val header: String,
    val receivedAt: Long,
    val reason: String,
    val body: String?,
    /** A phone-number sender is never recorded (CLAUDE.md rule 3); the message can only be deleted. */
    val canRecord: Boolean,
)

data class ProposalUi(val id: Long, val type: ProposalType, val first: TxnLineUi?, val second: TxnLineUi?)

data class AccountOption(val id: Long, val name: String, val last4: String)

data class CategoryChoice(val id: Long, val name: String, val icon: String, val color: Int)

data class MerchantChoice(val id: Long, val name: String)

/** The "Add manually" form. [rawSmsId] is the message it settles, if any. */
data class ManualDraft(
    val rawSmsId: Long?,
    val receivedAt: Long,
    val accountId: Long? = null,
    val isDebit: Boolean = true,
    val amountText: String = "",
    val categoryId: Long? = null,
    val pickingCategory: Boolean = false,
    val error: ManualError? = null,
)

enum class ManualError { Amount, Account }

/** A picker opened for one Check row. */
sealed interface ReviewDialog {
    data class CategoryFor(val txnId: Long) : ReviewDialog
    data class MerchantFor(val txnId: Long) : ReviewDialog
    data class Manual(val draft: ManualDraft) : ReviewDialog
}

/** One-shot results, shown once as a snackbar. */
enum class ReviewMessage { Recorded, NotRecorded, NoBody, Failed, ManualSaved, Ignored, Confirmed, Linked, Dismissed }

data class NeedsReviewUiState(
    val tab: ReviewTab = ReviewTab.Unparsed,
    val counts: ReviewCounts = ReviewCounts(0, 0, 0, 0),
    val unparsed: List<UnparsedUi> = emptyList(),
    val check: List<CheckUi> = emptyList(),
    val suspicious: List<SuspiciousUi> = emptyList(),
    val proposals: List<ProposalUi> = emptyList(),
    val accounts: List<AccountOption> = emptyList(),
    val categories: List<CategoryChoice> = emptyList(),
    val merchants: List<MerchantChoice> = emptyList(),
    val dialog: ReviewDialog? = null,
    val message: ReviewMessage? = null,
)

sealed interface NeedsReviewIntent {
    data class SelectTab(val tab: ReviewTab) : NeedsReviewIntent

    // Unparsed
    data class Ignore(val rawSmsId: Long) : NeedsReviewIntent
    data class OpenManual(val rawSmsId: Long?, val receivedAt: Long) : NeedsReviewIntent
    data class ManualAccount(val id: Long) : NeedsReviewIntent
    data class ManualDirection(val isDebit: Boolean) : NeedsReviewIntent
    data class ManualAmount(val text: String) : NeedsReviewIntent
    data class ManualOpenCategory(val open: Boolean) : NeedsReviewIntent
    data class ManualCategory(val id: Long?) : NeedsReviewIntent
    data object ManualSave : NeedsReviewIntent

    // Check
    data class Confirm(val txnId: Long) : NeedsReviewIntent
    data class OpenCategoryPicker(val txnId: Long) : NeedsReviewIntent
    data class PickCategory(val categoryId: Long) : NeedsReviewIntent
    data class OpenMerchantPicker(val txnId: Long) : NeedsReviewIntent
    data class PickMerchant(val merchantId: Long) : NeedsReviewIntent

    // Suspicious
    data class Record(val rawSmsId: Long) : NeedsReviewIntent
    data class DeleteSuspicious(val rawSmsId: Long) : NeedsReviewIntent

    // Duplicates & transfers
    data class Accept(val proposalId: Long) : NeedsReviewIntent
    data class Reject(val proposalId: Long) : NeedsReviewIntent

    data object DismissDialog : NeedsReviewIntent
    data object MessageShown : NeedsReviewIntent
}

private data class Local(
    val tab: ReviewTab = ReviewTab.Unparsed,
    val dialog: ReviewDialog? = null,
    val message: ReviewMessage? = null,
)

/** The number on the Home banner and tab badge. */
@HiltViewModel
class ReviewCountViewModel @Inject constructor(repository: ReviewRepository) : ViewModel() {
    val count: StateFlow<Int> = repository.counts.map { it.total }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NeedsReviewViewModel @Inject constructor(
    private val repository: ReviewRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    merchantRepository: MerchantRepository,
) : ViewModel() {

    private val local = MutableStateFlow(Local())

    private val proposalRows = repository.proposals.mapLatest { list ->
        list to repository.txns(list.flatMap { listOf(it.txnId, it.otherTxnId) }).associateBy { it.id }
    }

    private val lookups: Flow<Lookups> = combine(
        accountRepository.accounts,
        categoryRepository.categories,
        merchantRepository.merchants,
    ) { accounts, categories, merchants ->
        Lookups(
            accounts = accounts.associate { it.id to AccountOption(it.id, it.nickname, it.last4) },
            categories = categories,
            merchants = merchants,
        )
    }

    private val lists = combine(repository.unparsed, repository.check, repository.suspicious, proposalRows, repository.counts) {
        unparsed, check, suspicious, proposals, counts -> Lists(unparsed, check, suspicious, proposals, counts)
    }

    val state: StateFlow<NeedsReviewUiState> = combine(local, lists, lookups) { l, lists, lookup ->
        val categoryById = lookup.categories.associateBy { it.id }
        val merchantById = lookup.merchants.associateBy { it.id }
        fun line(t: TxnEntity): TxnLineUi {
            val category = t.categoryId?.let(categoryById::get)
            return TxnLineUi(
                id = t.id,
                amountPaise = t.amount,
                isDebit = t.direction == Direction.DEBIT,
                occurredAt = t.occurredAt,
                title = t.counterpartyId?.let { merchantById[it]?.displayName } ?: t.rawCounterparty.orEmpty(),
                categoryName = category?.name,
                categoryIcon = category?.icon,
                categoryColor = category?.color,
                accountName = lookup.accounts[t.accountId]?.let { "${it.name} ••${it.last4}" }.orEmpty(),
                kind = t.kind,
            )
        }
        NeedsReviewUiState(
            tab = l.tab,
            counts = lists.counts,
            unparsed = lists.unparsed.map { UnparsedUi(it.id, it.senderHeader.orEmpty(), it.receivedAt, it.reason, it.body) },
            check = lists.check.map { CheckUi(line(it), it.checkReason.orEmpty()) },
            suspicious = lists.suspicious.map {
                SuspiciousUi(
                    id = it.id,
                    header = it.senderHeader ?: it.address,
                    receivedAt = it.receivedAt,
                    reason = it.reason,
                    body = it.body,
                    canRecord = it.reason == Reasons.RED_FLAGS || it.reason == Reasons.MISSING_SUFFIX,
                )
            },
            proposals = lists.proposals.first.map { p ->
                ProposalUi(p.id, p.type, lists.proposals.second[p.txnId]?.let(::line), lists.proposals.second[p.otherTxnId]?.let(::line))
            },
            accounts = lookup.accounts.values.toList(),
            categories = lookup.categories.filter { !it.hidden && it.mergedIntoId == null }
                .map { CategoryChoice(it.id, it.name, it.icon, it.color) },
            merchants = lookup.merchants.map { MerchantChoice(it.id, it.displayName) },
            dialog = l.dialog,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NeedsReviewUiState())

    private class Lookups(
        val accounts: Map<Long, AccountOption>,
        val categories: List<CategoryEntity>,
        val merchants: List<CounterpartyEntity>,
    )

    private class Lists(
        val unparsed: List<com.ledgr.app.data.db.RawSmsEntity>,
        val check: List<TxnEntity>,
        val suspicious: List<com.ledgr.app.data.db.RawSmsEntity>,
        val proposals: Pair<List<com.ledgr.app.data.db.LinkProposalEntity>, Map<Long, TxnEntity>>,
        val counts: ReviewCounts,
    )

    fun onIntent(intent: NeedsReviewIntent) {
        when (intent) {
            is NeedsReviewIntent.SelectTab -> local.update { it.copy(tab = intent.tab) }
            is NeedsReviewIntent.Ignore -> act(ReviewMessage.Ignored) { repository.ignore(intent.rawSmsId) }
            is NeedsReviewIntent.OpenManual -> local.update {
                it.copy(dialog = ReviewDialog.Manual(ManualDraft(intent.rawSmsId, intent.receivedAt, accountId = singleAccount())))
            }
            is NeedsReviewIntent.ManualAccount -> editManual { it.copy(accountId = intent.id, error = null) }
            is NeedsReviewIntent.ManualDirection -> editManual { it.copy(isDebit = intent.isDebit) }
            is NeedsReviewIntent.ManualAmount -> editManual { it.copy(amountText = filterAmount(intent.text), error = null) }
            is NeedsReviewIntent.ManualOpenCategory -> editManual { it.copy(pickingCategory = intent.open) }
            is NeedsReviewIntent.ManualCategory -> editManual { it.copy(categoryId = intent.id, pickingCategory = false) }
            NeedsReviewIntent.ManualSave -> saveManual()
            is NeedsReviewIntent.Confirm -> act(ReviewMessage.Confirmed) { repository.confirmCheck(intent.txnId) }
            is NeedsReviewIntent.OpenCategoryPicker -> local.update { it.copy(dialog = ReviewDialog.CategoryFor(intent.txnId)) }
            is NeedsReviewIntent.PickCategory -> {
                val d = local.value.dialog as? ReviewDialog.CategoryFor ?: return
                local.update { it.copy(dialog = null) }
                act(ReviewMessage.Confirmed) { repository.setCategory(d.txnId, intent.categoryId) }
            }
            is NeedsReviewIntent.OpenMerchantPicker -> local.update { it.copy(dialog = ReviewDialog.MerchantFor(intent.txnId)) }
            is NeedsReviewIntent.PickMerchant -> {
                val d = local.value.dialog as? ReviewDialog.MerchantFor ?: return
                local.update { it.copy(dialog = null) }
                act(ReviewMessage.Confirmed) { repository.setCounterparty(d.txnId, intent.merchantId) }
            }
            is NeedsReviewIntent.Record -> viewModelScope.launch {
                val result = runCatching { repository.recordQuarantined(intent.rawSmsId) }.getOrNull()
                post(
                    when (result) {
                        RecordResult.Recorded -> ReviewMessage.Recorded
                        RecordResult.NotRecorded -> ReviewMessage.NotRecorded
                        RecordResult.NoBody -> ReviewMessage.NoBody
                        null -> ReviewMessage.Failed
                    },
                )
            }
            is NeedsReviewIntent.DeleteSuspicious -> act(ReviewMessage.Dismissed) { repository.deleteQuarantined(intent.rawSmsId) }
            is NeedsReviewIntent.Accept -> viewModelScope.launch {
                val ok = runCatching { repository.accept(intent.proposalId) }.getOrNull()
                post(if (ok == true) ReviewMessage.Linked else ReviewMessage.Failed)
            }
            is NeedsReviewIntent.Reject -> act(ReviewMessage.Dismissed) { repository.reject(intent.proposalId) }
            NeedsReviewIntent.DismissDialog -> local.update { it.copy(dialog = null) }
            NeedsReviewIntent.MessageShown -> local.update { it.copy(message = null) }
        }
    }

    private fun singleAccount(): Long? = state.value.accounts.singleOrNull()?.id

    private fun editManual(change: (ManualDraft) -> ManualDraft) {
        local.update { l ->
            val d = l.dialog as? ReviewDialog.Manual ?: return@update l
            l.copy(dialog = ReviewDialog.Manual(change(d.draft)))
        }
    }

    private fun saveManual() {
        val draft = (local.value.dialog as? ReviewDialog.Manual)?.draft ?: return
        val amount = Money.parsePaise(draft.amountText)
        val accountId = draft.accountId
        when {
            amount == null || amount <= 0L -> editManual { it.copy(error = ManualError.Amount) }
            accountId == null -> editManual { it.copy(error = ManualError.Account) }
            else -> viewModelScope.launch {
                val result = runCatching {
                    repository.addManual(
                        rawSmsId = draft.rawSmsId,
                        accountId = accountId,
                        direction = if (draft.isDebit) Direction.DEBIT else Direction.CREDIT,
                        amountPaise = amount,
                        occurredAt = draft.receivedAt.takeIf { it > 0 } ?: Ist.now(),
                        categoryId = draft.categoryId,
                        note = null,
                    )
                }.getOrNull()
                local.update { it.copy(dialog = null) }
                post(if (result == ManualResult.Saved) ReviewMessage.ManualSaved else ReviewMessage.Failed)
            }
        }
    }

    /** Runs a repository change, then shows [done]; any failure shows a generic message instead. */
    private fun act(done: ReviewMessage, block: suspend () -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { block() }.isSuccess
            post(if (ok) done else ReviewMessage.Failed)
        }
    }

    private fun post(message: ReviewMessage) = local.update { it.copy(message = message) }

    /** Digits and one decimal point, at most two decimals: what [Money.parsePaise] accepts. */
    private fun filterAmount(text: String): String {
        val cleaned = text.filter { it.isDigit() || it == '.' }
        val dot = cleaned.indexOf('.')
        if (dot < 0) return cleaned.take(MAX_AMOUNT_DIGITS)
        val whole = cleaned.substring(0, dot).take(MAX_AMOUNT_DIGITS)
        val fraction = cleaned.substring(dot + 1).filter { it.isDigit() }.take(2)
        return "$whole.$fraction"
    }

    private companion object {
        const val MAX_AMOUNT_DIGITS = 9
    }
}
