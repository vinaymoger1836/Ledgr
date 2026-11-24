package com.ledgr.app.ui.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The scope the nav host is wrapped in, so a transaction row and the detail screen can share an element (docs/08 §4:
 * "Txn row → detail: shared element (avatar + amount), 300 ms, emphasized"). Null when animations are off (animator
 * scale 0), which turns every modifier below into a no-op.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The animated-content scope of the destination being drawn: the `this` of its `composable { }` block. */
val LocalNavVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val SHARED_MS = 300

/** [key] ties a row's element to the same element on the detail screen, e.g. `"avatar-42"`. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTxnElement(key: String): Modifier {
    val scope = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    return with(scope) {
        this@sharedTxnElement.sharedElement(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = { _, _ -> tween(SHARED_MS, easing = Emphasized) },
        )
    }
}

/** Like [sharedTxnElement] but for text, whose size differs between the list and the detail screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedTxnBounds(key: String): Modifier {
    val scope = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavVisibilityScope.current ?: return this
    return with(scope) {
        this@sharedTxnBounds.sharedBounds(
            rememberSharedContentState(key),
            visibility,
            boundsTransform = { _, _ -> tween(SHARED_MS, easing = Emphasized) },
        )
    }
}
