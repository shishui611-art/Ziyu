package io.github.xgl34222220.ziyu

import org.json.JSONObject

internal object ModuleHandshake {
    suspend fun read(): ShellResult = RootShell.exec(
        "if [ -r /data/adb/modules/ziyu/common/app_handshake.sh ]; then " +
            "sh /data/adb/modules/ziyu/common/app_handshake.sh probe-v2; " +
            "else printf '%s\\n' '{\"status\":\"legacy\"}'; fi",
        timeoutMs = 12_000L,
    ).also { ModuleConnectionDiagnostics.record("handshake", it) }

    fun parse(raw: String): JSONObject {
        val fields = linkedMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            val pair = line.trimStart('\uFEFF').trimEnd('\r').split('=', limit = 2)
            if (pair.size == 2) fields.putIfAbsent(pair[0], pair[1])
        }
        if (fields["schema"] != "ziyu-app-handshake-v2") return RootReplyJson.firstObject(raw)
        require(fields["uid"] == "0") { "Root 尚未授权" }
        val directory = fields["module_dir"]
        require(directory in setOf("/data/adb/modules/ziyu", "/data/adb/modules/LuoShu")) { "模块目录未通过核对" }
        require(fields["module_id"] in setOf("ziyu", "LuoShu")) { "模块身份未通过核对" }
        require(fields["module_installed"] == "true" && fields["bridge_responding"] == "true") { "模块组件缺失，请刷入匹配模块" }
        val versionCode = fields["module_version_code"]?.toIntOrNull()
        require(versionCode != null && versionCode > 0 && !fields["module_version"].isNullOrBlank()) { "模块版本未读取完整" }
        fun flag(key: String): Boolean {
            require(fields[key] in setOf("true", "false")) { "模块状态未读取完整" }
            return fields[key] == "true"
        }
        val data = JSONObject().put("root", true).put("installed", true)
            .put("version", fields["module_version"].orEmpty()).put("versionCode", versionCode)
            .put("moduleDir", directory).put("rootManager", fields["root_manager"].orEmpty().ifBlank { "Root" })
            .put("active", fields["active_font"].orEmpty().ifBlank { "default" })
            .put("disabled", flag("module_disabled")).put("removePending", flag("module_remove_pending"))
            .put("pendingUpdate", flag("module_pending_update")).put("romCheck", true)
        return JSONObject().put("status", "ok").put("schema", "ziyu-app-handshake-v1").put("data", data)
    }

    fun failure(result: ShellResult, fallback: String): String {
        val structured = runCatching { RootReplyJson.firstObject(result.stdout).optString("message") }.getOrNull()
        return structured?.takeIf { it.isNotBlank() }
            ?: result.stderr.trim().takeLast(1200).ifBlank { "$fallback（退出码 ${result.code}）" }
    }
}
