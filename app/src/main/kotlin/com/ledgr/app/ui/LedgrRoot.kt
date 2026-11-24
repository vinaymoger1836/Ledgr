package com.ledgr.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ledgr.app.R
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.data.settings.ThemeMode
import com.ledgr.app.ui.lock.BiometricAuthenticator
import com.ledgr.app.ui.lock.LockHost
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.nav.LedgrBottomBar
import com.ledgr.app.ui.nav.LedgrNavHost
import com.ledgr.app.ui.theme.LedgrTheme

/**
 * Everything the activity shows: theme -> lock gate -> shell (bottom bar + nav host).
 *
 * The NavController is created *above* the lock gate. While locked the shell is not composed at all, but the
 * controller (and so the back stack and each tab's saved state) is kept.
 */
@Composable
fun LedgrRoot(
    themeMode: ThemeMode,
    dbStatus: DbStatus,
    authenticator: BiometricAuthenticator,
    onDarkThemeChanged: (Boolean) -> Unit,
) {
    LedgrTheme(themeMode) {
        val dark = LedgrTheme.colors.isDark
        // Status/navigation-bar icon colours must follow the in-app theme override, not just the system theme.
        DisposableEffect(dark) {
            onDarkThemeChanged(dark)
            onDispose { }
        }

        val reducedMotion = rememberReducedMotion()
        val navController = rememberNavController()

        Surface(color = MaterialTheme.colorScheme.surface) {
            LockHost(authenticator = authenticator, reducedMotion = reducedMotion) {
                MainShell(navController, reducedMotion, dbStatus)
            }
        }
    }
}

@Composable
private fun MainShell(
    navController: NavHostController,
    reducedMotion: Boolean,
    dbStatus: DbStatus,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        // Top inset is handled by each screen (so TopAppBar does not double it); bottom by the bar / screen.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        bottomBar = { LedgrBottomBar(navController, reducedMotion) },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            if (dbStatus is DbStatus.Failed) {
                DatabaseErrorBanner(dbStatus.reason)
            }
            LedgrNavHost(
                navController = navController,
                reducedMotion = reducedMotion,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Shown on every screen if the encrypted database could not be opened: nothing can be saved, and we say so. */
@Composable
private fun DatabaseErrorBanner(reason: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Text(
            text = stringResource(R.string.db_error_banner, reason),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
