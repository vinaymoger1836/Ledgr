package com.ledgr.app.ui.nav

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.ledgr.app.ui.screens.DashboardScreen
import com.ledgr.app.ui.screens.HomeScreen
import com.ledgr.app.ui.screens.ProfileScreen
import com.ledgr.app.ui.screens.ReconcileScreen
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
@Composable
fun LedgrNavHost(
    navController: NavHostController,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val enter: () -> EnterTransition = { if (reducedMotion) EnterTransition.None else fadeThroughEnter() }
    val exit: () -> ExitTransition = { if (reducedMotion) ExitTransition.None else fadeThroughExit() }

    NavHost(
        navController = navController,
        startDestination = Destination.Home.route,
        modifier = modifier,
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { enter() },
        popExitTransition = { exit() },
    ) {
        composable(Destination.Home.route) { HomeScreen() }
        composable(Destination.Dashboard.route) { DashboardScreen() }
        composable(Destination.Reconcile.route) { ReconcileScreen() }
        composable(Destination.Profile.route) {
            ProfileScreen(onOpenSettings = { navController.navigate(Destination.Settings.route) })
        }
        composable(Destination.Settings.route) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
