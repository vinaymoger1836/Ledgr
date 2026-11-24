package com.ledgr.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Create
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ledgr.app.R
import com.ledgr.app.data.home.HomeMerchant
import com.ledgr.app.data.home.HomeTxn
import com.ledgr.app.domain.ledger.TxnKind
import com.ledgr.app.domain.money.Money
import com.ledgr.app.domain.profile.NameColor
import com.ledgr.app.ui.nav.sharedTxnBounds
import com.ledgr.app.ui.nav.sharedTxnElement
import com.ledgr.app.ui.profile.MerchantAvatar
import com.ledgr.app.ui.review.amountLabel
import com.ledgr.app.ui.review.spokenAmount
import com.ledgr.app.ui.theme.LedgrTheme
import com.ledgr.app.ui.txn.kindLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IST: ZoneId = ZoneId.of("Asia/Kolkata")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

internal fun formatTime(epochMillis: Long): String = TIME.format(Instant.ofEpochMilli(epochMillis).atZone(IST))

/** A day heading in the recent list ("Today", "Yesterday", "Monday, 17 Nov"). */
@Composable
internal fun DayHeader(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .semantics { heading() },
    )
}

/**
 * One transaction (docs/08 §2.1 item 7): avatar, merchant, "category · account", time, and a signed amount. The sign and
 * the spoken verb carry direction, so colour is never the only cue (docs/08 §5).
 */
@Composable
internal fun TxnRow(item: HomeTxn, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val title = item.title.ifBlank {
        stringResource(if (item.isTransferLike) kindLabel(item.kind) else R.string.review_unknown_counterparty)
    }
    val category = item.category?.name ?: stringResource(R.string.review_uncategorised)
    val verb = stringResource(if (item.isDebit) R.string.review_spent else R.string.review_received)
    val time = formatTime(item.occurredAt)
    val spoken = stringResource(
        R.string.review_txn_description, verb, spokenAmount(item.amount), title, category, time, item.accountLabel,
    )
    val check = stringResource(R.string.home_row_check)
    val colors = LedgrTheme.colors
    val amountColor = when {
        item.isTransferLike -> colors.transfer
        item.isDebit -> MaterialTheme.colorScheme.onSurface
        else -> colors.income
    }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = if (item.inCheck) "$spoken. $check" else spoken
            },
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MerchantAvatar(
                name = title,
                nameColorIndex = if (item.title.isBlank()) NameColor.indexFor(title) else item.nameColor,
                categoryIconKey = item.category?.icon.takeIf { item.useCategoryIcon || item.title.isBlank() },
                categoryColorIndex = item.category?.color.takeIf { item.useCategoryIcon || item.title.isBlank() },
                image = item.image,
                modifier = Modifier.sharedTxnElement("avatar-${item.id}"),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(category, item.accountLabel.takeIf { it.isNotEmpty() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.inCheck) {
                    Text(
                        text = check,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.warning,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = amountLabel(item.amount, item.isDebit),
                    style = MaterialTheme.typography.titleMedium,
                    color = amountColor,
                    modifier = Modifier.sharedTxnBounds("amount-${item.id}"),
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * [TxnRow] with "swipe left to change category" (docs/08 §2.1). The row snaps back and a category sheet opens. The same
 * action is a TalkBack custom action, so the gesture is never the only way.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeableTxnRow(
    item: HomeTxn,
    onOpen: () -> Unit,
    onChangeCategory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.home_change_category)
    val state = rememberSwipeToDismissBoxState()
    val open by rememberUpdatedState(onChangeCategory)
    // The row is never dismissed: a full swipe opens the sheet and the row springs back.
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            open()
            state.reset()
        }
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction(label) { onChangeCategory(); true }) },
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Icon(
                        imageVector = Icons.Outlined.Create,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        },
    ) {
        TxnRow(item = item, onClick = onOpen)
    }
}

/** "Where it went": this month's top merchants, biggest first (docs/08 §2.1 item 5). */
@Composable
internal fun MerchantStrip(merchants: List<HomeMerchant>, onSeeAll: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.home_where_it_went),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(onClick = onSeeAll, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.home_see_all))
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(merchants, key = { it.counterpartyId ?: -1L }) { MerchantChip(it) }
        }
    }
}

@Composable
private fun MerchantChip(merchant: HomeMerchant) {
    val name = merchant.name ?: stringResource(R.string.home_merchant_other)
    val spoken = stringResource(R.string.home_merchant_description, name, spokenAmount(merchant.spend))
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .widthIn(min = 120.dp, max = 220.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MerchantAvatar(
                name = name,
                nameColorIndex = merchant.nameColor,
                categoryIconKey = merchant.category?.icon.takeIf { merchant.useCategoryIcon },
                categoryColorIndex = merchant.category?.color.takeIf { merchant.useCategoryIcon },
                image = merchant.image,
                size = 36.dp,
            )
            Column {
                Text(text = name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = Money.formatCompact(merchant.spend),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
