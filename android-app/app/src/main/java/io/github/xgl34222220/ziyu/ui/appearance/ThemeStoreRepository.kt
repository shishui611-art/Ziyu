package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ThemeSource(val url: String, val accessCode: String)
internal data class StoreTheme(
    val id: String, val name: String, val author: String, val description: String,
    val license: String, val tags: List<String>, val path: String, val sha256: String,
    val size: Long, val format: Int, val preview: String, val screenshots: List<String>,
    val downloads: Long, val updated: String, val adaptive: Boolean,
    val heat: Long? = null,
)
internal data class ThemeTransfer(val done: Long, val total: Long, val bytesPerSecond: Long = 0)

/** Catalog/range protocol follows SukiSU-Ultra 075ac81f1dfe58b3f59c665a1b07fda8b975015f.
 * Fresh Ziyu implementation: same-origin HTTPS, bounded streams, four range workers,
 * atomic chunk ledger and SHA-256 verification. No account or upload endpoints.
 */
internal class ThemeStoreRepository(private val context: Context) {
    private val access = ThemeStoreAccessRepository(context)
    private val config = File(context.noBackupFilesDir, "theme-store-source.json")
    private val cache = File(context.cacheDir, "theme-store").apply { mkdirs() }
    private val previewSlots = Semaphore(4)
    private val previewLocks = Array(16) { Mutex() }
    private val rangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
    private val idPattern = Regex("[a-z0-9][a-z0-9-]{0,63}")

    fun source(): ThemeSource = runCatching {
        val o = JSONObject(config.readText())
        ThemeStoreDefaults.migrate(ThemeSource(normalize(o.getString("url")), o.optString("accessCode")))
    }.getOrElse { ThemeSource(ThemeStoreDefaults.BASE_URL, "") }
    fun accepted(): Boolean = runCatching { JSONObject(config.readText()).optInt("consent") == 1 }.getOrDefault(false)
    fun accept() = save(source(), true)
    fun save(source: ThemeSource, consent: Boolean = true) {
        val value = ThemeStoreDefaults.migrate(source.copy(url = normalize(source.url), accessCode = source.accessCode.trim()))
        require(value.accessCode.length <= 512 && value.accessCode.none { it == '\r' || it == '\n' } &&
            !value.accessCode.startsWith("sr_") && !value.accessCode.startsWith("{")) { "请填写访问码，不能填写刷新密钥或凭证文件" }
        atomic(config, JSONObject().put("url", value.url).put("accessCode", value.accessCode).put("consent", if(consent)1 else 0).toString())
    }

