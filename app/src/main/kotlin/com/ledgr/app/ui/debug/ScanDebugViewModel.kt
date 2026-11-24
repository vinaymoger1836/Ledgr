package com.ledgr.app.ui.debug

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ledgr.app.data.db.DatabaseManager
import com.ledgr.app.data.db.RawSmsEntity
import com.ledgr.app.data.db.ReasonCount
import com.ledgr.app.data.db.ScanKind
import com.ledgr.app.data.db.ScanRunEntity
import com.ledgr.app.data.db.ScanStatus
import com.ledgr.app.data.sms.ScanProgress
import com.ledgr.app.data.sms.ScanProgressRepository
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.domain.time.Ist
import com.ledgr.app.ui.profile.SmsAccess
import com.ledgr.app.work.ScanStarter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** One reason and how many stored messages of the month have it. */
data class ReasonRowUi(val outcome: String, val reason: String, val count: Int) {
    val code: String get() = "$outcome: $reason"
}

/** All reasons of one outcome, with the outcome's total. */
data class OutcomeGroupUi(val outcome: String, val total: Int, val reasons: List<ReasonRowUi>)

data class ScanReportUi(
    val status: ScanStatus,
    val durationMs: Long?,
    val read: Int,
    val skipped: Int,
    val errors: Int,
    val rulesVersion: String,
    val finishedAt: Long?,
)

data class RawSmsRowUi(
    val id: Long,
    val header: String?,
    val suffix: SenderSuffix,
    val address: String,
    val receivedAt: Long,
    val outcome: String,
    val reason: String,
    /** Null once the body was purged. */
    val body: String?,
    val expanded: Boolean,
)

data class MessageFilter(val outcome: String? = null, val reason: String? = null, val limit: Int = PAGE) {
    companion object {
        const val PAGE = 200
    }
}

data class ScanDebugUiState(
    val access: SmsAccess = SmsAccess.Checking,
    val receiveGranted: Boolean = false,
    val notificationsGranted: Boolean = true,
    val month: YearMonth = Ist.monthOf(Ist.now()),
    val isCurrentMonth: Boolean = true,
    val progress: ScanProgress? = null,
    val report: ScanReportUi? = null,
    val storedTotal: Int = 0,
    val groups: List<OutcomeGroupUi> = emptyList(),
    val filter: MessageFilter = MessageFilter(),
    val messages: List<RawSmsRowUi> = emptyList(),
    val canShowMore: Boolean = false,
) {
    val running: Boolean get() = progress?.status == ScanStatus.RUNNING
}

sealed interface ScanDebugIntent {
    /** Screen shown or resumed, or a permission dialog finished: re-read what is allowed. */
    data object Refresh : ScanDebugIntent

    /** The SMS permission request came back; [granted] false means the system refused (restricted settings). */
    data class SmsResult(val granted: Boolean) : ScanDebugIntent
    data object PreviousMonth : ScanDebugIntent
    data object NextMonth : ScanDebugIntent
    data object StartScan : ScanDebugIntent
    data object CatchUpNow : ScanDebugIntent
    data class SetFilter(val outcome: String?, val reason: String?) : ScanDebugIntent
    data object ShowMore : ScanDebugIntent
    data class ToggleExpanded(val id: Long) : ScanDebugIntent
}

private data class MonthData(
    val counts: List<ReasonCount> = emptyList(),
    val total: Int = 0,
    val run: ScanRunEntity? = null,
)

private data class Local(
    val month: YearMonth,
    val filter: MessageFilter,
    val readGranted: Boolean,
    val receiveGranted: Boolean,
    val notificationsGranted: Boolean,
    val blocked: Boolean,
    val expandedId: Long?,
)

