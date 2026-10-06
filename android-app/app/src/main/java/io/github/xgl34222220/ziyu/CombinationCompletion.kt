package io.github.xgl34222220.ziyu

internal object CombinationCompletion {
    fun shouldRestart(state: String, confirmed: Boolean) = state == "success" && confirmed
    fun shouldPreview(state: String, result: String, fontId: String, taskId: String, acknowledged: String = "") =
        state == "success" && result == "prepared" && fontId.isNotBlank() && taskId.isNotBlank() && taskId != acknowledged
}

internal data class PreparedCombination(val taskId: String, val fontId: String, val name: String)