    suspend fun catalog(source: ThemeSource): Pair<List<StoreTheme>, Boolean> = withContext(Dispatchers.IO) {
        check(accepted()) { "请先同意连接主题来源" }
        trimCache()
        val catalogFile = File(cache, "catalog-${digest(source.url.toByteArray()).take(24)}.json")
        val raw = try {
            connection(source, "catalog.json").useConnection { conn ->
                require(conn.responseCode == 200) { httpError(conn.responseCode) }
                conn.inputStream.use { readLimited(it, 8L*1024*1024).toString(Charsets.UTF_8) }
            }.also { parseCatalog(it); atomic(catalogFile, it) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            // A rejected/revoked source is not represented as an online success.
            val cached = if(error !is java.io.IOException || error.message?.startsWith("HTTP 401") == true || error.message?.startsWith("HTTP 403") == true) null
                else catalogFile.takeIf { it.isFile && System.currentTimeMillis()-it.lastModified()<7*24*60*60*1000L }
            if(cached!=null) return@withContext parseCatalog(cached.readText()) to true
            throw error
        }
        parseCatalog(raw) to false
    }

    private fun parseCatalog(raw: String): List<StoreTheme> {
        val o=JSONObject(raw)
        require(o.optInt("schema",1)==1) { "不支持此主题来源版本" }
        require(!o.optBoolean("maintenance")) { "主题来源正在维护" }
        val entries=o.optJSONArray("themes") ?: error("主题目录缺失")
        require(entries.length()<=10000) { "主题目录过大" }
        return buildList {
            for(i in 0 until entries.length()) {
                val e=entries.getJSONObject(i)
                val tags=strings(e.optJSONArray("tags"),20,40)
                if(e.optBoolean("r18") || tags.any { it.equals("r18",true) || it.equals("nsfw",true) }) continue
                val id=e.optString("id"); if(!idPattern.matches(id))continue
                add(StoreTheme(id,(nonBlankThemeText(e.opt("name")) ?: "未命名主题").take(120),nonBlankThemeText(e.opt("author")).orEmpty().take(120),
                    nonBlankThemeText(e.opt("description")).orEmpty().take(1000),nonBlankThemeText(e.opt("license")).orEmpty().take(200),tags,
                    e.optString("path"),e.optString("sha256").lowercase(),e.optLong("size"),
                    e.optInt("formatVersion",1),e.optString("previewPath"),strings(e.optJSONArray("screenshotPaths"),12,512),
                    e.optLong("downloads").coerceAtLeast(0),nonBlankThemeText(e.opt("updatedAt")).orEmpty().take(40),e.optBoolean("adaptive"),
                    heat = if(e.has("heat") && !e.isNull("heat")) e.optLong("heat",-1).takeIf { it>=0 } else null))
            }
        }.distinctBy { it.id }
    }

    suspend fun preview(source: ThemeSource, path: String): File? = previewSlots.withPermit {
        withContext(Dispatchers.IO) {
            if(path.isBlank() || !accepted()) return@withContext null
            previewLocks[((source.url+path).hashCode().ushr(1)) % previewLocks.size].withLock {
            val target=File(cache,"preview-${digest((source.url+path).toByteArray()).take(32)}.image")
            if(target.isFile && target.length() in 1..(4L*1024*1024))return@withContext target
            val part=File(cache,target.name+".part")
            try {
                connection(source,path).useConnection { c ->
                    require(c.responseCode==200) { httpError(c.responseCode) }
                    c.inputStream.use { input -> part.outputStream().use { it.write(readLimited(input,4L*1024*1024)) } }
                }
                require(part.renameTo(target)) { "无法保存主题预览" }; target
            } finally { part.delete() }
            }
        }
    }

    suspend fun download(source: ThemeSource, theme: StoreTheme, progress: (ThemeTransfer)->Unit): File = withContext(Dispatchers.IO) {
        check(accepted()) { "请先同意连接主题来源" }
        require(theme.format in 1..4) { "暂不支持此主题版本" }
        require(theme.size in 1..ThemePackages.MAX_PACKAGE_BYTES && Regex("[0-9a-f]{64}").matches(theme.sha256)) { "主题大小或校验信息无效" }
        val target=File(cache,"${theme.id}-${theme.sha256.take(20)}.sukisu")
        if(target.isFile && target.length()==theme.size && hashFile(target)==theme.sha256) {
            progress(ThemeTransfer(theme.size,theme.size)); return@withContext target
        }
        target.delete()
        val part=File(cache,target.name+".part")
        val meta=File(cache,target.name+".part.meta")
        val start=System.nanoTime(); val transferred=AtomicLong(0)
        var lastProgress=0L
        fun report(delta: Long, base: Long=0) {
            val moved=transferred.addAndGet(delta)
            synchronized(transferred) {
                val now=System.nanoTime()
                if(now-lastProgress>=100_000_000L) {
                    lastProgress=now
                    progress(ThemeTransfer((base+moved).coerceIn(0,theme.size),theme.size,
                        (moved*1_000_000_000.0/(now-start).coerceAtLeast(1)).toLong().coerceAtLeast(0)))
                }
            }
        }
        val probe=connection(source,theme.path,"bytes=0-0")
        val ranges: Boolean
        val etag: String?
        try {
            val code=probe.responseCode
            ranges=code==206 && probe.getHeaderField("Content-Range")=="bytes 0-0/${theme.size}"
            require(code==200 || ranges) { httpError(code) }
            etag=probe.getHeaderField("ETag")?.takeIf { !it.startsWith("W/") }
        } finally { probe.disconnect() }
        if(ranges && theme.size>=1024*1024) {
            val chunk=2L*1024*1024
            val count=((theme.size+chunk-1)/chunk).toInt()
            val fingerprint=digest((source.url+theme.path+theme.sha256+theme.size).toByteArray())
            val old=runCatching { JSONObject(meta.readText()) }.getOrNull()
            val completed=mutableSetOf<Int>()
            if(part.isFile && part.length()==theme.size && old?.optString("fingerprint")==fingerprint) {
                old.optJSONArray("completed")?.let { a -> for(i in 0 until a.length())a.optInt(i,-1).takeIf { it in 0 until count }?.let(completed::add) }
            } else { part.delete(); meta.delete() }
            RandomAccessFile(part,"rw").use { it.setLength(theme.size) }
            val base=completed.sumOf { minOf(chunk,theme.size-it*chunk) }
            val slots=Semaphore(4)
            coroutineScope {
                (0 until count).filter { it !in completed }.map { index -> async {
                    slots.withPermit {
                        val from=index*chunk; val end=minOf(theme.size,from+chunk)-1
                        var attempt=0
                        while(true) {
                            currentCoroutineContext().ensureActive()
                            var moved=0L
                            try {
                                connection(source,theme.path,"bytes=$from-$end",etag).useConnection { c ->
                                    require(c.responseCode==206) { "主题来源未按范围返回文件，请重试" }
                                    val match=rangePattern.matchEntire(c.getHeaderField("Content-Range").orEmpty())
                                    require(match?.groupValues?.drop(1)?.map { it.toLong() }==listOf(from,end,theme.size)) { "主题下载范围不一致" }
                                    c.inputStream.use { input -> RandomAccessFile(part,"rw").use { output ->
                                        output.seek(from); val buffer=ByteArray(64*1024)
                                        while(moved<end-from+1) {
                                            currentCoroutineContext().ensureActive()
                                            val n=input.read(buffer,0,minOf(buffer.size.toLong(),end-from+1-moved).toInt())
                                            require(n>0) { "主题下载中断" }
                                            output.write(buffer,0,n); moved+=n; report(n.toLong(),base)
                                        }
                                        require(input.read()==-1) { "主题下载长度不一致" }
                                        output.fd.sync()
                                    } }
                                }
                                synchronized(completed) {
                                    completed.add(index)
                                    atomic(meta,JSONObject().put("fingerprint",fingerprint).put("completed",JSONArray(completed.sorted())).toString())
                                }
                                break
                            } catch(c: kotlinx.coroutines.CancellationException) { throw c }
                            catch(error: Exception) { report(-moved,base); if(++attempt>=3)throw error }
                        }
                    }
                } }.awaitAll()
            }
        } else {
            meta.delete()
            connection(source,theme.path).useConnection { c ->
                require(c.responseCode==200) { httpError(c.responseCode) }
                c.inputStream.use { input -> part.outputStream().use { output ->
                    val buffer=ByteArray(64*1024); var count=0L
                    while(true) {
                        currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0)break
                        count+=n; require(count<=theme.size) { "主题下载长度不一致" }
                        output.write(buffer,0,n); report(n.toLong())
                    }
                    require(count==theme.size) { "主题下载不完整" }
                } }
            }
        }
        currentCoroutineContext().ensureActive()
        if(part.length()!=theme.size || hashFile(part)!=theme.sha256) {
            part.delete(); meta.delete(); error("主题文件校验失败，请重新下载")
        }
        require(part.renameTo(target)) { "无法保存下载的主题" }; meta.delete()
        progress(ThemeTransfer(theme.size,theme.size)); target
    }

