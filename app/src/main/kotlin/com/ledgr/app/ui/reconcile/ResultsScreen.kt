package com.ledgr.app.ui.reconcile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ledgr.app.R
import com.ledgr.app.data.statement.ReconItem
import com.ledgr.app.data.statement.ReconSummary
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.statement.MatchStatus
import com.ledgr.app.ui.profile.SubScreenScaffold
import com.ledgr.app.ui.review.amountLabel
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme

/** docs/06 §4: the summary card, four tabs with counts, and the actions on each row. */
@Composable
fun ResultsScreen(onBack: () -> Unit, viewModel: ResultsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.eventFlow.collect { onBack() } }
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val addedText = (message as? ResultsMessage.Added)?.let { pluralStringResource(R.plurals.recon_added, it.count, it.count) }
    val updatedText = stringResource(R.string.recon_updated)
    val forgotText = stringResource(R.string.recon_forget_done)
    val noMapText = stringResource(R.string.recon_forget_none)
    LaunchedEffect(message) {
        when (message) {
            is ResultsMessage.Added -> snackbar.showSnackbar(addedText.orEmpty())
            ResultsMessage.Updated -> snackbar.showSnackbar(updatedText)
            is ResultsMessage.MapForgotten -> snackbar.showSnackbar(if (message.hadMap) forgotText else noMapText)
            null -> Unit
        }
        if (message != null) viewModel.onIntent(ResultsIntent.DismissMessage)
    }
    var menu by remember { mutableStateOf(false) }
    SubScreenScaffold(
        title = stringResource(R.string.recon_results_title),
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.recon_more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recon_rerun)) },
                        onClick = {
                            menu = false
                            viewModel.onIntent(ResultsIntent.Rerun)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recon_forget_map)) },
                        onClick = {
                            menu = false
                            viewModel.onIntent(ResultsIntent.ForgetMap)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recon_delete)) },
                        onClick = {
                            menu = false
                            viewModel.onIntent(ResultsIntent.AskDelete)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        },
    ) { padding ->
        val results = state.results
        if (results == null) {
            Text(
                stringResource(if (state.loading) R.string.recon_loading else R.string.recon_results_gone),
                modifier = Modifier.padding(padding).padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            ResultsBody(state, viewModel::onIntent, Modifier.padding(padding))
        }
    }
    if (state.confirmDelete) {
        var alsoAdded by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(ResultsIntent.CancelDelete) },
            title = { Text(stringResource(R.string.recon_delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.recon_delete_body))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoAdded, onCheckedChange = { alsoAdded = it }, modifier = Modifier.size(48.dp))
                        Text(stringResource(R.string.recon_delete_also_added), modifier = Modifier.weight(1f))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.onIntent(ResultsIntent.ConfirmDelete(alsoAdded)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_delete)) } },
            dismissButton = { TextButton(onClick = { viewModel.onIntent(ResultsIntent.CancelDelete) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_cancel)) } },
        )
    }
    if (state.pendingMove != null) {
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(ResultsIntent.CancelMove) },
            title = { Text(stringResource(R.string.recon_move_title)) },
            text = {
                Column {
                    if (state.otherAccounts.isEmpty()) Text(stringResource(R.string.recon_move_none))
                    state.otherAccounts.forEach { a ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { viewModel.onIntent(ResultsIntent.MoveTo(a.id)) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(modifier = Modifier.size(12.dp).background(Color(a.color), CircleShape))
                            Text(a.label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.onIntent(ResultsIntent.CancelMove) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_cancel)) } },
        )
    }
}

@Composable
private fun ResultsBody(state: ResultsUiState, onIntent: (ResultsIntent) -> Unit, modifier: Modifier = Modifier) {
    val results = state.results ?: return
    val items = state.items(state.tab)
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") { SummaryCard(state.accountLabel, results.summary) }
        if (results.warnings.isNotEmpty()) {
            item(key = "warnings") { WarningsCard(state, onIntent) }
        }
        item(key = "tabs") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ReconTab.entries, key = { it.name }) { tab ->
                    ChoiceChip(
                        selected = state.tab == tab,
                        label = stringResource(tabLabel(tab), state.count(tab)),
                        onClick = { onIntent(ResultsIntent.SelectTab(tab)) },
                    )
                }
            }
        }
        item(key = "tab-hint") {
            Text(stringResource(tabHint(state.tab)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.tab == ReconTab.MISSING && items.isNotEmpty()) {
            item(key = "bulk") { BulkBar(state, onIntent) }
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.recon_tab_empty), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
            }
        } else {
            items(items, key = { it.matchId }) { item ->
                ReconCard(item, selected = item.matchId in state.selected, busy = state.busy, onIntent = onIntent)
            }
        }
    }
}

private fun tabLabel(tab: ReconTab): Int = when (tab) {
    ReconTab.MATCHED -> R.string.recon_tab_matched
    ReconTab.CONFIRM -> R.string.recon_tab_confirm
    ReconTab.MISSING -> R.string.recon_tab_missing
    ReconTab.NOT_IN_STATEMENT -> R.string.recon_tab_not_in_statement
}

