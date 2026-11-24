package com.ledgr.app

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ledgr.app.data.db.DbStatus
import com.ledgr.app.ui.LedgrRoot
import com.ledgr.app.ui.lock.BiometricAuthenticator
import com.ledgr.app.ui.lock.LockViewModel
import com.ledgr.app.work.ScanScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Single activity. Extends [FragmentActivity] because `BiometricPrompt` needs one.
 *
 * `FLAG_SECURE` is set before anything is drawn: no screenshots, blank in Recents (docs/09 §2). It is only
 * cleared if the user turns on the (future) "allow screenshots" setting.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val lockViewModel: LockViewModel by viewModels()
    private lateinit var authenticator: BiometricAuthenticator

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        splash.setKeepOnScreenCondition { mainViewModel.settings.value == null }

        // Created here (not in composition) so a prompt that survives rotation still reports back to the ViewModel.
        authenticator = BiometricAuthenticator(
            activity = this,
            title = getString(R.string.lock_prompt_title),
            subtitle = getString(R.string.lock_prompt_subtitle),
            onSuccess = lockViewModel::onSucceeded,
            onFailure = lockViewModel::onFailed,
        )

        lifecycleScope.launch {
            mainViewModel.settings
                .filterNotNull()
                .map { it.allowScreenshots }
                .distinctUntilChanged()
                .collect { allow ->
                    if (allow) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
        }

        enableEdgeToEdge()
        setContent {
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val dbStatus by mainViewModel.dbStatus.collectAsStateWithLifecycle(initialValue = DbStatus.Opening)
            settings?.let { current ->
                LedgrRoot(
                    themeMode = current.themeMode,
                    dbStatus = dbStatus,
                    authenticator = authenticator,
                    onDarkThemeChanged = ::applySystemBars,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // docs/02 §3.2: catch up from the watermark whenever the app is opened (quiet; needs SMS access).
        ScanScheduler.catchUpNow(applicationContext)
    }

    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
    }

    private companion object {
        val LIGHT_SCRIM = Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
