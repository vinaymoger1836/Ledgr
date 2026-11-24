package com.ledgr.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.ui.nav.LedgrIcons

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.home_title),
        description = stringResource(R.string.home_placeholder),
        icon = Icons.Outlined.Home,
        modifier = modifier,
    )
}

@Composable
fun DashboardScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.dashboard_title),
        description = stringResource(R.string.dashboard_placeholder),
        icon = LedgrIcons.Dashboard,
        modifier = modifier,
    )
}

@Composable
fun ReconcileScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.reconcile_title),
        description = stringResource(R.string.reconcile_placeholder),
        icon = LedgrIcons.Reconcile,
        modifier = modifier,
    )
}

/** Profile placeholder; Settings is reachable from here (docs/08 §1). */
@Composable
fun ProfileScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.profile_title),
        description = stringResource(R.string.profile_placeholder),
        icon = Icons.Outlined.Person,
        modifier = modifier,
    ) {
        Surface(
            onClick = onOpenSettings,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.profile_open_settings),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.profile_open_settings_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
