/* Sealed CNB media envelope (SKM1) adapted from shirkneko/SukiSU 97d2442d.
 * GPL-3.0-or-later; Ziyu adaptation GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ThemeMediaCrypto {
    private val MAGIC = byteArrayOf(
        'S'.code.toByte(), 'K'.code.toByte(), 'M'.code.toByte(), '1'.code.toByte(),
    )
    private const val IV_SIZE = 12
    private const val LENGTH_SIZE = 8
    const val HEADER_SIZE = 4 + IV_SIZE + LENGTH_SIZE
    private const val TAG_BIT_LENGTH = 128

    private const val KEY_HEX =
        "13c4b9e823e7bce77af4b5e7add9af20f773cf51c25e968a8ab830f905113b31"

    private val secretKey = SecretKeySpec(
        ByteArray(KEY_HEX.length / 2) { index ->
            KEY_HEX.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        },
        "AES",
    )

    fun isSealed(prefix: ByteArray): Boolean =
        prefix.size >= MAGIC.size && MAGIC.indices.all { prefix[it] == MAGIC[it] }

    fun plainLength(header: ByteArray): Long {
        require(header.size >= HEADER_SIZE) { "媒体信封头无效" }
        return ByteBuffer.wrap(header, 16, LENGTH_SIZE).long
    }

    fun decrypt(bytes: ByteArray): ByteArray {
        require(bytes.size >= HEADER_SIZE) { "媒体信封数据不完整" }
        require(isSealed(bytes)) { "媒体信封头不匹配" }
        val iv = bytes.copyOfRange(4, 16)
        val declared = plainLength(bytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(TAG_BIT_LENGTH, iv))
        val plain = try {
            cipher.doFinal(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)
        } catch (error: GeneralSecurityException) {
            throw IllegalStateException("媒体信封校验失败", error)
        }
        require(plain.size.toLong() == declared) { "媒体信封长度不一致" }
        return plain
    }
}
