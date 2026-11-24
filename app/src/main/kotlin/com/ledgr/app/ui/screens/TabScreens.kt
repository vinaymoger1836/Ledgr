package com.ledgr.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ledgr.app.R
import com.ledgr.app.ui.nav.LedgrIcons

// Profile moved to com.ledgr.app.ui.profile in P1. These three arrive in P4, P5 and P6.

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
