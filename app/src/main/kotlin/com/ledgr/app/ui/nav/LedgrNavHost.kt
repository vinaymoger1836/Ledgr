package com.ledgr.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.navArgument
import com.ledgr.app.ui.debug.ScanDebugScreen
import com.ledgr.app.ui.dashboard.DashboardScreen
import com.ledgr.app.ui.home.HomeScreen
import com.ledgr.app.ui.txn.TxnDetailScreen
import com.ledgr.app.ui.profile.CategoriesScreen
import com.ledgr.app.ui.profile.IdentitiesScreen
import com.ledgr.app.ui.profile.MerchantsScreen
import com.ledgr.app.ui.profile.ProfileScreen
import com.ledgr.app.ui.profile.SendersSeenScreen
import com.ledgr.app.ui.review.NeedsReviewScreen
import com.ledgr.app.ui.reconcile.MappingScreen
import com.ledgr.app.ui.reconcile.ReconcileScreen
import com.ledgr.app.ui.reconcile.ResultsScreen
import com.ledgr.app.ui.screens.SettingsRoute

// Material "fade through" (docs/08 §4, tab switch 250 ms): the old screen fades out in 90 ms, then the
// new one fades in while scaling up from 92 % over 210 ms.
private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210
private const val SCALE_START = 0.92f

private fun fadeThroughEnter(): EnterTransition =
    fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(
            initialScale = SCALE_START,
            animationSpec = tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing),
        )

private fun fadeThroughExit(): ExitTransition =
    fadeOut(tween(FADE_OUT_MS, easing = FastOutLinearInEasing))

/**
 * The four tab destinations plus Settings. With [reducedMotion] (system animator scale 0) every transition
 * is instant.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun LedgrNavHost(
    navController: NavHostController,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val enter: () -> EnterTransition = { if (reducedMotion) EnterTransition.None else fadeThroughEnter() }
    val exit: () -> ExitTransition = { if (reducedMotion) ExitTransition.None else fadeThroughExit() }

    SharedTransitionLayout(modifier = modifier) {
        // With animations off there is no scope, so the shared-element modifiers do nothing.
        CompositionLocalProvider(LocalSharedTransitionScope provides if (reducedMotion) null else this) {
            LedgrNavGraph(navController, enter, exit)
        }
    }
}

@Composable
private fun LedgrNavGraph(
    navController: NavHostController,
    enter: () -> EnterTransition,
    exit: () -> ExitTransition,
) {
    fun openTab(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { enter() },
        popExitTransition = { exit() },
    ) {
        composable(Destination.Home.route) {
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
                HomeScreen(
                    onOpenReview = { navController.navigate(Destination.NeedsReview.route) },
                    onOpenTxn = { navController.navigate(Destination.TxnDetail.route(it)) },
                    onNewTxn = { navController.navigate(Destination.TxnNew.route) },
                    onSeeAll = { openTab(Destination.Dashboard) },
                    onOpenProfile = { openTab(Destination.Profile) },
                    onOpenImport = { openTab(Destination.Reconcile) },
                    onOpenSenders = { navController.navigate(Destination.Senders.route) },
                )
            }
        }
        composable(Destination.TxnNew.route) { TxnDetailScreen(onBack = { navController.popBackStack() }) }
        composable(
            route = Destination.TxnDetail.route,
            arguments = listOf(navArgument(Destination.TxnDetail.ARG_ID) { type = NavType.LongType }),
        ) {
            CompositionLocalProvider(LocalNavVisibilityScope provides this) {
                TxnDetailScreen(onBack = { navController.popBackStack() })
            }
        }
        composable(Destination.Dashboard.route) {
            DashboardScreen(onOpenTxn = { navController.navigate(Destination.TxnDetail.route(it)) })
        }
        composable(Destination.Reconcile.route) {
            ReconcileScreen(
                onOpenMapping = { navController.navigate(Destination.ReconcileMap.route(it)) },
                onOpenResults = { navController.navigate(Destination.ReconcileResults.route(it)) },
                onOpenProfile = { openTab(Destination.Profile) },
            )
        }
        composable(
            route = Destination.ReconcileMap.route,
            arguments = listOf(navArgument(Destination.ReconcileMap.ARG_DRAFT) { type = NavType.LongType }),
        ) {
            MappingScreen(
                // The map is saved: replace the mapping screen with the results so Back returns to the Reconcile tab.
                onSaved = { importId ->
                    navController.navigate(Destination.ReconcileResults.route(importId)) {
                        popUpTo(Destination.Reconcile.route)
                    }
                },
                onClosed = { navController.popBackStack() },
            )
        }
        composable(
            route = Destination.ReconcileResults.route,
            arguments = listOf(navArgument(Destination.ReconcileResults.ARG_IMPORT) { type = NavType.LongType }),
        ) {
            ResultsScreen(onBack = { navController.popBackStack() })
        }
        composable(Destination.Profile.route) {
            ProfileScreen(
                onOpenSettings = { navController.navigate(Destination.Settings.route) },
                onOpenIdentities = { navController.navigate(Destination.Identities.route) },
                onOpenCategories = { navController.navigate(Destination.Categories.route) },
                onOpenMerchants = { navController.navigate(Destination.Merchants.route) },
                onOpenSenders = { navController.navigate(Destination.Senders.route) },
                onOpenReview = { navController.navigate(Destination.NeedsReview.route) },
                onOpenScanDebug = { navController.navigate(Destination.ScanDebug.route) },
            )
        }
        composable(Destination.Settings.route) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
        composable(Destination.Identities.route) { IdentitiesScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Categories.route) { CategoriesScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Merchants.route) { MerchantsScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.Senders.route) { SendersSeenScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.NeedsReview.route) { NeedsReviewScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.ScanDebug.route) { ScanDebugScreen(onBack = { navController.popBackStack() }) }
    }
}
