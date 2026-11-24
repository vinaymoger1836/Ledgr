package com.ledgr.app.ui.nav

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.ledgr.app.R

/** Every screen in the shell. Tabs appear in the bottom bar; Settings is pushed from Profile (docs/08 §1). */
sealed class Destination(val route: String) {
    data object Home : Destination("home")
    data object Dashboard : Destination("dashboard")
    data object Reconcile : Destination("reconcile")
    data object Profile : Destination("profile")

    /** Needs review (docs/08 §2.5): pushed from the Home banner and from Profile. */
    data object NeedsReview : Destination("review")
    data object Settings : Destination("profile/settings")
    data object Identities : Destination("profile/identities")
    data object Categories : Destination("profile/categories")
    data object Merchants : Destination("profile/merchants")
    data object Senders : Destination("profile/senders")

    /** P2 only: temporary scan report and raw SMS list. Removed or hidden in P4. */
    data object ScanDebug : Destination("profile/debug-scan")
}

/** One bottom-bar entry. The label is always visible, so colour and icon are never the only cue. */
internal data class Tab(
    val destination: Destination,
    @StringRes val label: Int,
    val icon: ImageVector,
)

internal val Tabs: List<Tab> = listOf(
    Tab(Destination.Home, R.string.nav_home, Icons.Outlined.Home),
    Tab(Destination.Dashboard, R.string.nav_dashboard, LedgrIcons.Dashboard),
    Tab(Destination.Reconcile, R.string.nav_reconcile, LedgrIcons.Reconcile),
    Tab(Destination.Profile, R.string.nav_profile, Icons.Outlined.Person),
)
