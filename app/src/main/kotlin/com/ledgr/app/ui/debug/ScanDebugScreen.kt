package com.ledgr.app.ui.debug

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.data.db.ScanStatus
import com.ledgr.app.data.sms.ScanProgress
import com.ledgr.app.domain.time.Ist
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.profile.BlockedPanel
import com.ledgr.app.ui.profile.PanelScaffold
import com.ledgr.app.ui.profile.SectionTitle
import com.ledgr.app.ui.profile.SmsAccess
import com.ledgr.app.ui.profile.SubScreenScaffold
import java.text.NumberFormat
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
private val MONTH_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

/**
 * TEMPORARY (Phase 2, removed or hidden in Phase 4): scan a month, see the last scan report, the counts per reason,
 * and the raw SMS behind each outcome and reason. It exists to check the spam gate against the owner's real inbox.
 */
@Composable
fun ScanDebugScreen(onBack: () -> Unit, viewModel: ScanDebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Re-check on every return: the person may have just allowed SMS in App info.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onIntent(ScanDebugIntent.Refresh) }

    // Notifications only decide whether the progress notification is visible; the scan runs either way.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        viewModel.onIntent(ScanDebugIntent.Refresh)
        viewModel.onIntent(ScanDebugIntent.StartScan)
    }
    // READ_SMS and RECEIVE_SMS are one permission group: the system asks once.
    val smsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val read = result[Manifest.permission.READ_SMS] == true
        viewModel.onIntent(ScanDebugIntent.SmsResult(read))
        if (read) {
            if (needsNotificationPermission(context)) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.onIntent(ScanDebugIntent.StartScan)
            }
        }
    }
    val requestSms = { smsLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }
    val onScan = {
        when {
            state.access != SmsAccess.Granted -> requestSms()
            needsNotificationPermission(context) -> notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> viewModel.onIntent(ScanDebugIntent.StartScan)
        }
    }

    SubScreenScaffold(title = stringResource(R.string.scan_title), onBack = onBack) { padding ->
        when (state.access) {
            SmsAccess.Checking -> Unit
            SmsAccess.NeedsPermission -> Column(modifier = Modifier.padding(padding)) {
                ExplainerPanel(onAllow = { requestSms() })
            }
            SmsAccess.Blocked -> Column(modifier = Modifier.padding(padding)) {
                BlockedPanel(
                    onOpenAppInfo = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                    onTryAgain = { requestSms() },
                )
            }
            SmsAccess.Granted -> ScanContent(
                state = state,
                contentPadding = padding,
                onIntent = viewModel::onIntent,
                onScan = { onScan() },
                onAllowLive = { requestSms() },
            )
        }
    }
}

