package com.ledgr.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.domain.ledger.DaySpend
import com.ledgr.app.domain.money.Money
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.review.spokenAmount
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val FULL_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val BAR_AREA = 88.dp
private const val GROW_MS = 600

/**
 * Last 7 days (docs/08 §2.1 item 4): seven bars that grow from the baseline. Each day is a 48 dp-wide button that opens
 * that day's sheet. The heading carries a spoken summary of the peak (docs/08 §5), since the bars are only a picture.
 *
 * Bar heights are drawing only (a ratio of two amounts); the amounts themselves stay `Long` paise.
 */
@Composable
internal fun SevenDayBars(
    days: List<DaySpend>,
    today: LocalDate,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberReducedMotion()
    val grow = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(days.map { it.date }, reduced) {
        if (reduced) {
            grow.snapTo(1f)
        } else {
            grow.snapTo(0f)
            grow.animateTo(1f, tween(GROW_MS, easing = FastOutSlowInEasing))
        }
    }
    val peak = days.maxByOrNull { it.spend }?.takeIf { it.spend > 0 }
    val summary = if (peak == null) {
        stringResource(R.string.home_seven_summary_none)
    } else {
        stringResource(R.string.home_seven_summary_peak, FULL_DAY.format(peak.date), spokenAmount(peak.spend))
    }
    val maxSpend = days.maxOfOrNull { it.spend }?.coerceAtLeast(1L) ?: 1L

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.home_seven_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    heading()
                    contentDescription = summary
                },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (day in days) {
                    DayBar(
                        day = day,
                        fraction = (day.spend.coerceAtLeast(0L) * 1000 / maxSpend) / 1000f,
                        isToday = day.date == today,
                        grow = grow.value,
                        onClick = { onDay(day.date) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DayBar(day: DaySpend, fraction: Float, isToday: Boolean, grow: Float, onClick: () -> Unit) {
    val spoken = stringResource(
        R.string.home_seven_day_description,
        FULL_DAY.format(day.date),
        spokenAmount(day.spend),
    )
    val tone = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .width(44.dp)
            .heightIn(min = 48.dp)
            .clickable(onClickLabel = stringResource(R.string.home_seven_open_day), role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (day.spend > 0) Money.formatCompact(day.spend) else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.heightIn(min = 16.dp),
        )
        Box(modifier = Modifier.height(BAR_AREA).width(28.dp), contentAlignment = Alignment.BottomCenter) {
            // A day with no spend still shows a stub, so the seven days always read as seven days.
            val barHeight = (BAR_AREA * fraction).coerceAtLeast(3.dp)
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(barHeight)
                    .graphicsLayer {
                        scaleY = grow
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(tone, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
            )
        }
        Text(
            text = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH),
            style = MaterialTheme.typography.labelMedium,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}
