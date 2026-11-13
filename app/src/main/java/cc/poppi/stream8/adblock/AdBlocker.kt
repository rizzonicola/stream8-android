package cc.poppi.stream8.adblock

import cc.poppi.stream8.navigation.PolicyDefaults
import cc.poppi.stream8.navigation.DomainScope
import java.io.InputStream
import java.util.Locale

/**
 * Filtro leggero basato su una lista di domini (assets/adblock-domains.txt).
 *
 * Regole di sicurezza, in ordine:
 *  1. Stream8 e TMDB (incluso image.tmdb.org) non vengono mai bloccati, anche se finissero nella lista.
 *  2. Le richieste first-party (stesso dominio registrabile della pagina) non vengono mai bloccate.
 *  3. Si blocca solo se l'host, o uno dei suoi domini padre, è nella lista (confronto per etichette).
 *
 * Il chiamante lo applica solo a risorse e iframe, mai alla navigazione del frame principale.
 * La lista contiene soltanto domini pubblicitari/tracker, non CDN né host di API o video.
 */
class AdBlocker(domains: Collection<String>) {
    private val blocked: Set<String> = domains.mapNotNull { normalize(it) }.toHashSet()

    val size: Int get() = blocked.size

    fun shouldBlock(requestHost: String, pageHost: String?): Boolean {
        val host = requestHost.lowercase(Locale.ROOT).trimEnd('.')
        if (host.isEmpty() || isProtected(host)) return false
        if (pageHost != null && isSameSite(host, pageHost)) return false

        var candidate = host
        while (true) {
            if (candidate in blocked) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun isProtected(host: String): Boolean =
        host == PolicyDefaults.STREAM8_HOST || host.endsWith(".${PolicyDefaults.STREAM8_HOST}") ||
            host == PolicyDefaults.TMDB_DOMAIN || host.endsWith(".${PolicyDefaults.TMDB_DOMAIN}") ||
            host == PolicyDefaults.TMDB_IMAGE_HOST

    private fun isSameSite(host: String, pageHost: String): Boolean {
        val page = pageHost.lowercase(Locale.ROOT)
        if (host == page) return true
        val a = DomainScope.of(host)?.domain ?: return false
        val b = DomainScope.of(page)?.domain ?: return false
        return a == b
    }

    companion object {
        private val VALID = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        /** Una voce per riga; `#` inizia un commento; accetta anche il formato hosts (`0.0.0.0 dominio`). */
        fun parseLine(line: String): String? {
            val clean = line.substringBefore('#').trim()
            if (clean.isEmpty()) return null
            return normalize(clean.split(Regex("\\s+")).last())
        }

        private fun normalize(raw: String): String? {
            val d = raw.trim().lowercase(Locale.ROOT).trimEnd('.')
            return d.takeIf { VALID.matches(it) }
        }

        fun fromStream(stream: InputStream): AdBlocker =
            AdBlocker(stream.bufferedReader().useLines { lines -> lines.mapNotNull { parseLine(it) }.toList() })
    }
}
