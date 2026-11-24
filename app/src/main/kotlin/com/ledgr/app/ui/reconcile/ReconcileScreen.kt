package com.ledgr.app.ui.reconcile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.ledgr.app.R
import com.ledgr.app.ui.nav.LedgrIcons
import java.time.LocalDate

/**
 * The Reconcile tab (docs/08 §2.3): pick the account, pick a file, and see earlier imports. Everything after the pick (password,
 * mapping, results) opens from here.
 */
@Composable
fun ReconcileScreen(
    onOpenMapping: (Long) -> Unit,
    onOpenResults: (Long) -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReconcileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is ReconcileEvent.OpenMapping -> onOpenMapping(event.draftId)
                is ReconcileEvent.OpenResults -> onOpenResults(event.importId)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.onIntent(ReconcileIntent.FilePicked(uri))
    }
    ReconcileContent(
        state = state,
        onIntent = viewModel::onIntent,
        onPick = { picker.launch(arrayOf("*/*")) },
        onOpenResults = onOpenResults,
        onOpenProfile = onOpenProfile,
        modifier = modifier,
    )
}

@Composable
private fun ReconcileContent(
    state: ReconcileUiState,
    onIntent: (ReconcileIntent) -> Unit,
    onPick: () -> Unit,
    onOpenResults: (Long) -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") {
            Text(
                text = stringResource(R.string.reconcile_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.accounts.isEmpty()) {
            item(key = "no-accounts") { NoAccounts(onOpenProfile) }
        } else {
            item(key = "accounts") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.recon_pick_account), style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.accounts, key = { it.id }) { a ->
                            ChoiceChip(
                                selected = state.selectedAccountId == a.id,
                                label = a.label,
                                onClick = { onIntent(ReconcileIntent.SelectAccount(a.id)) },
                                dot = Color(a.color),
                            )
                        }
                    }
                }
            }
            item(key = "import") { ImportCard(enabled = state.canImport, busy = state.busy, onClick = onPick) }
        }
        item(key = "history-title") {
            Text(
                text = stringResource(R.string.recon_history),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (state.history.isEmpty()) {
            item(key = "history-empty") {
                Text(
                    stringResource(R.string.recon_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(state.history, key = { "h${it.id}" }) { row ->
                HistoryCard(row, onOpen = { onOpenResults(row.id) }, onIntent = onIntent)
            }
        }
    }

    state.password?.let { PasswordSheet(wrong = it.wrong, onSubmit = { p -> onIntent(ReconcileIntent.SubmitPassword(p)) }, onCancel = { onIntent(ReconcileIntent.CancelPassword) }) }
    state.error?.let { code ->
        AlertDialog(
            onDismissRequest = { onIntent(ReconcileIntent.DismissError) },
            title = { Text(stringResource(R.string.recon_error_title)) },
            text = { Text(stringResource(errorText(code))) },
            confirmButton = { TextButton(onClick = { onIntent(ReconcileIntent.DismissError) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_ok)) } },
        )
    }
    state.pendingDelete?.let { DeleteDialog(onConfirm = { also -> onIntent(ReconcileIntent.ConfirmDelete(also)) }, onCancel = { onIntent(ReconcileIntent.CancelDelete) }) }
}

@Composable
private fun NoAccounts(onOpenProfile: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recon_no_accounts), style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onOpenProfile, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_open_profile)) }
        }
    }
}

@Composable
private fun ImportCard(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.recon_import_description)
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Icon(LedgrIcons.Reconcile, contentDescription = null, modifier = Modifier.size(32.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(if (busy) R.string.recon_reading else R.string.recon_import),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("PDF", "Excel", "CSV").forEach { FileTypePill(it) }
                }
            }
        }
    }
}

/** A small text pill, so the supported types are readable rather than shown by icon alone. */
@Composable
private fun FileTypePill(text: String) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
private fun HistoryCard(row: HistoryRow, onOpen: () -> Unit, onIntent: (ReconcileIntent) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val period = LocalDate.parse(row.periodFrom).format(DAY_MONTH_YEAR) + " – " + LocalDate.parse(row.periodTo).format(DAY_MONTH_YEAR)
    val lines = pluralStringResource(R.plurals.recon_lines, row.lineCount, row.lineCount)
    val description = stringResource(R.string.recon_history_description, row.accountLabel, period, lines, row.matchRateBp / 100)
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp).heightIn(min = 72.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MatchRing(row.matchRateBp, 48.dp)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(modifier = Modifier.size(10.dp).background(Color(row.accountColor), CircleShape))
                    Text(row.accountLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                }
                Text(period, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$lines · ${row.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.recon_more_actions))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recon_rerun)) },
                        onClick = {
                            menu = false
                            onIntent(ReconcileIntent.Rerun(row.id))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.recon_delete)) },
                        onClick = {
                            menu = false
                            onIntent(ReconcileIntent.AskDelete(row.id))
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
        }
    }
}

/** The password is held only in this composable's state and passed once; it is never stored (docs/06 §1.3). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PasswordSheet(wrong: Boolean, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var visible by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onCancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.recon_password_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.recon_password_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.recon_password_label)) },
                singleLine = true,
                isError = wrong,
                supportingText = if (wrong) {
                    { Text(stringResource(R.string.recon_err_wrong_password)) }
                } else {
                    null
                },
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = visible, onCheckedChange = { visible = it }, modifier = Modifier.size(48.dp))
                Text(stringResource(R.string.recon_password_show))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_cancel)) }
                Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.recon_password_open))
                }
            }
        }
    }
}

@Composable
private fun DeleteDialog(onConfirm: (Boolean) -> Unit, onCancel: () -> Unit) {
    var alsoAdded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
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
        confirmButton = { TextButton(onClick = { onConfirm(alsoAdded) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_delete)) } },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_cancel)) } },
    )
}
