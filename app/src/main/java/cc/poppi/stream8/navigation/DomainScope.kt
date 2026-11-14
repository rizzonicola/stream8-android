package cc.poppi.stream8.navigation

import com.google.common.net.InternetDomainName
import java.util.Locale

/** Confronto per etichette: l'host viene normalizzato, il dominio deve essere già minuscolo. */
internal fun hostMatches(host: String, domain: String): Boolean {
    val h = host.lowercase(Locale.ROOT).trimEnd('.')
    return h == domain || h.endsWith(".$domain")
}

/**
 * Dominio registrabile (eTLD+1 secondo la Public Suffix List incorporata in Guava), oppure null
 * se l'host è un suffisso pubblico (es. `co.uk`), non ha un suffisso noto (es. `localhost`)
 * o non è un nome di dominio valido.
 */
private fun registrableDomain(host: String): String? = try {
    InternetDomainName.from(host).topPrivateDomain().toString()
} catch (e: IllegalArgumentException) {
    null
} catch (e: IllegalStateException) {
    null
}

/** Un dominio registrabile con tutti i suoi sottodomini autentici (confronto per etichette, mai per sottostringa). */
class DomainScope private constructor(val domain: String) {
    fun contains(host: String): Boolean = hostMatches(host, domain)

    companion object {
        fun of(host: String): DomainScope? =
            registrableDomain(host.lowercase(Locale.ROOT).trimEnd('.'))?.let(::DomainScope)
    }
}
