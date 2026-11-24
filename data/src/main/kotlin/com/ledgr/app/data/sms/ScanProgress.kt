package com.ledgr.app.data.sms

import com.ledgr.app.data.db.ScanKind
import com.ledgr.app.data.db.ScanStatus
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Live state of the scan in progress (or the one that just ended). Counts are keyed by full reason code. */
data class ScanProgress(
    val kind: ScanKind,
    /** `yyyy-MM` for a month scan. */
    val label: String,
    val status: ScanStatus,
    val read: Int,
    val total: Int,
    val counts: Map<String, Int>,
    val errors: Int,
) {
    /** Totals per outcome, from the `"OUTCOME: reason"` keys. */
    val byOutcome: Map<String, Int>
        get() = counts.entries.groupBy({ it.key.substringBefore(':') }, { it.value }).mapValues { it.value.sum() }
}

/**
 * In-process progress of the scan engine, for the foreground notification and the UI. Process-local on purpose:
 * the durable record is the `scan_run` row.
 */
@Singleton
class ScanProgressRepository @Inject constructor() {
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    internal fun publish(progress: ScanProgress?) {
        _progress.value = progress
    }
}

/** `scan_run.counts_json`: `{"IGNORED: otp": 41, ...}`. */
object ScanCounts {
    private val serializer = MapSerializer(String.serializer(), Int.serializer())

    fun encode(counts: Map<String, Int>): String = Json.encodeToString(serializer, counts.toSortedMap())

    fun decode(json: String): Map<String, Int> =
        try {
            Json.decodeFromString(serializer, json)
        } catch (_: Exception) {
            emptyMap()
        }
}
