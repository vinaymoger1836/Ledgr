package com.ledgr.app.ui.review

import androidx.annotation.StringRes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.domain.ledger.ProposalType
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.profile.NameColor
import com.ledgr.app.ui.motion.Appear
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.profile.ConfirmDialog
import com.ledgr.app.ui.profile.ErrorText
import com.ledgr.app.ui.profile.MerchantAvatar
import com.ledgr.app.ui.profile.SubScreenScaffold
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH)

/**
 * Needs review (docs/08 §2.5): what the pipeline could not settle on its own. Four tabs: Unparsed (bank SMS without a
 * template), Check (a transaction that needs a second look), Suspicious (quarantined messages) and Duplicates and
 * transfers (links the app is not sure about). Every item can be settled here; nothing is hidden or dropped.
 */
@Composable
fun NeedsReviewScreen(onBack: () -> Unit, viewModel: NeedsReviewViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val message = state.message
    val messageText = message?.let { stringResource(messageRes(it)) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.onIntent(NeedsReviewIntent.MessageShown)
        }
    }

    SubScreenScaffold(title = stringResource(R.string.review_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabChips(state, onSelect = { viewModel.onIntent(NeedsReviewIntent.SelectTab(it)) })
            when (state.tab) {
                ReviewTab.Unparsed -> UnparsedList(state, viewModel::onIntent)
                ReviewTab.Check -> CheckList(state, viewModel::onIntent)
                ReviewTab.Suspicious -> SuspiciousList(state, viewModel::onIntent)
                ReviewTab.Links -> ProposalList(state, viewModel::onIntent)
            }
        }
    }

    when (val dialog = state.dialog) {
        is ReviewDialog.CategoryFor -> CategoryDialog(
            categories = state.categories,
            onPick = { viewModel.onIntent(NeedsReviewIntent.PickCategory(it)) },
            onDismiss = { viewModel.onIntent(NeedsReviewIntent.DismissDialog) },
        )
        is ReviewDialog.MerchantFor -> MerchantDialog(
            merchants = state.merchants,
            onPick = { viewModel.onIntent(NeedsReviewIntent.PickMerchant(it)) },
            onDismiss = { viewModel.onIntent(NeedsReviewIntent.DismissDialog) },
        )
        is ReviewDialog.Manual -> ManualDialog(
            draft = dialog.draft,
            accounts = state.accounts,
            categories = state.categories,
            onIntent = viewModel::onIntent,
        )
        null -> Unit
    }
}

@StringRes
private fun messageRes(message: ReviewMessage): Int = when (message) {
    ReviewMessage.Recorded -> R.string.review_msg_recorded
    ReviewMessage.NotRecorded -> R.string.review_msg_not_recorded
    ReviewMessage.NoBody -> R.string.review_msg_no_body
    ReviewMessage.Failed -> R.string.review_msg_failed
    ReviewMessage.ManualSaved -> R.string.review_msg_manual_saved
    ReviewMessage.Ignored -> R.string.review_msg_ignored
    ReviewMessage.Confirmed -> R.string.review_msg_confirmed
    ReviewMessage.Linked -> R.string.review_msg_linked
    ReviewMessage.Dismissed -> R.string.review_msg_dismissed
}

// ───────────────────────────── tabs ─────────────────────────────

@Composable
private fun TabChips(state: NeedsReviewUiState, onSelect: (ReviewTab) -> Unit) {
    val tabs = listOf(
        Triple(ReviewTab.Unparsed, R.string.review_tab_unparsed, state.counts.unparsed),
        Triple(ReviewTab.Check, R.string.review_tab_check, state.counts.check),
        Triple(ReviewTab.Suspicious, R.string.review_tab_suspicious, state.counts.suspicious),
        Triple(ReviewTab.Links, R.string.review_tab_links, state.counts.proposals),
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(tabs, key = { it.first.name }) { (tab, label, count) ->
            val selected = tab == state.tab
            val text = stringResource(label)
            FilterChip(
                selected = selected,
                onClick = { onSelect(tab) },
                label = { Text(stringResource(R.string.review_tab_label, text, count)) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = "$text, $count" },
            )
        }
    }
}

