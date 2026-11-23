/* Built-in launcher icon switcher, icon pack storage and pinned desktop shortcut helper
 * adapted from shirkneko/SukiSU 97d2442d (GPL-3.0-or-later); Ziyu adaptation GPL-3.0-only.
 * See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Immutable
import io.github.xgl34222220.ziyu.MainActivity
import io.github.xgl34222220.ziyu.R
import io.github.xgl34222220.ziyu.ZiyuApplication
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class BuiltinAppIcon(
    val id: String,
    val label: String,
    @DrawableRes val previewRes: Int,
    val aliasSuffix: String,
)

object BuiltinAppIcons {
    const val DEFAULT_ID = "default"
    const val BLUE_ID = "ziyu_blue"
    const val DARK_ID = "ziyu_dark"
    const val LINE_ID = "ziyu_line"

    private const val TAG = "ZiyuAppIcons"
    private const val PREFS = "ziyu_app_icon"
    private const val KEY_ID = "icon_id"
    private const val KEY_DYNAMIC = "dynamic_color"
    private val mainActivityName = MainActivity::class.java.name

    val all: List<BuiltinAppIcon> = listOf(
        BuiltinAppIcon(DEFAULT_ID, "默认图标", R.mipmap.ic_ziyu, "Default"),
        BuiltinAppIcon(BLUE_ID, "字域蓝标", R.mipmap.ic_ziyu_blue_static, "Blue"),
        BuiltinAppIcon(DARK_ID, "曜黑字域", R.mipmap.ic_ziyu_dark_static, "Dark"),
        BuiltinAppIcon(LINE_ID, "极简线稿", R.mipmap.ic_ziyu_line_static, "Line"),
    )

    fun find(id: String?): BuiltinAppIcon = all.firstOrNull { it.id == id } ?: all.first()

    fun isKnown(id: String?): Boolean = all.any { it.id == id }

    fun readSaved(context: Context): Pair<String, Boolean> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return find(prefs.getString(KEY_ID, DEFAULT_ID)).id to prefs.getBoolean(KEY_DYNAMIC, false)
    }

    fun persist(context: Context, iconId: String, dynamicColor: Boolean) {
        val normalized = find(iconId).id
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ID, normalized)
            .putBoolean(KEY_DYNAMIC, dynamicColor)
            .apply()
    }

    fun apply(context: Context, icon: BuiltinAppIcon, dynamicColor: Boolean) {
        persist(context, icon.id, dynamicColor)
        val pm = context.packageManager
        val main = ComponentName(context, mainActivityName)
        val selected = aliasClassName(icon, dynamicColor)
        setEnabled(pm, main, true)
        setEnabled(pm, ComponentName(context, selected), true)
        allAliasClassNames().filter { it != selected }.forEach {
            setEnabled(pm, ComponentName(context, it), false)
        }
    }

    fun applyIfNeeded(context: Context, icon: BuiltinAppIcon, dynamicColor: Boolean) {
        if (!isApplied(context, icon, dynamicColor)) apply(context, icon, dynamicColor)
    }

    fun restoreSavedIfNeeded(context: Context) {
        val (id, dynamicColor) = readSaved(context)
        applyIfNeeded(context, find(id), dynamicColor)
    }

    fun isApplied(context: Context, icon: BuiltinAppIcon, dynamicColor: Boolean): Boolean {
        val pm = context.packageManager
        if (!isComponentEnabled(pm, ComponentName(context, mainActivityName))) return false
        val selected = aliasClassName(icon, dynamicColor)
        return isComponentEnabled(pm, ComponentName(context, selected)) &&
            allAliasClassNames().filter { it != selected }.none { isComponentEnabled(pm, ComponentName(context, it)) }
    }

    private fun aliasClassName(icon: BuiltinAppIcon, dynamicColor: Boolean): String =
        mainActivityName + "Alias" + icon.aliasSuffix + if (dynamicColor) "Dynamic" else "Static"

    private fun allAliasClassNames(): List<String> = all.flatMap { icon ->
        listOf(aliasClassName(icon, false), aliasClassName(icon, true))
    }

    private fun isComponentEnabled(pm: PackageManager, component: ComponentName): Boolean {
        val state = runCatching { pm.getComponentEnabledSetting(component) }.getOrElse { return false }
        return when (state) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
            else -> runCatching { pm.getActivityInfo(component, 0).enabled }.getOrDefault(false)
        }
    }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        runCatching {
            pm.setComponentEnabledSetting(
                component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }.onFailure { Log.w(TAG, "Unable to update component $component", it) }
    }
}

class AppIconShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val component = packageManager.queryIntentActivities(launcherIntent, 0)
            .firstOrNull()?.activityInfo?.let { ComponentName(packageName, it.name) }
        try {
            if (component != null) startActivity(Intent(launcherIntent).setComponent(component))
            else startActivity(Intent(this, MainActivity::class.java))
        } catch (_: Throwable) {
            runCatching { startActivity(Intent(this, MainActivity::class.java)) }
        } finally {
            finish()
        }
    }
}

class AppIconUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked != true) return
        if ((context.applicationContext as? ZiyuApplication)?.processForeground?.value == true) return
        runCatching { BuiltinAppIcons.restoreSavedIfNeeded(context.applicationContext) }
    }
}

@Immutable
data class AppIconEntry(val id: String, val name: String, val fileName: String)

@Immutable
data class AppIconPack(
    val id: String,
    val name: String,
    val importedAt: Long = 0L,
    val icons: List<AppIconEntry> = emptyList(),
)

object AppIconPackStorage {
    private const val STORAGE_DIR = "app_icons"
    private const val INDEX_FILE = "index.json"
    private const val SINGLE_PACK_ID = "custom-single"
    private const val SINGLE_PACK_NAME = "自选单图"
    private const val ICON_SIZE = 512
    private const val MAX_ICONS_PER_PACK = 64
    private const val MAX_ZIP_BYTES = 64L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 128L * 1024 * 1024
    private const val MAX_SOURCE_DIMENSION = 8192
    private const val MAX_SCANNED_ENTRIES = 512
    private val imageExtensions = setOf("png", "webp", "jpg", "jpeg")
    private val writeLock = Mutex()

    suspend fun load(context: Context): List<AppIconPack> = withContext(Dispatchers.IO) {
        readIndex(context).filter { pack ->
            pack.icons.isNotEmpty() && packDir(context, pack.id).isDirectory
        }
    }

    suspend fun importSingleImage(context: Context, uri: Uri): Result<AppIconEntry> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { readBounded(it, MAX_ENTRY_BYTES) }
                    ?: error("无法读取图片")
                val bitmap = normalizeSquareIcon(bytes) ?: error("无法解码该图片或尺寸超限")
                val dir = packDir(context, SINGLE_PACK_ID).apply { check(isDirectory || mkdirs()) { "无法保存图标" } }
                val iconId = randomId()
                val fileName = "$iconId.png"
                val file = File(dir, fileName)
                try {
                    file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "保存图标失败" } }
                } finally {
                    bitmap.recycle()
                }
                val entry = AppIconEntry(iconId, resolveDisplayName(context, uri, "自定义图标"), fileName)
                val packs = readIndex(context).toMutableList()
                val existingIndex = packs.indexOfFirst { it.id == SINGLE_PACK_ID }
                if (existingIndex >= 0) {
                    val existing = packs[existingIndex]
                    packs[existingIndex] = existing.copy(icons = listOf(entry) + existing.icons.take(MAX_ICONS_PER_PACK - 1))
                } else {
                    packs.add(0, AppIconPack(SINGLE_PACK_ID, SINGLE_PACK_NAME, System.currentTimeMillis(), listOf(entry)))
                }
                writeIndex(context, packs)
                entry
            }
        }
    }

    suspend fun importZipPack(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val tempZip = File.createTempFile("ziyu_icon_pack_", ".zip", context.cacheDir)
                try {
                    context.contentResolver.openInputStream(uri)?.use { src ->
                        tempZip.outputStream().use { dst -> copyBounded(src, dst, MAX_ZIP_BYTES) }
                    } ?: error("无法读取图标包")
                    val packId = randomId()
                    val dir = packDir(context, packId).apply { check(mkdirs()) { "无法创建图标目录" } }
                    val entries = mutableListOf<AppIconEntry>()
                    var remaining = MAX_TOTAL_UNCOMPRESSED
                    try {
                        ZipFile(tempZip).use { zip ->
                            var scanned = 0
                            for (zipEntry in zip.entries()) {
                                if (entries.size >= MAX_ICONS_PER_PACK || scanned >= MAX_SCANNED_ENTRIES || remaining <= 0L) break
                                scanned++
                                if (zipEntry.isDirectory || !isImageEntry(zipEntry.name)) continue
                                val bytes = runCatching {
                                    zip.getInputStream(zipEntry).use { readBounded(it, minOf(MAX_ENTRY_BYTES, remaining)) }
                                }.getOrNull() ?: continue
                                remaining -= bytes.size
                                val bitmap = normalizeSquareIcon(bytes) ?: continue
                                val iconId = randomId()
                                val fileName = "$iconId.png"
                                val file = File(dir, fileName)
                                val saved = runCatching {
                                    file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                                }.getOrDefault(false)
                                bitmap.recycle()
                                if (!saved) { file.delete(); continue }
                                val name = zipEntry.name.substringAfterLast('/').substringBeforeLast('.').trim()
                                    .take(40).ifEmpty { "图标 ${entries.size + 1}" }
                                entries += AppIconEntry(iconId, name, fileName)
                            }
                        }
                        require(entries.isNotEmpty()) { "压缩包内未找到可用图片" }
                    } catch (error: Throwable) {
                        dir.deleteRecursively()
                        throw error
                    }
                    val pack = AppIconPack(packId, resolveDisplayName(context, uri, "自定义图标包"), System.currentTimeMillis(), entries)
                    writeIndex(context, readIndex(context) + pack)
                    entries.size
                } finally {
                    tempZip.delete()
                }
            }
        }
    }

    suspend fun deletePack(context: Context, packId: String): Result<Unit> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val packs = readIndex(context)
                if (packs.none { it.id == packId }) return@runCatching
                writeIndex(context, packs.filterNot { it.id == packId })
                packDir(context, packId).deleteRecursively()
            }
        }
    }

    suspend fun deleteIcon(context: Context, packId: String, iconId: String): Result<Unit> = withContext(Dispatchers.IO) {
        writeLock.withLock {
            runCatching {
                val packs = readIndex(context)
                val target = packs.firstOrNull { it.id == packId } ?: return@runCatching
                val entry = target.icons.firstOrNull { it.id == iconId } ?: return@runCatching
                File(packDir(context, packId), entry.fileName).delete()
                val remaining = target.icons.filterNot { it.id == iconId }
                val updated = if (remaining.isEmpty()) {
                    packDir(context, packId).deleteRecursively()
                    packs.filterNot { it.id == packId }
                } else {
                    packs.map { if (it.id == packId) it.copy(icons = remaining) else it }
                }
                writeIndex(context, updated)
            }
        }
    }

    fun iconFile(context: Context, pack: AppIconPack, entry: AppIconEntry): File =
        File(packDir(context, pack.id), entry.fileName)

    suspend fun loadBitmap(context: Context, pack: AppIconPack, entry: AppIconEntry): Bitmap? = withContext(Dispatchers.IO) {
        runCatching { BitmapFactory.decodeFile(iconFile(context, pack, entry).absolutePath) }.getOrNull()
    }

    private fun isImageEntry(name: String): Boolean {
        if (name.startsWith("__MACOSX/") || name.startsWith(".")) return false
        val base = name.substringAfterLast('/')
        return !base.startsWith(".") && base.substringAfterLast('.', "").lowercase(Locale.ROOT) in imageExtensions
    }

    private fun resolveDisplayName(context: Context, uri: Uri, fallback: String): String {
        val raw = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return raw?.substringBeforeLast('.', raw)?.trim()?.take(40)?.takeIf { it.isNotEmpty() } ?: fallback
    }

    private fun normalizeSquareIcon(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > MAX_SOURCE_DIMENSION || bounds.outHeight > MAX_SOURCE_DIMENSION) return null
        var sample = 1
        while (bounds.outWidth / sample > ICON_SIZE * 2 || bounds.outHeight / sample > ICON_SIZE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val side = minOf(decoded.width, decoded.height)
        if (side <= 0) { decoded.recycle(); return null }
        val cropped = if (decoded.width == side && decoded.height == side) decoded
        else Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = if (side == ICON_SIZE) cropped else Bitmap.createScaledBitmap(cropped, ICON_SIZE, ICON_SIZE, true)
        if (cropped !== decoded) decoded.recycle()
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, max: Long) {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; require(total <= max) { "文件过大" }; output.write(buf, 0, n)
        }
    }

    private fun readBounded(input: InputStream, max: Long): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(32 * 1024); var total = 0L
        while (true) {
            val n = input.read(buf); if (n < 0) break
            total += n; require(total <= max) { "图片过大" }; out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun randomId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    private fun storageDir(context: Context): File = File(context.filesDir, STORAGE_DIR)
    private fun packDir(context: Context, id: String): File = File(storageDir(context), id)
    private fun indexFile(context: Context): File = File(storageDir(context), INDEX_FILE)

    private fun readIndex(context: Context): List<AppIconPack> {
        val file = indexFile(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val arr = JSONObject(file.readText()).optJSONArray("packs") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    val iconsArr = p.optJSONArray("icons") ?: JSONArray()
                    val icons = buildList {
                        for (j in 0 until iconsArr.length()) {
                            val e = iconsArr.getJSONObject(j)
                            val id = e.optString("id"); val fn = e.optString("fileName")
                            if (id.isNotBlank() && fn.isNotBlank()) add(AppIconEntry(id, e.optString("name", "图标"), fn))
                        }
                    }
                    add(AppIconPack(p.getString("id"), p.optString("name", "自定义图标包"), p.optLong("importedAt"), icons))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeIndex(context: Context, packs: List<AppIconPack>) {
        val file = indexFile(context)
        file.parentFile?.mkdirs()
        val arr = JSONArray()
        packs.forEach { pack ->
            val icons = JSONArray()
            pack.icons.forEach { e -> icons.put(JSONObject().put("id", e.id).put("name", e.name).put("fileName", e.fileName)) }
            arr.put(JSONObject().put("id", pack.id).put("name", pack.name).put("importedAt", pack.importedAt).put("icons", icons))
        }
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(JSONObject().put("version", 1).put("packs", arr).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(out)
        } catch (error: Throwable) {
            atomic.failWrite(out); throw error
        }
    }
}

sealed interface PinShortcutResult {
    data object Pinned : PinShortcutResult
    data object Updated : PinShortcutResult
    data class PermissionRequired(val message: String) : PinShortcutResult
    data class Failed(val message: String) : PinShortcutResult
}

object AppIconShortcutHelper {
    fun pinCustomIcon(context: Context, key: String, label: String, bitmap: Bitmap): PinShortcutResult {
        val manager = context.getSystemService(ShortcutManager::class.java)
            ?: return PinShortcutResult.Failed("当前系统不支持桌面快捷方式")
        val shortcutId = "ziyu_app_icon_$key"
        val intent = Intent(context, AppIconShortcutActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val info = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(label.ifBlank { context.getString(R.string.app_name) })
            .setLongLabel(label.ifBlank { context.getString(R.string.app_name) })
            .setIntent(intent)
            .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
            .build()

        val alreadyPinned = runCatching { manager.pinnedShortcuts.any { it.id == shortcutId && it.isEnabled } }.getOrDefault(false)
        if (alreadyPinned) {
            runCatching { manager.updateShortcuts(listOf(info)) }
            return PinShortcutResult.Updated
        }
        if (isShortcutPermissionDenied(context)) {
            return PinShortcutResult.PermissionRequired(permissionHint())
        }
        if (!manager.isRequestPinShortcutSupported) {
            return PinShortcutResult.Failed("当前桌面不支持固定自定义快捷方式")
        }
        val requested = runCatching { manager.requestPinShortcut(info, null) }.getOrDefault(false)
        return if (requested) PinShortcutResult.Pinned else PinShortcutResult.PermissionRequired(permissionHint())
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private fun isColorOS(): Boolean {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase(Locale.ROOT)
        return "oppo" in brand || "oneplus" in brand || "realme" in brand
    }

    private fun isMiuiOrHyperOS(): Boolean {
        val brand = "${Build.BRAND} ${Build.MANUFACTURER}".lowercase(Locale.ROOT)
        return "xiaomi" in brand || "redmi" in brand || "poco" in brand
    }

    private fun permissionHint(): String = when {
        isColorOS() -> "请在应用信息 → 权限中开启「创建桌面快捷方式」后重试。"
        isMiuiOrHyperOS() -> "请在应用信息 → 权限管理中允许「桌面快捷方式」后重试。"
        else -> "请检查系统或桌面是否允许创建桌面快捷方式。"
    }

    private fun isShortcutPermissionDenied(context: Context): Boolean = when {
        isColorOS() -> runCatching {
            val uri = Uri.parse("content://settings/secure/launcher_shortcut_permission_settings")
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex("value")
                if (idx >= 0) {
                    val pkg = context.packageName
                    while (c.moveToNext()) {
                        val value = c.getString(idx).orEmpty()
                        if (value.contains("$pkg, 0")) return@runCatching true
                        if (value.contains("$pkg, 1")) return@runCatching false
                    }
                }
                false
            } ?: false
        }.getOrDefault(false)
        isMiuiOrHyperOS() -> runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return@runCatching false
            val method = AppOpsManager::class.java.getDeclaredMethod(
                "checkOpNoThrow", Integer.TYPE, Integer.TYPE, String::class.java,
            )
            val result = method.invoke(appOps, 10017, context.applicationInfo.uid, context.packageName)?.toString()
            result == "1" || result == "5"
        }.getOrDefault(false)
        else -> false
    }
}
