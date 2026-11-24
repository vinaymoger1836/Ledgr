package com.ledgr.app.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.home.HomeSnapshot
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.rememberCountUp
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.review.spokenAmount
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale

private val INDIAN: NumberFormat = NumberFormat.getIntegerInstance(Locale.forLanguageTag("en-IN"))

/**
 * The hero (docs/08 §2.1 items 2–3). The current month leads with today's spend and how it compares with a usual
 * weekday; a past month leads with its total. Below it, the month so far, then the scan button or, while a scan runs,
 * its progress ring and counters. Everything on it is plain text as well as colour.
 */
@Composable
internal fun HeroCard(
    snapshot: HomeSnapshot,
    scan: ScanUi?,
    scanEnabled: Boolean,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val monthName = snapshot.month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val monthShort = snapshot.month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
    val headline = if (snapshot.isCurrentMonth) snapshot.todaySpend else snapshot.monthSpend
    val shown = rememberCountUp(headline, reduced)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = if (snapshot.isCurrentMonth) {
                    stringResource(R.string.home_hero_today)
                } else {
                    stringResource(R.string.home_hero_month_total, monthName)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            // The count-up is only for the eye: TalkBack reads the settled value, not every step.
            val headlineSpoken = spokenAmount(headline)
            Text(
                text = Money.format(shown),
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.semantics { contentDescription = headlineSpoken },
            )
            DeltaLine(snapshot)
            val monthSpoken = stringResource(
                R.string.home_hero_month_line_spoken,
                spokenAmount(snapshot.monthSpend),
                spokenAmount(snapshot.monthIncome),
            )
            Text(
                text = stringResource(
                    R.string.home_hero_month_line,
                    Money.format(snapshot.monthSpend),
                    Money.format(snapshot.monthIncome),
                ),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { contentDescription = monthSpoken },
            )
            ScanArea(
                scan = scan,
                label = stringResource(R.string.home_scan_button, monthShort),
                enabled = scanEnabled,
                onScan = onScan,
            )
        }
    }
}

/** "▲ 18% vs your usual Monday": an arrow and words, so direction never rests on colour. */
@Composable
private fun DeltaLine(snapshot: HomeSnapshot) {
    val delta = snapshot.weekdayDelta ?: return
    val day = snapshot.today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val text = when {
        delta.percent > 0 -> stringResource(R.string.home_delta_up, delta.percent, day)
        delta.percent < 0 -> stringResource(R.string.home_delta_down, -delta.percent, day)
        else -> stringResource(R.string.home_delta_same, day)
    }
    val spoken = when {
        delta.percent > 0 -> stringResource(R.string.home_delta_up_spoken, delta.percent, day)
        delta.percent < 0 -> stringResource(R.string.home_delta_down_spoken, -delta.percent, day)
        else -> stringResource(R.string.home_delta_same, day)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.semantics { contentDescription = spoken },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanArea(scan: ScanUi?, label: String, enabled: Boolean, onScan: () -> Unit) {
    val reduced = rememberReducedMotion()
    Crossfade(
        targetState = scan,
        animationSpec = if (reduced) tween(0) else tween(400),
        label = "scan-area",
    ) { running ->
        if (running == null) {
            Button(
                onClick = onScan,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text(label) }
        } else {
            val readText = stringResource(R.string.home_scan_read, INDIAN.format(running.read), INDIAN.format(running.total))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) { contentDescription = readText },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running.permille < 0) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { running.permille / 1000f },
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = readText, style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Counter(pluralStringResource(R.plurals.home_scan_txns, running.transactions, running.transactions))
                        Counter(stringResource(R.string.home_scan_ignored, running.ignored))
                        Counter(stringResource(R.string.home_scan_review, running.review))
                        if (running.errors > 0) Counter(pluralStringResource(R.plurals.home_scan_errors, running.errors, running.errors))
                    }
                }
            }
        }
    }
}

@Composable
private fun Counter(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}
