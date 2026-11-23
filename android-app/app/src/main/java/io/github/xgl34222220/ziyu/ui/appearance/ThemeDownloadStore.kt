/* Offline theme download library adapted from shirkneko/SukiSU 97d2442d.
 * GPL-3.0-or-later; Ziyu adaptation GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ThemeDownloadRecord(
    val key: String,
    val themeId: String,
    val sourceUrl: String = "",
    val name: String = "",
    val author: String = "",
    val version: Int = 1,
    val formatVersion: Int = 1,
    val size: Long = 0L,
    val sha256: String = "",
    val license: String = "",
    val fileName: String = "",
    val previewFile: String = "",
    val previewPath: String = "",
    val adaptive: Boolean = false,
    val dynamic: Boolean = false,
    val r18: Boolean = false,
    val downloadedAt: Long = 0L,
)

internal class ThemeDownloadStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "theme_downloads")
    private val indexFile = File(dir, "index.json")
    private val mutex = Mutex()

    fun fileFor(record: ThemeDownloadRecord): File = File(dir, record.fileName)

    fun previewFor(record: ThemeDownloadRecord): File? =
        record.previewFile.takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }

    suspend fun list(): List<ThemeDownloadRecord> = mutex.withLock {
        withContext(Dispatchers.IO) { readLocked() }
    }

    suspend fun save(theme: StoreTheme, packageFile: File, previewFileSource: File? = null): ThemeDownloadRecord? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!packageFile.isFile || packageFile.length() <= 0L) return@withContext null
            dir.mkdirs()
            val safeKey = theme.key.replace(UNSAFE, "_").trim('_').ifBlank { theme.id }
            val fileName = "$safeKey-v${theme.version}.sukisu"
            val target = File(dir, fileName)
            if (!(target.isFile && target.length() == packageFile.length())) {
                runCatching { packageFile.copyTo(target, overwrite = true) }.getOrNull() ?: return@withContext null
            }
            val current = readLocked()
            val existing = current.firstOrNull { it.key == theme.key }
            if (existing != null && existing.fileName.isNotBlank() && existing.fileName != fileName) {
                runCatching { File(dir, existing.fileName).delete() }
            }
            val savedPreview = copyPreviewLocked(safeKey, theme.version, previewFileSource, existing)
            val record = ThemeDownloadRecord(
                key = theme.key,
                themeId = theme.id,
                sourceUrl = theme.source.url,
                name = theme.name,
                author = theme.author,
                version = theme.version,
                formatVersion = theme.format,
                size = theme.size.takeIf { it > 0L } ?: target.length(),
                sha256 = theme.sha256,
                license = theme.license,
                fileName = fileName,
                previewFile = savedPreview,
                previewPath = theme.preview.ifBlank { theme.screenshots.firstOrNull().orEmpty() },
                adaptive = theme.isAdaptiveTheme(),
                dynamic = theme.isDynamicTheme(),
                r18 = theme.isR18Theme(),
                downloadedAt = System.currentTimeMillis(),
            )
            val updated = (listOf(record) + current.filterNot { it.key == record.key }).sortedByDescending { it.downloadedAt }
            writeIndexLocked(updated)
            record
        }
    }

    suspend fun attachPreview(key: String, file: File): ThemeDownloadRecord? = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile || file.length() <= 0L) return@withContext null
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
            if (ThemeMediaCrypto.isSealed(bytes)) return@withContext null
            val ext = previewExtension(bytes) ?: return@withContext null
            val current = readLocked()
            val record = current.firstOrNull { it.key == key } ?: return@withContext null
            val safeKey = key.replace(UNSAFE, "_").trim('_').ifBlank { record.themeId }
            val name = "preview-$safeKey-v${record.version}.$ext"
            val target = File(dir, name)
            runCatching { target.writeBytes(bytes) }.getOrNull() ?: return@withContext null
            if (record.previewFile.isNotBlank() && record.previewFile != name) {
                runCatching { File(dir, record.previewFile).delete() }
            }
            val updated = record.copy(previewFile = name)
            writeIndexLocked(current.map { if (it.key == key) updated else it })
            updated
        }
    }

    suspend fun delete(keys: Set<String>): List<ThemeDownloadRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (keys.isEmpty()) return@withContext readLocked()
            val current = readLocked()
            current.filter { it.key in keys }.forEach { r ->
                runCatching { fileFor(r).delete() }
                r.previewFile.takeIf { it.isNotBlank() }?.let { runCatching { File(dir, it).delete() } }
            }
            val remaining = current.filterNot { it.key in keys }
            writeIndexLocked(remaining)
            remaining
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.listFiles()?.forEach { if (it.isFile) runCatching { it.delete() } }
            dir.mkdirs()
            writeIndexLocked(emptyList())
        }
    }

    private fun copyPreviewLocked(safeKey: String, version: Int, source: File?, existing: ThemeDownloadRecord?): String {
        val bytes = source?.takeIf { it.isFile && it.length() in 1..(8L * 1024 * 1024) }
            ?.let { runCatching { it.readBytes() }.getOrNull() }
        if (bytes != null && !ThemeMediaCrypto.isSealed(bytes)) {
            val ext = previewExtension(bytes)
            if (ext != null) {
                val name = "preview-$safeKey-v$version.$ext"
                val target = File(dir, name)
                if (runCatching { target.writeBytes(bytes) }.isSuccess) {
                    if (existing != null && existing.previewFile.isNotBlank() && existing.previewFile != name) {
                        runCatching { File(dir, existing.previewFile).delete() }
                    }
                    return name
                }
            }
        }
        val kept = existing?.previewFile.orEmpty()
        return if (kept.isNotBlank() && File(dir, kept).isFile) kept else ""
    }

    private fun readLocked(): List<ThemeDownloadRecord> {
        if (!indexFile.isFile) return emptyList()
        val stored = runCatching {
            val arr = JSONObject(indexFile.readText()).optJSONArray("items") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        ThemeDownloadRecord(
                            key = o.optString("key"),
                            themeId = o.optString("themeId"),
                            sourceUrl = o.optString("sourceUrl"),
                            name = o.optString("name"),
                            author = o.optString("author"),
                            version = o.optInt("version", 1),
                            formatVersion = o.optInt("formatVersion", 1),
                            size = o.optLong("size"),
                            sha256 = o.optString("sha256"),
                            license = o.optString("license"),
                            fileName = o.optString("fileName"),
                            previewFile = o.optString("previewFile"),
                            previewPath = o.optString("previewPath"),
                            adaptive = o.optBoolean("adaptive"),
                            dynamic = o.optBoolean("dynamic"),
                            r18 = o.optBoolean("r18"),
                            downloadedAt = o.optLong("downloadedAt"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        val present = stored.filter { it.fileName.isNotBlank() && fileFor(it).isFile }
            .map { r ->
                if (r.previewFile.isBlank()) r else {
                    val f = File(dir, r.previewFile)
                    val ok = f.isFile && runCatching {
                        f.inputStream().use { input ->
                            val hdr = ByteArray(ThemeMediaCrypto.HEADER_SIZE)
                            val n = input.read(hdr)
                            n >= 4 && !ThemeMediaCrypto.isSealed(hdr.copyOf(n)) && previewExtension(hdr.copyOf(n)) != null
                        }
                    }.getOrDefault(false)
                    if (ok) r else { runCatching { f.delete() }; r.copy(previewFile = "") }
                }
            }
            .sortedByDescending { it.downloadedAt }
        if (present != stored) writeIndexLocked(present)
        return present
    }

    private fun writeIndexLocked(items: List<ThemeDownloadRecord>) {
        dir.mkdirs()
        val arr = JSONArray()
        items.forEach { r ->
            arr.put(
                JSONObject()
                    .put("key", r.key).put("themeId", r.themeId).put("sourceUrl", r.sourceUrl)
                    .put("name", r.name).put("author", r.author).put("version", r.version)
                    .put("formatVersion", r.formatVersion).put("size", r.size).put("sha256", r.sha256)
                    .put("license", r.license).put("fileName", r.fileName).put("previewFile", r.previewFile)
                    .put("previewPath", r.previewPath).put("adaptive", r.adaptive).put("dynamic", r.dynamic)
                    .put("r18", r.r18).put("downloadedAt", r.downloadedAt),
            )
        }
        val atomic = AtomicFile(indexFile)
        val out = atomic.startWrite()
        try {
            out.write(JSONObject().put("version", 1).put("items", arr).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out)
        }
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        fun previewExtension(bytes: ByteArray): String? = when {
            bytes.size >= 12 && bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x46.toByte() && bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
                bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte() -> "webp"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() &&
                bytes[3] == 0x47.toByte() -> "png"
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "jpg"
            bytes.size >= 6 && bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() && bytes[2] == 0x46.toByte() &&
                bytes[3] == 0x38.toByte() -> "gif"
            bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4D.toByte() -> "bmp"
            else -> null
        }
    }
}
