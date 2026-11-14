package cc.poppi.stream8.navigation

import java.util.Locale

/**
 * Regole sintattiche comuni ai parser degli URL.
 *
 * La WebView interpreta gli URL in modo permissivo: qui si rifiuta tutto ciò che può essere letto in
 * due modi diversi, così i parser dell'app non possono essere aggirati da una stringa ambigua.
 */
internal object UrlSyntax {
    const val MAX_LENGTH = 8192

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*$")

    /** Schemi che non devono mai essere eseguiti o aperti come contenuto. */
    val CONTENT_SCHEMES = setOf(
        "javascript", "data", "file", "content", "blob", "about", "view-source", "filesystem", "ftp",
    )

    /** Schemi che non devono mai essere caricati nella WebView né aperti come app. */
    val BLOCKED_SCHEMES = CONTENT_SCHEMES + setOf("ws", "wss") + BrowserTargets.SCHEMES

    /** Schema valido e minuscolo, oppure null. */
    fun schemeOf(raw: String): String? {
        val colon = raw.indexOf(':')
        if (colon <= 0) return null
        val scheme = raw.substring(0, colon).lowercase(Locale.ROOT)
        return scheme.takeIf { isValidScheme(it) }
    }

    fun isValidScheme(scheme: String): Boolean = SCHEME.matches(scheme)

    /** Spazi, caratteri di controllo e backslash: i parser permissivi li interpretano diversamente. */
    fun hasUnsafeCharacters(raw: String): Boolean =
        raw.any { it.code <= 0x20 || it.code == 0x7F || it == '\\' }
}
