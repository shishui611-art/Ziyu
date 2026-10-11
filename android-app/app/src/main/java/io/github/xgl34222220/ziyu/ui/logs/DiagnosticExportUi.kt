package io.github.xgl34222220.ziyu.ui.logs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.xgl34222220.ziyu.RootShell
import io.github.xgl34222220.ziyu.ui.appearance.UiStyle
import io.github.xgl34222220.ziyu.ui.theme.LocalMiuixTokens
import io.github.xgl34222220.ziyu.ui.theme.ZiyuHeaderAction

internal data class DiagnosticExportState(
    val busy: Boolean = false,
    val path: String = "",
    val error: String = "",
) {
    val resultVisible: Boolean get() = path.isNotBlank() || error.isNotBlank()
}

internal suspend fun exportSanitizedDiagnostic(): DiagnosticExportState {
    val command = """
        MOD=/data/adb/modules/LuoShu
        CFG="${'$'}MOD/config"
        LOG="${'$'}MOD/logs/fontswitch.log"
        OUT_DIR=/sdcard/Ziyu/reports
        OUT="${'$'}OUT_DIR/Ziyu-diagnostic-${'$'}(date '+%Y%m%d-%H%M%S').txt"
        mkdir -p "${'$'}OUT_DIR" 2>/dev/null || exit 20
        read_value() {
            sed -n "s/^${'$'}2=//p" "${'$'}1" 2>/dev/null | head -n1 | tr -d '\r\n'
        }
        version="${'$'}(read_value "${'$'}MOD/module.prop" version)"
        versionCode="${'$'}(read_value "${'$'}MOD/module.prop" versionCode)"
        active="${'$'}(head -n1 "${'$'}CFG/active_font.conf" 2>/dev/null | tr -d '\r\n')"
        case "${'$'}active" in
            ''|default) activeType=default ;;
            mix) activeType=composite ;;
            *) activeType=custom ;;
        esac
        inventory=missing
        [ -s "${'$'}CFG/device_font_inventory.json" ] && inventory=available
        engine="${'$'}(read_value "${'$'}CFG/device-font-engine.conf" state)"
        template="${'$'}(read_value "${'$'}CFG/device-font-template.state" state)"
        alignment="${'$'}(read_value "${'$'}CFG/device-font-load-verification.conf" state)"
        alignmentMode="${'$'}(read_value "${'$'}CFG/device-font-load-verification.conf" mode)"
        alignmentReason="${'$'}(read_value "${'$'}CFG/device-font-load-verification.conf" reason)"
        selfMountState="${'$'}(read_value "${'$'}CFG/self-mount.conf" state)"
        selfMountBackend="${'$'}(read_value "${'$'}CFG/self-mount.conf" backend)"
        selfMountFailed="${'$'}(read_value "${'$'}CFG/self-mount.conf" failed)"
        moduleDirectory=missing
        [ -d "${'$'}MOD" ] && moduleDirectory=present
        pendingModuleDirectory=missing
        [ -d /data/adb/modules_update/LuoShu ] && pendingModuleDirectory=present
        postMountScript=missing
        [ -f "${'$'}MOD/post-mount.sh" ] && postMountScript=present
        cachePending=no
        [ -s "${'$'}CFG/device-font-cache-pending.conf" ] && cachePending=yes
        rootManager=unknown
        if [ -f "${'$'}MOD/common/root_manager_detection.sh" ]; then
            . "${'$'}MOD/common/root_manager_detection.sh" >/dev/null 2>&1 || true
            if type luoshu_detect_root_manager >/dev/null 2>&1; then
                rootManager="${'$'}(luoshu_detect_root_manager 2>/dev/null)"
            fi
        fi
        mountEngine=unknown
        if [ -f "${'$'}MOD/common/mount_compat.sh" ]; then
            . "${'$'}MOD/common/mount_compat.sh" >/dev/null 2>&1 || true
            if type luoshu_detect_mount_engine >/dev/null 2>&1; then
                mountEngine="${'$'}(luoshu_detect_mount_engine 2>/dev/null)"
            fi
        fi
        logHealth="${'$'}(MODDIR="${'$'}MOD" sh "${'$'}MOD/common/action_control.sh" log-health 2>/dev/null)"
        warningCount="${'$'}(printf '%s\n' "${'$'}logHealth" | sed -n 's/^recentWarnings=//p')"
        errorCount="${'$'}(printf '%s\n' "${'$'}logHealth" | sed -n 's/^recentErrors=//p')"
        ignoredCount="${'$'}(printf '%s\n' "${'$'}logHealth" | sed -n 's/^ignoredWarnings=//p')"
        if [ -z "${'$'}warningCount" ] || [ -z "${'$'}errorCount" ]; then
            warningCount="${'$'}(tail -n 500 "${'$'}LOG" 2>/dev/null | grep -Eic '(^|[^[:alnum:]_])(warn|warning)([^[:alnum:]_]|$)|警告')"
            errorCount="${'$'}(tail -n 500 "${'$'}LOG" 2>/dev/null | grep -Eiv '(^|[^[:alnum:]_])(warn|warning)([^[:alnum:]_]|$)|警告' | grep -Eic '(^|[^[:alnum:]_])(error|failed|failure|fatal)([^[:alnum:]_]|$)|失败|错误')"
        fi
        [ -n "${'$'}warningCount" ] || warningCount=0
        [ -n "${'$'}errorCount" ] || errorCount=0
        {
            printf 'report=ziyu-diagnostic-v2\n'
            printf 'time=%s\n' "${'$'}(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)"
            printf 'moduleVersion=%s\n' "${'$'}{version:-unknown}"
            printf 'moduleVersionCode=%s\n' "${'$'}{versionCode:-0}"
            printf 'pendingModuleVersion=%s\n' "${'$'}(read_value /data/adb/modules_update/LuoShu/module.prop version)"
            printf 'pendingModuleVersionCode=%s\n' "${'$'}(read_value /data/adb/modules_update/LuoShu/module.prop versionCode)"
            printf 'versionScope=installed and pending metadata; switch runtime version is recorded in CUTOVER log\n'
            printf 'activeFontType=%s\n' "${'$'}activeType"
            printf 'inventory=%s\n' "${'$'}inventory"
            printf 'engineState=%s\n' "${'$'}{engine:-missing}"
            printf 'templateState=%s\n' "${'$'}{template:-missing}"
            printf 'alignmentState=%s\n' "${'$'}{alignment:-pending}"
            printf 'alignmentMode=%s\n' "${'$'}{alignmentMode:-compatibility}"
            printf 'alignmentReason=%s\n' "${'$'}{alignmentReason:-none}"
            printf 'selfMountState=%s\n' "${'$'}{selfMountState:-missing}"
            printf 'selfMountBackend=%s\n' "${'$'}{selfMountBackend:-missing}"
            printf 'selfMountFailed=%s\n' "${'$'}{selfMountFailed:-none}"
            printf 'moduleDirectory=%s\n' "${'$'}moduleDirectory"
            printf 'pendingModuleDirectory=%s\n' "${'$'}pendingModuleDirectory"
            printf 'postMountScript=%s\n' "${'$'}postMountScript"
            printf 'cachePending=%s\n' "${'$'}cachePending"
            printf 'rootManager=%s\n' "${'$'}rootManager"
            printf 'mountEngine=%s\n' "${'$'}mountEngine"
            printf 'androidSdk=%s\n' "${'$'}(getprop ro.build.version.sdk 2>/dev/null)"
            printf 'androidRelease=%s\n' "${'$'}(getprop ro.build.version.release 2>/dev/null)"
            printf 'deviceBrand=%s\n' "${'$'}(getprop ro.product.brand 2>/dev/null)"
            printf 'deviceModel=%s\n' "${'$'}(getprop ro.product.model 2>/dev/null)"
            printf 'kernel=%s\n' "${'$'}(uname -r 2>/dev/null)"
            printf 'recentWarningCount=%s\n' "${'$'}warningCount"
            printf 'recentErrorCount=%s\n' "${'$'}errorCount"
            printf 'ignoredWarningCount=%s\n' "${'$'}{ignoredCount:-0}"
            printf 'logCountScope=recent font operation records; mount result is recorded separately\n'
            printf 'currentBootId=%s\n' "${'$'}(cat /proc/sys/kernel/random/boot_id 2>/dev/null)"
            printf 'privacy=no serial, accounts or chat content collected; font names, paths, selected axes and exception stacks included\n'
        } > "${'$'}OUT" 2>/dev/null || exit 21
        # Explicit allowlist: never export arbitrary config files or all system properties.
        for name in axes_task.conf switch_task.conf font_mix.conf device-font-engine.conf self-mount.conf; do
            printf '\n[config:%s]\n' "${'$'}name" >> "${'$'}OUT"
            if [ -f "${'$'}CFG/${'$'}name" ]; then
                head -c 16384 "${'$'}CFG/${'$'}name" >> "${'$'}OUT"
                printf '\n' >> "${'$'}OUT"
            else
                printf 'unavailable\n' >> "${'$'}OUT"
            fi
        done
        for relative in config/mount-backend.conf config/mount-backend-verification.json \
            config/font-payload-next.conf config/font-payload-activated.conf \
            config/font-live.conf config/font-live-previous.conf config/font-live-boot-previous.conf \
            config/font-live-transaction.conf config/font-live-attempt.conf config/font-ui-cache.json \
            config/universal-font-next.conf config/universal-font-cutover.conf \
            config/switch_task_worker.pid.cleanup.json config/text_reboot_required.conf \
            .luoshu-state/backup/next-transaction/journal.conf \
            config/font-apply-result.conf config/font-mount-warnings.conf config/device_font_partitions.conf \
            config/device-font-load-verification.conf config/device-font-load-route-verification.json \
            logs/mount-backend.log logs/self-mount.log logs/mount-diagnostics.log \
            logs/mount-diagnostics.log.previous logs/soft-reboot.log logs/font-live.log; do
            path="${'$'}MOD/${'$'}relative"
            printf '\n[module-file:%s]\n' "${'$'}relative" >> "${'$'}OUT"
            if [ -s "${'$'}path" ]; then
                case "${'$'}relative" in
                    logs/mount-diagnostics.log*) tail -n 1200 "${'$'}path" 2>/dev/null | tail -c 196608 >> "${'$'}OUT" ;;
                    logs/*) tail -n 600 "${'$'}path" 2>/dev/null | tail -c 131072 >> "${'$'}OUT" ;;
                    *verification.json) head -c 32768 "${'$'}path" >> "${'$'}OUT" ;;
                    *) head -c 16384 "${'$'}path" >> "${'$'}OUT" ;;
                esac
                printf '\n' >> "${'$'}OUT"
            else
                printf 'unavailable\n' >> "${'$'}OUT"
            fi
        done
        printf '\n[metamodule-detection-evidence]\n' >> "${'$'}OUT"
        META="${'$'}(readlink -f /data/adb/metamodule 2>/dev/null)"
        case "${'$'}META" in
            /data/adb/modules/*)
                printf 'selector=%s\n' "${'$'}META" >> "${'$'}OUT"
                for entry in module.prop metamount.sh meta-overlayfs meta-overlay modules.img mnt; do
                    ls -ldZ "${'$'}META/${'$'}entry" >> "${'$'}OUT" 2>&1
                done
                ;;
            *) printf 'selector=unavailable\n' >> "${'$'}OUT" ;;
        esac
        printf '\n[font-mountinfo]\n' >> "${'$'}OUT"
        grep -E '/fonts|/data/adb/(metamodule|modules/[^/]+/mnt|luoshu/(self-mount|live-mount))' /proc/1/mountinfo 2>/dev/null | head -n 160 >> "${'$'}OUT"
        printf '\n[recent-operation-log]\n' >> "${'$'}OUT"
        tail -n 180 "${'$'}LOG" 2>/dev/null | tail -c 196608 >> "${'$'}OUT"
        printf '\n[persistent-font-diagnostics]\n' >> "${'$'}OUT"
        count=0
        for file in ${'$'}(ls -1t "${'$'}MOD/logs/font-diagnostics"/*.json 2>/dev/null); do
            count=${'$'}((count + 1))
            [ "${'$'}count" -le 12 ] || break
            printf '\n[file:%s]\n' "${'$'}{file##*/}" >> "${'$'}OUT"
            head -c 32768 "${'$'}file" >> "${'$'}OUT"
            printf '\n' >> "${'$'}OUT"
        done
        LAYOUT_HELPER="${'$'}MOD/common/font_layout_diagnostic.sh"
        LAYOUT_OUT="${'$'}OUT_DIR/Ziyu-font-layout.json"
        if [ -f "${'$'}LAYOUT_HELPER" ]; then
            if MODDIR="${'$'}MOD" sh "${'$'}LAYOUT_HELPER" --output "${'$'}LAYOUT_OUT" >/dev/null 2>&1; then
                printf '\n[font-layout]\n' >> "${'$'}OUT"
                cat "${'$'}LAYOUT_OUT" >> "${'$'}OUT"
            else
                printf '\nlayoutDiagnostic=unavailable\n' >> "${'$'}OUT"
            fi
        else
            printf '\nlayoutDiagnostic=module-helper-missing\n' >> "${'$'}OUT"
        fi
        chmod 0644 "${'$'}OUT" 2>/dev/null || true
        printf '%s\n' "${'$'}OUT"
    """.trimIndent()
    val result = RootShell.exec(command, timeoutMs = 45_000L)
    if (result.code != 0) {
        return DiagnosticExportState(error = result.stderr.ifBlank { "脱敏诊断报告生成失败" })
    }
    val path = result.stdout.lineSequence().lastOrNull { it.trim().startsWith("/") }?.trim().orEmpty()
    return if (path.isBlank()) {
        DiagnosticExportState(error = "报告已执行，但没有返回保存路径")
    } else {
        DiagnosticExportState(path = path)
    }
}

