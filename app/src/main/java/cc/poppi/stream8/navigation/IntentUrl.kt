package cc.poppi.stream8.navigation

import java.net.URLDecoder
import java.util.Locale

/** Ciò che serve davvero per aprire un'app a partire da un URL `intent://`. */
data class IntentTarget(
    val scheme: String,
    /** URI dei dati, es. `https://www.sito.com/titolo/1`. */
    val dataUri: String,
    val packageName: String?,
    /** `S.browser_fallback_url`, solo se è un URL https valido. */
    val fallbackUrl: String?,
)

/**
 * Parser di `intent://...#Intent;scheme=...;package=...;end`.
 *
 * Non si usa Intent.parseUri: accetta componenti espliciti, selector ed extra arbitrari, cioè permette
 * a una pagina di costruire intent verso qualunque componente esportato (intent redirection).
 * Qui si estraggono solo scheme, package e URL di fallback; l'Intent viene poi costruito dall'app
 * con azione VIEW, categoria BROWSABLE e nessun componente.
 */
object IntentUrlParser {
    private const val PREFIX = "intent:"
    private const val MARKER = "#Intent;"
    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*$")
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val FORBIDDEN_SCHEMES = setOf(
        "intent", "http", "javascript", "data", "file", "content", "blob", "about", "view-source", "filesystem", "ftp",
    )

    /** Un intent:// non può essere usato per lanciare un browser esterno. */
    private val BROWSER_PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "com.android.browser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "org.chromium.webview_shell",
    )

    fun parse(url: String): IntentTarget? {
        if (url.length > 8192 || !url.startsWith(PREFIX, ignoreCase = true)) return null
        if (url.any { it.code <= 0x20 || it.code == 0x7F }) return null

        val body = url.substring(PREFIX.length)
        val marker = body.indexOf(MARKER)
        if (marker < 0) return null
        val dataPart = body.substring(0, marker)
        if (!dataPart.startsWith("//")) return null

        val params = body.substring(marker + MARKER.length).split(';').filter { it.isNotEmpty() }
        if (params.lastOrNull() != "end") return null

        var scheme: String? = null
        var packageName: String? = null
        var fallback: String? = null
        for (param in params) {
            val eq = param.indexOf('=')
            if (eq <= 0) continue
            val key = param.substring(0, eq)
            val value = param.substring(eq + 1)
            when (key) {
                "scheme" -> scheme = value.lowercase(Locale.ROOT)
                "package" -> packageName = value
                "S.browser_fallback_url" -> fallback = percentDecode(value)
                // component, selector, action, extra e flag vengono ignorati di proposito
            }
        }

        val s = scheme ?: return null
        if (!SCHEME.matches(s) || s in FORBIDDEN_SCHEMES) return null
        if (packageName != null && (!PACKAGE.matches(packageName) || packageName in BROWSER_PACKAGES)) return null

        val safeFallback = fallback?.takeIf { UrlParser.parse(it)?.scheme == "https" }
        return IntentTarget(s, "$s:$dataPart", packageName, safeFallback)
    }

    private fun percentDecode(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
