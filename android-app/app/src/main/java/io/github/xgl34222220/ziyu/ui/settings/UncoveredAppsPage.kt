package io.github.xgl34222220.ziyu.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.RootReplyJson
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuCircularProgressIndicator as CircularProgressIndicator
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuModalBottomSheet as ModalBottomSheet
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedTextField as OutlinedTextField
import io.github.xgl34222220.ziyu.ui.design.ZiyuSearchIconSlot
import io.github.xgl34222220.ziyu.ui.design.ZiyuSurface as Surface
import io.github.xgl34222220.ziyu.ui.design.ZiyuText as Text
import io.github.xgl34222220.ziyu.ui.design.ZiyuTextButton as TextButton
import io.github.xgl34222220.ziyu.ui.design.rememberZiyuModalBottomSheetState as rememberModalBottomSheetState
import io.github.xgl34222220.ziyu.ui.theme.LocalUiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuPaletteTokens
import io.github.xgl34222220.ziyu.ui.theme.LocalZiyuThemeTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuCard as Card
import io.github.xgl34222220.ziyu.ui.theme.ZiyuShapeTokens
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

private const val APP_COVERAGE_BRIDGE = "/data/adb/modules/ziyu/common/app_bridge.sh"
private val APP_PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
private data class AppChoice(val packageName: String, val label: String)

// Discover selectable launcher apps independently of Root and running processes.
private suspend fun loadAppChoices(manager: PackageManager): List<AppChoice> = withContext(Dispatchers.IO) {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val rows = if (Build.VERSION.SDK_INT >= 33) manager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") manager.queryIntentActivities(intent, 0)
    val collator = Collator.getInstance()
    rows.mapNotNull { row ->
        val info = row.activityInfo ?: return@mapNotNull null
        val name = info.packageName
        if (!APP_PACKAGE_PATTERN.matches(name)) return@mapNotNull null
        val label = runCatching { manager.getApplicationLabel(info.applicationInfo).toString().trim() }
            .getOrDefault(name).ifBlank { name }.take(120)
        AppChoice(name, label)
    }.distinctBy { it.packageName }.sortedWith { a, b ->
        collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName)
    }
}

private data class CoverageFinding(
    val category: String,
    val title: String,
    val action: String,
    val files: List<String>,
)

private data class CoverageFile(val kind: String, val name: String)

private data class AppCoverageResult(
    val packageName: String,
    val verdict: String,
    val audited: Int,
    val processCount: Int,
    val memfdCount: Int,
    val truncated: Boolean,
    val findings: List<CoverageFinding>,
    val files: List<CoverageFile>,
)

private data class CoverageScanResult(
    val verdict: String,
    val scanned: Int,
    val covered: Int,
    val uncertain: Int,
    val truncated: Boolean,
    val items: List<AppCoverageResult>,
)

