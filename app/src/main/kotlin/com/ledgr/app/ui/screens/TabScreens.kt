package com.ledgr.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.ui.nav.LedgrIcons
import com.ledgr.app.ui.review.ReviewCountViewModel

// Profile moved to com.ledgr.app.ui.profile in P1. These three arrive in P4, P5 and P6.

@Composable
fun HomeScreen(onOpenReview: () -> Unit, modifier: Modifier = Modifier) {
    val reviewCount by hiltViewModel<ReviewCountViewModel>().count.collectAsStateWithLifecycle()
    PlaceholderScreen(
        title = stringResource(R.string.home_title),
        description = stringResource(R.string.home_placeholder),
        icon = Icons.Outlined.Home,
        modifier = modifier,
        extra = {
            // docs/08 §2.5: a banner with the number of open items, so nothing waits unseen.
            if (reviewCount > 0) {
                val text = pluralStringResource(R.plurals.review_banner, reviewCount, reviewCount)
                FilledTonalButton(
                    onClick = onOpenReview,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) { Text(text) }
            }
        },
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
