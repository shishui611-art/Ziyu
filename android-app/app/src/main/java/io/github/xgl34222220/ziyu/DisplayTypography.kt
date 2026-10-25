package io.github.xgl34222220.ziyu

import androidx.compose.runtime.Immutable

@Immutable
data class DisplayTypographyValues(
    val user: Int,
    val sdk: Int,
    val scale: Int,
    val weight: Int,
    val density: Int,
    val baseDensity: Int,
    val densityMin: Int,
    val densityMax: Int,
)

@Immutable
data class DisplayTypographyState(
    val fontId: String = "",
    val sourceRevision: String = "",
    val loading: Boolean = true,
    val saving: Boolean = false,
    val values: DisplayTypographyValues? = null,
    val supportsWeight: Boolean = false,
    val error: String = "",
    val message: String = "",
)

internal fun parseDisplayTypography(result: ShellResult): Pair<DisplayTypographyValues, String> {
    val fields = result.stdout.lineSequence().take(40).mapNotNull { line ->
        val split = line.indexOf('=')
        if (split <= 0) null else line.substring(0, split) to line.substring(split + 1).trimEnd('\r')
    }.toMap()
    check(result.code == 0 && fields["status"] == "ok") {
        fields["message"] ?: result.stderr.ifBlank { "显示设置读取失败，请确认模块与 App 版本匹配" }
    }
    fun number(key: String) = fields[key]?.toIntOrNull() ?: error("显示设置 $key 无效")
    val values = DisplayTypographyValues(
        user = number("user"), sdk = number("sdk"), scale = number("scale"), weight = number("weight"),
        density = number("density"), baseDensity = number("baseDensity"),
        densityMin = number("densityMin"), densityMax = number("densityMax"),
    )
    check(values.user >= 0 && values.sdk >= 28 && values.scale in 1..1000 && values.weight in -1000..1000 &&
        values.baseDensity > 0 && values.densityMin > 0 && values.densityMax >= values.densityMin &&
        values.density in values.densityMin..values.densityMax) { "系统显示数值超出有效范围" }
    return values to fields["message"].orEmpty()
}
