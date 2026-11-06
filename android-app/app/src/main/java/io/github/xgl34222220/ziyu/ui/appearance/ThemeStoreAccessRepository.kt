/* Device-access protocol adapted from SukiSU-Ultra
 * 8ed5bd556fba073d52f4a4d98500b782db6437e6, GPL-3.0-or-later.
 * Ziyu implementation: GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** Called on IO. Credentials stay in private, non-backed-up storage and never enter UI/export. */
internal class ThemeStoreAccessRepository(context: Context) {
    private val directory = File(context.noBackupFilesDir, "theme-device-access")

    fun automatic(source: ThemeSource) = source.accessCode.isBlank() &&
        ThemeStoreRepository.normalize(source.url) == ThemeStoreDefaults.BASE_URL

    suspend fun resolve(source: ThemeSource): String {
        if (!automatic(source)) return source.accessCode
        return rotation.withLock { resolveLocked(source, null) }
    }

    suspend fun refresh(source: ThemeSource, rejected: String): String = rotation.withLock {
        resolveLocked(source, rejected)
    }

    suspend fun reconnect(source: ThemeSource) = rotation.withLock {
        // Only an explicit user connection action discards a revoked grant.
        if (automatic(source)) file(source).delete()
    }

    suspend fun reject(source: ThemeSource, token: String) = rotation.withLock {
        if (automatic(source)) read(source)?.let {
            if (it.optString("accessToken") == token) save(source, it.put("revoked", true))
        }
    }

    private suspend fun resolveLocked(source: ThemeSource, rejected: String?): String {
        currentCoroutineContext().ensureActive()
        val saved = read(source)
        if (saved?.optBoolean("revoked") == true) throw authorizationError()
        val now = System.currentTimeMillis() / 1000
        if (saved != null) {
            validate(saved)
            val expires = saved.getLong("expiresAt")
            val refreshAfter = minOf(saved.optLong("refreshAfter", expires - 120), expires - 15)
            val token = saved.getString("accessToken")
            if ((rejected == null || rejected != token) && now < refreshAfter && now < expires) return token
            if (saved.getLong("refreshExpiresAt") <= now) throw authorizationError()
        }
        val payload = if (saved == null) JSONObject().put("name", "Ziyu Android")
            else JSONObject().put("refreshToken", saved.getString("refreshToken"))
        val path = if (saved == null) "client/access" else "client/refresh"
        val connection = URI(ThemeStoreRepository.normalize(source.url)).resolve(path).toURL()
            .openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", "Ziyu/theme-compatible")
            connection.setRequestProperty("X-Requested-With", "SukiSUThemeAdmin")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Content-Type", "application/json")
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status == 401 || status == 403) {
                // Do not create a replacement device identity after revocation.
                if (saved != null) save(source, saved.put("revoked", true))
                else save(source, JSONObject().put("revoked", true))
                throw authorizationError()
            }
            if (status != 200) throw IOException("主题授权暂时不可用（HTTP $status）")
            val raw = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65536) { "主题授权响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
            // Never include response bodies or credentials in error messages.
            val value = try { JSONObject(raw) } catch (_: Exception) { error("主题授权响应无效") }
            validate(value)
            require(value.getLong("expiresAt") > now && value.getLong("refreshExpiresAt") > now) { "主题授权已过期，请检查手机时间" }
            // Persist rotation before observing cancellation: the old refresh secret may be spent.
            val persisted = JSONObject().put("accessToken", value.getString("accessToken"))
                .put("refreshToken", value.getString("refreshToken"))
                .put("expiresAt", value.getLong("expiresAt"))
                .put("refreshExpiresAt", value.getLong("refreshExpiresAt"))
                .put("refreshAfter", value.optLong("refreshAfter", value.getLong("expiresAt") - 120))
            save(source, persisted)
            currentCoroutineContext().ensureActive()
            return persisted.getString("accessToken")
        } finally { connection.disconnect() }
    }

    private fun validate(value: JSONObject) {
        require(value.optString("accessToken").matches(Regex("sa_[a-f0-9]{64}")) &&
            value.optString("refreshToken").matches(Regex("sr_[a-f0-9]{64}")) &&
            value.optLong("expiresAt") > 0 && value.optLong("refreshExpiresAt") > 0) { "主题授权数据无效，请重新连接" }
    }

    private fun file(source: ThemeSource): AtomicFile {
        val key = MessageDigest.getInstance("SHA-256").digest(
            ThemeStoreRepository.normalize(source.url).toByteArray(Charsets.UTF_8),
        ).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$key.json"))
    }

    private fun read(source: ThemeSource): JSONObject? {
        val storage = file(source)
        return try { JSONObject(storage.readFully().toString(Charsets.UTF_8)) }
        catch (_: java.io.FileNotFoundException) { null }
        catch (_: Exception) { throw IllegalStateException("无法读取主题授权，请在主题来源中重新连接") }
    }

    private fun save(source: ThemeSource, value: JSONObject) {
        check(directory.isDirectory || directory.mkdirs()) { "无法保存主题授权" }
        val storage = file(source)
        val output = storage.startWrite()
        try { output.write(value.toString().toByteArray(Charsets.UTF_8)); storage.finishWrite(output) }
        catch (error: Throwable) { storage.failWrite(output); throw error }
    }

    private fun authorizationError() = IllegalStateException("主题访问授权已失效，请在主题来源中重新连接")

    companion object { private val rotation = Mutex() }
}
