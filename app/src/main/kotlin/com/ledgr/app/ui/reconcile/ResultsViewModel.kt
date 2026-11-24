package com.ledgr.app.ui.reconcile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.statement.ReconItem
import com.ledgr.app.data.statement.ReconResults
import com.ledgr.app.data.statement.ReconcileRepository
import com.ledgr.app.domain.statement.MatchStatus
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

/** The four tabs of docs/06 §4. */
enum class ReconTab { MATCHED, CONFIRM, MISSING, NOT_IN_STATEMENT }

fun ReconItem.tab(): ReconTab = when (status) {
    MatchStatus.MATCHED -> ReconTab.MATCHED
    MatchStatus.MATCHED_FUZZY, MatchStatus.AMOUNT_MISMATCH -> ReconTab.CONFIRM
    MatchStatus.STATEMENT_ONLY -> ReconTab.MISSING
    MatchStatus.LEDGER_ONLY -> ReconTab.NOT_IN_STATEMENT
}

sealed interface ResultsMessage {
    data class Added(val count: Int) : ResultsMessage
    data object Updated : ResultsMessage
    data class MapForgotten(val hadMap: Boolean) : ResultsMessage
}

data class ResultsUiState(
    val loading: Boolean = true,
    val results: ReconResults? = null,
    val accountLabel: String = "",
    val tab: ReconTab = ReconTab.MATCHED,
    /** `recon_match` ids ticked on the "Missing in app" tab. */
    val selected: Set<Long> = emptySet(),
    val otherAccounts: List<ImportAccount> = emptyList(),
    /** The ledger-only row the person is choosing an account for. */
    val pendingMove: Long? = null,
    val confirmDelete: Boolean = false,
    val warningsOpen: Boolean = false,
    val busy: Boolean = false,
    val message: ResultsMessage? = null,
) {
    fun items(tab: ReconTab): List<ReconItem> = results?.items.orEmpty().filter { it.tab() == tab }

    fun count(tab: ReconTab): Int = items(tab).size
}

sealed interface ResultsIntent {
    data class SelectTab(val tab: ReconTab) : ResultsIntent
    data class ToggleSelect(val matchId: Long) : ResultsIntent
    data object SelectAllMissing : ResultsIntent
    data object ClearSelection : ResultsIntent
    data class Confirm(val matchId: Long) : ResultsIntent
    data class Reject(val matchId: Long) : ResultsIntent
    data class ResolveAmount(val matchId: Long, val useStatement: Boolean) : ResultsIntent
    data class AddToLedger(val matchIds: List<Long>) : ResultsIntent
    data object AddSelected : ResultsIntent
    data object AddAllMissing : ResultsIntent
    data class MarkDuplicate(val matchId: Long) : ResultsIntent
    data class AskMove(val matchId: Long) : ResultsIntent
    data class MoveTo(val accountId: Long) : ResultsIntent
    data object CancelMove : ResultsIntent
    data class Keep(val matchId: Long) : ResultsIntent
    data object Rerun : ResultsIntent
    data object ForgetMap : ResultsIntent
    data object AskDelete : ResultsIntent
    data object CancelDelete : ResultsIntent
    data class ConfirmDelete(val alsoDeleteAdded: Boolean) : ResultsIntent
    data object ToggleWarnings : ResultsIntent
    data object DismissMessage : ResultsIntent
}

sealed interface ResultsEvent {
    data object Closed : ResultsEvent
}

/** Results of one import (docs/06 §4): summary, four tabs, and the actions on each row. Everything updates live from the database. */
@HiltViewModel
class ResultsViewModel @Inject constructor(
    saved: SavedStateHandle,
    private val repo: ReconcileRepository,
    accounts: AccountRepository,
) : ViewModel() {
    private val importId: Long = saved.get<Long>(ARG_IMPORT) ?: -1L
    private val local = MutableStateFlow(ResultsUiState())
    private val events = Channel<ResultsEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<ResultsUiState> = combine(local, repo.observeResults(importId), accounts.accounts) { s, results, accs ->
        val own = results?.import?.accountId
        val account = accs.firstOrNull { it.id == own }
        val visibleIds = results?.items?.map { it.matchId }?.toSet().orEmpty()
        s.copy(
            loading = false,
            results = results,
            accountLabel = account?.let { "${it.nickname} ••${it.last4}" }.orEmpty(),
            otherAccounts = accs.filter { it.active && it.id != own }.map { ImportAccount(it.id, "${it.nickname} ••${it.last4}", it.color) },
            selected = s.selected.intersect(visibleIds),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ResultsUiState())

    fun onIntent(intent: ResultsIntent) {
        when (intent) {
            is ResultsIntent.SelectTab -> local.update { it.copy(tab = intent.tab) }
            is ResultsIntent.ToggleSelect -> local.update { s -> s.copy(selected = if (intent.matchId in s.selected) s.selected - intent.matchId else s.selected + intent.matchId) }
            ResultsIntent.SelectAllMissing -> local.update { it.copy(selected = state.value.items(ReconTab.MISSING).map { i -> i.matchId }.toSet()) }
            ResultsIntent.ClearSelection -> local.update { it.copy(selected = emptySet()) }
            is ResultsIntent.Confirm -> act { repo.confirmFuzzy(intent.matchId) }
            is ResultsIntent.Reject -> act { repo.rejectMatch(intent.matchId) }
            is ResultsIntent.ResolveAmount -> act { repo.resolveMismatch(intent.matchId, intent.useStatement) }
            is ResultsIntent.AddToLedger -> add(intent.matchIds)
            ResultsIntent.AddSelected -> add(state.value.selected.toList())
            ResultsIntent.AddAllMissing -> add(state.value.items(ReconTab.MISSING).map { it.matchId })
            is ResultsIntent.MarkDuplicate -> act { repo.markDuplicate(intent.matchId) }
            is ResultsIntent.AskMove -> local.update { it.copy(pendingMove = intent.matchId) }
            is ResultsIntent.MoveTo -> {
                val id = local.value.pendingMove ?: return
                local.update { it.copy(pendingMove = null) }
                act { repo.moveToAccount(id, intent.accountId) }
            }
            ResultsIntent.CancelMove -> local.update { it.copy(pendingMove = null) }
            is ResultsIntent.Keep -> act { repo.keepLedgerOnly(intent.matchId) }
            ResultsIntent.Rerun -> act { repo.rerun(importId) }
            ResultsIntent.ForgetMap -> viewModelScope.launch {
                val had = repo.forgetMap(importId)
                local.update { it.copy(message = ResultsMessage.MapForgotten(had)) }
            }
            ResultsIntent.AskDelete -> local.update { it.copy(confirmDelete = true) }
            ResultsIntent.CancelDelete -> local.update { it.copy(confirmDelete = false) }
            is ResultsIntent.ConfirmDelete -> viewModelScope.launch {
                local.update { it.copy(confirmDelete = false) }
                repo.deleteImport(importId, intent.alsoDeleteAdded)
                events.send(ResultsEvent.Closed)
            }
            ResultsIntent.ToggleWarnings -> local.update { it.copy(warningsOpen = !it.warningsOpen) }
            ResultsIntent.DismissMessage -> local.update { it.copy(message = null) }
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            block()
            local.update { it.copy(busy = false, message = ResultsMessage.Updated) }
        }
    }

    private fun add(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val n = repo.addToLedger(ids)
            local.update { it.copy(busy = false, selected = emptySet(), message = ResultsMessage.Added(n)) }
        }
    }

    companion object {
        const val ARG_IMPORT = "importId"
    }
}