@Composable
private fun ExplainerPanel(onAllow: () -> Unit) {
    PanelScaffold {
        Icon(
            Icons.Outlined.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp),
        )
        Text(stringResource(R.string.scan_explainer_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.scan_explainer_what), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.scan_explainer_why), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.scan_explainer_private),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAllow, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.scan_allow))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanContent(
    state: ScanDebugUiState,
    contentPadding: PaddingValues,
    onIntent: (ScanDebugIntent) -> Unit,
    onScan: () -> Unit,
    onAllowLive: () -> Unit,
) {
    val number = remember { NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN")) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "notice") {
            Text(
                text = stringResource(R.string.scan_temp_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!state.receiveGranted) {
            item(key = "live-off") {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.scan_live_off), style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = onAllowLive, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.scan_live_allow))
                        }
                    }
                }
            }
        }

        item(key = "month") { MonthSelector(state = state, onIntent = onIntent) }

        item(key = "actions") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onScan, enabled = !state.running, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_action_scan))
                }
                OutlinedButton(
                    onClick = { onIntent(ScanDebugIntent.CatchUpNow) },
                    enabled = !state.running,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.scan_action_catch_up)) }
            }
        }

        state.progress?.takeIf { it.status == ScanStatus.RUNNING }?.let { progress ->
            item(key = "progress") { ProgressCard(progress = progress, number = number) }
        }

        item(key = "report") { ReportCard(state = state, number = number) }

        item(key = "counts-title") { SectionTitle(stringResource(R.string.scan_counts_title)) }
        if (state.groups.isEmpty()) {
            item(key = "counts-empty") {
                Text(
                    text = stringResource(R.string.scan_counts_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item(key = "counts-total") {
                Text(
                    text = stringResource(R.string.scan_counts_total, number.format(state.storedTotal)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(state.groups, key = { "group-${it.outcome}" }) { group ->
                OutcomeGroup(group = group, filter = state.filter, number = number, onIntent = onIntent)
            }
        }

        item(key = "messages-title") { SectionTitle(stringResource(R.string.scan_messages_title)) }
        item(key = "filter-line") { FilterLine(filter = state.filter, onIntent = onIntent) }
        if (state.messages.isEmpty() && state.groups.isNotEmpty()) {
            item(key = "messages-empty") {
                Text(stringResource(R.string.scan_messages_empty), style = MaterialTheme.typography.bodyLarge)
            }
        }
        items(state.messages, key = { "sms-${it.id}" }) { row ->
            MessageCard(row = row, onClick = { onIntent(ScanDebugIntent.ToggleExpanded(row.id)) })
        }
        if (state.canShowMore) {
            item(key = "more") {
                OutlinedButton(
                    onClick = { onIntent(ScanDebugIntent.ShowMore) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.scan_show_more)) }
            }
        }
    }
}

@Composable
private fun MonthSelector(state: ScanDebugUiState, onIntent: (ScanDebugIntent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { onIntent(ScanDebugIntent.PreviousMonth) },
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.scan_prev_month))
        }
        Text(
            text = MONTH_TITLE.format(state.month),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        OutlinedButton(
            onClick = { onIntent(ScanDebugIntent.NextMonth) },
            enabled = !state.isCurrentMonth,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.scan_next_month))
        }
    }
}

