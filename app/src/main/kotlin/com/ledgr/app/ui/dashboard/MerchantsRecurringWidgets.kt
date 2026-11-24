package com.ledgr.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.dashboard.DashMerchant
import com.ledgr.app.data.dashboard.DashRecurring
import com.ledgr.app.data.dashboard.DashboardSnapshot
import com.ledgr.app.domain.forecast.Cadence
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.profile.MerchantAvatar
import com.ledgr.app.ui.review.spokenAmount
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NEXT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

/** W7 (docs/07): merchant ranking with count, amount and a share bar. A merchant row opens its detail sheet. */
@Composable
internal fun MerchantsWidget(
    snapshot: DashboardSnapshot,
    onViewAsTable: () -> Unit,
    onMerchant: (DashMerchant) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = snapshot.merchants
    val top = rows.firstOrNull()
    val summary = if (top == null) {
        stringResource(R.string.dash_merchants_summary_none)
    } else {
        stringResource(R.string.dash_merchants_summary, top.name ?: stringResource(R.string.dash_unknown_merchant), spokenAmount(top.spend))
    }
    WidgetCard(
        title = stringResource(R.string.dash_merchants_title),
        summary = summary,
        onViewAsTable = onViewAsTable,
        modifier = modifier,
    ) {
        if (rows.isEmpty()) {
            Text(stringResource(R.string.dash_no_spend), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in rows) MerchantRankRow(m, onClick = { onMerchant(m) })
            }
        }
    }
}

@Composable
private fun MerchantRankRow(m: DashMerchant, onClick: () -> Unit) {
    val name = m.name ?: stringResource(R.string.dash_unknown_merchant)
    val count = pluralStringResource(R.plurals.dash_payments, m.count, m.count)
    val spoken = stringResource(R.string.dash_merchant_spoken, name, count, spokenAmount(m.spend), percentText(m.sharePerMille))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(
                enabled = m.counterpartyId != null,
                onClickLabel = stringResource(R.string.dash_merchant_open),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MerchantAvatar(
            name = m.name.orEmpty(),
            nameColorIndex = m.nameColor,
            categoryIconKey = m.category?.icon.takeIf { m.useCategoryIcon },
            categoryColorIndex = m.category?.color.takeIf { m.useCategoryIcon },
            image = m.image,
            size = 40.dp,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
                Text(Money.format(m.spend), style = MaterialTheme.typography.bodyMedium)
            }
            ShareBar(m.sharePerMille)
            Text(
                "$count · ${percentText(m.sharePerMille)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A thin bar for a share in per-mille. Drawing only: the share is also written next to it. */
@Composable
internal fun ShareBar(perMille: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(perMille.coerceIn(0, 1000) / 1000f)
                .height(6.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}

/** W8 (docs/07): detected subscriptions, EMIs and rent with the next expected date, and Confirm / Not recurring. */
@Composable
internal fun RecurringWidget(
    snapshot: DashboardSnapshot,
    onConfirm: (DashRecurring) -> Unit,
    onDismiss: (DashRecurring) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = snapshot.recurring
    val monthly = items.filter { it.cadence == Cadence.MONTHLY }.sumOf { it.amount }
    val summary = if (items.isEmpty()) {
        stringResource(R.string.dash_recurring_summary_none)
    } else {
        pluralStringResource(R.plurals.dash_recurring_summary, items.size, items.size, spokenAmount(monthly))
    }
    WidgetCard(title = stringResource(R.string.dash_recurring_title), summary = summary, modifier = modifier) {
        if (items.isEmpty()) {
            Text(stringResource(R.string.dash_recurring_none), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in items) RecurringRow(r, onConfirm = { onConfirm(r) }, onDismiss = { onDismiss(r) })
            }
        }
    }
}

@Composable
private fun RecurringRow(r: DashRecurring, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val cadence = stringResource(if (r.cadence == Cadence.WEEKLY) R.string.dash_cadence_weekly else R.string.dash_cadence_monthly)
    val next = NEXT_DATE.format(r.next)
    val spoken = stringResource(R.string.dash_recurring_spoken, r.name, spokenAmount(r.amount), cadence, next)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                Text(
                    stringResource(R.string.dash_recurring_next, cadence, next),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(Money.format(r.amount), style = MaterialTheme.typography.bodyMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.confirmed) {
                Text(stringResource(R.string.dash_recurring_confirmed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            } else {
                OutlinedButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.dash_recurring_confirm))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.dash_recurring_dismiss))
            }
        }
    }
}
