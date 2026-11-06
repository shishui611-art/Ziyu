/* Endpoint and device-access protocol follow SukiSU-Ultra
 * 8ed5bd556fba073d52f4a4d98500b782db6437e6, GPL-3.0-or-later.
 * See THIRD_PARTY_NOTICES.md. Service contact requires in-app consent.
 */
package io.github.xgl34222220.ziyu.ui.appearance

internal object ThemeStoreDefaults {
    const val BASE_URL = "https://themes.neko.shirkneko.top:8443/"
    // Recognize the obsolete bundled gate only to migrate existing configurations.
    private const val LEGACY_TOKEN = "80791959008c80bc4ceee61ae66bbab6877192394a3f76498259992bc1cebb1e"

    fun migrate(source: ThemeSource): ThemeSource =
        if (source.url == BASE_URL && source.accessCode == LEGACY_TOKEN) source.copy(accessCode = "") else source
}
