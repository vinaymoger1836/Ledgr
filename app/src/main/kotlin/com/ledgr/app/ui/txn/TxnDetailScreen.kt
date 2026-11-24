package com.ledgr.app.ui.txn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.domain.ledger.Source
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.ledger.TxnKinds
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.profile.NameColor
import com.ledgr.app.domain.time.Ist
import com.ledgr.app.ui.nav.sharedTxnBounds
import com.ledgr.app.ui.nav.sharedTxnElement
import com.ledgr.app.ui.profile.CategoryBadge
import com.ledgr.app.ui.profile.ConfirmDialog
import com.ledgr.app.ui.profile.ErrorText
import com.ledgr.app.ui.profile.MerchantAvatar
import com.ledgr.app.ui.profile.SectionTitle
import com.ledgr.app.ui.profile.SubScreenScaffold
import com.ledgr.app.ui.review.spokenAmount
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_FIELD: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val TIME_FIELD: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

/**
 * Transaction detail and edit, or a new manual entry when opened from the Home "+" (docs/08 §2.1). Merchant, category
 * (optionally for every row of that merchant), kind, amount, date, time, account and note can be changed; delete asks
 * first. Provenance (where the row came from) is shown read-only.
 */
@Composable
fun TxnDetailScreen(onBack: () -> Unit, viewModel: TxnDetailViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onBack() }

    SubScreenScaffold(
        title = stringResource(if (state.isNew) R.string.txn_title_new else R.string.txn_title),
        onBack = onBack,
        actions = {
            if (!state.isNew && state.loaded && !state.notFound) {
                IconButton(onClick = { viewModel.onIntent(TxnIntent.Open(TxnDialog.ConfirmDelete)) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.txn_delete))
                }
            }
        },
    ) { padding ->
        when {
            !state.loaded -> Unit
            state.notFound -> Text(
                text = stringResource(R.string.txn_not_found),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .padding(padding)
                    .padding(24.dp),
            )
            else -> Form(state = state, onIntent = viewModel::onIntent, modifier = Modifier.padding(padding))
        }
    }

    Dialogs(state = state, onIntent = viewModel::onIntent)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(state: TxnFormUi, onIntent: (TxnIntent) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!state.isNew) Header(state)

        if (state.isNew) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(true to R.string.txn_direction_spent, false to R.string.txn_direction_received).forEachIndexed { i, (debit, label) ->
                    SegmentedButton(
                        selected = state.isDebit == debit,
                        onClick = { onIntent(TxnIntent.SetDirection(debit)) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(label)) }
                }
            }
        }

        val amountError = state.error == TxnFormError.Amount
        OutlinedTextField(
            value = state.amountText,
            onValueChange = { onIntent(TxnIntent.SetAmount(it)) },
            label = { Text(stringResource(R.string.txn_amount)) },
            prefix = { Text("₹") },
            singleLine = true,
            isError = amountError,
            supportingText = if (amountError) ({ Text(stringResource(R.string.txn_amount_error)) }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (amountError) error("") },
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.txn_kind))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val kinds = TxnKinds.forDirection(state.direction).let { if (state.kind in it) it else listOf(state.kind) + it }
                for (kind in kinds) {
                    FilterChip(
                        selected = kind == state.kind,
                        onClick = { onIntent(TxnIntent.SetKind(kind)) },
                        label = { Text(stringResource(kindLabel(kind))) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }

        PickerRow(
            title = stringResource(R.string.txn_merchant),
            value = state.merchantName ?: stringResource(R.string.txn_merchant_none),
            onClick = { onIntent(TxnIntent.Open(TxnDialog.Merchant)) },
        )

        val category = state.category
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PickerRow(
                title = stringResource(R.string.txn_category),
                value = category?.name ?: stringResource(R.string.review_uncategorised),
                onClick = { onIntent(TxnIntent.Open(TxnDialog.Category)) },
                leading = category?.let { { CategoryBadge(it.icon, it.color, size = 32.dp) } },
            )
            // "Apply to all from this merchant": only for a saved row with a merchant and a category.
            if (!state.isNew && state.counterpartyId != null && state.categoryId != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .toggleable(value = state.applyToMerchant, role = Role.Switch, onValueChange = { onIntent(TxnIntent.SetApply(it)) }),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.txn_apply_all), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.txn_apply_all_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.applyToMerchant, onCheckedChange = null)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { onIntent(TxnIntent.Open(TxnDialog.Date)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(DATE_FIELD.format(state.date)) }
            OutlinedButton(
                onClick = { onIntent(TxnIntent.Open(TxnDialog.Time)) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            ) { Text(TIME_FIELD.format(Ist.localTime(state.occurredAt))) }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val accountError = state.error == TxnFormError.Account
            SectionTitle(stringResource(R.string.txn_account))
            if (state.accounts.isEmpty()) {
                Text(
                    text = stringResource(R.string.review_manual_no_accounts),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (account in state.accounts) {
                    FilterChip(
                        selected = account.id == state.accountId,
                        onClick = { onIntent(TxnIntent.SetAccount(account.id)) },
                        label = { Text(account.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (accountError) ErrorText(stringResource(R.string.review_manual_account_error))
        }

        OutlinedTextField(
            value = state.note,
            onValueChange = { onIntent(TxnIntent.SetNote(it)) },
            label = { Text(stringResource(R.string.txn_note)) },
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        state.provenance?.let { ProvenanceBlock(it) }

        if (state.error == TxnFormError.Failed) ErrorText(stringResource(R.string.review_msg_failed))
        Button(
            onClick = { onIntent(TxnIntent.Save) },
            enabled = !state.saving,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.action_save)) }
    }
}

/** The row's own avatar and amount: the other end of the shared-element transition from Home. */
@Composable
private fun Header(state: TxnFormUi) {
    val title = state.merchantName ?: stringResource(kindLabel(state.kind))
    val category = state.category
    val paise = state.amountPaise ?: 0L
    val sign = if (state.isDebit) "−" else "+"
    val spoken = stringResource(
        if (state.isDebit) R.string.review_spent else R.string.review_received,
    ) + " " + spokenAmount(paise) + ", " + title
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MerchantAvatar(
            name = title,
            nameColorIndex = NameColor.indexFor(title),
            categoryIconKey = category?.icon,
            categoryColorIndex = category?.color,
            image = null,
            size = 56.dp,
            modifier = Modifier.sharedTxnElement("avatar-${state.txnId}"),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(kindLabel(state.kind)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = sign + Money.format(paise),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.sharedTxnBounds("amount-${state.txnId}"),
        )
    }
}

@Composable
private fun PickerRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$title: $value" },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 56.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Where this row came from and which rules made it: shown, never editable. */
@Composable
private fun ProvenanceBlock(p: Provenance) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionTitle(stringResource(R.string.txn_provenance))
        val source = stringResource(
            when (p.source) {
                Source.SMS -> R.string.txn_source_sms
                Source.STATEMENT -> R.string.txn_source_statement
                Source.MANUAL -> R.string.txn_source_manual
            },
        )
        val lines = listOfNotNull(
            stringResource(R.string.txn_prov_source, source),
            p.templateId?.let { stringResource(R.string.txn_prov_template, it) },
            stringResource(R.string.txn_prov_rules, p.rulesVersion),
            stringResource(R.string.txn_prov_confidence, p.confidence),
            p.rawCounterparty?.let { stringResource(R.string.txn_prov_raw, it) },
        )
        lines.forEach {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dialogs(state: TxnFormUi, onIntent: (TxnIntent) -> Unit) {
    val dismiss = { onIntent(TxnIntent.CloseDialog) }
    when (state.dialog) {
        null -> Unit
        TxnDialog.Category -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.review_pick_category)) },
            text = {
                val fitting = state.categories.filter { it.kind == categoryKindFor(state.kind) }
                LazyColumn {
                    items(fitting, key = { it.id }) { c ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .selectable(selected = c.id == state.categoryId, role = Role.Button) { onIntent(TxnIntent.PickCategory(c.id)) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CategoryBadge(c.icon, c.color)
                            Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
        TxnDialog.Merchant -> AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(R.string.review_pick_merchant)) },
            text = {
                LazyColumn {
                    item(key = "none") {
                        PickText(stringResource(R.string.txn_merchant_none), state.counterpartyId == null) { onIntent(TxnIntent.PickMerchant(null)) }
                    }
                    if (state.merchants.isEmpty()) {
                        item(key = "empty") {
                            Text(stringResource(R.string.review_no_merchants), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(state.merchants, key = { it.id }) { m ->
                        PickText(m.name, m.id == state.counterpartyId) { onIntent(TxnIntent.PickMerchant(m.id)) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
        )
        TxnDialog.Date -> {
            val today = Ist.today()
            val picker = rememberDatePickerState(
                initialSelectedDateMillis = utcMillis(state.date),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcDate(utcTimeMillis) <= today
                },
            )
            DatePickerDialog(
                onDismissRequest = dismiss,
                confirmButton = {
                    TextButton(
                        onClick = { picker.selectedDateMillis?.let { onIntent(TxnIntent.PickDate(utcDate(it))) } ?: dismiss() },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_done)) }
                },
                dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
            ) { DatePicker(state = picker) }
        }
        TxnDialog.Time -> {
            val at = Ist.localTime(state.occurredAt)
            val picker = rememberTimePickerState(initialHour = at.hour, initialMinute = at.minute, is24Hour = false)
            AlertDialog(
                onDismissRequest = dismiss,
                text = { TimePicker(state = picker) },
                confirmButton = {
                    TextButton(
                        onClick = { onIntent(TxnIntent.PickTime(picker.hour, picker.minute)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_done)) }
                },
                dismissButton = { TextButton(onClick = dismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) } },
            )
        }
        TxnDialog.ConfirmDelete -> ConfirmDialog(
            title = stringResource(R.string.txn_delete_title),
            message = stringResource(R.string.txn_delete_message),
            confirmLabel = stringResource(R.string.txn_delete),
            onConfirm = { onIntent(TxnIntent.ConfirmDelete) },
            onDismiss = dismiss,
        )
    }
}

@Composable
private fun PickText(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The Material date picker works in UTC midnights; a [LocalDate] goes in and out through them. */
private fun utcMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
