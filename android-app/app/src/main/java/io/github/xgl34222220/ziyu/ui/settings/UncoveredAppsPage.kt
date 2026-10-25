package io.github.xgl34222220.ziyu.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.design.ZiyuButton as Button
import io.github.xgl34222220.ziyu.ui.design.ZiyuCircularProgressIndicator as CircularProgressIndicator
import io.github.xgl34222220.ziyu.ui.design.ZiyuIcon as Icon
import io.github.xgl34222220.ziyu.ui.design.ZiyuModalBottomSheet as ModalBottomSheet
import io.github.xgl34222220.ziyu.ui.design.ZiyuOutlinedButton as OutlinedButton
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
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private const val APP_COVERAGE_BRIDGE = "/data/adb/modules/ziyu/common/app_bridge.sh"
private val APP_PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")

private data class RunningAppChoice(val packageName: String, val processCount: Int)

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
    val layout = LocalZiyuThemeTokens.current.layout
    val tokens = LocalZiyuPaletteTokens.current
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf(emptyList<RunningAppChoice>()) }
    var selectedPackage by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<AppCoverageResult?>(null) }
    var scanResult by remember { mutableStateOf<CoverageScanResult?>(null) }
    var error by remember { mutableStateOf("") }
    var loadingApps by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    suspend fun request(action: String, packageName: String = ""): JSONObject {
        val args = if (action == "locate") " ${RootShell.quote(packageName)}" else ""
        val shell = "sh ${RootShell.quote(APP_COVERAGE_BRIDGE)} app_coverage $action$args"
        val shellResult = RootShell.exec(shell, if (action == "scan") 25_000L else 10_000L)
        val response = runCatching { JSONObject(shellResult.stdout.trim()) }.getOrNull()
        if (shellResult.code != 0 || response?.optString("status") != "ok") {
            val message = response?.optString("message").orEmpty()
                .ifBlank { shellResult.stderr.ifBlank { "Root 诊断命令失败" } }
            throw IllegalStateException(message.take(180))
        }
        return response.optJSONObject("data") ?: throw IllegalStateException("诊断结果格式无效")
    }

    fun refreshApps() {
        if (loadingApps || locating || scanning) return
        scope.launch {
            loadingApps = true
            error = ""
            try {
                val data = request("running")
                apps = data.arrayObjects("packages").mapNotNull { row ->
                    val packageName = row.optString("package")
                    if (!APP_PACKAGE_PATTERN.matches(packageName)) null
                    else RunningAppChoice(packageName, row.optInt("processCount").coerceAtLeast(1))
                }
                if (selectedPackage !in apps.map { it.packageName }) selectedPackage = ""
                if (apps.isEmpty()) error = "当前没有发现可选择的运行中应用。先打开目标应用，再刷新列表。"
                if (data.optBoolean("truncated")) error = "运行进程较多，列表已限制为前 96 个应用；可重新打开目标应用后刷新。"
            } catch (failure: Throwable) {
                error = failure.message.orEmpty().ifBlank { "读取运行应用失败" }
            } finally {
                loadingApps = false
            }
        }
    }

    fun locateSelected() {
        if (locating || scanning || !APP_PACKAGE_PATTERN.matches(selectedPackage)) return
        scope.launch {
            locating = true
            error = ""
            scanResult = null
            result = null
            try {
                result = parseCoverageResult(request("locate", selectedPackage))
            } catch (failure: Throwable) {
                error = failure.message.orEmpty().ifBlank { "应用字体检测失败" }
            } finally {
                locating = false
            }
        }
    }

    fun scanRunningApps() {
        if (locating || scanning) return
        scope.launch {
            scanning = true
            error = ""
            result = null
            scanResult = null
            try {
                scanResult = parseCoverageScan(request("scan"))
            } catch (failure: Throwable) {
                error = failure.message.orEmpty().ifBlank { "应用扫描失败" }
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(Unit) { refreshApps() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layout.pageHorizontal,
            top = layout.pageTop,
            end = layout.pageHorizontal,
            bottom = maxOf(layout.dockSafeBottom, 24.dp),
        ),
        verticalArrangement = Arrangement.spacedBy(layout.cardGap),
    ) {
        item {
            CoverageCard("只读字体来源检查") {
                Text(
                    "按运行进程的字体映射与该进程自己的挂载命名空间核对字体来源。检查只读取状态，不修改挂载、字体或应用设置。",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Android 共享内存字体、应用自绘文字和未打开的页面可能无法定位；检测到应用内置或下载字体时，字域不能替换这些文件。",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            CoverageCard("选择应用") {
                Text(
                    if (selectedPackage.isBlank()) "先打开要检查的应用，再从运行列表选择。也可以直接扫描当前运行应用。"
                    else "已选择：$selectedPackage",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = apps.isNotEmpty() && !loadingApps && !locating && !scanning,
                ) {
                    Text(if (selectedPackage.isBlank()) "选择运行中的应用" else "更换应用")
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = ::refreshApps,
                        modifier = Modifier.weight(1f),
                        enabled = !loadingApps && !locating && !scanning,
                    ) {
                        if (loadingApps) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("刷新列表")
                    }
                    Button(
                        onClick = ::locateSelected,
                        modifier = Modifier.weight(1f),
                        enabled = APP_PACKAGE_PATTERN.matches(selectedPackage) && !locating && !scanning,
                    ) {
                        if (locating) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Search, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("检测此应用")
                    }
                }
            }
        }
        item {
            CoverageCard("一键扫描") {
                Text(
                    "扫描最多 24 个运行中的应用，最多运行 16 秒。超出范围时会标记部分结果。",
                    color = tokens.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(9.dp))
                Button(
                    onClick = ::scanRunningApps,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !locating && !scanning,
                ) {
                    if (scanning) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Rounded.Search, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (scanning) "正在扫描…" else "扫描未覆盖应用")
                }
            }
        }
        if (error.isNotBlank()) item {
            CoverageCard("状态") {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        result?.let { report ->
            item { CoverageResultCard(report) }
        }
        scanResult?.let { report ->
            item {
                CoverageCard("扫描结果") {
                    if (report.verdict == "no-payload") {
                        Text("没有找到已应用的字域字体负载。先应用字体并完成所需重启，再扫描。", color = tokens.textSecondary)
                    } else {
                        Text("已检查 ${report.scanned} 个应用 · ${report.covered} 个映射到字域字体 · ${report.uncertain} 个无法判断", color = tokens.textSecondary)
                        if (report.truncated) {
                            Spacer(Modifier.height(5.dp))
                            Text("已到达进程或时间上限，以下是部分结果。", color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp)
                        }
                        if (report.items.isEmpty() && report.scanned == 0) {
                            Spacer(Modifier.height(6.dp))
                            Text("没有发现运行中的应用。先打开需要检查的应用，再扫描。", color = tokens.textSecondary)
                        }
                    }
                }
            }
            items(report.items, key = { "${it.packageName}:${it.verdict}" }) { item ->
                CoverageResultCard(item)
            }
        }
    }

    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        ) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("选择运行中的应用", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("列表只显示当前运行中的包名；要检查的应用需要保持打开。", color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
                if (apps.isEmpty()) {
                    Text("没有可选应用。关闭此页后打开目标应用，再刷新列表。", color = tokens.textSecondary)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(apps, key = { it.packageName }) { app ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = ZiyuShapeTokens.Medium,
                                color = if (app.packageName == selectedPackage) MaterialTheme.colorScheme.primaryContainer else tokens.cardBackground,
                                border = io.github.xgl34222220.ziyu.ui.theme.ZiyuCardDefaults.border(),
                                onClick = {
                                    selectedPackage = app.packageName
                                    showPicker = false
                                },
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(app.packageName, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text("${app.processCount} 个运行进程", color = tokens.textSecondary, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { showPicker = false; refreshApps() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新应用列表")
                }
            }
        }
    }
}

@Composable
private fun CoverageResultCard(result: AppCoverageResult) {
    val tokens = LocalZiyuPaletteTokens.current
    CoverageCard(result.packageName) {
        Text("检查到 ${result.audited}/${result.processCount} 个进程", color = tokens.textSecondary, fontSize = 12.sp)
        if (result.memfdCount > 0) {
            Text("共享内存字体映射段：${result.memfdCount}", color = tokens.textSecondary, fontSize = 12.sp)
        }
        result.findings.forEach { finding ->
            Spacer(Modifier.height(7.dp))
            Text(finding.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(finding.action, color = tokens.textSecondary, style = MaterialTheme.typography.bodySmall)
            finding.files.takeIf { it.isNotEmpty() }?.let { files ->
                Text("相关字体：${files.joinToString("、")}", color = tokens.textSecondary, fontSize = 11.sp)
            }
        }
        result.files.takeIf { it.isNotEmpty() }?.let { files ->
            Spacer(Modifier.height(7.dp))
            files.forEach { file ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(file.name, Modifier.weight(1f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Text(fontKindLabel(file.kind), color = tokens.textSecondary, fontSize = 11.sp)
                }
            }
        }
        if (result.truncated) {
            Spacer(Modifier.height(5.dp))
            Text("映射列表达到读取上限，结果可能不完整。", color = MaterialTheme.colorScheme.tertiary, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "这是进程映射来源的诊断，不保证当前屏幕文字都由所列字体绘制。",
            color = tokens.textSecondary,
            fontSize = 11.sp,
        )
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
    "payload" -> "字域负载"
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
