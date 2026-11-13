package cc.poppi.stream8.adblock

import cc.poppi.stream8.navigation.PolicyDefaults
import cc.poppi.stream8.navigation.DomainScope
import java.io.InputStream
import java.util.Locale

/**
 * Filtro leggero basato su una lista di domini (assets/adblock-domains.txt e cache aggiornata).
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
    /** Sostituita in modo atomico dall'aggiornamento in background: mai un insieme a metà. */
    @Volatile
    private var blocked: Set<String> = normalize(domains)

    val size: Int get() = blocked.size

    /** Applica una lista nuova (cache o download); le richieste in corso vedono la vecchia o la nuova. */
    fun update(domains: Collection<String>) {
        blocked = normalize(domains)
    }

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

    private fun normalize(domains: Collection<String>): Set<String> =
        domains.mapNotNull { normalizeDomain(it) }.toHashSet()

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

        /** Limite di sicurezza per una lista scaricata: evita file enormi o corrotti in memoria. */
        const val MAX_DOMAINS = 150_000

        /**
         * Una voce per riga; `#` e `!` iniziano un commento. Accetta il formato hosts (`0.0.0.0 dominio`),
         * i wildcard di OISD (`*.dominio`) e le regole Adblock di solo dominio (`||dominio^`).
         * Le regole con percorso, opzioni o cosmetiche vengono ignorate: non sono blocchi dell'intero host.
         */
        fun parseLine(line: String): String? {
            val clean = line.trim()
            if (clean.isEmpty() || clean.startsWith("#") || clean.startsWith("!") || clean.startsWith("[")) {
                return null
            }
            if (clean.startsWith("@@") || clean.contains("##") || clean.contains("#@#") ||
                clean.contains("#?#") || clean.contains("#$#")
            ) {
                return null
            }
            val withoutComment = clean.substringBefore('#').trim()
            if (withoutComment.isEmpty()) return null
            val token = withoutComment.split(Regex("\\s+")).last()
            if (token.contains('$')) return null // es. ||dominio^$third-party
            return normalizeDomain(
                token.removePrefix("||").removeSuffix("^").removePrefix("*."),
            )
        }

        private fun normalizeDomain(raw: String): String? {
            val d = raw.trim().lowercase(Locale.ROOT).trimEnd('.')
            return d.takeIf { VALID.matches(it) }
        }

        /**
         * Legge una lista senza superare [MAX_DOMAINS] voci. Usata sia per l'asset incorporato
         * sia per la cache scaricata; il chiamante decide se la lista è abbastanza grande.
         */
        fun readDomains(stream: InputStream): List<String> =
            stream.bufferedReader().useLines { lines ->
                lines.mapNotNull { parseLine(it) }.take(MAX_DOMAINS + 1).toList()
            }

        fun fromStream(stream: InputStream): AdBlocker = AdBlocker(readDomains(stream))
    }
}
