package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashAccount
import com.ledgr.app.domain.ledger.Period
import com.ledgr.app.domain.ledger.PeriodKind
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MONTH_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_MONTH_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** "Nov 2026" or "9 Nov – 15 Nov 2026" (docs/07 §1). */
internal fun periodLabel(period: Period): String = when (period.kind) {
    PeriodKind.MONTH -> MONTH_LABEL.format(period.start)
    PeriodKind.WEEK -> if (period.start.year == period.end.year) {
        DAY_MONTH.format(period.start) + " – " + DAY_MONTH_YEAR.format(period.end)
    } else {
        DAY_MONTH_YEAR.format(period.start) + " – " + DAY_MONTH_YEAR.format(period.end)
    }
}

/**
 * The sticky header (docs/07 §1): account chips, Week / Month with a stepper, and the own-transfers chip on "All
 * accounts". Every target is at least 48 dp; selection is shown by a check mark as well as colour.
 */
@Composable
internal fun DashboardControls(
    accounts: List<DashAccount>,
    selectedAccount: Long?,
    kind: PeriodKind,
    period: Period,
    canGoNext: Boolean,
    countOwnTransfers: Boolean,
    onAccount: (Long?) -> Unit,
    onKind: (PeriodKind) -> Unit,
    onStep: (Int) -> Unit,
    onToggleOwnTransfers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "all") {
                    SelectableChip(
                        selected = selectedAccount == null,
                        label = stringResource(R.string.dash_all_accounts),
                        onClick = { onAccount(null) },
                    )
                }
                items(accounts, key = { it.id }) { a ->
                    SelectableChip(
                        selected = selectedAccount == a.id,
                        label = "${a.nickname} ••${a.last4}",
                        onClick = { onAccount(a.id) },
                        dot = Color(a.color),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow {
                    PeriodKind.entries.forEachIndexed { i, k ->
                        SegmentedButton(
                            selected = kind == k,
                            onClick = { onKind(k) },
                            shape = SegmentedButtonDefaults.itemShape(i, PeriodKind.entries.size),
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text(stringResource(if (k == PeriodKind.WEEK) R.string.dash_week else R.string.dash_month))
                        }
                    }
                }
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    IconButton(onClick = { onStep(-1) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.dash_previous_period))
                    }
                    Text(
                        text = periodLabel(period),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    IconButton(onClick = { onStep(1) }, enabled = canGoNext, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.dash_next_period))
                    }
                }
            }
            if (selectedAccount == null) {
                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    val description = stringResource(
                        if (countOwnTransfers) R.string.dash_own_transfers_on_description else R.string.dash_own_transfers_off_description,
                    )
                    FilterChip(
                        selected = countOwnTransfers,
                        onClick = onToggleOwnTransfers,
                        label = { Text(stringResource(R.string.dash_own_transfers)) },
                        leadingIcon = if (countOwnTransfers) {
                            { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics { contentDescription = description },
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectableChip(selected: Boolean, label: String, onClick: () -> Unit, dot: Color? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = when {
            selected -> {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
            dot != null -> {
                { Box(modifier = Modifier.size(12.dp).background(dot, CircleShape)) }
            }
            else -> null
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
}
