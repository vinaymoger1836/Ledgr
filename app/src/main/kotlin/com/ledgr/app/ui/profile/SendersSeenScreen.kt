package com.ledgr.app.ui.profile

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.ui.motion.rememberReducedMotion
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Senders seen (docs/03 §3, docs/08 §2.4): every sender header in the SMS inbox with a count, and a way to say
 * which bank each belongs to. Reads sender and date only, never message text.
 */
@Composable
fun SendersSeenScreen(onBack: () -> Unit, viewModel: SendersSeenViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    // Re-check on every return to the screen: the user may have just allowed SMS in App info.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(SendersIntent.Refresh) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.onIntent(SendersIntent.PermissionResult(granted))
    }

    val message = state.message
    val messageText = when (message) {
        is SendersMessage.Assigned ->
            pluralStringResource(R.plurals.senders_assigned, message.accounts, message.header, message.bank, message.accounts)
        is SendersMessage.Unassigned -> stringResource(R.string.senders_unassigned, message.header)
        SendersMessage.Failed -> stringResource(R.string.senders_failed)
        null -> null
    }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.onIntent(SendersIntent.MessageShown)
        }
    }

    SubScreenScaffold(title = stringResource(R.string.senders_title), onBack = onBack, snackbarHostState = snackbar) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (state.access) {
                SmsAccess.Checking -> Unit
                SmsAccess.NeedsPermission -> ExplainerPanel(
                    onAllow = { permission.launch(Manifest.permission.READ_SMS) },
                )
                SmsAccess.Blocked -> BlockedPanel(
                    onOpenAppInfo = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onTryAgain = { permission.launch(Manifest.permission.READ_SMS) },
                )
                SmsAccess.Granted -> SendersList(state = state, onIntent = viewModel::onIntent)
            }
        }
    }

    state.pickerHeader?.let { header ->
        BankPickerDialog(
            header = header,
            banks = state.banks,
            withAccounts = state.bankIdsWithAccounts,
            currentBankId = state.pickerCurrentBankId,
            onPick = { viewModel.onIntent(SendersIntent.Assign(header, it)) },
            onNone = { viewModel.onIntent(SendersIntent.Unassign(header)) },
            onDismiss = { viewModel.onIntent(SendersIntent.ClosePicker) },
        )
    }

    state.noAccountBank?.let { bank ->
        AlertDialog(
            onDismissRequest = { viewModel.onIntent(SendersIntent.DismissNoAccount) },
            title = { Text(stringResource(R.string.senders_no_account_title, bank.name)) },
            text = { Text(stringResource(R.string.senders_no_account_message, bank.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onIntent(SendersIntent.DismissNoAccount)
                        onBack()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.senders_no_account_go)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.onIntent(SendersIntent.DismissNoAccount) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Composable
private fun PanelScaffold(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = { content() },
    )
}

/** docs/03 §1: say what is read, why, and where it stays, before the system dialog. */
@Composable
private fun ExplainerPanel(onAllow: () -> Unit) {
    PanelScaffold {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.senders_explainer_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(stringResource(R.string.senders_explainer_what), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.senders_explainer_why), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.senders_explainer_private),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.senders_allow))
        }
        Text(
            stringResource(R.string.senders_skip_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** docs/03 §1 sideload note: the grant may be refused until "Allow restricted settings" is turned on. */
@Composable
private fun BlockedPanel(onOpenAppInfo: () -> Unit, onTryAgain: () -> Unit) {
    PanelScaffold {
        Icon(
            Icons.Outlined.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(R.string.senders_blocked_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.senders_blocked_body), style = MaterialTheme.typography.bodyLarge)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                R.string.senders_blocked_step1,
                R.string.senders_blocked_step2,
                R.string.senders_blocked_step3,
                R.string.senders_blocked_step4,
            ).forEach { step ->
                Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = onOpenAppInfo, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.senders_open_app_info))
        }
        OutlinedButton(onClick = onTryAgain, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.senders_try_again))
        }
        Text(
            stringResource(R.string.senders_skip_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendersList(state: SendersUiState, onIntent: (SendersIntent) -> Unit) {
    val reducedMotion = rememberReducedMotion()
    val number = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")) }
    val visible = if (state.showAll) state.rows else state.rows.filter { it.isLikelyBank }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "summary") {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                when {
                    state.loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text(stringResource(R.string.senders_loading), style = MaterialTheme.typography.bodyLarge)
                    }
                    state.loadFailed -> {
                        ErrorText(stringResource(R.string.senders_load_failed))
                        OutlinedButton(
                            onClick = { onIntent(SendersIntent.Refresh) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.senders_try_again)) }
                    }
                    else -> Text(
                        text = pluralStringResource(
                            R.plurals.senders_summary,
                            state.rows.size,
                            number.format(state.totalMessages),
                            state.rows.size,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.senders_privacy_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.rows.isNotEmpty() && state.likelyCount > 0) {
            item(key = "filter") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !state.showAll,
                        onClick = { onIntent(SendersIntent.SetShowAll(false)) },
                        label = { Text(stringResource(R.string.senders_filter_likely, state.likelyCount)) },
                        leadingIcon = if (!state.showAll) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                    FilterChip(
                        selected = state.showAll,
                        onClick = { onIntent(SendersIntent.SetShowAll(true)) },
                        label = { Text(stringResource(R.string.senders_filter_all, state.rows.size)) },
                        leadingIcon = if (state.showAll) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                    )
                }
            }
        }

        if (!state.loading && !state.loadFailed && state.rows.isEmpty()) {
            item(key = "empty") {
                Text(stringResource(R.string.senders_empty), style = MaterialTheme.typography.bodyLarge)
            }
        }

        items(visible, key = { "sender-${it.header}" }) { row ->
            SenderCard(
                row = row,
                number = number,
                onAssign = { onIntent(SendersIntent.OpenPicker(row.header)) },
                onUseSuggestion = { bank -> onIntent(SendersIntent.Assign(row.header, bank.id)) },
                modifier = Modifier.animateItem(
                    fadeInSpec = if (reducedMotion) null else tween(200),
                    placementSpec = if (reducedMotion) null else spring(),
                    fadeOutSpec = if (reducedMotion) null else tween(120),
                ),
            )
        }

        if (state.phoneMessages > 0 || state.otherMessages > 0) {
            item(key = "not-banks") {
                Text(
                    text = stringResource(
                        R.string.senders_not_banks,
                        number.format(state.phoneMessages),
                        number.format(state.otherMessages),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SenderCard(
    row: SenderRowUi,
    number: NumberFormat,
    onAssign: () -> Unit,
    onUseSuggestion: (BankOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val suffixNames = mapOf(
        SenderSuffix.T to stringResource(R.string.suffix_t),
        SenderSuffix.S to stringResource(R.string.suffix_s),
        SenderSuffix.P to stringResource(R.string.suffix_p),
        SenderSuffix.G to stringResource(R.string.suffix_g),
        SenderSuffix.NONE to stringResource(R.string.suffix_none),
    )
    val suffixText = row.suffixes.joinToString(" · ") { (suffix, count) ->
        "${suffixNames.getValue(suffix)} ${number.format(count)}"
    }
    val lastSeen = DATE_FORMAT.format(Instant.ofEpochMilli(row.lastSeen).atZone(IST))
    val count = pluralStringResource(R.plurals.senders_message_count, row.messageCount, number.format(row.messageCount))

    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = row.header,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = row.header.toList().joinToString(" ") },
                )
                Text(text = count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = stringResource(R.string.senders_last_seen, lastSeen) + " · " + suffixText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.promotionalOnly) {
                Text(
                    text = stringResource(R.string.senders_promo_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val assigned = row.assigned
            val suggested = row.suggested
            if (assigned != null) {
                OutlinedButton(onClick = onAssign, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(
                        text = stringResource(R.string.senders_belongs_to, assigned.name),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                if (suggested != null) {
                    Text(
                        text = stringResource(
                            if (suggested.unverified) R.string.senders_suggested_unverified else R.string.senders_suggested,
                            suggested.name,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (suggested != null) {
                        FilledTonalButton(onClick = { onUseSuggestion(suggested) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.senders_assign_suggested, suggested.name))
                        }
                    }
                    OutlinedButton(onClick = onAssign, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.senders_belongs_to_bank))
                    }
                }
            }
        }
    }
}

@Composable
private fun BankPickerDialog(
    header: String,
    banks: List<BankOption>,
    withAccounts: Set<String>,
    currentBankId: String?,
    onPick: (String) -> Unit,
    onNone: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Banks you already have an account at come first.
    val ordered = banks.sortedByDescending { it.id in withAccounts }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.senders_picker_title, header)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                ordered.forEach { bank ->
                    val chosen = bank.id == currentBankId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(bank.id) }),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RadioButton(selected = chosen, onClick = null)
                        ColorDot(bank.color)
                        Column(modifier = Modifier.weight(1f)) {
                            Text(bank.name, style = MaterialTheme.typography.bodyLarge)
                            if (bank.id in withAccounts) {
                                Text(
                                    stringResource(R.string.senders_picker_has_account),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                if (currentBankId != null) {
                    TextButton(onClick = onNone, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.senders_picker_none))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