@Composable
internal fun DiagnosticExportButton(
    style: UiStyle,
    state: DiagnosticExportState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ZiyuHeaderAction(
        icon = Icons.Rounded.Description,
        contentDescription = "导出完整诊断报告",
        onClick = onClick,
        enabled = !state.busy,
        loading = state.busy,
        containerColor = if (style == UiStyle.MIUIX) {
            LocalMiuixTokens.current.elevatedCardBackground
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier,
        opticalScale = .96f,
    )
}

@Composable
internal fun DiagnosticExportDialog(
    style: UiStyle,
    state: DiagnosticExportState,
    onDismiss: () -> Unit,
) {
    val failed = state.error.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(if (style == UiStyle.MIUIX) 34.dp else 28.dp),
        icon = {
            Icon(
                if (failed) Icons.Rounded.Warning else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(if (failed) "诊断报告生成失败" else "完整诊断报告已生成", fontWeight = FontWeight.Black)
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    if (failed) state.error else "请把下面的报告文件发给开发者。报告包含机型与版本、任务参数、字体名称和路径、字重、错误码、异常堆栈及挂载诊断，不采集序列号、账号或聊天内容。",
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                if (!failed) {
                    Spacer(Modifier.size(12.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(9.dp))
                            Text(state.path, modifier = Modifier.weight(1f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
