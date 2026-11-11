package io.github.xgl34222220.ziyu

import org.json.JSONObject

/** Full runtime state uses the same bounded su transport, without font engines. */
internal object ModuleStatus {
    private const val SCHEMA = "ziyu-app-status-v1"
    private val booleans = setOf("root", "installed", "rollbackPending", "rebootRequired", "temporaryRootMode")
    private val integers = setOf("versionCode", "taskProgress", "slotTotal", "slotCjk", "slotDigit")
    private val strings = setOf("version", "active", "effectiveActive", "fontEffectState", "verificationState",
        "verificationGrade", "verificationMode", "verificationReason", "mountState", "mountFailure", "cutoverState",
        "cutoverDecision", "rollbackState", "rollbackTargetFont", "rollbackTargetMode", "taskType", "taskId",
        "taskFont", "taskState", "taskMessage", "rootManager", "mountEngine", "moduleDir", "slotRom")

    suspend fun read(): ShellResult = RootShell.exec(
        "if [ -r /data/adb/modules/ziyu/common/app_status.sh ]; then " +
            "sh /data/adb/modules/ziyu/common/app_status.sh kv; " +
            "elif [ -r /data/adb/modules/ziyu/common/app_bridge.sh ]; then " +
            "sh /data/adb/modules/ziyu/common/app_bridge.sh status; " +
            "else printf '%s\\n' '{\"status\":\"error\",\"message\":\"请先刷入匹配的字域模块\"}'; exit 1; fi",
        timeoutMs = 20_000L,
    ).also { ModuleConnectionDiagnostics.record("status", it) }

    fun parse(raw: String): JSONObject {
        if (!raw.lineSequence().any { it.trimStart('\uFEFF').trimEnd('\r') == "schema=$SCHEMA" }) {
            return RootReplyJson.firstObject(raw)
        }
        val values = linkedMapOf<String, String>()
        val allowed = booleans + integers + strings + setOf("schema", "status", "field_count", "complete")
        raw.lineSequence().forEach { line ->
            val row = line.trimStart('\uFEFF').trimEnd('\r')
            if (row.isBlank()) return@forEach
            val separator = row.indexOf('=')
            require(separator > 0) { "模块状态格式不完整" }
            val key = row.substring(0, separator)
            require(key in allowed && values.put(key, decode(row.substring(separator + 1))) == null) { "模块状态字段不完整" }
        }
        require(values.keys == allowed && values["complete"] == SCHEMA && values["status"] == "ok" &&
            values["field_count"]?.toIntOrNull() == booleans.size + integers.size + strings.size) { "模块状态未读取完整" }
        val data = JSONObject()
        for (key in booleans) {
            require(values[key] in setOf("true", "false")) { "模块状态值无效" }
            data.put(key, values[key] == "true")
        }
        for (key in integers) data.put(key, values[key]?.toIntOrNull() ?: error("模块数值未读取完整"))
        for (key in strings) data.put(key, values.getValue(key))
        require(data.getBoolean("root") && data.getBoolean("installed")) { "模块未通过连接核对" }
        require(data.getString("moduleDir") in setOf("/data/adb/modules/ziyu", "/data/adb/modules/LuoShu")) { "模块目录未通过核对" }
        require(data.getInt("versionCode") > 0 && data.getString("version").isNotBlank()) { "模块版本未读取完整" }
        require(data.getInt("taskProgress") in 0..100) { "任务进度无效" }
        return JSONObject().put("status", "ok").put("data", data)
    }

    private fun decode(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            if (value[index] != '%') append(value[index++]) else {
                require(index + 2 < value.length) { "模块状态文字不完整" }
                append(when (value.substring(index, index + 3)) {
                    "%25" -> '%'
                    "%0A" -> '\n'
                    "%0D" -> '\r'
                    else -> error("模块状态文字无效")
                })
                index += 3
            }
        }
    }
}

/** Only connection replies, in memory; no arbitrary commands or background work. */
internal object ModuleConnectionDiagnostics {
    private val replies = linkedMapOf<String, String>()

    @Synchronized fun record(stage: String, result: ShellResult) {
        require(stage in setOf("handshake", "status"))
        replies[stage] = "[$stage]\ntimeMillis=${System.currentTimeMillis()}\nexitCode=${result.code}\nstdoutChars=${result.stdout.length}\n" +
            "stderrChars=${result.stderr.length}\nstdout:\n${result.stdout.take(16_384)}\n" +
            "stderr:\n${result.stderr.take(8_192)}\n"
    }

    @Synchronized fun report(): String = "[app-module-connection]\n" +
        if (replies.isEmpty()) "No connection reply captured in this App process.\n" else replies.values.joinToString("\n")
}
