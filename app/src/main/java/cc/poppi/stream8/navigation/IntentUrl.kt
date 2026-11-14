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
    private const val MARKER = "#Intent;"
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private val FORBIDDEN_SCHEMES = UrlSyntax.BLOCKED_SCHEMES + setOf("intent", "http")

    fun parse(url: String): IntentTarget? {
        if (url.length > UrlSyntax.MAX_LENGTH || UrlSyntax.schemeOf(url) != "intent") return null
        if (UrlSyntax.hasUnsafeCharacters(url)) return null

        val body = url.substring(url.indexOf(':') + 1)
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
        if (!UrlSyntax.isValidScheme(s) || s in FORBIDDEN_SCHEMES) return null
        if (packageName != null && (!PACKAGE.matches(packageName) || packageName in BrowserTargets.PACKAGES)) return null

        val safeFallback = fallback?.takeIf { UrlParser.parse(it)?.scheme == "https" }
        return IntentTarget(s, "$s:$dataPart", packageName, safeFallback)
    }

    private fun percentDecode(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (e: IllegalArgumentException) {
        null
    }
}
