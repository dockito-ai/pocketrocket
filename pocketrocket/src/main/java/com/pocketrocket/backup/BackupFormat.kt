package com.pocketrocket.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a backup or restore stopped. The message is shown as it is; it never holds a passphrase or file contents. */
open class BackupException(message: String) : IOException(message)
class WrongPassphrase : BackupException("That passphrase doesn't open this backup.")
class DamagedBackup(detail: String = "") : BackupException("This backup is damaged and can't be restored." + if (detail.isEmpty()) "" else " $detail")
class UnsupportedBackup : BackupException("This backup was made by a newer PocketRocket. Update the app, then try again.")
class UnsafeBackup(path: String) : BackupException("This backup holds something it shouldn't and can't be restored: $path")
class BackupCancelled : BackupException("Cancelled.")
class NotEnoughSpace(val needed: Long, val free: Long) : BackupException("Not enough space.")
class SessionsRunning(val count: Int) : BackupException("Sessions are running.")

/**
 * The file's encrypted container (decision 53), independent of what is inside it:
 *
 *     header   "PRBACKUP", u16 version, u8 kdf, u32 iterations, u8 salt size, salt, u32 chunk size,
 *              7-byte nonce prefix, 16-byte verifier
 *     chunks   u32 size, AES-256-GCM ciphertext and tag, of at most [CHUNK_SIZE] plaintext bytes each
 *
 * The key comes from the passphrase with PBKDF2-HMAC-SHA256 ([ITERATIONS], salt in the header). Each chunk
 * is sealed alone, because Android's GCM can buffer a whole stream in memory on decrypt. Its nonce is the
 * prefix, its u32 index and a flag (1 on the last chunk), and the whole header is its associated data, so
 * reordered, dropped, added or cut chunks and a changed header all fail authentication. The verifier
 * is an empty message sealed with flag 2: it tells a wrong passphrase from a damaged file.
 */
internal object BackupFormat {
    val MAGIC = "PRBACKUP".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    const val ITERATIONS = 600_000
    const val MAX_ITERATIONS = 20_000_000
    const val SALT_SIZE = 16
    const val CHUNK_SIZE = 1024 * 1024
    const val MAX_CHUNK_SIZE = 16 * 1024 * 1024
    const val PREFIX_SIZE = 7
    const val TAG_BYTES = 16
    private const val FLAG_MIDDLE = 0
    const val FLAG_LAST = 1
    private const val FLAG_VERIFY = 2

    /** How much [size] plaintext bytes grow in the file, at most. */
    fun overhead(size: Long): Long = 64 + (size / CHUNK_SIZE + 1) * (4 + TAG_BYTES)

    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun nonce(prefix: ByteArray, index: Int, flag: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).put(flag.toByte()).array()

    fun seal(key: SecretKeySpec, nonce: ByteArray, aad: ByteArray, data: ByteArray, size: Int): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
            doFinal(data, 0, size)
        }

    fun open(key: SecretKeySpec, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray =
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(aad)
            doFinal(sealed)
        }

    fun verifierNonce(prefix: ByteArray) = nonce(prefix, 0, FLAG_VERIFY)
    fun chunkNonce(prefix: ByteArray, index: Int, last: Boolean) = nonce(prefix, index, if (last) FLAG_LAST else FLAG_MIDDLE)

    /** The header up to, and not including, the verifier. */
    fun headerStart(iterations: Int, salt: ByteArray, chunkSize: Int, prefix: ByteArray): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).run {
            write(MAGIC)
            writeShort(VERSION)
            writeByte(KDF_PBKDF2_SHA256)
            writeInt(iterations)
            writeByte(salt.size)
            write(salt)
            writeInt(chunkSize)
            write(prefix)
        }
        return bytes.toByteArray()
    }
}

/** Seals what is written into chunks on [out]. [close] writes the last chunk and closes [out]. */
internal class EncryptingOutput(
    private val out: OutputStream,
    passphrase: CharArray,
    iterations: Int = BackupFormat.ITERATIONS,
    private val chunkSize: Int = BackupFormat.CHUNK_SIZE,
    random: SecureRandom = SecureRandom(),
) : OutputStream() {
    private val prefix = ByteArray(BackupFormat.PREFIX_SIZE).also(random::nextBytes)
    private val key: SecretKeySpec
    private val header: ByteArray
    private val buffer = ByteArray(chunkSize)
    private var used = 0
    private var index = 0
    private var closed = false

    init {
        val salt = ByteArray(BackupFormat.SALT_SIZE).also(random::nextBytes)
        key = BackupFormat.deriveKey(passphrase, salt, iterations)
        val start = BackupFormat.headerStart(iterations, salt, chunkSize, prefix)
        header = start + BackupFormat.seal(key, BackupFormat.verifierNonce(prefix), start, ByteArray(0), 0)
        out.write(header)
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var at = off
        var left = len
        while (left > 0) {
            // A full buffer is sent only once more arrives, so the last chunk is never empty unless the file is.
            if (used == chunkSize) flushChunk(last = false)
            val n = minOf(left, chunkSize - used)
            System.arraycopy(b, at, buffer, used, n)
            used += n
            at += n
            left -= n
        }
    }

    private fun flushChunk(last: Boolean) {
        val sealed = BackupFormat.seal(key, BackupFormat.chunkNonce(prefix, index, last), header, buffer, used)
        out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
        out.write(sealed)
        index++
        used = 0
    }

    override fun flush() = out.flush()

    override fun close() {
        if (closed) return
        closed = true
        out.use { flushChunk(last = true) }
    }
}