@Composable
private fun EmptyTab(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(24.dp),
    )
}

private val ListPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

// ───────────────────────────── Unparsed ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnparsedList(state: NeedsReviewUiState, onIntent: (NeedsReviewIntent) -> Unit) {
    val reduced = rememberReducedMotion()
    if (state.unparsed.isEmpty()) {
        EmptyTab(R.string.review_empty_unparsed)
        return
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.unparsed.size, key = { "unparsed-${state.unparsed[it].id}" }) { index ->
            val item = state.unparsed[index]
            Appear(index, itemMotion(reduced)) {
                ReviewCard {
                    MessageHeader(item.header, item.receivedAt, stringResource(unparsedReason(item.reason)))
                    MessageBody(item.body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // docs/08 §2.5: "teach template" arrives with P7; it is shown so the path is visible.
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.review_teach_template))
                        }
                        FilledTonalButton(
                            onClick = { onIntent(NeedsReviewIntent.OpenManual(item.id, item.receivedAt)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_add_manually)) }
                        TextButton(
                            onClick = { onIntent(NeedsReviewIntent.Ignore(item.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_ignore)) }
                    }
                    Text(
                        text = stringResource(R.string.review_teach_later),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ───────────────────────────── Check ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckList(state: NeedsReviewUiState, onIntent: (NeedsReviewIntent) -> Unit) {
    val reduced = rememberReducedMotion()
    if (state.check.isEmpty()) {
        EmptyTab(R.string.review_empty_check)
        return
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.check.size, key = { "check-${state.check[it].line.id}" }) { index ->
            val item = state.check[index]
            Appear(index, itemMotion(reduced)) {
                ReviewCard {
                    TxnLine(item.line)
                    Text(
                        text = stringResource(checkReason(item.reason)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onIntent(NeedsReviewIntent.Confirm(item.line.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_looks_right)) }
                        OutlinedButton(
                            onClick = { onIntent(NeedsReviewIntent.OpenCategoryPicker(item.line.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_change_category)) }
                        OutlinedButton(
                            onClick = { onIntent(NeedsReviewIntent.OpenMerchantPicker(item.line.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_change_merchant)) }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── Suspicious ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuspiciousList(state: NeedsReviewUiState, onIntent: (NeedsReviewIntent) -> Unit) {
    val reduced = rememberReducedMotion()
    var confirmRecord by remember { mutableStateOf<Long?>(null) }
    if (state.suspicious.isEmpty()) {
        EmptyTab(R.string.review_empty_suspicious)
        return
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "warning") {
            Text(
                text = stringResource(R.string.review_suspicious_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.suspicious.size, key = { "suspicious-${state.suspicious[it].id}" }) { index ->
            val item = state.suspicious[index]
            Appear(index, itemMotion(reduced)) {
                ReviewCard {
                    MessageHeader(item.header, item.receivedAt, stringResource(suspiciousReason(item.reason)))
                    MessageBody(item.body)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.canRecord) {
                            FilledTonalButton(
                                onClick = { confirmRecord = item.id },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.review_record_genuine)) }
                        }
                        TextButton(
                            onClick = { onIntent(NeedsReviewIntent.DeleteSuspicious(item.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_delete)) }
                    }
                }
            }
        }
    }
    confirmRecord?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.review_record_title),
            message = stringResource(R.string.review_record_message),
            confirmLabel = stringResource(R.string.review_record_confirm),
            onConfirm = {
                confirmRecord = null
                onIntent(NeedsReviewIntent.Record(id))
            },
            onDismiss = { confirmRecord = null },
        )
    }
}

// ───────────────────────────── Duplicates & transfers ─────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProposalList(state: NeedsReviewUiState, onIntent: (NeedsReviewIntent) -> Unit) {
    val reduced = rememberReducedMotion()
    if (state.proposals.isEmpty()) {
        EmptyTab(R.string.review_empty_links)
        return
    }
    LazyColumn(contentPadding = ListPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(state.proposals.size, key = { "proposal-${state.proposals[it].id}" }) { index ->
            val item = state.proposals[index]
            Appear(index, itemMotion(reduced)) {
                ReviewCard {
                    Text(
                        text = stringResource(
                            if (item.type == ProposalType.DUPLICATE) R.string.review_link_duplicate else R.string.review_link_transfer,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(
                            if (item.type == ProposalType.DUPLICATE) R.string.review_link_duplicate_hint else R.string.review_link_transfer_hint,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    item.first?.let { TxnLine(it) }
                    item.second?.let { TxnLine(it) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onIntent(NeedsReviewIntent.Accept(item.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_link_yes)) }
                        OutlinedButton(
                            onClick = { onIntent(NeedsReviewIntent.Reject(item.id)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.review_link_no)) }
                    }
                }
            }
        }
    }
}

// ───────────────────────────── shared pieces ─────────────────────────────

/** Rows fade and slide into place; with animations off (animator scale 0) they just appear (docs/08 §4). */
private fun LazyItemScope.itemMotion(reduced: Boolean): Modifier = Modifier.animateItem(
    fadeInSpec = if (reduced) null else tween(200),
    placementSpec = if (reduced) null else spring(),
    fadeOutSpec = if (reduced) null else tween(120),
)

@Composable
private fun ReviewCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun MessageHeader(header: String, receivedAt: Long, reason: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = header,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = header.toList().joinToString(" ") },
        )
        Text(
            text = DATE_TIME.format(Instant.ofEpochMilli(receivedAt).atZone(IST)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Text(text = reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** The SMS text, shown so the person can decide. It is their own message on their own phone and is never logged. */
@Composable
private fun MessageBody(body: String?) {
    Text(
        text = body ?: stringResource(R.string.review_body_removed),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 5,
    )
}

/** `−₹450` / `+₹2,000`: the sign and the word make direction readable without colour. */
internal fun amountLabel(paise: Long, isDebit: Boolean): String =
    (if (isDebit) "−" else "+") + Money.format(paise)

@Composable
internal fun spokenAmount(paise: Long): String {
    val rupees = paise / 100
    val rest = (paise % 100).toInt()
    val grouped = Money.format(rupees * 100).removePrefix("₹")
    return if (rest == 0) {
        stringResource(R.string.amount_spoken_rupees, grouped)
    } else {
        stringResource(R.string.amount_spoken_rupees_paise, grouped, rest)
    }
}

@Composable
private fun TxnLine(line: TxnLineUi) {
    val date = DATE_TIME.format(Instant.ofEpochMilli(line.occurredAt).atZone(IST))
    val verb = stringResource(if (line.isDebit) R.string.review_spent else R.string.review_received)
    val title = line.title.ifBlank { stringResource(R.string.review_unknown_counterparty) }
    val category = line.categoryName ?: stringResource(R.string.review_uncategorised)
    val description = stringResource(
        R.string.review_txn_description, verb, spokenAmount(line.amountPaise), title, category, date, line.accountName,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MerchantAvatar(
            name = title,
            nameColorIndex = NameColor.indexFor(title),
            categoryIconKey = line.categoryIcon,
            categoryColorIndex = line.categoryColor,
            image = null,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "$category · $date",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (line.accountName.isNotBlank()) {
                Text(
                    text = line.accountName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = amountLabel(line.amountPaise, line.isDebit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(text = verb, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@StringRes
private fun unparsedReason(reason: String): Int = when (reason) {
    "no_template" -> R.string.review_reason_no_template
    "unknown_account" -> R.string.review_reason_unknown_account
    "amount_over_cap" -> R.string.review_reason_amount_over_cap
    "balance_equals_amount" -> R.string.review_reason_balance_equals_amount
    "invalid_amount" -> R.string.review_reason_invalid_amount
    else -> R.string.review_reason_error
}

@StringRes
private fun checkReason(reason: String): Int = when (reason) {
    "low_confidence" -> R.string.review_check_low_confidence
    "unknown_merchant" -> R.string.review_check_unknown_merchant
    "date_suspect" -> R.string.review_check_date_suspect
    else -> R.string.review_check_generic
}

@StringRes
private fun suspiciousReason(reason: String): Int = when (reason) {
    "red_flags" -> R.string.review_suspicious_red_flags
    "missing_suffix" -> R.string.review_suspicious_missing_suffix
    "phone_sender_txn_like" -> R.string.review_suspicious_phone
    else -> R.string.review_suspicious_generic
}

// ───────────────────────────── dialogs ─────────────────────────────

@Composable
private fun CategoryDialog(categories: List<CategoryChoice>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_pick_category)) },
        text = { CategoryChoices(categories, selected = null, onPick = { it?.let(onPick) }, allowNone = false) },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun CategoryChoices(
    categories: List<CategoryChoice>,
    selected: Long?,
    onPick: (Long?) -> Unit,
    allowNone: Boolean,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        if (allowNone) {
            ChoiceRow(label = stringResource(R.string.review_uncategorised), chosen = selected == null, onClick = { onPick(null) })
        }
        categories.forEach { c ->
            ChoiceRow(label = c.name, chosen = c.id == selected, onClick = { onPick(c.id) })
        }
    }
}

@Composable
private fun ChoiceRow(label: String, chosen: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = chosen, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = chosen, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun MerchantDialog(merchants: List<MerchantChoice>, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_pick_merchant)) },
        text = {
            if (merchants.isEmpty()) {
                Text(stringResource(R.string.review_no_merchants))
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    merchants.forEach { m -> ChoiceRow(label = m.name, chosen = false, onClick = { onPick(m.id) }) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualDialog(
    draft: ManualDraft,
    accounts: List<AccountOption>,
    categories: List<CategoryChoice>,
    onIntent: (NeedsReviewIntent) -> Unit,
) {
    val dismiss = { onIntent(NeedsReviewIntent.DismissDialog) }
    if (draft.pickingCategory) {
        AlertDialog(
            onDismissRequest = { onIntent(NeedsReviewIntent.ManualOpenCategory(false)) },
            title = { Text(stringResource(R.string.review_pick_category)) },
            text = {
                CategoryChoices(categories, selected = draft.categoryId, onPick = { onIntent(NeedsReviewIntent.ManualCategory(it)) }, allowNone = true)
            },
            confirmButton = {},
            dismissButton = {
                TextButton(
                    onClick = { onIntent(NeedsReviewIntent.ManualOpenCategory(false)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        return
    }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.review_manual_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = DATE_TIME.format(Instant.ofEpochMilli(draft.receivedAt).atZone(IST)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val options = listOf(true to R.string.review_manual_spent, false to R.string.review_manual_received)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    options.forEachIndexed { index, (debit, label) ->
                        SegmentedButton(
                            selected = draft.isDebit == debit,
                            onClick = { onIntent(NeedsReviewIntent.ManualDirection(debit)) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(label)) }
                    }
                }
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { onIntent(NeedsReviewIntent.ManualAmount(it)) },
                    label = { Text(stringResource(R.string.review_manual_amount)) },
                    prefix = { Text("₹") },
                    singleLine = true,
                    isError = draft.error == ManualError.Amount,
                    supportingText = if (draft.error == ManualError.Amount) {
                        { Text(stringResource(R.string.review_manual_amount_error)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.review_manual_account), style = MaterialTheme.typography.titleSmall)
                if (accounts.isEmpty()) {
                    ErrorText(stringResource(R.string.review_manual_no_accounts))
                }
                accounts.forEach { a ->
                    ChoiceRow(
                        label = "${a.name} ••${a.last4}",
                        chosen = draft.accountId == a.id,
                        onClick = { onIntent(NeedsReviewIntent.ManualAccount(a.id)) },
                    )
                }
                if (draft.error == ManualError.Account) ErrorText(stringResource(R.string.review_manual_account_error))
                val categoryName = categories.firstOrNull { it.id == draft.categoryId }?.name
                OutlinedButton(
                    onClick = { onIntent(NeedsReviewIntent.ManualOpenCategory(true)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.review_manual_category, categoryName ?: stringResource(R.string.review_uncategorised)))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onIntent(NeedsReviewIntent.ManualSave) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
