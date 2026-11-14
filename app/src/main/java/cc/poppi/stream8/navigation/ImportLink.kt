package cc.poppi.stream8.navigation

import java.net.URI
import java.net.URISyntaxException

/**
 * Accetta solo i link di condivisione impostazioni generati da Stream8
 * (`https://<host Stream8>/import-settings?data=...`). Il payload viene validato
 * dal sito: qui serve solo a impedire che l'app carichi un URL arbitrario.
 */
object ImportLink {
    /** Percorso usato anche nei testi nativi, così il link mostrato resta quello accettato. */
    const val PATH = "/import-settings"

    fun parse(raw: String): String? {
        val value = raw.trim()
        val parsed = UrlParser.parse(value) ?: return null
        if (parsed.scheme != "https" || parsed.host != PolicyDefaults.STREAM8_HOST) return null
        if (parsed.port != null && parsed.port != 443) return null

        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.rawUserInfo != null || uri.path != PATH) return null
        if (uri.host == null || !uri.host.equals(parsed.host, ignoreCase = true)) return null

        val data = uri.rawQuery.orEmpty()
            .split('&')
            .firstOrNull { it.substringBefore('=') == "data" }
            ?.substringAfter('=', "")
            .orEmpty()
        if (data.isEmpty()) return null
        return value
    }
}
