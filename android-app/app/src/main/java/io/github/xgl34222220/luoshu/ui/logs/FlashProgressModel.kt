package io.github.xgl34222220.luoshu.ui.logs

import org.json.JSONObject

/** Display text only. Never discard/overwrite the diagnostic source. */
internal fun taskDisplayMessage(raw: String): String {
    val text = raw.trim()
    val jsonStart = text.indexOf('{')
    if (jsonStart >= 0 && text.endsWith('}')) {
        val parsed = runCatching { JSONObject(text.substring(jsonStart)) }.getOrNull()
        val message = parsed?.optString("message").orEmpty().ifBlank {
            parsed?.optJSONObject("data")?.optString("message").orEmpty()
        }
        if (message.isNotBlank()) return message
    }
    // SAFE-SWITCH console telemetry isn't appropriate as a task title/body.
    val stage = Regex("^(?:\\[[^]]+]\\s*)*(?:stage=\\d+\\s+)?message=(.*)$").matchEntire(text)
    return stage?.groupValues?.get(1)?.trim().orEmpty().ifBlank { text }
}

internal fun primaryFlashTask(tasks: List<TaskCenterItem>): TaskCenterItem? =
    tasks.firstOrNull { it.current && it.active }
        ?: tasks.firstOrNull { it.current && it.phase == TaskPhase.FAILED }
        ?: tasks.firstOrNull { it.current && it.phase == TaskPhase.WAITING_REBOOT }
        ?: tasks.firstOrNull { it.current }
        ?: tasks.firstOrNull()
