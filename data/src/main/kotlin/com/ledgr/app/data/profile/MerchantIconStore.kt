package com.ledgr.app.data.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.graphics.scale
import com.ledgr.app.data.db.CounterpartyEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pictures the user picks for merchants (docs/05 `counterparty.icon` = `IMG:<file name>`).
 *
 * The Photo Picker hands over a URI and no permission is needed. The picture is decoded, centre-cropped to a
 * square, scaled to [SIZE] px and saved in app-private storage (never shared, never backed up, see the data
 * extraction rules). Only the file name goes in the database.
 */
@Singleton
class MerchantIconStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, DIR_NAME)

    /** Saves the picked image and returns the `icon` value to store, or null if it could not be read. */
    suspend fun import(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // Never hold a full-size photo in memory.
                val longest = max(info.size.width, info.size.height)
                if (longest > DECODE_LIMIT) {
                    val scale = DECODE_LIMIT.toFloat() / longest
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).toInt()),
                        max(1, (info.size.height * scale).toInt()),
                    )
                }
            }
            val side = min(decoded.width, decoded.height)
            val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
            val scaled = square.scale(SIZE, SIZE)
            dir.mkdirs()
            val name = "m-${System.currentTimeMillis()}-${System.nanoTime() % 100_000}.png"
            File(dir, name).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            CounterpartyEntity.ICON_IMAGE_PREFIX + name
        } catch (_: Exception) {
            null
        }
    }

    /** The saved picture behind an `icon` value, or null if [icon] is not an image or the file is gone. */
    fun fileFor(icon: String): File? {
        if (!icon.startsWith(CounterpartyEntity.ICON_IMAGE_PREFIX)) return null
        val name = icon.removePrefix(CounterpartyEntity.ICON_IMAGE_PREFIX)
        // The name is ours, but never let a damaged row escape the folder.
        if (name.isEmpty() || '/' in name || '\\' in name) return null
        return File(dir, name).takeIf { it.isFile }
    }

    /** Deletes the file behind an `icon` value (no-op for non-image icons). */
    suspend fun delete(icon: String) {
        withContext(Dispatchers.IO) { fileFor(icon)?.delete() }
    }

    private companion object {
        const val DIR_NAME = "merchant_icons"
        const val SIZE = 256
        const val DECODE_LIMIT = 1024
    }
}
