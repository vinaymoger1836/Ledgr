package com.ledgr.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgr.app.R
import com.ledgr.app.data.home.HomeSnapshot
import com.ledgr.app.domain.model.CategoryKind
import com.ledgr.app.domain.time.Ist
import com.ledgr.app.ui.motion.Appear
import com.ledgr.app.ui.motion.rememberReducedMotion
import com.ledgr.app.ui.onboarding.OnboardingScreen
import com.ledgr.app.ui.permissions.SmsPermissionState
import com.ledgr.app.ui.permissions.rememberSmsPermission
import com.ledgr.app.ui.profile.SmsAccess
import com.ledgr.app.ui.txn.categoryKindFor
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_HEADING: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)
private const val MONTH_SLIDE_MS = 280
private const val SLIDE_DP = 32

private enum class PendingAction { ScanMonth, Refresh }

/**
 * Home (docs/08 §2.1): the selected month only. Today's spend or the month total, the scan button, the last 7 days,
 * where the money went, what needs review, and the month's transactions. Everything is a live query, so a new SMS shows
 * up without a manual refresh.
 */
@Composable
fun HomeScreen(
    onOpenReview: () -> Unit,
    onOpenTxn: (Long) -> Unit,
    onNewTxn: () -> Unit,
    onSeeAll: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenSenders: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf(PendingAction.ScanMonth) }
    val permission = rememberSmsPermission(
        onReady = {
            viewModel.onIntent(if (pending == PendingAction.ScanMonth) HomeIntent.ScanMonth else HomeIntent.Refresh)
        },
    )

    if (state.showOnboarding) {
        OnboardingScreen(
            onFinish = { scan ->
                if (scan) viewModel.onIntent(HomeIntent.ScanMonth)
                viewModel.onIntent(HomeIntent.FinishOnboarding)
            },
            onFindSenders = onOpenSenders,
            modifier = modifier,
        )
        return
    }

    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    val messageText = message?.let { homeMessageText(it) }
    LaunchedEffect(message) {
        if (messageText != null) {
            snackbar.showSnackbar(messageText)
            viewModel.onIntent(HomeIntent.MessageShown)
        }
    }

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        val snapshot = state.snapshot
        if (state.ready && snapshot != null) {
            HomeBody(
                state = state,
                snapshot = snapshot,
                permission = permission,
                onIntent = viewModel::onIntent,
                onRefresh = {
                    pending = PendingAction.Refresh
                    permission.runWhenReady()
                },
                onScan = {
                    pending = PendingAction.ScanMonth
                    permission.runWhenReady()
                },
                onOpenReview = onOpenReview,
                onOpenTxn = onOpenTxn,
                onSeeAll = onSeeAll,
                onOpenProfile = onOpenProfile,
                onOpenImport = onOpenImport,
            )
        }
        FloatingActionButton(
            onClick = onNewTxn,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.home_add_txn))
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }

    HomeSheets(state = state, onIntent = viewModel::onIntent, onOpenTxn = onOpenTxn)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeBody(
    state: HomeUiState,
    snapshot: HomeSnapshot,
    permission: SmsPermissionState,
    onIntent: (HomeIntent) -> Unit,
    onRefresh: () -> Unit,
    onScan: () -> Unit,
    onOpenReview: () -> Unit,
    onOpenTxn: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenImport: () -> Unit,
) {
    val reduced = rememberReducedMotion()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Month change (docs/08 §4, 280 ms): the content fades in sliding in the direction of time.
    val shift = remember { Animatable(1f) }
    LaunchedEffect(state.month) {
        if (reduced) {
            shift.snapTo(1f)
        } else {
            shift.snapTo(0f)
            shift.animateTo(1f, tween(MONTH_SLIDE_MS, easing = FastOutSlowInEasing))
        }
    }
    val slidePx = with(density) { SLIDE_DP.dp.toPx() }

    val today = snapshot.today
    val order = remember(snapshot.txns) { snapshot.txns.withIndex().associate { it.value.id to it.index } }
    val groups = remember(snapshot.txns) { snapshot.txns.groupBy { it.localDate } }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = shift.value
                    translationX = (1f - shift.value) * state.direction * slidePx
                },
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "top") {
                HomeTopRow(
                    greeting = greeting(),
                    month = state.month,
                    onPickMonth = { onIntent(HomeIntent.OpenMonthPicker) },
                )
            }
            item(key = "hero") {
                HeroCard(
                    snapshot = snapshot,
                    scan = state.scan,
                    scanEnabled = state.scan == null,
                    onScan = onScan,
                )
            }
            if (permission.access != SmsAccess.Granted) {
                item(key = "permission") { SmsPermissionCard(permission = permission, onImport = onOpenImport) }
            }
            if (state.reviewCount > 0) {
                item(key = "review") { ReviewBanner(count = state.reviewCount, onClick = onOpenReview) }
            }
            if (!snapshot.hasData) {
                item(key = "empty") {
                    EmptyMonthCard(month = state.month, hasAccounts = state.accountCount > 0, onAddAccount = onOpenProfile)
                }
            } else {
                item(key = "seven") {
                    SevenDayBars(
                        days = snapshot.lastSeven,
                        today = today,
                        onDay = { onIntent(HomeIntent.OpenDay(it)) },
                    )
                }
                if (snapshot.merchants.isNotEmpty()) {
                    item(key = "merchants") { MerchantStrip(snapshot.merchants, onSeeAll = onSeeAll) }
                }
                groups.forEach { (date, rows) ->
                    item(key = "day-$date") { DayHeader(dayLabel(date, today)) }
                    items(rows, key = { "txn-${it.id}" }) { row ->
                        Appear(
                            index = order[row.id] ?: Int.MAX_VALUE,
                            modifier = Modifier.animateItem(
                                fadeInSpec = if (reduced) null else tween(200),
                                placementSpec = if (reduced) null else spring(),
                                fadeOutSpec = if (reduced) null else tween(120),
                            ),
                        ) {
                            SwipeableTxnRow(
                                item = row,
                                onOpen = { onOpenTxn(row.id) },
                                onChangeCategory = { onIntent(HomeIntent.OpenCategory(row.id)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSheets(state: HomeUiState, onIntent: (HomeIntent) -> Unit, onOpenTxn: (Long) -> Unit) {
    val snapshot = state.snapshot ?: return
    when (val sheet = state.sheet) {
        null -> Unit
        HomeSheet.MonthPicker -> MonthPickerSheet(
            selected = state.month,
            current = YearMonth.from(Ist.today()),
            withData = state.monthsWithData,
            onSelect = { onIntent(HomeIntent.SelectMonth(it)) },
            onDismiss = { onIntent(HomeIntent.CloseSheet) },
        )
        is HomeSheet.Day -> DaySheet(
            day = snapshot.lastSeven.firstOrNull { it.date == sheet.date },
            date = sheet.date,
            txns = snapshot.txns.filter { it.localDate == sheet.date },
            onOpenTxn = {
                onIntent(HomeIntent.CloseSheet)
                onOpenTxn(it)
            },
            onDismiss = { onIntent(HomeIntent.CloseSheet) },
        )
        is HomeSheet.Category -> {
            val txn = snapshot.txns.firstOrNull { it.id == sheet.txnId }
            val kind: CategoryKind = txn?.let { categoryKindFor(it.kind) } ?: CategoryKind.EXPENSE
            CategorySheet(
                categories = snapshot.categories.filter { it.kind == kind },
                currentId = txn?.category?.id,
                onPick = { onIntent(HomeIntent.PickCategory(it)) },
                onDismiss = { onIntent(HomeIntent.CloseSheet) },
            )
        }
    }
}

@Composable
private fun greeting(): String {
    val hour = remember { java.time.LocalTime.now(Ist.ZONE).hour }
    return stringResource(
        when {
            hour < 12 -> R.string.home_greeting_morning
            hour < 17 -> R.string.home_greeting_afternoon
            else -> R.string.home_greeting_evening
        },
    )
}

@Composable
private fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
    today -> stringResource(R.string.home_day_today)
    today.minusDays(1) -> stringResource(R.string.home_day_yesterday)
    else -> DAY_HEADING.format(date)
}

@Composable
private fun homeMessageText(message: HomeMessage): String = when (message) {
    is HomeMessage.ScanDone -> stringResource(
        R.string.home_scan_done,
        pluralStringResource(R.plurals.home_scan_txns, message.transactions, message.transactions),
        pluralStringResource(R.plurals.home_scan_to_review, message.review, message.review),
    )
    HomeMessage.UpToDate -> stringResource(R.string.home_scan_up_to_date)
    HomeMessage.ScanFailed -> stringResource(R.string.home_scan_failed)
    HomeMessage.CategoryChanged -> stringResource(R.string.home_category_changed)
    HomeMessage.Failed -> stringResource(R.string.review_msg_failed)
}
