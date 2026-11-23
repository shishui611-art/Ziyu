/* Regional mirror selection and failover protocol adapted from shirkneko/SukiSU 97d2442d.
 * GPL-3.0-or-later; Ziyu adaptation GPL-3.0-only. See THIRD_PARTY_NOTICES.md.
 */
package io.github.xgl34222220.ziyu.ui.appearance

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone

internal object ThemeStoreDefaults {
    const val INTERNATIONAL_BASE_URL = "https://themes.oddnexus.top/"
    const val MAINLAND_PRIMARY_BASE_URL = "https://themes.neko.shirkneko.top:8443/"
    const val MAINLAND_SECONDARY_BASE_URL = "https://themes.zako.shirkneko.top:20005/"
    const val MAINLAND_TERTIARY_BASE_URL = "https://themes.shirkneko.top:41112/"

    const val BASE_URL = MAINLAND_PRIMARY_BASE_URL

    val BASE_URLS = listOf(
        INTERNATIONAL_BASE_URL,
        MAINLAND_PRIMARY_BASE_URL,
        MAINLAND_SECONDARY_BASE_URL,
        MAINLAND_TERTIARY_BASE_URL,
    )

    private val MAINLAND_BASE_URLS = listOf(
        MAINLAND_PRIMARY_BASE_URL,
        MAINLAND_SECONDARY_BASE_URL,
        MAINLAND_TERTIARY_BASE_URL,
    )

    private val MAINLAND_TIME_ZONES = setOf(
        "Asia/Shanghai",
        "Asia/Chongqing",
        "Asia/Harbin",
        "Asia/Urumqi",
        "Asia/Kashgar",
        "PRC",
    )

    private val VPN_INTERFACE_PREFIXES = listOf("tun", "tap", "ppp", "utun")
    private const val FAILOVER_COOLDOWN_MS = 30 * 60 * 1000L
    private const val VPN_CACHE_MS = 10_000L
    private const val LEGACY_TOKEN = "80791959008c80bc4ceee61ae66bbab6877192394a3f76498259992bc1cebb1e"

    @Volatile private var unavailableBaseUrl: String? = null
    @Volatile private var unavailableUntilMs: Long = 0L
    @Volatile private var vpnDetected: Boolean = false
    @Volatile private var vpnCheckedAtMs: Long = 0L

    fun preferredBaseUrl(context: Context): String {
        val mirrors = regionalBaseUrls(context)
        if (mirrors.size > 1 && isPrimaryUnavailable(context)) return mirrors[1]
        return mirrors.first()
    }

    private fun regionalBaseUrls(context: Context): List<String> =
        if (isMainlandChina() && !isVpnActive(context)) MAINLAND_BASE_URLS
        else listOf(INTERNATIONAL_BASE_URL)

    fun isVpnActive(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - vpnCheckedAtMs < VPN_CACHE_MS) return vpnDetected
        val detected = runCatching {
            val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = manager?.activeNetwork ?: return@runCatching false
            val caps = manager.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                manager.getLinkProperties(network)?.httpProxy != null
        }.getOrDefault(false) || hasVpnInterface()
        vpnDetected = detected
        vpnCheckedAtMs = now
        return detected
    }

    private fun hasVpnInterface(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
            iface.isUp && !iface.isLoopback &&
                VPN_INTERFACE_PREFIXES.any { prefix -> iface.name.startsWith(prefix) }
        }
    }.getOrDefault(false)

    private fun isPrimaryUnavailable(context: Context): Boolean {
        val primary = runCatching { ThemeStoreRepository.normalize(regionalBaseUrls(context).first()) }.getOrNull() ?: return false
        return unavailableBaseUrl == primary && System.currentTimeMillis() < unavailableUntilMs
    }

    fun fallbackBaseUrl(context: Context, baseUrl: String): String? {
        val normalized = runCatching { ThemeStoreRepository.normalize(baseUrl) }.getOrNull() ?: return null
        val mirrors = regionalBaseUrls(context).mapNotNull { runCatching { ThemeStoreRepository.normalize(it) }.getOrNull() }
        val index = mirrors.indexOf(normalized)
        return if (index < 0) null else mirrors.getOrNull(index + 1)
    }

    fun reportUnreachable(context: Context, baseUrl: String) {
        val normalized = runCatching { ThemeStoreRepository.normalize(baseUrl) }.getOrNull() ?: return
        val primary = runCatching { ThemeStoreRepository.normalize(regionalBaseUrls(context).first()) }.getOrNull() ?: return
        if (normalized != primary) return
        unavailableBaseUrl = normalized
        unavailableUntilMs = System.currentTimeMillis() + FAILOVER_COOLDOWN_MS
    }

    fun reportReachable(baseUrl: String) {
        val normalized = runCatching { ThemeStoreRepository.normalize(baseUrl) }.getOrNull() ?: return
        if (normalized == unavailableBaseUrl) {
            unavailableBaseUrl = null
            unavailableUntilMs = 0L
        }
    }

    fun isMainlandChina(): Boolean {
        val zone = runCatching { TimeZone.getDefault().id }.getOrNull().orEmpty()
        if (MAINLAND_TIME_ZONES.any { it.equals(zone, ignoreCase = true) }) return true
        val country = runCatching { Locale.getDefault().country }.getOrNull().orEmpty()
        return country.equals("CN", ignoreCase = true)
    }

    fun isBuiltInBaseUrl(raw: String): Boolean {
        val normalized = runCatching { ThemeStoreRepository.normalize(raw) }.getOrNull() ?: return false
        return BASE_URLS.any { runCatching { ThemeStoreRepository.normalize(it) }.getOrNull() == normalized }
    }

    fun mirrorLabel(url: String): String = when (url.trimEnd('/') + "/") {
        INTERNATIONAL_BASE_URL -> "国际镜像（oddnexus）"
        MAINLAND_PRIMARY_BASE_URL -> "国内主镜像（neko）"
        MAINLAND_SECONDARY_BASE_URL -> "国内备用 1（zako）"
        MAINLAND_TERTIARY_BASE_URL -> "国内备用 2（shirkneko）"
        else -> url.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    fun migrate(source: ThemeSource): ThemeSource =
        if (isBuiltInBaseUrl(source.url) && source.accessCode == LEGACY_TOKEN) source.copy(accessCode = "") else source
}