/**
 * Opens what [EncryptingOutput] wrote. The constructor reads the header and checks the passphrase
 * ([WrongPassphrase], [DamagedBackup], [UnsupportedBackup]); reads then throw [DamagedBackup] on any chunk that
 * doesn't authenticate. The end of the file decides which chunk is last: a cut file makes a middle chunk look
 * last, which fails, and anything after the real last chunk makes it look like a middle one, which fails too.
 */
internal class DecryptingInput(raw: InputStream, passphrase: CharArray) : InputStream() {
    private val input = DataInputStream(raw)
    private val prefix: ByteArray
    private val key: SecretKeySpec
    private val header: ByteArray
    private val maxSealed: Int
    private var current = ByteArray(0)
    private var at = 0
    private var index = 0
    private var ended = false
    private var nextSize: Int

    init {
        val start = ByteArrayOutputStream()
        fun take(n: Int): ByteArray = ByteArray(n).also { input.readFully(it); start.write(it) }
        val verifier: ByteArray
        val iterations: Int
        val salt: ByteArray
        try {
            if (!take(BackupFormat.MAGIC.size).contentEquals(BackupFormat.MAGIC)) throw DamagedBackup("It isn't a PocketRocket backup.")
            val version = ByteBuffer.wrap(take(2)).short.toInt() and 0xFFFF
            if (version > BackupFormat.VERSION || version < 1) throw UnsupportedBackup()
            if (take(1)[0].toInt() != BackupFormat.KDF_PBKDF2_SHA256) throw UnsupportedBackup()
            iterations = ByteBuffer.wrap(take(4)).int
            if (iterations !in 1..BackupFormat.MAX_ITERATIONS) throw DamagedBackup()
            val saltSize = take(1)[0].toInt() and 0xFF
            if (saltSize == 0) throw DamagedBackup()
            salt = take(saltSize)
            val chunkSize = ByteBuffer.wrap(take(4)).int
            if (chunkSize !in 1..BackupFormat.MAX_CHUNK_SIZE) throw DamagedBackup()
            maxSealed = chunkSize + BackupFormat.TAG_BYTES
            prefix = take(BackupFormat.PREFIX_SIZE)
            verifier = ByteArray(BackupFormat.TAG_BYTES).also(input::readFully)
        } catch (_: EOFException) {
            throw DamagedBackup("It is cut short.")
        }
        val startBytes = start.toByteArray()
        key = BackupFormat.deriveKey(passphrase, salt, iterations)
        try {
            BackupFormat.open(key, BackupFormat.verifierNonce(prefix), startBytes, verifier)
        } catch (_: GeneralSecurityException) {
            throw WrongPassphrase()
        }
        header = startBytes + verifier
        nextSize = readSize() ?: throw DamagedBackup("It is cut short.")
    }

    /** The next chunk's size, or null exactly at the end of the file. */
    private fun readSize(): Int? {
        val first = input.read()
        if (first < 0) return null
        val rest = ByteArray(3)
        try {
            input.readFully(rest)
        } catch (_: EOFException) {
            throw DamagedBackup("It is cut short.")
        }
        val size = (first shl 24) or ((rest[0].toInt() and 0xFF) shl 16) or ((rest[1].toInt() and 0xFF) shl 8) or (rest[2].toInt() and 0xFF)
        if (size < BackupFormat.TAG_BYTES || size > maxSealed) throw DamagedBackup()
        return size
    }

    private fun nextChunk(): Boolean {
        if (ended) return false
        val sealed = ByteArray(nextSize)
        try {
            input.readFully(sealed)
        } catch (_: EOFException) {
            throw DamagedBackup("It is cut short.")
        }
        val following = readSize()
        val last = following == null
        current = try {
            BackupFormat.open(key, BackupFormat.chunkNonce(prefix, index, last), header, sealed)
        } catch (_: GeneralSecurityException) {
            throw DamagedBackup()
        }
        index++
        at = 0
        if (last) ended = true else nextSize = following!!
        return true
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (at >= current.size) if (!nextChunk()) return -1
        val n = minOf(len, current.size - at)
        System.arraycopy(current, at, b, off, n)
        at += n
        return n
    }

    override fun close() = input.close()
}
