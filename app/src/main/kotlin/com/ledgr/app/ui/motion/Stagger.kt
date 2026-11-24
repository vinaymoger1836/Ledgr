package com.ledgr.app.ui.motion

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

private const val MAX_STAGGER = 8
private const val STAGGER_MS = 40
private const val APPEAR_MS = 220

/**
 * The root of one list item: docs/08 §4 says lists fade in with an 8 dp rise, 40 ms apart, for the first eight items;
 * without animations (animator scale 0) it is instant. [modifier] carries the list's own placement motion.
 */
@Composable
fun Appear(index: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    if (reduced || index >= MAX_STAGGER) {
        Box(modifier = modifier) { content() }
        return
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = APPEAR_MS, delayMillis = index * STAGGER_MS),
        label = "appear",
    )
    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 8.dp.toPx()
        },
    ) { content() }
}
