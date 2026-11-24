package com.ledgr.app.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.ledgr.app.domain.sender.SenderNormalizer
import com.ledgr.app.domain.sender.SenderSuffix
import com.ledgr.app.domain.sender.SenderType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One sender header found in the inbox, e.g. `HDFCBK` (messages from `VM-HDFCBK-S` and `AD-HDFCBK-T` merge). */
data class SeenHeader(
    val header: String,
    val messageCount: Int,
    /** Epoch ms of the newest message. */
    val lastSeen: Long,
    /** Messages per TRAI suffix; [SenderSuffix.NONE] counts addresses without one. */
    val suffixCounts: Map<SenderSuffix, Int>,
)

/** What the inbox holds, by sender. Contains no message text. */
data class SendersSnapshot(
    val headers: List<SeenHeader>,
    /** Messages from mobile numbers: never a bank (docs/04 L1), so they are summarised, not listed. */
    val phoneNumberMessages: Int,
    /** Messages from addresses that are neither a header nor a number (short codes, odd values). */
    val otherMessages: Int,
    val totalMessages: Int,
)

sealed interface SendersResult {
    data class Success(val snapshot: SendersSnapshot) : SendersResult
    data object PermissionDenied : SendersResult

    /** The provider could not be read; [reason] is an exception class name, never SMS content. */
    data class Failure(val reason: String) : SendersResult
}

/**
 * Lists the distinct sender headers in the SMS inbox with counts (docs/03 §3 "Senders seen").
 *
 * **Privacy:** the query asks the provider for `address` and `date` only. The message body is never requested,
 * so it never enters this process for this feature. Nothing is logged.
 */
@Singleton
class SmsInboxSenders @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun read(): SendersResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext SendersResult.PermissionDenied
        try {
            SendersResult.Success(aggregate(readAddresses()))
        } catch (_: SecurityException) {
            SendersResult.PermissionDenied
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendersResult.Failure(e.javaClass.simpleName)
        }
    }

    /** address -> (count, newest date). Only these two columns are requested. */
    private suspend fun readAddresses(): Map<String, Pair<Int, Long>> {
        val seen = HashMap<String, Pair<Int, Long>>()
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE),
            null,
            null,
            null,
        ) ?: return seen
        cursor.use {
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)
            var rows = 0
            val job = currentCoroutineContext()
            while (it.moveToNext()) {
                if (++rows % CANCEL_CHECK_EVERY == 0) job.ensureActive()
                val address = it.getString(addressIndex)?.takeIf(String::isNotBlank) ?: continue
                val date = it.getLong(dateIndex)
                val previous = seen[address]
                seen[address] = if (previous == null) 1 to date else (previous.first + 1) to maxOf(previous.second, date)
            }
        }
        return seen
    }

    private fun aggregate(byAddress: Map<String, Pair<Int, Long>>): SendersSnapshot {
        class Acc(var count: Int = 0, var last: Long = 0, val suffixes: MutableMap<SenderSuffix, Int> = HashMap())

        val byHeader = HashMap<String, Acc>()
        var phone = 0
        var other = 0
        var total = 0
        for ((address, stats) in byAddress) {
            val (count, last) = stats
            total += count
            val sender = SenderNormalizer.normalize(address)
            when (sender.type) {
                SenderType.PHONE_NUMBER -> phone += count
                SenderType.INVALID -> other += count
                SenderType.HEADER -> {
                    val acc = byHeader.getOrPut(requireNotNull(sender.header)) { Acc() }
                    acc.count += count
                    acc.last = maxOf(acc.last, last)
                    acc.suffixes.merge(sender.suffix, count, Int::plus)
                }
            }
        }
        val headers = byHeader.map { (header, acc) ->
            SeenHeader(header, acc.count, acc.last, acc.suffixes.toMap())
        }.sortedWith(compareByDescending<SeenHeader> { it.messageCount }.thenBy { it.header })
        return SendersSnapshot(headers, phone, other, total)
    }

    private companion object {
        const val CANCEL_CHECK_EVERY = 500
    }
}
