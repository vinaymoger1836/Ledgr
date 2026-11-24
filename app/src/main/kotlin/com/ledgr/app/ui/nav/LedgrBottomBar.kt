package com.ledgr.app.ui.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/** Bottom bar: Home / Dashboard / Reconcile / Profile. Hidden on pushed screens such as Settings. */
@Composable
fun LedgrBottomBar(navController: NavHostController, reducedMotion: Boolean) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val current = backStackEntry?.destination
    val visible = current.isTopLevel()

    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(150)),
        exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(100)),
    ) {
        NavigationBar {
            Tabs.forEach { tab ->
                val selected = current?.route == tab.destination.route
                val label = stringResource(tab.label)
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        navController.navigate(tab.destination.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(imageVector = tab.icon, contentDescription = null) },
                    label = { Text(label) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}

private fun NavDestination?.isTopLevel(): Boolean =
    this != null && Tabs.any { it.destination.route == route }
