package io.github.xgl34222220.ziyu.ui.appearance

import org.json.JSONObject

/** Only appearance values cross this boundary; no Root, font, DPI or filesystem commands. */
internal object ThemeSettingsCodec {
    fun encode(s: AppearanceSettings, paths: Boolean = false): JSONObject = JSONObject()
        .put("schema", 1).put("uiStyle", s.uiStyle.name).put("themeMode", s.themeMode.storageValue)
        .put("seedArgb", s.seedArgb).put("kolorStyle", s.kolorStyle.name)
        .put("colorStandard", s.colorStandard.name).put("monetEnabled", s.monetEnabled)
        .put("nativeMonetEnabled", s.nativeMonetEnabled)
        .put("amoledBlack", s.amoledBlack).put("blurEnabled", s.blurEnabled)
        .put("glassEnabled", s.glassEnabled).put("floatingDock", s.floatingDock)
        .put("cardTransparency", s.cardTransparency).put("backgroundBlur", s.backgroundBlur)
        .put("backgroundScrim", s.backgroundScrim).put("appearance", s.overrides.toJson())
        .put("backgroundScrimIsSigned", s.backgroundScrimIsSigned)
        .put("customTexts", ThemeTexts.json(s.customTexts)).put("homeCard", s.homeCard.toJson(paths))
        .put("appIconId", s.appIconId).put("appIconDynamicColor", s.appIconDynamicColor)
        .put("dynamicWallpaperTransform", s.dynamicWallpaperTransform.toJson())
        .put("dynamicHomeCardTransform", s.dynamicHomeCardTransform.toJson())
        .put("dynamicWallpaperMuted", s.dynamicWallpaperMuted)
        .put("dynamicWallpaperFps", s.dynamicWallpaperFps)
        .put("dynamicWallpaperSpeed", s.dynamicWallpaperSpeed.toDouble())
        .apply {
            if (paths) {
                put("backgroundPath", s.backgroundPath)
                put("bottomBarIcons", bottomBarIconMapJson(s.bottomBarIcons))
                put("dynamicWallpaperPath", s.dynamicWallpaperPath)
                put("dynamicHomeCardPath", s.dynamicHomeCardPath)
            }
        }

    fun decode(o: JSONObject, base: AppearanceSettings = AppearanceSettings()): AppearanceSettings {
        require(o.optInt("schema", 1) == 1) { "不支持此字域主题版本" }
        fun percent(key: String, default: Int): Int = o.optInt(key, default).also {
            require(it in 0..100) { "主题数值超出范围：$key" }
        }
        return base.copy(
            uiStyle = if (o.has("uiStyle")) UiStyle.fromStorage(o.getString("uiStyle")) else base.uiStyle,
            themeMode = ThemeMode.fromStorage(o.optString("themeMode", base.themeMode.storageValue)),
            seedArgb = o.optLong("seedArgb", base.seedArgb.toLong()).toInt(),
            kolorStyle = KolorStyle.fromStorage(o.optString("kolorStyle", base.kolorStyle.name)),
            colorStandard = ColorStandard.fromStorage(o.optString("colorStandard", base.colorStandard.name)),
            monetEnabled = o.optBoolean("monetEnabled", base.monetEnabled),
            nativeMonetEnabled = o.optBoolean("nativeMonetEnabled", base.nativeMonetEnabled),
            amoledBlack = o.optBoolean("amoledBlack", base.amoledBlack),
            blurEnabled = o.optBoolean("blurEnabled", base.blurEnabled),
            glassEnabled = o.optBoolean("glassEnabled", base.glassEnabled),
            floatingDock = o.optBoolean("floatingDock", base.floatingDock),
            cardTransparency = percent("cardTransparency", base.cardTransparency),
            backgroundBlur = percent("backgroundBlur", base.backgroundBlur),
            backgroundScrimIsSigned = o.optBoolean("backgroundScrimIsSigned", base.backgroundScrimIsSigned),
            backgroundScrim = o.optInt("backgroundScrim", base.backgroundScrim).also {
                require(it in (if (o.optBoolean("backgroundScrimIsSigned", base.backgroundScrimIsSigned)) -100 else 0)..100) { "主题数值超出范围：backgroundScrim" }
            },
            overrides = AppearanceOverrides.parse(o.optJSONObject("appearance")),
            backgroundPath = o.optString("backgroundPath", base.backgroundPath),
            customTexts = ThemeTexts.parse(o.optJSONObject("customTexts")),
            homeCard = HomeCardDecoration.parse(o.optJSONObject("homeCard")),
            appIconId = nonBlankThemeText(o.opt("appIconId"))?.takeIf(BuiltinAppIcons::isKnown) ?: base.appIconId,
            appIconDynamicColor = o.optBoolean("appIconDynamicColor", base.appIconDynamicColor),
            bottomBarIcons = if (o.has("bottomBarIcons")) parseBottomBarIconMap(o.optJSONObject("bottomBarIcons")) else base.bottomBarIcons,
            dynamicWallpaperPath = o.optString("dynamicWallpaperPath", base.dynamicWallpaperPath),
            dynamicWallpaperTransform = if (o.has("dynamicWallpaperTransform")) DynamicWallpaperTransform.parse(o.optJSONObject("dynamicWallpaperTransform")) else base.dynamicWallpaperTransform,
            dynamicHomeCardPath = o.optString("dynamicHomeCardPath", base.dynamicHomeCardPath),
            dynamicHomeCardTransform = if (o.has("dynamicHomeCardTransform")) DynamicWallpaperTransform.parse(o.optJSONObject("dynamicHomeCardTransform")) else base.dynamicHomeCardTransform,
            dynamicWallpaperMuted = o.optBoolean("dynamicWallpaperMuted", base.dynamicWallpaperMuted),
            dynamicWallpaperFps = o.optInt("dynamicWallpaperFps", base.dynamicWallpaperFps),
            dynamicWallpaperSpeed = o.optDouble("dynamicWallpaperSpeed", base.dynamicWallpaperSpeed.toDouble()).toFloat(),
            adaptive = null,
        ).normalized()
    }

    fun encodeVariant(v: AppearanceVariant, paths: Boolean = false): JSONObject =
        encode(v.applyTo(AppearanceSettings()), paths).put("variantUiStyle", v.uiStyle?.name ?: JSONObject.NULL)
            .put("variantThemeMode", v.themeMode?.storageValue ?: JSONObject.NULL)

    fun decodeVariant(o: JSONObject, base: AppearanceSettings = AppearanceSettings()): AppearanceVariant =
        decode(o, base).asVariant().copy(uiStyle = nonBlankThemeText(o.opt("variantUiStyle"))?.let(UiStyle::fromStorage),
            themeMode = nonBlankThemeText(o.opt("variantThemeMode"))?.let(ThemeMode::fromStorage))

    fun adaptiveToJson(a: AdaptiveAppearance): JSONObject = JSONObject()
        .put("light", encodeVariant(a.light, true))
        .put("dark", encodeVariant(a.dark, true))

    fun adaptiveFromJson(raw: String): AdaptiveAppearance? {
        if (raw.isBlank()) return null
        val o = JSONObject(raw)
        return AdaptiveAppearance(decodeVariant(o.getJSONObject("light")), decodeVariant(o.getJSONObject("dark")))
    }
}
