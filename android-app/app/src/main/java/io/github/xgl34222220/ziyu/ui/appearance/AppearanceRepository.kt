package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.xgl34222220.ziyu.shouldRequireSetup
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.appearanceDataStore by preferencesDataStore(name = "appearance")

class AppearanceRepository(private val context: Context) {
    // Package timestamps survive normal app upgrades even when a user has never changed
    // appearance settings, so those existing installs must not be sent through first-run setup.
    private val isUpgradeInstall = context.isUpgradeInstall()

    private object Keys {
        val uiStyle = stringPreferencesKey("ui_style")
        val themeMode = stringPreferencesKey("theme_mode")
        val setupCompleted = booleanPreferencesKey("setup_completed")
        val seedArgb = intPreferencesKey("theme_seed_argb")
        val kolorStyle = stringPreferencesKey("theme_kolor_style")
        val monetEnabled = booleanPreferencesKey("theme_monet_enabled")
        val amoledBlack = booleanPreferencesKey("theme_amoled_black")
        val blurEnabled = booleanPreferencesKey("theme_blur_enabled")
        val glassEnabled = booleanPreferencesKey("theme_glass_enabled")
        val floatingDock = booleanPreferencesKey("theme_floating_dock")
        val highRefreshRate = booleanPreferencesKey("display_high_refresh_rate")
    }

    val settings: Flow<AppearanceSettings> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            AppearanceSettings(
                uiStyle = UiStyle.fromStorage(preferences[Keys.uiStyle]),
                themeMode = ThemeMode.fromStorage(preferences[Keys.themeMode] ?: ThemeMode.SYSTEM.storageValue),
                seedArgb = preferences[Keys.seedArgb] ?: AccentOptions.first().argb,
                kolorStyle = KolorStyle.fromStorage(preferences[Keys.kolorStyle]),
                monetEnabled = preferences[Keys.monetEnabled] ?: true,
                amoledBlack = preferences[Keys.amoledBlack] ?: false,
                blurEnabled = preferences[Keys.blurEnabled] ?: true,
                glassEnabled = preferences[Keys.glassEnabled] ?: true,
                floatingDock = preferences[Keys.floatingDock] ?: true,
                highRefreshRate = preferences[Keys.highRefreshRate] ?: false,
            ).normalized()
        }

    val setupRequired: Flow<Boolean> = context.appearanceDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            val hadLegacyPreferences = preferences.asMap().keys.any { it != Keys.setupCompleted }
            shouldRequireSetup(
                storedSetupCompleted = preferences[Keys.setupCompleted],
                hasLegacyPreferences = hadLegacyPreferences,
                isUpgradeInstall = isUpgradeInstall,
            )
        }

    suspend fun setUiStyle(value: UiStyle) = edit { it[Keys.uiStyle] = value.name }
    suspend fun setThemeMode(value: ThemeMode) = edit { it[Keys.themeMode] = value.storageValue }
    suspend fun setSetupCompleted() = edit { it[Keys.setupCompleted] = true }
    suspend fun setSeedArgb(value: Int) = edit { it[Keys.seedArgb] = accentOptionFor(value).argb }
    suspend fun setKolorStyle(value: KolorStyle) = edit { it[Keys.kolorStyle] = value.name }
    suspend fun setMonetEnabled(enabled: Boolean) = edit { it[Keys.monetEnabled] = enabled }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoledBlack] = enabled }
    suspend fun setBlurEnabled(enabled: Boolean) = edit { preferences ->
        preferences[Keys.blurEnabled] = enabled && (preferences[Keys.glassEnabled] ?: true)
    }

    suspend fun setGlassEnabled(enabled: Boolean) {
        edit { preferences ->
            preferences[Keys.glassEnabled] = enabled
            if (!enabled) preferences[Keys.blurEnabled] = false
        }
    }

    suspend fun setFloatingDock(enabled: Boolean) = edit { it[Keys.floatingDock] = enabled }
    suspend fun setHighRefreshRate(enabled: Boolean) = edit { it[Keys.highRefreshRate] = enabled }

    private suspend inline fun edit(crossinline block: (MutablePreferences) -> Unit) {
        context.appearanceDataStore.edit { preferences -> block(preferences) }
    }
}

@Suppress("DEPRECATION")
private fun Context.isUpgradeInstall(): Boolean = runCatching {
    val packageInfo = packageManager.getPackageInfo(packageName, 0)
    packageInfo.lastUpdateTime > packageInfo.firstInstallTime
}.getOrDefault(false)