    suspend fun reconnect(source: ThemeSource) = withContext(Dispatchers.IO) {
        check(accepted()) { "请先同意连接主题来源" }
        access.reconnect(source)
    }

    private suspend fun connection(source: ThemeSource, path: String, range: String?=null, etag: String?=null): HttpURLConnection {
        check(accepted()) { "请先同意连接主题来源" }
        var token = access.resolve(source)
        var connection = openConnection(source, token, path, range, etag)
        if (connection.responseCode == 401 && access.automatic(source)) {
            connection.disconnect()
            token = access.refresh(source, token)
            connection = openConnection(source, token, path, range, etag)
        }
        try {
            if (connection.responseCode in setOf(401, 403) && access.automatic(source)) {
                access.reject(source, token)
                error("主题访问授权已失效，请在主题来源中重新连接")
            }
            return connection
        } catch (error: Throwable) { connection.disconnect(); throw error }
    }

    private fun openConnection(source: ThemeSource, token: String, path: String, range: String?, etag: String?): HttpURLConnection {
        val base=URI(normalize(source.url))
        val relative=URI(path)
        require(!relative.isAbsolute && relative.rawAuthority==null && relative.rawQuery==null && relative.rawFragment==null &&
            !path.startsWith('/') && '\\' !in path && relative.path.split('/').none { it==".." || it=="." }) { "主题文件地址无效" }
        var url=base.resolve(relative)
        repeat(4) { redirects ->
            require(url.scheme=="https" && url.host==base.host && url.port==base.port && url.path.startsWith(base.path)) { "主题来源的跳转地址无效" }
            val c=url.toURL().openConnection() as HttpURLConnection
            c.instanceFollowRedirects=false; c.connectTimeout=15000; c.readTimeout=20000
            c.setRequestProperty("User-Agent","Ziyu/theme-compatible")
            c.setRequestProperty("Accept-Encoding","identity")
            if(token.isNotEmpty())c.setRequestProperty("X-Sukisu-Theme-Token",token)
            if(range!=null)c.setRequestProperty("Range",range)
            if(etag!=null)c.setRequestProperty("If-Range",etag)
            try {
                if(c.responseCode !in setOf(301,302,303,307,308))return c
                require(redirects<3) { "主题来源跳转过多" }
                url=url.resolve(c.getHeaderField("Location") ?: error("主题来源跳转无效"))
            } catch(e:Exception) { c.disconnect(); throw e }
            c.disconnect()
        }
        error("主题来源跳转无效")
    }

