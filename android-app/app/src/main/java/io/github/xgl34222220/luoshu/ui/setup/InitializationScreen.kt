package io.github.xgl34222220.luoshu.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.luoshu.InitializationUiState
import io.github.xgl34222220.luoshu.ModuleProbeState
import io.github.xgl34222220.luoshu.RootProbeState
import io.github.xgl34222220.luoshu.ui.theme.LocalMiuixTokens

@Composable
internal fun InitializationScreen(
    state: InitializationUiState,
    onRecheck: () -> Unit,
    onStart: () -> Unit,
) {
    val tokens = LocalMiuixTokens.current
    val environment = state.environment
    val rootReady = environment.root == RootProbeState.AUTHORIZED
    val moduleReady = environment.module == ModuleProbeState.READY
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .padding(top = 28.dp, bottom = bottomInset + 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "欢迎使用字域",
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 42.sp,
                lineHeight = 50.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "完成几个步骤，即可开始管理你的系统字体。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SetupStepCard(
            number = "01",
            title = "Root 环境",
            icon = Icons.Rounded.Security,
            ready = rootReady,
            checking = environment.root == RootProbeState.CHECKING,
            status = rootStatusLabel(state),
            detail = rootStatusDetail(state),
        )
        SetupStepCard(
            number = "02",
            title = "字域模块",
            icon = Icons.Rounded.Extension,
            ready = moduleReady,
            checking = environment.module == ModuleProbeState.CHECKING,
            status = moduleStatusLabel(state),
            detail = moduleStatusDetail(state),
        )

        if (environment.canStart) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = tokens.successContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = "环境检查通过",
                        tint = tokens.success,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "字域已准备就绪",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "Root 已授权，字域模块已安装并启用。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        Button(
            onClick = if (environment.canStart) onStart else onRecheck,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = environment.canStart || environment.root != RootProbeState.CHECKING,
            shape = CircleShape,
        ) {
            if (!environment.canStart && environment.root == RootProbeState.CHECKING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.size(10.dp))
                Text("正在检查环境…")
            } else {
                Text(if (environment.canStart) "开始使用" else "重新检查环境")
                if (!environment.canStart) {
                    Spacer(Modifier.size(8.dp))
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    number: String,
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ready: Boolean,
    checking: Boolean,
    status: String,
    detail: String,
) {
    val tokens = LocalMiuixTokens.current
    val statusContainer = when {
        ready -> tokens.successContainer
        checking -> MaterialTheme.colorScheme.primaryContainer
        status.startsWith("未安装") || status.contains("停用") || status.contains("失败") || status.contains("不可用") ->
            MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val statusText = when {
        ready -> tokens.success
        checking -> MaterialTheme.colorScheme.onPrimaryContainer
        status.startsWith("未安装") || status.contains("停用") || status.contains("失败") || status.contains("不可用") ->
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = tokens.cardBackground,
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(22.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(number, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge)
                }
                if (checking) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Surface(shape = CircleShape, color = statusContainer) {
                Text(
                    text = status,
                    color = statusText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = detail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

private fun rootStatusLabel(state: InitializationUiState): String = when (state.environment.root) {
    RootProbeState.CHECKING -> "正在检查 Root 权限"
    RootProbeState.SU_MISSING -> "未检测到 Root"
    RootProbeState.WAITING_FOR_AUTHORIZATION -> "等待 Root 管理器授权"
    RootProbeState.AUTHORIZED -> "Root 已授权 · ${state.rootManager.ifBlank { "兼容 su" }}"
    RootProbeState.DENIED -> "Root 授权失败"
    RootProbeState.ERROR -> "Root 检查失败"
}

private fun rootStatusDetail(state: InitializationUiState): String = when (state.environment.root) {
    RootProbeState.CHECKING -> "正在通过 su -c id 确认真实 Root UID；系统会弹出 Root 管理器授权请求。"
    RootProbeState.SU_MISSING -> "请先安装并配置 KernelSU、Magisk、APatch 或其他兼容 su 的 Root 管理器。"
    RootProbeState.WAITING_FOR_AUTHORIZATION -> "请在 Root 管理器弹窗中允许字域，然后点下面的按钮重新检查。"
    RootProbeState.AUTHORIZED -> "已确认当前 su 命令以 root UID 运行。"
    RootProbeState.DENIED -> "Root 请求未获授权。请检查 Root 管理器中的字域授权记录后重试。"
    RootProbeState.ERROR -> state.detail.ifBlank { "Root 检查未完成，请重试。" }
}

private fun moduleStatusLabel(state: InitializationUiState): String = when (state.environment.module) {
    ModuleProbeState.CHECKING -> if (state.environment.root == RootProbeState.AUTHORIZED) "正在检查字域模块" else "等待 Root 权限"
    ModuleProbeState.NOT_INSTALLED -> "未安装字域模块"
    ModuleProbeState.PENDING_REBOOT -> "模块更新待重启"
    ModuleProbeState.DISABLED -> "字域模块已停用"
    ModuleProbeState.REMOVE_PENDING -> "模块等待卸载"
    ModuleProbeState.INVALID_MODULE -> "模块 ID 不匹配"
    ModuleProbeState.BRIDGE_UNAVAILABLE -> "模块接口不可用"
    ModuleProbeState.READY -> "模块已启用 · ${state.moduleVersion.ifBlank { "App Bridge 已连接" }}"
}

private fun moduleStatusDetail(state: InitializationUiState): String = when (state.environment.module) {
    ModuleProbeState.CHECKING -> "获得 Root 后会检查已安装模块、启停标记和 App Bridge 响应。"
    ModuleProbeState.NOT_INSTALLED -> "请在 Root 管理器中安装本项目配套的字域模块 ZIP，完成重启后回来检查。"
    ModuleProbeState.PENDING_REBOOT -> "检测到模块更新正在等待重启。请先完整重启手机，再重新检查。"
    ModuleProbeState.DISABLED -> "请在 Root 管理器中启用字域模块并重启，然后重新检查。"
    ModuleProbeState.REMOVE_PENDING -> "Root 管理器正在等待移除模块。取消卸载并重启后再检查。"
    ModuleProbeState.INVALID_MODULE -> "检测到的模块目录与字域模块 ID 不一致；请安装配套模块 ZIP。"
    ModuleProbeState.BRIDGE_UNAVAILABLE -> "模块目录存在，但状态接口没有返回有效结果。请检查 ZIP 版本并重新刷入模块。"
    ModuleProbeState.READY -> "已确认模块目录、启用状态和状态接口均正常。"
}