@Composable
internal fun UncoveredAppsPage() {
    val context = LocalContext.current
    val layout = LocalZiyuThemeTokens.current.layout
    val tokens = LocalZiyuPaletteTokens.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<AppChoice>()) }
    var selectedPackage by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<AppCoverageResult?>(null) }
    var scanResult by remember { mutableStateOf<CoverageScanResult?>(null) }
    var error by remember { mutableStateOf("") }
    var loadingApps by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showBatch by rememberSaveable { mutableStateOf(false) }
    val busy = locating || scanning
    fun appName(name: String): String = apps.firstOrNull { it.packageName == name }?.label ?: name

    suspend fun request(action: String, packageName: String = ""): JSONObject {
        val args = if (action == "locate") " ${RootShell.quote(packageName)}" else ""
        val reply = RootShell.exec("sh ${RootShell.quote(APP_COVERAGE_BRIDGE)} app_coverage $action$args", 25_000L)
        val response = runCatching { RootReplyJson.firstObject(reply.stdout) }.getOrNull()
        if (reply.code != 0 || response?.optString("status") != "ok") {
            throw IllegalStateException(response?.optString("message").orEmpty().ifBlank {
                reply.stderr.trim().ifBlank { "应用检查未返回结果（退出码 ${reply.code}）" }
            }.take(1200))
        }
        return response.optJSONObject("data") ?: kotlin.error("应用检查结果不完整")
    }

    fun refreshApps() {
        if (loadingApps || busy) return
        scope.launch {
            loadingApps = true
            error = ""
            try {
                apps = loadAppChoices(context.packageManager)
                // Closing an app must not remove the user's selection.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message.orEmpty().ifBlank { "读取应用列表失败" }
            } finally { loadingApps = false }
        }
    }

    fun locateSelected() {
        if (busy || !APP_PACKAGE_PATTERN.matches(selectedPackage)) return
        val target = selectedPackage
        scope.launch {
            locating = true; error = ""; scanResult = null; result = null
            try { result = parseCoverageResult(request("locate", target)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty().ifBlank { "检查失败，请重试" } }
            finally { locating = false }
        }
    }

    fun scanRunningApps() {
        if (busy) return
        scope.launch {
            scanning = true; error = ""; result = null; scanResult = null
            try { scanResult = parseCoverageScan(request("scan")) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message.orEmpty().ifBlank { "批量检查失败" } }
            finally { scanning = false }
        }
    }

    LaunchedEffect(Unit) { refreshApps() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = layout.pageHorizontal, top = layout.pageTop,
            end = layout.pageHorizontal, bottom = maxOf(layout.dockSafeBottom, 24.dp)),
        verticalArrangement = Arrangement.spacedBy(layout.cardGap),
    ) {
        item {
            CoverageCard("字体没有变化？") {
                Text("查看应用使用了什么字体，帮助查找原因。", color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Text(if (selectedPackage.isBlank()) "选择应用" else appName(selectedPackage),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text("打开文字异常的页面，再回到这里检查。", color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage(selectedPackage)
                                ?: kotlin.error("这个应用没有启动入口，请手动打开后检查")
                            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            error = ""
                        } catch (failure: Exception) {
                            error = failure.message.orEmpty().ifBlank { "无法打开应用，请手动打开后检查" }
                        }
                    }, enabled = selectedPackage.isNotBlank() && !busy) { Text("打开应用") }
                    Button(onClick = ::locateSelected, enabled = APP_PACKAGE_PATTERN.matches(selectedPackage) && !busy) {
                        if (locating) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)) }
                        Text(if (locating) "检查中…" else "检查字体")
                    }
                }
            }
        }
        if (error.isNotBlank()) item {
            CoverageCard("检查未完成") { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        result?.let { report -> item { CoverageResultCard(report, appName(report.packageName)) } }
        scanResult?.let { report ->
            item {
                CoverageCard("批量检查结果") {
                    Text(if (report.verdict == "no-payload") "先应用字体并按提示重启，再回来检查。"
                        else "检查 ${report.scanned} 个 · ${report.covered} 个读到字域字体 · ${report.uncertain} 个无法判断",
                        color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                    if (report.truncated) Text("本次只检查了部分应用。", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
                    if (report.scanned == 0 && report.verdict != "no-payload") Text("先打开要检查的应用。", color = tokens.textSecondary)
                }
            }
            items(report.items, key = { it.packageName }) { row -> CoverageResultCard(row, appName(row.packageName)) }
        }
        if (result != null || scanResult != null) item {
            Text("结果仅反映已读到的字体，不能代表应用里的所有文字。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        item {
            TextButton(onClick = { showBatch = !showBatch }, enabled = !busy) { Text(if (showBatch) "收起批量检查" else "批量检查") }
            if (showBatch) CoverageCard("运行中的应用") {
                Text("一次检查多个已打开的应用。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = ::scanRunningApps, enabled = !busy) {
                    if (scanning) { CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp); Spacer(Modifier.width(7.dp)) }
                    Text(if (scanning) "检查中…" else "开始检查")
                }
            }
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/shishui611-art/Ziyu"))) }
                    .onFailure { error = "无法打开浏览器，请前往 GitHub 字域仓库查看说明" }
            }) { Text("使用说明") }
        }
    }
    if (showPicker) AppChoiceSheet(apps, selectedPackage, loadingApps,
        onRefresh = ::refreshApps, onDismiss = { showPicker = false },
        onChoose = { selectedPackage = it; result = null; scanResult = null; error = ""; showPicker = false })
}

