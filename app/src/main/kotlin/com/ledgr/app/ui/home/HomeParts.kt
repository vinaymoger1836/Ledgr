package com.ledgr.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.ui.permissions.SmsPermissionState
import com.ledgr.app.ui.profile.SmsAccess
import com.ledgr.app.ui.theme.LedgrTheme
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** The greeting and the month chip (docs/08 §2.1 item 1). There is no name to show: only the time of day. */
@Composable
internal fun HomeTopRow(greeting: String, month: YearMonth, onPickMonth: () -> Unit, modifier: Modifier = Modifier) {
    val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val chipText = "${month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)} ${month.year}"
    val spoken = stringResource(R.string.home_month_chip_description, "$monthName ${month.year}")
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        FilledTonalButton(
            onClick = onPickMonth,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = spoken },
        ) {
            Text(chipText)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
    }
}

/** "7 SMS need a look" (docs/08 §2.5): opens Needs review. */
@Composable
internal fun ReviewBanner(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val text = pluralStringResource(R.plurals.review_banner, count, count)
    val colors = LedgrTheme.colors
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = colors.warningContainer,
        contentColor = colors.onWarningContainer,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .semantics {
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Warning, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(text = stringResource(R.string.home_review_open), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * Shown while SMS access is missing (docs/08 §6 "permission denied"): what it is for, a way to allow it or, if Android
 * refused, to open App info, and the way out that needs no SMS at all, a statement.
 */
@Composable
internal fun SmsPermissionCard(
    permission: SmsPermissionState,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocked = permission.access == SmsAccess.Blocked
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(if (blocked) R.string.home_perm_blocked_title else R.string.home_perm_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = stringResource(if (blocked) R.string.home_perm_blocked_body else R.string.home_perm_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (blocked) {
                Button(onClick = permission::openAppInfo, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.senders_open_app_info))
                }
                OutlinedButton(onClick = permission::request, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.senders_try_again))
                }
            } else {
                Button(onClick = permission::request, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.scan_allow))
                }
            }
            TextButton(onClick = onImport, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_perm_import))
            }
        }
    }
}

/** A month with nothing in it yet (docs/08 §6). The scan button lives on the hero above. */
@Composable
internal fun EmptyMonthCard(month: YearMonth, hasAccounts: Boolean, onAddAccount: () -> Unit, modifier: Modifier = Modifier) {
    val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.DateRange,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = stringResource(R.string.home_empty_title, monthName, month.year),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(if (hasAccounts) R.string.home_empty_body else R.string.home_empty_body_no_accounts),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!hasAccounts) {
            Button(onClick = onAddAccount, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_empty_add_account))
            }
        }
    }
}
