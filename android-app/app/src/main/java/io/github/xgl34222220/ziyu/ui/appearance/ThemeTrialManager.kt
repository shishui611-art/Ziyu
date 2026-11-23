/* Theme trial lifecycle tracker adapted from shirkneko/SukiSU 97d2442d.
 * GPL-3.0-or-later; Ziyu adaptation GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object ThemeTrialManager {
    private const val PREFS = "ziyu_theme_trial"
    private const val KEY_ACTIVE = "active"
    private const val KEY_BACKUP = "backup"
    private const val KEY_PROCESS = "process"
    private const val KEY_THEME_NAME = "theme_name"

    private val processToken = UUID.randomUUID().toString()
    private val lock = Mutex()
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()
    private val mutableThemeName = MutableStateFlow("")
    val trialThemeName: StateFlow<String> = mutableThemeName.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun trialDir(context: Context): File = File(context.applicationContext.filesDir, "theme_trial")
    private fun backupFile(context: Context): File = File(trialDir(context), "backup.ziyu-theme")

    fun syncState(context: Context) {
        val p = prefs(context)
        val on = p.getBoolean(KEY_ACTIVE, false) && p.getString(KEY_BACKUP, null)?.let(::File)?.isFile == true
        mutableActive.value = on
        mutableThemeName.value = if (on) p.getString(KEY_THEME_NAME, "").orEmpty() else ""
    }

    fun shouldRestoreOnLaunch(context: Context): Boolean {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ACTIVE, false)) return false
        return p.getString(KEY_PROCESS, null) != processToken
    }

    suspend fun startTrial(
        context: Context,
        currentSettings: AppearanceSettings,
        candidate: AppearanceSettings,
        options: ThemeImportOptions = ThemeImportOptions(),
    ) = lock.withLock {
        val appContext = context.applicationContext
        val backup = backupFile(appContext)
        // If a trial is already active, keep the original pre-trial backup intact.
        if (!prefs(appContext).getBoolean(KEY_ACTIVE, false) || !backup.isFile) {
            withContext(Dispatchers.IO) { trialDir(appContext).mkdirs() }
            ThemePackages.export(appContext, currentSettings, Uri.fromFile(backup))
        }
        AppearanceRepository(appContext).applyTheme(candidate, options)
        prefs(appContext).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_BACKUP, backup.absolutePath)
            .putString(KEY_PROCESS, processToken)
            .putString(KEY_THEME_NAME, candidate.themeName)
            .apply()
        syncState(appContext)
    }

    suspend fun restoreIfNeeded(context: Context): Boolean {
        if (!shouldRestoreOnLaunch(context)) {
            syncState(context)
            return false
        }
        return restoreNow(context)
    }

    suspend fun restoreNow(context: Context): Boolean = lock.withLock {
        val appContext = context.applicationContext
        val p = prefs(appContext)
        if (!p.getBoolean(KEY_ACTIVE, false)) {
            clearLocked(appContext)
            return@withLock false
        }
        val file = p.getString(KEY_BACKUP, null)?.let(::File)
        if (file == null || !file.isFile) {
            clearLocked(appContext)
            return@withLock false
        }
        var prepared: PreparedTheme? = null
        try {
            prepared = ThemePackages.prepare(appContext, Uri.fromFile(file), UiStyle.MIUIX)
            val restoreAll = ThemeImportOptions(
                applyColorsAndStyle = true,
                applyBackground = true,
                applyDynamicWallpaper = true,
                applyHomeCard = true,
                applyDynamicHomeCard = true,
                applyStickers = true,
                applyBottomBarIcons = true,
                applyAppIcon = true,
                textGroups = ThemeImportOptions.ALL_TEXT_GROUPS,
                themeMode = prepared.settings.themeMode,
            )
            AppearanceRepository(appContext).applyTheme(prepared.settings, restoreAll)
            clearLocked(appContext)
            true
        } finally {
            prepared?.discard()
        }
    }

    fun keepCurrent(context: Context) {
        clearLocked(context.applicationContext)
    }

    private fun clearLocked(context: Context) {
        prefs(context).edit().clear().apply()
        runCatching { trialDir(context).deleteRecursively() }
        mutableActive.value = false
        mutableThemeName.value = ""
    }
}