    private suspend fun readLimited(input: InputStream, max: Long): ByteArray {
        val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(65536); var count=0L
        while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0)break
            count+=n; require(count<=max) { "主题来源返回的文件过大" }; output.write(buffer,0,n) }
        return output.toByteArray()
    }
    private suspend fun hashFile(file: File): String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { val b=ByteArray(128*1024); while(true) { currentCoroutineContext().ensureActive(); val n=it.read(b); if(n<0)break; md.update(b,0,n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    private fun trimCache() {
        val files=cache.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var size=files.sumOf { it.length() }
        files.forEach { if(size>256L*1024*1024 && System.currentTimeMillis()-it.lastModified()>60*60*1000L) {
            val bytes=it.length(); if(it.delete())size-=bytes
        } }
    }
    private fun strings(a: JSONArray?, max: Int, length: Int): List<String> = if(a==null)emptyList() else
        (0 until minOf(a.length(),max)).map { a.optString(it).take(length) }
    private inline fun <T> HttpURLConnection.useConnection(block:(HttpURLConnection)->T):T = try { block(this) } finally { disconnect() }
    companion object {
        fun normalize(raw: String): String {
            val uri=URI(raw.trim())
            require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.rawQuery==null && uri.rawFragment==null && uri.normalize()==uri) { "请输入 HTTPS 主题来源地址" }
            return uri.toASCIIString().trimEnd('/')+"/"
        }
        private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun httpError(code: Int)=when(code) { 401,403 -> "HTTP $code：主题来源拒绝访问，请检查访问码"; else -> "HTTP $code：主题来源暂时不可用" }
        private fun atomic(file: File, text: String) {
            file.parentFile?.mkdirs(); val a=AtomicFile(file); val output=a.startWrite()
            try { output.write(text.toByteArray(Charsets.UTF_8)); a.finishWrite(output) }
            catch(e:Throwable) { a.failWrite(output); throw e }
        }
    }
}
