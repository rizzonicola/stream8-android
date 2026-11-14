package cc.poppi.stream8.navigation

import java.net.IDN
import java.util.Locale

/** Risultato del parsing. Per gli schemi non web (intent, deep link) `host` e `port` sono null. */
data class ParsedUrl(val scheme: String, val host: String?, val port: Int?)

/**
 * Parser volutamente rigoroso: nel dubbio rifiuta l'URL (restituisce null) invece di interpretarlo.
 *
 * Non usa android.net.Uri: i parser permissivi interpretano in modo diverso da WebView stringhe
 * come `https://sito.com\@evil.com` o `https://sito.com@evil.com`, e la differenza è esattamente
 * ciò che serve per aggirare una whitelist. Qui userinfo, backslash, spazi e caratteri di controllo
 * vengono rifiutati.
 */
object UrlParser {
    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?$")

    fun parse(raw: String): ParsedUrl? {
        if (raw.isEmpty() || raw.length > UrlSyntax.MAX_LENGTH) return null
        if (UrlSyntax.hasUnsafeCharacters(raw)) return null

        val scheme = UrlSyntax.schemeOf(raw) ?: return null
        if (scheme != "http" && scheme != "https") return ParsedUrl(scheme, null, null)

        val rest = raw.substring(scheme.length + 1)
        if (!rest.startsWith("//")) return null
        val afterSlashes = rest.substring(2)
        val end = afterSlashes.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val authority = if (end < 0) afterSlashes else afterSlashes.substring(0, end)
        if (authority.isEmpty() || authority.contains('@')) return null

        var hostPart = authority
        var port: Int? = null
        val portSep = authority.lastIndexOf(':')
        if (portSep >= 0) {
            val portText = authority.substring(portSep + 1)
            if (portText.isEmpty() || portText.length > 5 || !portText.all { it in '0'..'9' }) return null
            val parsedPort = portText.toInt()
            if (parsedPort !in 1..65535) return null
            port = parsedPort
            hostPart = authority.substring(0, portSep)
        }

        val host = normalizeHost(hostPart) ?: return null
        return ParsedUrl(scheme, host, port)
    }

    /** Minuscolo, punycode per gli IDN, niente punto finale. Rifiuta IP, host vuoti e caratteri strani. */
    private fun normalizeHost(raw: String): String? {
        var h = raw
        if (h.endsWith(".")) h = h.dropLast(1)
        if (h.isEmpty()) return null
        val ascii = try {
            IDN.toASCII(h, IDN.ALLOW_UNASSIGNED)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val host = ascii.lowercase(Locale.ROOT)
        if (host.length > 253) return null
        val labels = host.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 || !LABEL.matches(it) }) return null
        // Rifiuta IPv4 e TLD numerici: i servizi devono essere nomi di dominio.
        if (labels.last().all { it in '0'..'9' }) return null
        return host
    }
}
