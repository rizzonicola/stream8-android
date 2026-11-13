package cc.poppi.stream8.navigation

import java.net.URI

/**
 * Accetta solo i link di condivisione impostazioni generati da Stream8
 * (`https://stream8.poppi.cc/import-settings?data=...`). Il payload viene validato
 * dal sito: qui serve solo a impedire che l'app carichi un URL arbitrario.
 */
object ImportLink {
    private const val PATH = "/import-settings"
    private const val MAX_DATA_LENGTH = 20_000

    fun parse(raw: String): String? {
        val value = raw.trim()
        val parsed = UrlParser.parse(value) ?: return null
        if (parsed.scheme != "https" || parsed.host != PolicyDefaults.STREAM8_HOST) return null
        if (parsed.port != null && parsed.port != 443) return null

        val uri = try {
            URI(value)
        } catch (e: Exception) {
            return null
        }
        if (uri.rawUserInfo != null || uri.path != PATH) return null
        if (uri.host == null || !uri.host.equals(parsed.host, ignoreCase = true)) return null

        val data = uri.rawQuery.orEmpty()
            .split('&')
            .firstOrNull { it.substringBefore('=') == "data" }
            ?.substringAfter('=', "")
            .orEmpty()
        if (data.isEmpty() || data.length > MAX_DATA_LENGTH) return null
        return value
    }
}
