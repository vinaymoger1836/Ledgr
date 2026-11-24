package com.ledgr.app.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.ledgr.app.R
import com.ledgr.app.ui.nav.LedgrIcons

// Home lives in com.ledgr.app.ui.home (P4), Dashboard in com.ledgr.app.ui.dashboard (P5) and Profile in
// com.ledgr.app.ui.profile (P1). Reconcile arrives in P6.

@Composable
fun ReconcileScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.reconcile_title),
        description = stringResource(R.string.reconcile_placeholder),
        icon = LedgrIcons.Reconcile,
        modifier = modifier,
    )
}
