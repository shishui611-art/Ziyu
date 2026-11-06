/* Adapted from SukiSU-Ultra 075ac81f1dfe58b3f59c665a1b07fda8b975015f.
 * GPL-3.0-or-later; Ziyu adaptation under GPL-3.0-only.
 * See THIRD_PARTY_NOTICES.md and licenses/SukiSU-Ultra-GPL-3.0.txt.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import java.io.IOException
import java.io.InputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ThemePackageCrypto {
    private val MAGIC = byteArrayOf('S'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), 'H'.code.toByte())
    private const val IV_SIZE = 12
    private const val TAG_BIT_LENGTH = 128
    /**
     * `Cipher.update` is a JNI round trip. Android's `CipherInputStream` feeds it
     * 512-byte pieces, so a 20 MB package turns into tens of thousands of calls,
     * which is what makes large packages feel stuck. Batching both directions
     * through this buffer keeps the cost proportional to the crypto work.
     */
    private const val STREAM_BUFFER_SIZE = 256 * 1024

    // 48-byte precomputed hash of theme key
    private val HASH_BYTES = byteArrayOf(
        0x41.toByte(), 0xdc.toByte(), 0x17.toByte(), 0x4e.toByte(), 0x50.toByte(), 0x96.toByte(), 0xb4.toByte(), 0xee.toByte(),
        0x93.toByte(), 0x31.toByte(), 0x1c.toByte(), 0xc8.toByte(), 0x92.toByte(), 0xb1.toByte(), 0x32.toByte(), 0xe2.toByte(),
        0x19.toByte(), 0x61.toByte(), 0x01.toByte(), 0x2b.toByte(), 0x14.toByte(), 0xcf.toByte(), 0x21.toByte(), 0x79.toByte(),
        0x54.toByte(), 0x0f.toByte(), 0x20.toByte(), 0x3c.toByte(), 0xa7.toByte(), 0x2a.toByte(), 0xb9.toByte(), 0xc1.toByte(),
        0xd9.toByte(), 0xcd.toByte(), 0xa5.toByte(), 0x45.toByte(), 0x2e.toByte(), 0x58.toByte(), 0xe6.toByte(), 0xdf.toByte(),
        0x56.toByte(), 0x79.toByte(), 0xa1.toByte(), 0x46.toByte(), 0x4d.toByte(), 0x44.toByte(), 0x0a.toByte(), 0x0a.toByte()
    )

    private val secretKey = SecretKeySpec(HASH_BYTES.copyOfRange(0, 32), "AES")
    /**
     * Streams decryption without buffering the whole package. The GCM tag is verified when
     * the returned stream reaches EOF or is closed, so callers must not silently drop it:
     * closing drains any unread payload and surfaces a tampered package.
     */
    fun decryptingStream(input: InputStream): InputStream {
        val header = ByteArray(MAGIC.size + IV_SIZE)
        readFully(input, header)
        for (i in MAGIC.indices) {
            if (header[i] != MAGIC[i]) {
                error("Invalid theme package: header mismatch")
            }
        }
        val iv = header.copyOfRange(MAGIC.size, MAGIC.size + IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BIT_LENGTH, iv))
        return CipherSource(input, cipher)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) error("Invalid theme package: truncated header")
            offset += read
        }
    }


    private class CipherSource(
        private val input: InputStream,
        private val cipher: Cipher,
    ) : InputStream() {
        private val encrypted = ByteArray(STREAM_BUFFER_SIZE)
        private val singleByte = ByteArray(1)
        private var plain = ByteArray(0)
        private var plainOffset = 0
        private var eof = false
        private var closed = false

        override fun read(): Int {
            val count = read(singleByte, 0, 1)
            return if (count < 0) -1 else singleByte[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (off < 0 || len < 0 || off + len > b.size) {
                throw IndexOutOfBoundsException()
            }
            if (len == 0) return 0
            if (closed) throw IOException("Stream closed")
            while (plainOffset >= plain.size) {
                if (eof) return -1
                fill()
            }
            val count = minOf(len, plain.size - plainOffset)
            System.arraycopy(plain, plainOffset, b, off, count)
            plainOffset += count
            return count
        }

        private fun fill() {
            while (true) {
                val read = input.read(encrypted)
                if (read < 0) {
                    eof = true
                    plain = cipher.doFinal()
                    plainOffset = 0
                    return
                }
                if (read == 0) continue
                val decrypted = cipher.update(encrypted, 0, read)
                if (decrypted != null && decrypted.isNotEmpty()) {
                    plain = decrypted
                    plainOffset = 0
                    return
                }
            }
        }

        override fun close() {
            if (closed) return
            try {
                if (!eof) {
                    // Draining forces GCM to verify the tag even when the caller only
                    // needed a header (e.g. reading theme.json while inspecting a package).
                    val sink = ByteArray(64 * 1024)
                    while (read(sink, 0, sink.size) >= 0) {
                        // Discard the remaining plaintext.
                    }
                }
            } finally {
                closed = true
                input.close()
            }
        }
    }
}
