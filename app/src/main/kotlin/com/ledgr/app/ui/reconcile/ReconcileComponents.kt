package com.ledgr.app.ui.reconcile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.theme.LedgrTheme

/** The match rate as a ring (docs/06 §4). It sweeps in over 600 ms, or appears at once when animations are off. */
@Composable
internal fun MatchRing(rateBp: Int, size: Dp, modifier: Modifier = Modifier, showLabel: Boolean = true) {
    val reduced = rememberReducedMotion()
    val target = rateBp.coerceIn(0, 10_000) / 10_000f
    val sweep = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target, reduced) {
        if (reduced) sweep.snapTo(target) else sweep.animateTo(target, tween(600, easing = FastOutSlowInEasing))
    }
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = LedgrTheme.colors.income
    val percent = rateBp / 100
    val description = stringResource(R.string.recon_rate_description, percent)
    Box(
        modifier = modifier.size(size).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = this.size.minDimension * 0.12f
            val inset = stroke / 2
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(color, -90f, 360f * sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (showLabel) {
            Text(
                text = "$percent%",
                style = if (size >= 96.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

/** A choice chip with a check mark when selected (selection is never colour alone) and an optional account colour dot. */
@Composable
internal fun ChoiceChip(selected: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, dot: Color? = null) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = when {
            selected -> {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
            dot != null -> {
                { Box(modifier = Modifier.size(12.dp).background(dot, CircleShape)) }
            }
            else -> null
        },
        modifier = modifier.heightIn(min = 48.dp).padding(end = 0.dp),
    )
}
