package com.ledgr.app.ui.reconcile

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ledgr.app.R
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.statement.AmountStyle
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.StatementField
import com.ledgr.app.ui.profile.SubScreenScaffold
import com.ledgr.app.ui.theme.LedgrTheme

/** docs/06 §1.6: "which column is what", with five parsed lines to check it against. */
@Composable
fun MappingScreen(
    onSaved: (Long) -> Unit,
    onClosed: () -> Unit,
    viewModel: MappingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { e ->
            when (e) {
                is MappingEvent.Saved -> onSaved(e.importId)
                MappingEvent.Closed -> onClosed()
            }
        }
    }
    if (state.lost) {
        LostDraft(onBack = onClosed)
        return
    }
    SubScreenScaffold(title = stringResource(R.string.recon_map_title), onBack = { viewModel.onIntent(MappingIntent.Cancel) }) { padding ->
        if (state.loading) {
            Column(Modifier.padding(padding).fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
            }
        } else {
            MappingBody(state, viewModel::onIntent, Modifier.padding(padding))
        }
    }
    state.error?.let { code ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(MappingIntent.DismissError) },
            title = { Text(stringResource(R.string.recon_error_title)) },
            text = { Text(stringResource(errorText(code))) },
            confirmButton = { TextButton(onClick = { viewModel.onIntent(MappingIntent.DismissError) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_ok)) } },
        )
    }
}

@Composable
private fun LostDraft(onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.recon_map_title), onBack = onBack) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.recon_err_draft_lost), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_back_to_start)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MappingBody(state: MappingUiState, onIntent: (MappingIntent) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "intro") {
            Text(stringResource(R.string.recon_map_intro, state.fileName), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.type == FileType.PDF && state.pdfLines.isNotEmpty()) {
            item(key = "pdf") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionHeading(R.string.recon_dividers_title)
                    Text(stringResource(R.string.recon_dividers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PdfDividerEditor(
                        lines = state.pdfLines,
                        dividers = state.dividers,
                        onChange = { onIntent(MappingIntent.SetDividers(it)) },
                        onReset = { onIntent(MappingIntent.ResetDividers) },
                    )
                }
            }
        }
        item(key = "style") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeading(R.string.recon_style_title)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountStyle.entries.forEach { style ->
                        ChoiceChip(selected = state.style == style, label = stringResource(amountStyleLabel(style)), onClick = { onIntent(MappingIntent.SetStyle(style)) })
                    }
                }
            }
        }
        item(key = "fields") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading(R.string.recon_fields_title)
                fieldsFor(state.style).forEach { field ->
                    FieldPicker(
                        field = field,
                        required = field in listOf(StatementField.DATE) || field in requiredFor(state.style),
                        column = state.choices[field],
                        header = state.headerCells,
                        onPick = { onIntent(MappingIntent.SetField(field, it)) },
                    )
                }
            }
        }
        item(key = "date") {
            DateFormatPicker(state, onIntent)
        }
        item(key = "preview") { Preview(state) }
        if (state.warningCount > 0) {
            item(key = "warnings") {
                Text(
                    pluralStringResource(R.plurals.recon_map_warnings, state.warningCount, state.warningCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LedgrTheme.colors.warning,
                )
            }
        }
        item(key = "remember") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.remember, onCheckedChange = { onIntent(MappingIntent.ToggleRemember) }, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.recon_map_remember), modifier = Modifier.weight(1f))
            }
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.missing.isNotEmpty()) {
                    Text(
                        stringResource(R.string.recon_map_missing, state.missing.map { stringResource(fieldLabel(it)) }.joinToString(", ")),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Button(onClick = { onIntent(MappingIntent.Save) }, enabled = state.canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    if (state.saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.recon_map_save))
                }
                OutlinedButton(onClick = { onIntent(MappingIntent.Cancel) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.recon_cancel))
                }
            }
        }
    }
}

private fun requiredFor(style: AmountStyle): List<StatementField> = when (style) {
    AmountStyle.DEBIT_CREDIT_COLUMNS -> listOf(StatementField.DEBIT, StatementField.CREDIT)
    AmountStyle.SIGNED, AmountStyle.AMOUNT_PLUS_DRCR -> listOf(StatementField.AMOUNT)
}

@Composable
private fun SectionHeading(res: Int) {
    Text(stringResource(res), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
}

/** "Date: [Column 1 · Date ▾]". Required fields say so in words. */
@Composable
private fun FieldPicker(field: StatementField, required: Boolean, column: Int?, header: List<String>, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = stringResource(fieldLabel(field))
    val none = stringResource(R.string.recon_field_none)
    val current = column?.let { columnLabel(it, header) } ?: none
    val description = stringResource(R.string.recon_field_description_format, name, current)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (required) stringResource(R.string.recon_field_required, name) else name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.45f),
        )
        Column(modifier = Modifier.weight(0.55f)) {
            Surface(
                onClick = { open = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = description },
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(current, maxLines = 1, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(none) },
                    onClick = {
                        open = false
                        onPick(null)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                header.indices.forEach { i ->
                    DropdownMenuItem(
                        text = { Text(columnLabel(i, header)) },
                        onClick = {
                            open = false
                            onPick(i)
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun columnLabel(index: Int, header: List<String>): String {
    val text = header.getOrNull(index).orEmpty().ifBlank { stringResource(R.string.recon_column_blank) }
    return stringResource(R.string.recon_column_label, index + 1, text)
}

@Composable
private fun DateFormatPicker(state: MappingUiState, onIntent: (MappingIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(R.string.recon_date_title)
        state.dateExample?.let {
            Text(stringResource(R.string.recon_date_example, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column {
            Surface(
                onClick = { open = true },
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.dateFormat ?: stringResource(R.string.recon_date_unknown),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.recon_date_choose))
                }
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                state.dateFormats.forEach { pattern ->
                    DropdownMenuItem(
                        text = { Text(pattern, fontFamily = FontFamily.Monospace) },
                        onClick = {
                            open = false
                            onIntent(MappingIntent.SetDateFormat(pattern))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Preview(state: MappingUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeading(R.string.recon_preview_title)
        if (state.preview.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.recon_preview_count, state.lineCount, state.lineCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.preview.forEach { l ->
                val amount = if (l.debit != 0L) "−" + Money.format(l.debit) else "+" + Money.format(l.credit)
                val spoken = stringResource(
                    if (l.debit != 0L) R.string.recon_preview_row_spent else R.string.recon_preview_row_received,
                    l.date.format(DAY_MONTH_YEAR), l.description, Money.format(if (l.debit != 0L) l.debit else l.credit),
                )
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().semantics { contentDescription = spoken }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(l.date.format(DAY_MONTH_YEAR), style = MaterialTheme.typography.labelMedium)
                            Text(l.description, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                amount,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (l.debit != 0L) LedgrTheme.colors.spend else LedgrTheme.colors.income,
                            )
                            l.balance?.let {
                                Text(stringResource(R.string.recon_balance_short, Money.format(it)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        } else {
            Text(stringResource(R.string.recon_preview_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.rawPreview.forEach { cells ->
                Text(cells.joinToString("  ·  ").take(160), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2)
            }
        }
    }
}
