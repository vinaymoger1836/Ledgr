package com.ledgr.app.ui.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Shows [content] only while unlocked. While locked it shows [LockScreen] and, once per lock event,
 * opens the system prompt as soon as the activity is resumed.
 */
@Composable
fun LockHost(
    authenticator: BiometricAuthenticator,
    reducedMotion: Boolean,
    viewModel: LockViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun startUnlock() {
        val availability = authenticator.availability()
        viewModel.onAvailability(availability)
        if (availability == LockAvailability.Available) {
            viewModel.onPrompting()
            authenticator.authenticate()
        }
    }

    LifecycleResumeEffect(locked) {
        if (locked && (viewModel.consumeAutoPrompt() || viewModel.needsRecheck())) startUnlock()
        onPauseOrDispose { }
    }

    AnimatedContent(
        targetState = locked,
        transitionSpec = { lockTransition(reducedMotion) },
        label = "lock",
    ) { isLocked ->
        if (isLocked) {
            LockScreen(
                state = ui,
                onUnlock = ::startUnlock,
                onOpenSecuritySettings = {
                    context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                },
            )
        } else {
            content()
        }
    }
}

private fun lockTransition(reducedMotion: Boolean): ContentTransform =
    if (reducedMotion) {
        EnterTransition.None togetherWith ExitTransition.None
    } else {
        fadeIn(tween(250)) togetherWith fadeOut(tween(150))
    }