private fun tabHint(tab: ReconTab): Int = when (tab) {
    ReconTab.MATCHED -> R.string.recon_hint_matched
    ReconTab.CONFIRM -> R.string.recon_hint_confirm
    ReconTab.MISSING -> R.string.recon_hint_missing
    ReconTab.NOT_IN_STATEMENT -> R.string.recon_hint_not_in_statement
}

@Composable
private fun SummaryCard(accountLabel: String, s: ReconSummary) {
    val period = s.periodFrom.format(DAY_MONTH_YEAR) + " – " + s.periodTo.format(DAY_MONTH_YEAR)
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MatchRing(s.matchRateBp, 96.dp)
                Column(modifier = Modifier.weight(1f)) {
                    Text(accountLabel, style = MaterialTheme.typography.titleMedium)
                    Text(period, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        pluralStringResource(R.plurals.recon_lines, s.lineCount, s.lineCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.recon_rate_label, s.matchRateBp / 100), style = MaterialTheme.typography.labelLarge)
                }
            }
            TotalsRow(R.string.recon_total_debits, s.statementDebit, s.ledgerDebit, s.debitDifference)
            TotalsRow(R.string.recon_total_credits, s.statementCredit, s.ledgerCredit, s.creditDifference)
        }
    }
}

@Composable
private fun TotalsRow(label: Int, statement: Long, ledger: Long, difference: Long) {
    val name = stringResource(label)
    val diffText = when {
        difference == 0L -> stringResource(R.string.recon_diff_none)
        difference > 0 -> stringResource(R.string.recon_diff_statement_more, Money.format(difference))
        else -> stringResource(R.string.recon_diff_app_more, Money.format(-difference))
    }
    val spoken = stringResource(R.string.recon_total_description, name, spokenAmount(statement), spokenAmount(ledger), diffText)
    Column(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Text(name, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recon_col_statement), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(statement), style = MaterialTheme.typography.titleSmall)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.recon_col_app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.format(ledger), style = MaterialTheme.typography.titleSmall)
            }
            Column(modifier = Modifier.weight(1.2f)) {
                Text(stringResource(R.string.recon_col_difference), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(diffText, style = MaterialTheme.typography.titleSmall, color = if (difference == 0L) LedgrTheme.colors.income else LedgrTheme.colors.warning)
            }
        }
    }
}

@Composable
private fun WarningsCard(state: ResultsUiState, onIntent: (ResultsIntent) -> Unit) {
    val warnings = state.results?.warnings.orEmpty()
    Surface(shape = MaterialTheme.shapes.large, color = LedgrTheme.colors.warningContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            TextButton(onClick = { onIntent(ResultsIntent.ToggleWarnings) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(
                    pluralStringResource(R.plurals.recon_warnings, warnings.size, warnings.size),
                    color = LedgrTheme.colors.onWarningContainer,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (state.warningsOpen) {
                warnings.take(MAX_WARNINGS_SHOWN).forEach { w ->
                    Text(
                        warningMessage(w),
                        style = MaterialTheme.typography.bodySmall,
                        color = LedgrTheme.colors.onWarningContainer,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                if (warnings.size > MAX_WARNINGS_SHOWN) {
                    Text(pluralStringResource(R.plurals.recon_warnings_more, warnings.size - MAX_WARNINGS_SHOWN, warnings.size - MAX_WARNINGS_SHOWN), style = MaterialTheme.typography.bodySmall, color = LedgrTheme.colors.onWarningContainer)
                }
            }
        }
    }
}

private const val MAX_WARNINGS_SHOWN = 20

@Composable
private fun BulkBar(state: ResultsUiState, onIntent: (ResultsIntent) -> Unit) {
    val all = state.count(ReconTab.MISSING)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onIntent(ResultsIntent.AddSelected) }, enabled = state.selected.isNotEmpty() && !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(pluralStringResource(R.plurals.recon_add_selected, state.selected.size, state.selected.size))
            }
            OutlinedButton(onClick = { onIntent(ResultsIntent.AddAllMissing) }, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.recon_add_all, all))
            }
            TextButton(
                onClick = { onIntent(if (state.selected.size == all) ResultsIntent.ClearSelection else ResultsIntent.SelectAllMissing) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (state.selected.size == all) R.string.recon_select_none else R.string.recon_select_all))
            }
        }
    }
}

@Composable
private fun statusLabel(item: ReconItem): String = when (item.status) {
    MatchStatus.MATCHED -> stringResource(if (item.resolvedByUser) R.string.recon_status_matched_user else R.string.recon_status_matched)
    MatchStatus.MATCHED_FUZZY -> stringResource(R.string.recon_status_fuzzy, item.scorePm / 10)
    MatchStatus.AMOUNT_MISMATCH -> stringResource(R.string.recon_status_mismatch)
    MatchStatus.STATEMENT_ONLY -> stringResource(R.string.recon_status_missing)
    MatchStatus.LEDGER_ONLY -> stringResource(R.string.recon_status_not_in_statement)
}

