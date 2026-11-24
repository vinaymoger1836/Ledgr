package com.ledgr.app.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

private const val COUNT_UP_MS = 600
private const val STEPS = 1000

/**
 * Count-up for an amount (docs/08 §4: 600 ms, `FastOutSlowIn`, from the previous value; the first time from 0).
 * Money stays `Long` paise: the animation runs on an integer 0..1000 progress and the value is interpolated with
 * integer arithmetic, so there is no floating-point money. With animations off it is just [target].
 */
@Composable
fun rememberCountUp(target: Long, reduced: Boolean): Long {
    if (reduced) return target
    var shown by remember { mutableLongStateOf(0L) }
    val progress = remember { Animatable(STEPS, Int.VectorConverter) }
    LaunchedEffect(target) {
        val start = shown
        val delta = target - start
        if (delta == 0L) return@LaunchedEffect
        progress.snapTo(0)
        progress.animateTo(STEPS, tween(COUNT_UP_MS, easing = FastOutSlowInEasing)) {
            shown = start + delta * value / STEPS
        }
        shown = target
    }
    return shown
}
