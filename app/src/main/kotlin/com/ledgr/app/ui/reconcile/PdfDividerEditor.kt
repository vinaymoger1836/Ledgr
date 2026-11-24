package com.ledgr.app.ui.reconcile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.domain.statement.PdfLine
import kotlin.math.roundToInt

private const val MIN_PAGE_WIDTH = 5_950 // an A4 page is 595 pt = 5950 tenths of a point
private const val NUDGE = 50 // 5 pt per accessibility "move" action
private const val MIN_GAP = 40 // dividers keep at least 4 pt apart

/**
 * docs/06 §2 / §1.6: the first page of a PDF drawn as text at its real positions, with the column dividers on top. Each
 * divider can be dragged (48 dp wide handle) or moved from TalkBack's actions menu in 5 pt steps. Positions are in 1/10 pt,
 * the unit the layout code uses, so what is dragged is exactly what is saved in `column_map.pdf_columns`.
 */
@Composable
internal fun PdfDividerEditor(
    lines: List<PdfLine>,
    dividers: List<Int>,
    onChange: (List<Int>) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = pluralStringResource(R.plurals.recon_dividers_summary, dividers.size + 1, dividers.size + 1)
    Column(modifier = modifier, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 280.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .semantics { contentDescription = summary },
        ) {
            val density = LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val pageWidth = maxOf(MIN_PAGE_WIDTH, (lines.flatMap { it.phrases }.maxOfOrNull { it.xEnd } ?: 0) + 200)
            val pxPerUnit = widthPx / pageWidth
            val lineHeightPx = with(density) { 18.dp.toPx() }
            val contentHeightPx = lineHeightPx * lines.size + with(density) { 12.dp.toPx() }
            val measurer = rememberTextMeasurer()
            val textColor = MaterialTheme.colorScheme.onSurface
            val fontSize = with(density) { (pxPerUnit * 10f * 1.25f * 8f).toSp() }
            Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Canvas(modifier = Modifier.fillMaxWidth().height(with(density) { contentHeightPx.toDp() })) {
                    lines.forEachIndexed { i, line ->
                        for (p in line.phrases) {
                            drawText(
                                textMeasurer = measurer,
                                text = p.text,
                                topLeft = Offset(p.x * pxPerUnit, i * lineHeightPx + 4f),
                                style = TextStyle(color = textColor, fontSize = fontSize),
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
                val current by rememberUpdatedState(dividers)
                dividers.forEachIndexed { index, x ->
                    DividerHandle(
                        index = index,
                        count = dividers.size,
                        xUnits = x,
                        pxPerUnit = pxPerUnit,
                        height = with(density) { contentHeightPx.toDp() },
                        onMove = { delta ->
                            onChange(moved(current, index, delta, pageWidth))
                        },
                    )
                }
            }
        }
        TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.recon_dividers_reset)) }
    }
}

/** [dividers] with divider [index] moved by [delta], kept inside the page and clear of its neighbours. */
private fun moved(dividers: List<Int>, index: Int, delta: Int, pageWidth: Int): List<Int> {
    val low = if (index == 0) 0 else dividers[index - 1] + MIN_GAP
    val high = if (index == dividers.size - 1) pageWidth else dividers[index + 1] - MIN_GAP
    if (low > high) return dividers
    val next = (dividers[index] + delta).coerceIn(low, high)
    return dividers.toMutableList().also { it[index] = next }
}

@Composable
private fun DividerHandle(
    index: Int,
    count: Int,
    xUnits: Int,
    pxPerUnit: Float,
    height: androidx.compose.ui.unit.Dp,
    onMove: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val handleWidth = 48.dp
    val centerPx = xUnits * pxPerUnit
    val halfPx = with(density) { handleWidth.toPx() / 2 }
    val label = stringResource(R.string.recon_divider_label, index + 1, count)
    val position = pluralStringResource(R.plurals.recon_divider_position, xUnits / 10, xUnits / 10)
    val left = stringResource(R.string.recon_divider_left)
    val right = stringResource(R.string.recon_divider_right)
    val currentMove by rememberUpdatedState(onMove)
    val line = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .offset { IntOffset((centerPx - halfPx).roundToInt(), 0) }
            .width(handleWidth)
            .height(height)
            .pointerInput(pxPerUnit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    currentMove((dragAmount / pxPerUnit).roundToInt())
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = position
                customActions = listOf(
                    CustomAccessibilityAction(left) {
                        currentMove(-NUDGE)
                        true
                    },
                    CustomAccessibilityAction(right) {
                        currentMove(NUDGE)
                        true
                    },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(modifier = Modifier.fillMaxHeight().width(3.dp).background(line.copy(alpha = 0.7f)))
        // A grip so the handle looks draggable; a number so dividers are told apart without colour.
        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 32.dp)
                .background(line, RoundedCornerShape(8.dp))
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
