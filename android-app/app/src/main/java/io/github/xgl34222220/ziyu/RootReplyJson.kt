package io.github.xgl34222220.ziyu

import org.json.JSONObject

/** Accept a complete object after su banners/BOMs, including formatted JSON. */
internal object RootReplyJson {
    fun firstObject(raw: String): JSONObject {
        var start = raw.indexOf('{')
        var fallback: JSONObject? = null
        var attempts = 0
        while (start >= 0 && attempts++ < 64) {
            val end = objectEnd(raw, start)
            val value = if (end >= 0) runCatching { JSONObject(raw.substring(start, end + 1)) }.getOrNull() else null
            if (value != null) {
                if (value.has("status")) return value
                if (fallback == null) fallback = value
            }
            start = raw.indexOf('{', if (value != null) end + 1 else start + 1)
        }
        return fallback ?: error("模块未返回完整状态，请导出日志")
    }

    private fun objectEnd(raw: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until raw.length) {
            val char = raw[index]
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return -1
    }
}
