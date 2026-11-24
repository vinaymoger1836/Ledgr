package com.ledgr.app.ui.home

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.ScanKind
import com.ledgr.app.data.db.ScanStatus
import com.ledgr.app.data.home.HomeRepository
import com.ledgr.app.data.home.HomeSnapshot
import com.ledgr.app.data.profile.AccountRepository
import com.ledgr.app.data.review.ReviewRepository
import com.ledgr.app.data.settings.SettingsRepository
import com.ledgr.app.data.sms.ScanProgress
import com.ledgr.app.data.sms.ScanProgressRepository
import com.ledgr.app.domain.time.Ist
import com.ledgr.app.work.ScanStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A month scan or catch-up in progress, as the hero's ring and counters need it. */
data class ScanUi(
    val read: Int = 0,
    val total: Int = 0,
    val transactions: Int = 0,
    val ignored: Int = 0,
    val review: Int = 0,
    val errors: Int = 0,
) {
    /** 0..1000 for the ring; indeterminate (-1) until the inbox size is known. */
    val permille: Int get() = if (total <= 0) -1 else (read.toLong() * 1000 / total).toInt().coerceIn(0, 1000)
}

/** One-shot results Home reports in a snackbar. */
sealed interface HomeMessage {
    data class ScanDone(val transactions: Int, val review: Int) : HomeMessage
    data object UpToDate : HomeMessage
    data object ScanFailed : HomeMessage
    data object CategoryChanged : HomeMessage
    data object Failed : HomeMessage
}

/** Which sheet, if any, is open on top of Home. */
sealed interface HomeSheet {
    data object MonthPicker : HomeSheet
    data class Day(val date: LocalDate) : HomeSheet
    data class Category(val txnId: Long) : HomeSheet
}

data class HomeUiState(
    /** False until the stored month and the first snapshot are known; Home shows nothing rather than flicker. */
    val ready: Boolean = false,
    val month: YearMonth = YearMonth.now(Ist.ZONE),
    /** -1 when the last month change went back in time, +1 forward: the content slides that way. */
    val direction: Int = 0,
    val snapshot: HomeSnapshot? = null,
    val monthsWithData: Set<YearMonth> = emptySet(),
    val reviewCount: Int = 0,
    val accountCount: Int = 0,
    /** The month scan for [month] that is running now, if any. */
    val scan: ScanUi? = null,
    /** A catch-up (pull to refresh or app open) is running. */
    val refreshing: Boolean = false,
    /** First run: nothing finished yet and no account added (docs/08 §6). */
    val showOnboarding: Boolean = false,
    val sheet: HomeSheet? = null,
    val message: HomeMessage? = null,
)

