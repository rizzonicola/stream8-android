package cc.poppi.stream8.navigation

/**
 * Etichetta breve per la destinazione di un link che può aprire un'app esterna.
 * Serve solo al testo della conferma: non autorizza nulla e non modifica la policy.
 */
object AppLaunchTarget {
    fun label(url: String): String {
        val parsed = UrlParser.parse(url) ?: return url.take(MAX_LABEL)
        if (parsed.scheme != "intent") return parsed.host ?: parsed.scheme
        val intent = IntentUrlParser.parse(url)
        val host = intent?.dataUri?.let { UrlParser.parse(it)?.host }
        return host ?: intent?.packageName ?: parsed.scheme
    }

    private const val MAX_LABEL = 80
}
