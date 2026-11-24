package com.ledgr.app.data.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.provider.Telephony
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/** One inbox row. The provider assembles multi-part messages, so [body] is the whole text. */
data class InboxSms(
    /** `Telephony.Sms._id`. */
    val providerId: Long,
    val address: String,
    val body: String,
    /** Receipt time, epoch ms. */
    val date: Long,
    /** Telephony subscription id (not the SIM slot), when the device reports one. */
    val subId: Int?,
)

/**
 * Reads SMS bodies from the system inbox (docs/02 §3.1). Unlike `SmsInboxSenders` (sender names and dates only,
 * for Profile) this reads the text, so it is used only by the scan engine, which stores the result encrypted.
 * Nothing here logs message content.
 */
@Singleton
class SmsSource @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** How many inbox messages fall in `[from, to)` (epoch ms). Used for the progress total. */
    suspend fun count(from: Long, to: Long): Int = withContext(Dispatchers.IO) {
        val args = rangeArgs("date >= ? AND date < ?", arrayOf(from.toString(), to.toString()), sort = null, limit = null)
        context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, arrayOf(Telephony.Sms._ID), args, null)
            ?.use { it.count } ?: 0
    }

    /**
     * Messages in `[from, to)` in pages, oldest first. Keyset paging on `(date, _id)`, so a message arriving
     * mid-scan can never shift a page and cause a skip or a repeat (offset paging would).
     */
    fun pages(from: Long, to: Long, pageSize: Int = PAGE_SIZE): Flow<List<InboxSms>> = flow {
        var lastDate = -1L
        var lastId = -1L
        while (true) {
            val selection: String
            val args: Array<String>
            if (lastId < 0) {
                selection = "date >= ? AND date < ?"
                args = arrayOf(from.toString(), to.toString())
            } else {
                selection = "date >= ? AND date < ? AND (date > ? OR (date = ? AND _id > ?))"
                args = arrayOf(from.toString(), to.toString(), lastDate.toString(), lastDate.toString(), lastId.toString())
            }
            val page = query(selection, args, pageSize)
            if (page.isEmpty()) break
            emit(page)
            val last = page.last()
            lastDate = last.date
            lastId = last.providerId
            if (page.size < pageSize) break
        }
    }.flowOn(Dispatchers.IO)

    /** Inbox messages received within [windowMs] of [aroundMs]. For matching a just-arrived SMS (the caller filters by hash). */
    suspend fun findNear(aroundMs: Long, windowMs: Long): List<InboxSms> = withContext(Dispatchers.IO) {
        query(
            "date >= ? AND date <= ?",
            arrayOf((aroundMs - windowMs).toString(), (aroundMs + windowMs).toString()),
            limit = 200,
        )
    }

    private fun query(selection: String, selectionArgs: Array<String>, limit: Int): List<InboxSms> {
        val args = rangeArgs(selection, selectionArgs, sort = "date ASC, _id ASC", limit = limit)
        val cursor = try {
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, PROJECTION_WITH_SUB, args, null)
        } catch (_: IllegalArgumentException) {
            // Some devices do not expose `sub_id` on the inbox: read without it.
            context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, PROJECTION, args, null)
        } ?: return emptyList()
        return cursor.use { read(it) }
    }

    private fun read(c: Cursor): List<InboxSms> {
        val id = c.getColumnIndexOrThrow(Telephony.Sms._ID)
        val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
        val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
        val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
        val sub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
        val out = ArrayList<InboxSms>(c.count)
        while (c.moveToNext()) {
            out += InboxSms(
                providerId = c.getLong(id),
                address = c.getString(address).orEmpty(),
                body = c.getString(body).orEmpty(),
                date = c.getLong(date),
                subId = if (sub >= 0 && !c.isNull(sub)) c.getInt(sub) else null,
            )
        }
        return out
    }

    private fun rangeArgs(selection: String, selectionArgs: Array<String>, sort: String?, limit: Int?): Bundle =
        Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            if (sort != null) putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
            if (limit != null) putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }

    private companion object {
        const val PAGE_SIZE = 500
        val PROJECTION = arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE)
        val PROJECTION_WITH_SUB = PROJECTION + Telephony.Sms.SUBSCRIPTION_ID
    }
}
