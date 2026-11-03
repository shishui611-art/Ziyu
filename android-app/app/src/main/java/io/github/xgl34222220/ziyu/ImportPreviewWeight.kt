package io.github.xgl34222220.ziyu

internal fun importPreviewDefaultWeight(value: Float?, range: ClosedFloatingPointRange<Float>?): Float {
    val weight = value?.takeIf { it.isFinite() } ?: 400f
    return if (range != null && range.start.isFinite() && range.endInclusive.isFinite() && range.start < range.endInclusive)
        weight.coerceIn(range) else weight
}