/** A mark in the pill as well as colour: ✓ matched, ≈ near, ≠ amount differs, + missing in the app, − not on the statement. */
private fun statusMark(status: MatchStatus): String = when (status) {
    MatchStatus.MATCHED -> "✓"
    MatchStatus.MATCHED_FUZZY -> "≈"
    MatchStatus.AMOUNT_MISMATCH -> "≠"
    MatchStatus.STATEMENT_ONLY -> "+"
    MatchStatus.LEDGER_ONLY -> "−"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReconCard(item: ReconItem, selected: Boolean, busy: Boolean, onIntent: (ResultsIntent) -> Unit) {
    val colors = LedgrTheme.colors
    val (container, content) = when (item.status) {
        MatchStatus.MATCHED -> colors.incomeContainer to colors.onIncomeContainer
        MatchStatus.MATCHED_FUZZY, MatchStatus.AMOUNT_MISMATCH -> colors.warningContainer to colors.onWarningContainer
        MatchStatus.STATEMENT_ONLY -> colors.spendContainer to colors.onSpendContainer
        MatchStatus.LEDGER_ONLY -> colors.transferContainer to colors.onTransferContainer
    }
    val status = statusLabel(item)
    val selectLabel = stringResource(R.string.recon_select_row)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.status == MatchStatus.STATEMENT_ONLY) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onIntent(ResultsIntent.ToggleSelect(item.matchId)) },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = selectLabel },
                    )
                }
                Surface(shape = MaterialTheme.shapes.small, color = container) {
                    Text(
                        text = statusMark(item.status) + " " + status,
                        style = MaterialTheme.typography.labelLarge,
                        color = content,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (item.lineId != null) {
                SideBlock(
                    title = stringResource(R.string.recon_col_statement),
                    date = item.lineDate?.format(DAY_MONTH_YEAR).orEmpty(),
                    text = item.lineDescription.orEmpty(),
                    secondary = item.lineRef,
                    amount = item.lineAmount,
                    isDebit = item.lineIsDebit,
                )
            }
            if (item.txnId != null) {
                SideBlock(
                    title = stringResource(R.string.recon_col_app),
                    date = item.txnDate?.format(DAY_MONTH_YEAR).orEmpty(),
                    text = item.txnName.orEmpty().ifBlank { stringResource(R.string.recon_unknown_name) },
                    secondary = item.txnCategory,
                    amount = item.txnAmount,
                    isDebit = item.txnIsDebit,
                )
            }
            if (item.balanceDiffers) {
                Text(
                    stringResource(R.string.recon_balance_differs, Money.format(item.lineBalance ?: 0), Money.format(item.txnBalance ?: 0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                )
            }
            ItemActions(item, busy, onIntent)
        }
    }
}

@Composable
private fun SideBlock(title: String, date: String, text: String, secondary: String?, amount: Long, isDebit: Boolean) {
    val verb = stringResource(if (isDebit) R.string.recon_spoken_paid else R.string.recon_spoken_received)
    val spoken = stringResource(R.string.recon_side_description, title, date, text, verb, spokenAmount(amount))
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("$title · $date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
            if (!secondary.isNullOrBlank()) Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(
            amountLabel(amount, isDebit),
            style = MaterialTheme.typography.titleSmall,
            color = if (isDebit) LedgrTheme.colors.spend else LedgrTheme.colors.income,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemActions(item: ReconItem, busy: Boolean, onIntent: (ResultsIntent) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (item.status) {
            MatchStatus.MATCHED_FUZZY -> {
                ActionButton(R.string.recon_action_confirm, !busy, primary = true) { onIntent(ResultsIntent.Confirm(item.matchId)) }
                ActionButton(R.string.recon_action_reject, !busy) { onIntent(ResultsIntent.Reject(item.matchId)) }
            }
            MatchStatus.AMOUNT_MISMATCH -> {
                ActionButton(R.string.recon_action_use_statement, !busy, primary = true) { onIntent(ResultsIntent.ResolveAmount(item.matchId, true)) }
                ActionButton(R.string.recon_action_keep_app_amount, !busy) { onIntent(ResultsIntent.ResolveAmount(item.matchId, false)) }
                ActionButton(R.string.recon_action_reject, !busy) { onIntent(ResultsIntent.Reject(item.matchId)) }
            }
            MatchStatus.STATEMENT_ONLY ->
                ActionButton(R.string.recon_action_add, !busy, primary = true) { onIntent(ResultsIntent.AddToLedger(listOf(item.matchId))) }
            MatchStatus.LEDGER_ONLY -> if (!item.resolvedByUser) {
                ActionButton(R.string.recon_action_duplicate, !busy) { onIntent(ResultsIntent.MarkDuplicate(item.matchId)) }
                ActionButton(R.string.recon_action_move, !busy) { onIntent(ResultsIntent.AskMove(item.matchId)) }
                ActionButton(R.string.recon_action_keep, !busy) { onIntent(ResultsIntent.Keep(item.matchId)) }
            }
            MatchStatus.MATCHED -> Unit
        }
    }
}

@Composable
private fun ActionButton(label: Int, enabled: Boolean, primary: Boolean = false, onClick: () -> Unit) {
    if (primary) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
    } else {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(label)) }
    }
}
