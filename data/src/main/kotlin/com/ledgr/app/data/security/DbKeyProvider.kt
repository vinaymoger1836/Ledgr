package com.ledgr.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The database key cannot be produced. The app must NOT silently create a new key (that would orphan the data). */
class DbKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Owns the SQLCipher key (docs/09 §2).
 *
 * - 32 random bytes are generated once, on first run.
 * - They are wrapped with an AES-256-GCM key that lives in the Android Keystore
 *   (`setUserAuthenticationRequired(false)` so background workers can open the DB; the app lock protects the UI).
 * - Only the wrapped blob is stored, in `noBackupFilesDir`. The raw key exists only in memory.
 * - Nothing here is ever logged.
 *
 * Blob layout: `[version=1][ivLength][iv][ciphertext + 16-byte GCM tag]`, AAD = [AAD].
 */
class DbKeyProvider(
    private val context: Context,
    private val databaseName: String,
) {
    private val keyFile: File get() = File(context.noBackupFilesDir, KEY_FILE_NAME)
    private val databaseFile: File get() = context.getDatabasePath(databaseName)

    /** SQLCipher passphrase: lower-case hex of the 32 key bytes, as ASCII. */
    fun passphrase(): ByteArray {
        val key = if (keyFile.exists()) unwrap() else createAndStore()
        return try {
            key.toHex().toByteArray(Charsets.US_ASCII)
        } finally {
            key.fill(0)
        }
    }

    private fun createAndStore(): ByteArray {
        if (databaseFile.exists()) {
            throw DbKeyException("The database exists but its key file is missing; refusing to create a new key.")
        }
        val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(createIfMissing = true))
            cipher.updateAAD(AAD)
            val iv = cipher.iv
            val sealed = cipher.doFinal(key)
            val blob = ByteArray(2 + iv.size + sealed.size)
            blob[0] = VERSION
            blob[1] = iv.size.toByte()
            iv.copyInto(blob, 2)
            sealed.copyInto(blob, 2 + iv.size)
            writeAtomically(blob)
        } catch (e: DbKeyException) {
            key.fill(0)
            throw e
        } catch (e: Exception) {
            key.fill(0)
            throw DbKeyException("Could not wrap the database key (${e.javaClass.simpleName}).", e)
        }
        return key
    }

    private fun unwrap(): ByteArray {
        try {
            val blob = keyFile.readBytes()
            if (blob.size < 2 || blob[0] != VERSION) throw DbKeyException("Unknown key file format.")
            val ivLength = blob[1].toInt() and 0xFF
            if (blob.size <= 2 + ivLength) throw DbKeyException("Key file is truncated.")
            val iv = blob.copyOfRange(2, 2 + ivLength)
            val sealed = blob.copyOfRange(2 + ivLength, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(createIfMissing = false), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val key = cipher.doFinal(sealed)
            if (key.size != KEY_BYTES) {
                key.fill(0)
                throw DbKeyException("Unexpected key length.")
            }
            return key
        } catch (e: DbKeyException) {
            throw e
        } catch (e: Exception) {
            throw DbKeyException("Could not unwrap the database key (${e.javaClass.simpleName}).", e)
        }
    }

    private fun wrappingKey(createIfMissing: Boolean): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!createIfMissing) {
            throw DbKeyException("The Keystore key that wraps the database key is gone.")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun writeAtomically(bytes: ByteArray) {
        val dir = keyFile.parentFile ?: throw DbKeyException("No private storage directory.")
        dir.mkdirs()
        val tmp = File(dir, "$KEY_FILE_NAME.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(digits[(b.toInt() shr 4) and 0xF]).append(digits[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "com.ledgr.app.db_wrap_key.v1"
        const val KEY_FILE_NAME = "ledgr_db.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val KEY_BYTES = 32
        const val VERSION: Byte = 1
        val AAD = "ledgr-db-key-v1".toByteArray(Charsets.US_ASCII)
    }
}