sealed interface HomeIntent {
    data class SelectMonth(val month: YearMonth) : HomeIntent
    data object OpenMonthPicker : HomeIntent
    data class OpenDay(val date: LocalDate) : HomeIntent
    data class OpenCategory(val txnId: Long) : HomeIntent
    data class PickCategory(val categoryId: Long) : HomeIntent
    data object CloseSheet : HomeIntent
    data object ScanMonth : HomeIntent
    data object Refresh : HomeIntent
    data object MessageShown : HomeIntent
    data object FinishOnboarding : HomeIntent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val saved: SavedStateHandle,
    private val settings: SettingsRepository,
    private val home: HomeRepository,
    private val review: ReviewRepository,
    accounts: AccountRepository,
    private val scanStarter: ScanStarter,
    progress: ScanProgressRepository,
) : ViewModel() {

    /** Null until resolved: the month in SavedState (a rotation or process restart), else DataStore, else this month. */
    private val selected = MutableStateFlow<YearMonth?>(saved.get<String>(KEY_MONTH)?.let(::parseMonth))
    private val local = MutableStateFlow(Local())

    /** The person pulled to refresh, so the catch-up that follows should end in a snackbar. */
    private var askedToRefresh = false

    private data class Local(val direction: Int = 0, val sheet: HomeSheet? = null, val message: HomeMessage? = null)

    init {
        viewModelScope.launch {
            if (selected.value == null) {
                val stored = settings.settings.first().selectedMonth?.let(::parseMonth)
                selected.value = stored?.takeIf { !it.isAfter(currentMonth()) } ?: currentMonth()
            }
        }
        // A finished run becomes one snackbar: "96 transactions · 7 to review".
        viewModelScope.launch {
            var running = false
            progress.progress.collect { p ->
                val nowRunning = p?.status == ScanStatus.RUNNING
                if (running && !nowRunning && p != null) {
                    when (p.kind) {
                        ScanKind.MONTH -> finished(p)?.let(::post)
                        // The catch-up that runs on every app open stays quiet; only a pull to refresh reports.
                        ScanKind.CATCHUP -> if (askedToRefresh) {
                            askedToRefresh = false
                            finished(p)?.let(::post)
                        }
                        ScanKind.LIVE -> Unit
                    }
                }
                running = nowRunning
            }
        }
        // An old install that already has accounts has seen enough of the first-run flow.
        viewModelScope.launch {
            combine(settings.settings, accounts.accounts) { s, a -> !s.onboardingDone && a.isNotEmpty() }
                .collect { if (it) settings.setOnboardingDone(true) }
        }
    }

    private val snapshot: Flow<HomeSnapshot?> = selected.flatMapLatest { m -> if (m == null) flowOf(null) else home.observe(m) }

    private val scan: Flow<ScanUi?> = combine(selected, progress.progress) { month, p ->
        p?.takeIf { it.kind == ScanKind.MONTH && it.label == month.toString() && it.status == ScanStatus.RUNNING }?.toUi()
    }

    private val refreshing: Flow<Boolean> = progress.progress.map {
        it != null && it.kind == ScanKind.CATCHUP && it.status == ScanStatus.RUNNING
    }

    private val shell: Flow<Shell> = combine(
        home.monthsWithData,
        review.counts.map { it.total },
        accounts.accounts.map { it.size },
        settings.settings.map { it.onboardingDone },
    ) { months, reviewCount, accountCount, onboardingDone -> Shell(months, reviewCount, accountCount, onboardingDone) }

    private data class Shell(val months: Set<YearMonth>, val review: Int, val accounts: Int, val onboardingDone: Boolean)

    val state: StateFlow<HomeUiState> = combine(selected, snapshot, shell, scan, refreshing) { month, snap, shell, scan, refreshing ->
        Partial(month, snap, shell, scan, refreshing)
    }.combine(local) { p, l ->
        HomeUiState(
            ready = p.month != null && p.snapshot != null,
            month = p.month ?: currentMonth(),
            direction = l.direction,
            snapshot = p.snapshot,
            monthsWithData = p.shell.months,
            reviewCount = p.shell.review,
            accountCount = p.shell.accounts,
            scan = p.scan,
            refreshing = p.refreshing,
            showOnboarding = !p.shell.onboardingDone && p.shell.accounts == 0,
            sheet = l.sheet,
            message = l.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private class Partial(
        val month: YearMonth?,
        val snapshot: HomeSnapshot?,
        val shell: Shell,
        val scan: ScanUi?,
        val refreshing: Boolean,
    )

    fun onIntent(intent: HomeIntent) {
        when (intent) {
            is HomeIntent.SelectMonth -> selectMonth(intent.month)
            HomeIntent.OpenMonthPicker -> local.update { it.copy(sheet = HomeSheet.MonthPicker) }
            is HomeIntent.OpenDay -> local.update { it.copy(sheet = HomeSheet.Day(intent.date)) }
            is HomeIntent.OpenCategory -> local.update { it.copy(sheet = HomeSheet.Category(intent.txnId)) }
            is HomeIntent.PickCategory -> pickCategory(intent.categoryId)
            HomeIntent.CloseSheet -> local.update { it.copy(sheet = null) }
            // Only the selected month's messages are read: switching months alone never starts a scan.
            HomeIntent.ScanMonth -> selected.value?.let(scanStarter::scanMonth)
            HomeIntent.Refresh -> {
                askedToRefresh = true
                scanStarter.catchUpNow()
            }
            HomeIntent.MessageShown -> local.update { it.copy(message = null) }
            HomeIntent.FinishOnboarding -> viewModelScope.launch { settings.setOnboardingDone(true) }
        }
    }

    private fun selectMonth(month: YearMonth) {
        val current = selected.value ?: return
        if (month.isAfter(currentMonth())) return // docs/08 §2.1: future months are disabled
        local.update { it.copy(direction = month.compareTo(current).coerceIn(-1, 1), sheet = null) }
        selected.value = month
        saved[KEY_MONTH] = month.toString()
        viewModelScope.launch { settings.setSelectedMonth(month.toString()) }
    }

    private fun pickCategory(categoryId: Long) {
        val sheet = local.value.sheet as? HomeSheet.Category ?: return
        local.update { it.copy(sheet = null) }
        viewModelScope.launch {
            val ok = runCatching { review.setCategory(sheet.txnId, categoryId) }.isSuccess
            post(if (ok) HomeMessage.CategoryChanged else HomeMessage.Failed)
        }
    }

    private fun post(message: HomeMessage) = local.update { it.copy(message = message) }

    /** The snackbar for a finished run; a stopped one says nothing. */
    private fun finished(p: ScanProgress): HomeMessage? {
        val outcomes = p.byOutcome
        val txns = outcomes["TXN"] ?: 0
        val review = (outcomes["REVIEW"] ?: 0) + (outcomes["QUARANTINE"] ?: 0)
        return when {
            p.status == ScanStatus.FAILED -> HomeMessage.ScanFailed
            p.status != ScanStatus.DONE -> null
            txns == 0 && review == 0 && p.kind == ScanKind.CATCHUP -> HomeMessage.UpToDate
            else -> HomeMessage.ScanDone(txns, review)
        }
    }

    private fun ScanProgress.toUi() = ScanUi(
        read = read,
        total = total,
        transactions = byOutcome["TXN"] ?: 0,
        ignored = byOutcome["IGNORED"] ?: 0,
        review = (byOutcome["REVIEW"] ?: 0) + (byOutcome["QUARANTINE"] ?: 0),
        errors = errors,
    )

    private fun currentMonth(): YearMonth = YearMonth.from(Ist.today())

    private fun parseMonth(text: String): YearMonth? = runCatching { YearMonth.parse(text) }.getOrNull()

    private companion object {
        const val KEY_MONTH = "month"
    }
}