@Composable
private fun ProgressCard(progress: ScanProgress, number: NumberFormat) {
    val reducedMotion = rememberReducedMotion()
    val target = if (progress.total > 0) (progress.read.toFloat() / progress.total).coerceIn(0f, 1f) else 0f
    // docs/08 §4: animate the bar, but snap when the system animator scale is 0.
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reducedMotion) tween(0) else tween(250),
        label = "scan-progress",
    )
    val readText = stringResource(R.string.scan_progress_read, number.format(progress.read), number.format(progress.total))
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.scan_progress_title, progress.label), style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = readText },
            )
            Text(readText, style = MaterialTheme.typography.bodyLarge)
            val names = OUTCOMES.associateWith { stringResource(outcomeLabel(it)) }
            val counters = progress.byOutcome.entries.joinToString(" · ") { (outcome, n) ->
                "${names[outcome] ?: outcome} ${number.format(n)}"
            }
            if (counters.isNotEmpty()) {
                Text(counters, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress.errors > 0) {
                Text(
                    stringResource(R.string.scan_progress_errors, number.format(progress.errors)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ReportCard(state: ScanDebugUiState, number: NumberFormat) {
    val report = state.report
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.scan_report_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (report == null) {
                Text(stringResource(R.string.scan_report_none), style = MaterialTheme.typography.bodyLarge)
            } else {
                // Status is a word, so it never relies on colour.
                val status = stringResource(
                    when (report.status) {
                        ScanStatus.RUNNING -> R.string.scan_status_running
                        ScanStatus.DONE -> R.string.scan_status_done
                        ScanStatus.FAILED -> R.string.scan_status_failed
                        ScanStatus.CANCELLED -> R.string.scan_status_cancelled
                    },
                )
                val finished = report.finishedAt?.let { DATE_TIME.format(Instant.ofEpochMilli(it).atZone(Ist.ZONE)) }
                Text(
                    text = if (finished != null) "$status · $finished" else status,
                    style = MaterialTheme.typography.bodyLarge,
                )
                report.durationMs?.let { ms ->
                    Text(stringResource(R.string.scan_report_duration, formatDuration(ms)), style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = stringResource(
                        R.string.scan_report_counts,
                        number.format(report.read),
                        number.format(report.skipped),
                        number.format(report.errors),
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.scan_report_rules, report.rulesVersion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(Locale.ENGLISH, "%.1f s", ms / 1000.0)

@Composable
private fun outcomeLabelText(outcome: String): String = stringResource(outcomeLabel(outcome))

private val OUTCOMES = listOf("TXN", "REVIEW", "QUARANTINE", "BALANCE", "IGNORED")

private fun outcomeLabel(outcome: String): Int = when (outcome) {
    "TXN" -> R.string.scan_outcome_txn
    "REVIEW" -> R.string.scan_outcome_review
    "QUARANTINE" -> R.string.scan_outcome_quarantine
    "BALANCE" -> R.string.scan_outcome_balance
    else -> R.string.scan_outcome_ignored
}

@Composable
private fun OutcomeGroup(group: OutcomeGroupUi, filter: MessageFilter, number: NumberFormat, onIntent: (ScanDebugIntent) -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(vertical = 8.dp)) {
            val label = outcomeLabelText(group.outcome)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$label (${group.outcome})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                )
                Text(number.format(group.total), style = MaterialTheme.typography.titleSmall)
            }
            group.reasons.forEach { row ->
                val selected = filter.outcome == row.outcome && filter.reason == row.reason
                val description = stringResource(R.string.scan_count_row_desc, label, row.reason, number.format(row.count))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button, onClickLabel = stringResource(R.string.scan_count_row_action)) {
                            onIntent(
                                if (selected) {
                                    ScanDebugIntent.SetFilter(null, null)
                                } else {
                                    ScanDebugIntent.SetFilter(row.outcome, row.reason)
                                },
                            )
                        }
                        .padding(horizontal = 16.dp)
                        .semantics(mergeDescendants = true) { contentDescription = description },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // The check and the bold weight say "selected"; colour is not the only cue.
                    if (selected) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        text = row.reason,
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    Text(number.format(row.count), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun FilterLine(filter: MessageFilter, onIntent: (ScanDebugIntent) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val text = if (filter.outcome == null) {
            stringResource(R.string.scan_filter_all)
        } else {
            stringResource(R.string.scan_filter_showing, "${filter.outcome}: ${filter.reason}")
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (filter.outcome != null) {
            TextButton(
                onClick = { onIntent(ScanDebugIntent.SetFilter(null, null)) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.scan_filter_clear)) }
        }
    }
}

@Composable
private fun MessageCard(row: RawSmsRowUi, onClick: () -> Unit) {
    val received = DATE_TIME.format(Instant.ofEpochMilli(row.receivedAt).atZone(Ist.ZONE))
    val suffix = stringResource(
        when (row.suffix) {
            com.ledgr.app.domain.sender.SenderSuffix.T -> R.string.suffix_t
            com.ledgr.app.domain.sender.SenderSuffix.S -> R.string.suffix_s
            com.ledgr.app.domain.sender.SenderSuffix.P -> R.string.suffix_p
            com.ledgr.app.domain.sender.SenderSuffix.G -> R.string.suffix_g
            com.ledgr.app.domain.sender.SenderSuffix.NONE -> R.string.suffix_none
        },
    )
    val sender = row.header ?: stringResource(R.string.scan_unknown_sender)
    val code = "${row.outcome}: ${row.reason}"
    val description = stringResource(R.string.scan_message_desc, sender.toList().joinToString(" "), suffix, received, code)

    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.scan_message_toggle), onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = sender,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                Text(received, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$suffix · $code", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.expanded) {
                Text(
                    stringResource(R.string.scan_address_label, row.address),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = row.body ?: stringResource(R.string.scan_body_purged),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
