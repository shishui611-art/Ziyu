package io.github.xgl34222220.ziyu

import org.json.JSONObject
import kotlin.math.roundToInt

internal data class VariableAxisInfo(
    val tag: String,
    val min: Float,
    val default: Float,
    val max: Float,
    val name: String = "",
    val hidden: Boolean = false,
)

internal data class WeightAxisInfo(
    val loading: Boolean = true,
    val hasWeight: Boolean = false,
    val min: Int = 100,
    val default: Int = 400,
    val max: Int = 900,
    val axes: List<VariableAxisInfo> = emptyList(),
    val error: String = "",
) {
    val visibleAxes: List<VariableAxisInfo> get() = axes.filterNot { it.hidden }
}

/** Font-declared capability only; malformed metadata cannot become a slider. */
internal fun parseWeightAxisInfo(root: JSONObject): WeightAxisInfo {
    require(root.optString("status") == "ok") { root.optString("message", "字体轴读取失败") }
    val rawAxes = root.optJSONArray("axes") ?: error("未收到字体轴列表")
    require(rawAxes.length() <= 64) { "字体轴数量异常" }
    val seen = mutableSetOf<String>()
    val axes = buildList {
        for (index in 0 until rawAxes.length()) {
            val axis = rawAxes.optJSONObject(index) ?: error("字体轴数据无效")
            val tag = axis.optString("tag")
            val minimum = axis.optDouble("min", Double.NaN).toFloat()
            val maximum = axis.optDouble("max", Double.NaN).toFloat()
            val default = axis.optDouble("default", Double.NaN).toFloat()
            require(
                tag.length == 4 && tag.all { it.code in 32..126 } && seen.add(tag) &&
                    minimum.isFinite() && maximum.isFinite() && default.isFinite() &&
                    minimum <= default && default <= maximum,
            ) { "字体轴 $tag 的定义无效" }
            val name = axis.optString("name").map {
                if (it.isISOControl() || it == '\u2028' || it == '\u2029') ' ' else it
            }.joinToString("").trim().take(128)
            add(VariableAxisInfo(tag, minimum, default, maximum, name, axis.optBoolean("hidden", false)))
        }
    }
    val weight = axes.firstOrNull { it.tag == "wght" }
    return WeightAxisInfo(
        loading = false,
        hasWeight = weight != null,
        min = weight?.min?.roundToInt() ?: 100,
        default = weight?.default?.roundToInt() ?: 400,
        max = weight?.max?.roundToInt() ?: 900,
        axes = axes,
    )
}

/** Size/date labels are rounded; the font ID plus both labels bind metadata to one library source. */
internal val FontItem.sourceRevision: String
    get() = "$id|$size|$date"

/** A variable default comes from fvar, not the separate OS/2 weight class. */
internal fun fontMetadataWeightLabel(face: JSONObject): String {
    val axes = face.optJSONArray("axes")
    for (index in 0 until (axes?.length() ?: 0)) {
        val axis = axes?.optJSONObject(index) ?: continue
        if (axis.optString("tag") != "wght") continue
        val value = axis.optDouble("default", Double.NaN)
        val min = axis.optDouble("min", Double.NaN)
        val max = axis.optDouble("max", Double.NaN)
        if (value.isFinite() && min.isFinite() && max.isFinite() && min <= value && value <= max) {
            val label = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            return "默认字重 $label"
        }
    }
    val weight = face.optInt("weight", -1)
    return if (weight in 1..1000) "字重 $weight" else "字重未读取"
}
