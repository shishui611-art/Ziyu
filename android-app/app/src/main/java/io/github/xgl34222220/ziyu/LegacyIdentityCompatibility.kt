package io.github.xgl34222220.ziyu

/** Read old exports and module commands while every new write uses Ziyu. */
internal object LegacyIdentityCompatibility {
    fun healthCommand(action: String): String {
        require(action in setOf("report", "repair-stale", "restore-default"))
        return """
            _ziyu_health_mod=/data/adb/modules/ziyu
            _ziyu_health_script=''
            for _ziyu_health_relative in \
                common/ziyu_health.sh \
                .ziyu-payload/system/bin/ziyu-health \
                .luoshu-payload/system/bin/luoshu-health \
                .ziyu-payload/system/bin/luoshu-health \
                .luoshu-payload/system/bin/ziyu-health \
                system/bin/ziyu-health system/bin/luoshu-health; do
                _ziyu_health_candidate="${'$'}_ziyu_health_mod/${'$'}_ziyu_health_relative"
                if [ -f "${'$'}_ziyu_health_candidate" ] && [ -r "${'$'}_ziyu_health_candidate" ]; then
                    _ziyu_health_script="${'$'}_ziyu_health_candidate"
                    break
                fi
            done
            if [ -n "${'$'}_ziyu_health_script" ]; then
                MODDIR="${'$'}_ziyu_health_mod" MODULE_DIR="${'$'}_ziyu_health_mod" \
                    sh "${'$'}_ziyu_health_script" ${RootShell.quote(action)}
            else
                printf '%s\n' '字域体检组件缺失，请刷入与 App 匹配的模块；已有字体保持原状。' >&2
                exit 127
            fi
        """.trimIndent()
    }

    fun matchesFormat(actual: String, current: String): Boolean =
        actual == current || actual == current.replace("ziyu-", "luoshu-")

    fun commandFallbacks(command: String): String = buildString {
        for (name in listOf("ziyu_detect_root_manager", "ziyu_detect_mount_engine", "ziyu_current_boot_backend_state")) {
            if (command.contains(name)) {
                val old = name.replace("ziyu_", "luoshu_")
                append("$name() { $old \"\$@\"; }\n")
            }
        }
    }
}