@Composable
private fun AppChoiceSheet(apps: List<AppChoice>, selected: String, loading: Boolean,
    onRefresh: () -> Unit, onDismiss: () -> Unit, onChoose: (String) -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    var query by rememberSaveable { mutableStateOf("") }
    var manual by rememberSaveable { mutableStateOf(false) }
    var packageName by rememberSaveable { mutableStateOf("") }
    val filtered = remember(apps, query) {
        val text = query.trim()
        apps.filter { text.isBlank() || it.label.contains(text, true) || it.packageName.contains(text, true) }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)) {
        // The entire sheet scrolls, including manual input/actions at large font scales.
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("选择应用", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = query, onValueChange = { query = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, placeholder = { Text("搜索应用") },
                    leadingIcon = { ZiyuSearchIconSlot { Icon(Icons.Rounded.Search, null) } })
            }
            if (loading) item { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) }
            if (!loading && filtered.isEmpty()) item {
                Text(if (query.isBlank()) "没有读到应用，可刷新或按包名选择。" else "没有找到应用", color = tokens.textSecondary)
            }
            items(filtered, key = { it.packageName }) { app ->
                Surface(modifier = Modifier.fillMaxWidth(), shape = ZiyuShapeTokens.Medium,
                    color = if (app.packageName == selected) MaterialTheme.colorScheme.primaryContainer else tokens.cardBackground,
                    border = io.github.xgl34222220.ziyu.ui.theme.ZiyuCardDefaults.border(),
                    onClick = { onChoose(app.packageName) }) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(app.label, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, color = tokens.textSecondary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRefresh, enabled = !loading) { Text("刷新列表") }
                    TextButton(onClick = { manual = !manual }) { Text(if (manual) "收起包名" else "按包名选择") }
                }
                if (manual) {
                    OutlinedTextField(value = packageName, onValueChange = { packageName = it.trim().take(256) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("应用包名") },
                        placeholder = { Text("如 com.tencent.mm") })
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onChoose(packageName) }, enabled = APP_PACKAGE_PATTERN.matches(packageName)) { Text("选择") }
                }
            }
        }
    }
}

@Composable
private fun CoverageResultCard(result: AppCoverageResult, label: String) {
    val tokens = LocalZiyuPaletteTokens.current
    var expanded by rememberSaveable(result.packageName, result.verdict) { mutableStateOf(false) }
    CoverageCard(label) {
        result.findings.forEach { finding ->
            Text(finding.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(finding.action, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
        }
        if (result.truncated) Text("本次检查不完整，可稍后重试。", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起结果" else "查看结果详情") }
        if (expanded) {
            Text(result.packageName, color = tokens.textSecondary, fontSize = 11.sp)
            Text("已检查 ${result.audited}/${result.processCount} 个进程", color = tokens.textSecondary, fontSize = 12.sp)
            if (result.memfdCount > 0) Text("共享内存字体映射段：${result.memfdCount}", color = tokens.textSecondary, fontSize = 12.sp)
            result.files.forEach { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(fontKindLabel(file.kind), color = tokens.textSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CoverageCard(title: String, content: @Composable () -> Unit) {
    val tokens = LocalZiyuPaletteTokens.current
    val layout = LocalZiyuThemeTokens.current.layout
    val style = LocalUiStyle.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ZiyuShapeTokens.Card,
        colors = CardDefaults.cardColors(
            containerColor = if (style == UiStyle.MATERIAL) MaterialTheme.colorScheme.surfaceContainerLow else tokens.cardBackground,
            contentColor = tokens.textPrimary,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(layout.cardPadding), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            content()
        }
    }
}

private fun fontKindLabel(kind: String): String = when (kind) {
    "payload" -> "字域字体"
    "stock" -> "系统字体"
    "theme" -> "主题/字体服务"
    "updatable" -> "系统字体服务"
    "other" -> "应用/其他来源"
    else -> "未知来源"
}

private fun JSONObject.arrayObjects(key: String): List<JSONObject> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}

private fun JSONArray.strings(): List<String> = (0 until length()).mapNotNull { index ->
    optString(index).takeIf { it.isNotBlank() }
}

private fun parseCoverageResult(data: JSONObject): AppCoverageResult = AppCoverageResult(
    packageName = data.optString("package"),
    verdict = data.optString("verdict"),
    audited = data.optInt("audited"),
    processCount = data.optInt("processCount", 0),
    memfdCount = data.optInt("memfdFontSegments"),
    truncated = data.optBoolean("truncated") || data.optBoolean("mapsTruncated"),
    findings = data.arrayObjects("findings").map { finding ->
        CoverageFinding(
            category = finding.optString("category"),
            title = finding.optString("title"),
            action = finding.optString("action"),
            files = finding.optJSONArray("files")?.strings().orEmpty(),
        )
    },
    files = data.arrayObjects("files").map { file ->
        CoverageFile(file.optString("kind"), file.optString("name"))
    },
)

private fun parseCoverageScan(data: JSONObject): CoverageScanResult = CoverageScanResult(
    verdict = data.optString("verdict", "ok"),
    scanned = data.optInt("scanned"),
    covered = data.optInt("covered"),
    uncertain = data.optInt("uncertain"),
    truncated = data.optBoolean("truncated"),
    items = data.arrayObjects("items").map(::parseCoverageResult),
)
