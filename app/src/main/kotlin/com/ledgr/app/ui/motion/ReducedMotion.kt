package com.ledgr.app.ui.motion

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * True when the system "animator duration scale" is 0 (Remove animations / Developer options), docs/08 §4.
 * Transitions and count-ups use this to switch to instant state changes. It updates live when the user
 * changes the setting while the app is open.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(readAnimatorScale(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                scale = readAnimatorScale(context)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        scale = readAnimatorScale(context)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return scale == 0f
}

private fun readAnimatorScale(context: Context): Float =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
