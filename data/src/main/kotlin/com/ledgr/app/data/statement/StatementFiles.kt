package com.ledgr.app.data.statement

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ledgr.app.data.rules.RulesLoader
import com.ledgr.app.domain.statement.FileSniffer
import com.ledgr.app.domain.statement.FileType
import com.ledgr.app.domain.statement.PdfLayout
import com.ledgr.app.domain.statement.RawStatement
import com.ledgr.app.domain.statement.StatementAnalyser
import com.ledgr.app.domain.statement.StatementException
import com.ledgr.app.domain.statement.StatementLimits
import com.ledgr.app.domain.statement.TextGlyph
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * docs/06 §1.1: a picked document is copied into app-private storage (`filesDir/imports`, docs/09 §1), streamed with a hard
 * size cap, never modified, and deleted once the import is saved or abandoned. The Storage Access Framework grants access to
 * the one file the person picked; the app needs no storage permission.
 */
@Singleton
class StatementIntake @Inject constructor(@ApplicationContext private val context: Context) {
    class Stored(val file: File, val displayName: String)

    private val dir: File get() = File(context.filesDir, "imports").apply { mkdirs() }

    /** @throws StatementException `too_large` when the file passes 20 MB, `corrupt` when it cannot be opened. */
    fun copy(uri: Uri): Stored {
        val out = File(dir, UUID.randomUUID().toString())
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw StatementException(StatementException.CORRUPT, "The file could not be opened")
            input.use { src ->
                out.outputStream().use { dst ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = src.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > StatementLimits.MAX_FILE_BYTES) throw StatementException(StatementException.TOO_LARGE, "The file is larger than 20 MB")
                        dst.write(buffer, 0, n)
                    }
                }
            }
        } catch (e: StatementException) {
            out.delete()
            throw e
        } catch (e: IOException) {
            out.delete()
            throw StatementException(StatementException.CORRUPT, "The file could not be read")
        } catch (e: SecurityException) {
            out.delete()
            throw StatementException(StatementException.CORRUPT, "The file could not be opened")
        }
        return Stored(out, displayName(uri))
    }

    /** Deletes a copy; a missing file is fine. */
    fun delete(file: File) {
        file.delete()
    }

    /** Copies left behind by a crash or a killed process (older than a day). Called when the Reconcile screen opens. */
    fun purgeStale() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun displayName(uri: Uri): String {
        val name = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return (name ?: "statement").trim().take(MAX_NAME)
    }

    private companion object {
        const val MAX_NAME = 120
    }
}

/** The result of reading one file: its type and either the rows or the positioned text lines. */
class ReadFile(val type: FileType, val raw: RawStatement)

/**
 * Reads a statement file on a background thread with a hard timeout (docs/06, CLAUDE.md rule 12). This is deliberately not a
 * WorkManager job: its input data is stored on disk, which would write a PDF password there (rule 8, ADR-016). The password
 * lives only in this call.
 */
@Singleton
class StatementReader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rulesLoader: RulesLoader,
) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "statement-reader").apply { isDaemon = true } }

    /**
     * @throws StatementException `needs_password` / `wrong_password` for an encrypted PDF, `no_text` for a scanned PDF,
     * `timeout`, `unsupported`, `too_large`, `corrupt`.
     */
    suspend fun read(file: File, password: String?): ReadFile {
        val profiles = rulesLoader.bundle().statementProfiles
        return onReaderThread {
            val bytes = file.readBytes()
            when (val type = FileSniffer.detect(bytes)) {
                FileType.PDF -> {
                    val glyphs = PdfExtractor.extract(context, file, password, profiles.pdf.maxPages)
                    if (glyphs.isEmpty()) throw StatementException(StatementException.NO_TEXT, "This PDF has no text")
                    ReadFile(type, RawStatement.Pdf(PdfLayout.lines(glyphs, profiles.pdf)))
                }
                else -> ReadFile(type, RawStatement.Tabular(StatementAnalyser.read(type, bytes)))
            }
        }
    }

    private suspend fun <T> onReaderThread(block: () -> T): T = withContext(Dispatchers.IO) {
        val future = pool.submit(Callable { block() })
        try {
            runInterruptible { future.get(StatementLimits.PARSE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw StatementException(StatementException.TIMEOUT, "Reading the file took too long")
        } catch (e: ExecutionException) {
            throw when (val cause = e.cause) {
                is StatementException -> cause
                is OutOfMemoryError -> StatementException(StatementException.TOO_LARGE, "The file is too big to read on this phone")
                is IOException -> StatementException(StatementException.CORRUPT, "The file is damaged")
                else -> StatementException(StatementException.CORRUPT, "The file could not be read")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            future.cancel(true)
            throw e
        }
    }
}

/**
 * PdfBox-Android adapter: one [TextGlyph] per character with its position. Positions are converted to 1/10 point here and
 * everything after is the shared, pure `:domain` layout. The JVM golden extractor (`GReconPdfCase`) does the same with
 * Apache PDFBox; only package names differ.
 */
internal object PdfExtractor {
    @Volatile private var initialised = false

    fun extract(context: Context, file: File, password: String?, maxPages: Int): List<TextGlyph> {
        if (!initialised) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialised = true
        }
        val doc = try {
            PDDocument.load(file, password ?: "")
        } catch (e: InvalidPasswordException) {
            throw if (password == null) StatementException(StatementException.NEEDS_PASSWORD, "This PDF needs a password")
            else StatementException(StatementException.WRONG_PASSWORD, "That password did not open the PDF")
        }
        doc.use {
            val stripper = GlyphStripper()
            stripper.startPage = 1
            stripper.endPage = maxPages
            stripper.getText(it)
            return stripper.glyphs
        }
    }

    private class GlyphStripper : PDFTextStripper() {
        val glyphs = ArrayList<TextGlyph>()

        override fun writeString(text: String, textPositions: List<TextPosition>) {
            for (tp in textPositions) {
                glyphs += TextGlyph(
                    page = currentPageNo,
                    x = (tp.xDirAdj * 10).roundToInt(),
                    y = (tp.yDirAdj * 10).roundToInt(),
                    width = (tp.widthDirAdj * 10).roundToInt(),
                    size = (tp.fontSizeInPt * 10).roundToInt(),
                    text = tp.unicode,
                )
            }
        }
    }
}
