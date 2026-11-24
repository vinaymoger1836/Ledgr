package com.ledgr.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ledgr.app.domain.money.Money
import com.ledgr.app.R
import com.ledgr.app.data.home.HomeCategory
import com.ledgr.app.data.home.HomeTxn
import com.ledgr.app.domain.ledger.DaySpend
import com.ledgr.app.ui.profile.CategoryBadge
import com.ledgr.app.ui.review.spokenAmount
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private const val YEARS_BACK = 5
private val DAY_TITLE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

/**
 * The month grid (docs/08 §2.1 item 1). A dot marks a month that has data; future months are disabled; the chosen month is
 * marked with a check. Dot, check and "disabled" are also in each cell's spoken description, so none rests on colour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MonthPickerSheet(
    selected: YearMonth,
    current: YearMonth,
    withData: Set<YearMonth>,
    onSelect: (YearMonth) -> Unit,
    onDismiss: () -> Unit,
) {
    var year by rememberSaveable { mutableIntStateOf(selected.year) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { year -= 1 }, enabled = year > current.year - YEARS_BACK) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.home_month_prev_year))
                }
                Text(
                    text = year.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { heading() },
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { year += 1 }, enabled = year < current.year) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.home_month_next_year))
                }
            }
            for (row in 0 until 4) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0 until 3) {
                        val month = YearMonth.of(year, row * 3 + col + 1)
                        MonthCell(
                            month = month,
                            isSelected = month == selected,
                            hasData = month in withData,
                            enabled = !month.isAfter(current),
                            onClick = { onSelect(month) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCell(
    month: YearMonth,
    isSelected: Boolean,
    hasData: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
    val status = when {
        !enabled -> stringResource(R.string.home_month_future)
        hasData -> stringResource(R.string.home_month_has_data)
        else -> stringResource(R.string.home_month_no_data)
    }
    val label = "$name ${month.year}"
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .heightIn(min = 56.dp)
            .selectable(selected = isSelected, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = status
            },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = month.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                )
                if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (hasData && enabled) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
            )
        }
    }
}

/** A tapped bar of the 7-day chart: that day's total and its transactions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DaySheet(
    day: DaySpend?,
    date: LocalDate,
    txns: List<HomeTxn>,
    onOpenTxn: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = DAY_TITLE.format(date),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            if (day != null) {
                val spokenDay = stringResource(R.string.home_day_spent, spokenAmount(day.spend))
                Text(
                    text = stringResource(R.string.home_day_spent, Money.format(day.spend)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { contentDescription = spokenDay },
                )
            }
            if (txns.isEmpty()) {
                Text(
                    text = stringResource(R.string.home_day_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(txns, key = { it.id }) { TxnRow(item = it, onClick = { onOpenTxn(it.id) }) }
                }
            }
        }
    }
}

/** "Change category" from a swipe or the row's accessibility action: only categories that fit the row's kind. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CategorySheet(
    categories: List<HomeCategory>,
    currentId: Long?,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(R.string.home_category_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .semantics { heading() },
            )
            LazyColumn {
                items(categories, key = { it.id }) { category ->
                    val isCurrent = category.id == currentId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .selectable(selected = isCurrent, role = Role.Button, onClick = { onPick(category.id) })
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CategoryBadge(category.icon, category.color)
                        Text(text = category.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.home_category_current),
                            )
                        }
                    }
                }
            }
        }
    }
}