/** The outcome order a person wants to read a report in: what needs attention first. */
private val OUTCOME_ORDER = listOf("TXN", "REVIEW", "QUARANTINE", "BALANCE", "IGNORED")

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScanDebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseManager: DatabaseManager,
    progressRepository: ScanProgressRepository,
    private val scanStarter: ScanStarter,
) : ViewModel() {

    private val month = MutableStateFlow(Ist.monthOf(Ist.now()))
    private val filter = MutableStateFlow(MessageFilter())
    private val permissions = MutableStateFlow(readPermissions())
    private val blocked = MutableStateFlow(false)
    private val expandedId = MutableStateFlow<Long?>(null)

    private val local: Flow<Local> = combine(month, filter, permissions, blocked, expandedId) { m, f, p, b, e ->
        Local(m, f, p.read, p.receive, p.notifications, b, e)
    }

    private val monthData: Flow<MonthData> = month.flatMapLatest { m ->
        val range = Ist.monthRange(m)
        val from = range.first
        val to = range.last + 1
        databaseManager.observe { db ->
            combine(
                db.rawSmsDao().observeCounts(from, to),
                db.rawSmsDao().observeTotal(from, to),
                db.scanRunDao().observeLatest(m.toString(), ScanKind.MONTH.name),
            ) { counts, total, run -> MonthData(counts, total, run) }
        }
    }.onStart { emit(MonthData()) }

    private val messages: Flow<List<RawSmsEntity>> = combine(month, filter) { m, f -> m to f }
        .flatMapLatest { (m, f) ->
            val range = Ist.monthRange(m)
            databaseManager.observe { db ->
                db.rawSmsDao().observeFiltered(range.first, range.last + 1, f.outcome, f.reason, f.limit)
            }
        }.onStart { emit(emptyList()) }

    val state: StateFlow<ScanDebugUiState> = combine(local, monthData, messages, progressRepository.progress) { l, data, rows, progress ->
        val groups = data.counts
            .groupBy { it.outcome }
            .map { (outcome, items) ->
                OutcomeGroupUi(
                    outcome = outcome,
                    total = items.sumOf { it.n },
                    reasons = items.sortedByDescending { it.n }.map { ReasonRowUi(it.outcome, it.reason, it.n) },
                )
            }
            .sortedBy { OUTCOME_ORDER.indexOf(it.outcome).let { i -> if (i < 0) OUTCOME_ORDER.size else i } }
        ScanDebugUiState(
            access = when {
                l.readGranted -> SmsAccess.Granted
                l.blocked -> SmsAccess.Blocked
                else -> SmsAccess.NeedsPermission
            },
            receiveGranted = l.receiveGranted,
            notificationsGranted = l.notificationsGranted,
            month = l.month,
            isCurrentMonth = l.month == Ist.monthOf(Ist.now()),
            progress = progress,
            report = data.run?.toReport(),
            storedTotal = data.total,
            groups = groups,
            filter = l.filter,
            messages = rows.map { it.toRow(it.id == l.expandedId) },
            canShowMore = rows.size >= l.filter.limit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanDebugUiState())

    fun onIntent(intent: ScanDebugIntent) {
        when (intent) {
            ScanDebugIntent.Refresh -> permissions.value = readPermissions()
            is ScanDebugIntent.SmsResult -> {
                permissions.value = readPermissions()
                blocked.value = !intent.granted
            }
            ScanDebugIntent.PreviousMonth -> changeMonth(month.value.minusMonths(1))
            ScanDebugIntent.NextMonth -> {
                val next = month.value.plusMonths(1)
                if (!next.isAfter(Ist.monthOf(Ist.now()))) changeMonth(next)
            }
            ScanDebugIntent.StartScan -> scanStarter.scanMonth(month.value)
            ScanDebugIntent.CatchUpNow -> scanStarter.catchUpNow()
            is ScanDebugIntent.SetFilter -> filter.value = MessageFilter(intent.outcome, intent.reason)
            ScanDebugIntent.ShowMore -> filter.update { it.copy(limit = it.limit + MessageFilter.PAGE) }
            is ScanDebugIntent.ToggleExpanded -> expandedId.update { if (it == intent.id) null else intent.id }
        }
    }

    private fun changeMonth(target: YearMonth) {
        month.value = target
        filter.value = MessageFilter()
        expandedId.value = null
    }

    private data class Permissions(val read: Boolean, val receive: Boolean, val notifications: Boolean)

    private fun readPermissions(): Permissions = Permissions(
        read = granted(Manifest.permission.READ_SMS),
        receive = granted(Manifest.permission.RECEIVE_SMS),
        // Below Android 13 notifications need no runtime permission.
        notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
    )

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun ScanRunEntity.toReport() = ScanReportUi(
        status = status,
        durationMs = finishedAt?.let { it - startedAt },
        read = readCount,
        skipped = skippedCount,
        errors = errorCount,
        rulesVersion = rulesVersion,
        finishedAt = finishedAt,
    )

    private fun RawSmsEntity.toRow(expanded: Boolean) = RawSmsRowUi(
        id = id,
        header = senderHeader,
        suffix = senderSuffix,
        address = address,
        receivedAt = receivedAt,
        outcome = outcome.name,
        reason = reason,
        body = body,
        expanded = expanded,
    )
}
