package cc.poppi.stream8.navigation

import java.util.Locale

object PolicyDefaults {
    const val HOME_URL = "https://stream8.poppi.cc/"
    const val STREAM8_HOST = "stream8.poppi.cc"
    const val TMDB_DOMAIN = "themoviedb.org"

    /** Solo risorse (immagini): non è una destinazione di navigazione. */
    const val TMDB_IMAGE_HOST = "image.tmdb.org"
}

enum class NavKind { MAIN_FRAME, POPUP }

enum class WebAction {
    BLOCK,

    /** Caricamento interno nella WebView, senza cambiare il servizio. */
    LOAD,

    /** Stream8 apre un nuovo servizio: il dominio autorizzato diventa quello della destinazione. */
    LOAD_NEW_SERVICE,
}

enum class Reason {
    SAME_SITE, TMDB, SAME_SERVICE, NEW_SERVICE, EXTERNAL_APP,
    MALFORMED_URL, INSECURE_SCHEME, FORBIDDEN_SCHEME, UNSUPPORTED_HOST,
    NO_USER_GESTURE, POPUP_WITHOUT_GESTURE, OUTSIDE_SERVICE, SERVICE_CANNOT_LEAVE,
    APP_LAUNCH_FORBIDDEN, NO_ORIGIN,
}

/**
 * Una richiesta di navigazione.
 * @param userGesture true solo se WebView riporta un gesto reale dell'utente (hasGesture / isUserGesture).
 * @param redirect true per i salti intermedi di un redirect del server.
 */
data class NavRequest(
    val url: String,
    val kind: NavKind,
    val userGesture: Boolean,
    val redirect: Boolean = false,
)

/**
 * Esito della valutazione.
 * @param mayLaunchApp se true il livello Android può PROVARE ad aprire un'app esterna per questo URL
 *   (solo da Stream8/TMDB, con gesto reale). Se non esiste un'app compatibile si applica [web].
 */
data class Verdict(
    val web: WebAction,
    val mayLaunchApp: Boolean,
    val reason: Reason,
    internal val newService: DomainScope? = null,
)

/**
 * Policy di navigazione. Pura logica JVM, nessuna dipendenza Android: è il punto unico di decisione
 * ed è interamente coperta da test unitari.
 *
 * L'origine di una navigazione è SEMPRE la pagina principale effettivamente caricata ([onMainFrameCommitted]),
 * mai il dominio dichiarato dal link o dati passati dal JavaScript della pagina.
 *
 * Scrittura solo dal thread UI; lettura (shouldInterceptRequest) da qualsiasi thread: lo stato è
 * immutabile e sostituito atomicamente.
 */
class NavigationPolicy(
    private val stream8Host: String = PolicyDefaults.STREAM8_HOST,
    private val tmdbDomain: String = PolicyDefaults.TMDB_DOMAIN,
) {
    private enum class Origin { NONE, STREAM8, TMDB, SERVICE }

    private class Context(val origin: Origin, val service: DomainScope?)

    private data class State(
        /** Host della pagina principale attualmente caricata. */
        val currentHost: String? = null,
        /** Servizio aperto più di recente (null finché si è su Stream8). Resta valido navigando su TMDB. */
        val lastService: DomainScope? = null,
        /** Servizio appena autorizzato da Stream8 e non ancora caricato: serve ai redirect interni. */
        val pending: DomainScope? = null,
        /** Domini dei servizi autorizzati in questa sessione: servono a Indietro/Avanti nella cronologia. */
        val visited: Set<String> = emptySet(),
        /** Una volta visitato TMDB, la navigazione può proseguire a cascata su nuovi domini. */
        val cascade: Boolean = false,
    )

    @Volatile
    private var state = State()

    val currentHost: String? get() = state.currentHost
    val cascadeEnabled: Boolean get() = state.cascade

    // ---------------------------------------------------------------- decisione

    fun evaluate(request: NavRequest): Verdict {
        val parsed = UrlParser.parse(request.url) ?: return deny(Reason.MALFORMED_URL)
        if (request.kind == NavKind.POPUP && !request.userGesture) return deny(Reason.POPUP_WITHOUT_GESTURE)

        val ctx = contextOf(state, request.redirect)
        val trusted = ctx.origin == Origin.STREAM8 || ctx.origin == Origin.TMDB
        // Solo Stream8/TMDB, solo con un click reale (non automatismi, non redirect).
        val mayLaunch = trusted && request.userGesture && !request.redirect

        when (parsed.scheme) {
            "https" -> Unit
            "http" -> return deny(Reason.INSECURE_SCHEME)
            in BLOCKED_SCHEMES -> return deny(Reason.FORBIDDEN_SCHEME)
            else -> return when { // intent: e deep link personalizzati
                mayLaunch -> Verdict(WebAction.BLOCK, true, Reason.EXTERNAL_APP)
                trusted -> deny(Reason.NO_USER_GESTURE)
                else -> deny(Reason.APP_LAUNCH_FORBIDDEN)
            }
        }

        val host = parsed.host ?: return deny(Reason.MALFORMED_URL)
        val toStream8 = hostMatches(host, stream8Host)
        val toTmdb = hostMatches(host, tmdbDomain)

        return when (ctx.origin) {
            Origin.NONE ->
                if (toStream8 || toTmdb) allow(Reason.SAME_SITE) else deny(Reason.NO_ORIGIN)

            Origin.SERVICE -> when {
                toStream8 -> deny(Reason.SERVICE_CANNOT_LEAVE)
                toTmdb -> allow(Reason.TMDB)
                ctx.service?.contains(host) == true -> allow(Reason.SAME_SERVICE)
                // Se TMDB e' gia' nella catena, i servizi possono aprirne altri con un click reale
                // o tramite i redirect della catena (es. JustWatch -> Prime Video).
                !state.cascade -> deny(Reason.OUTSIDE_SERVICE)
                !request.userGesture && !request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, mayLaunch)
            }

            Origin.STREAM8 -> when {
                toStream8 -> allow(Reason.SAME_SITE)
                toTmdb -> allow(Reason.TMDB)
                !request.userGesture || request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, mayLaunch)
            }

            Origin.TMDB -> when {
                toTmdb -> allow(Reason.TMDB)
                toStream8 -> allow(Reason.SAME_SITE)
                ctx.service?.contains(host) == true -> Verdict(WebAction.LOAD, mayLaunch, Reason.SAME_SERVICE)
                // TMDB e' l'origine della cascata: puo' aprire nuovi servizi con un click reale
                // o tramite un redirect del server (es. provider -> JustWatch), e questi altri ancora.
                !request.userGesture && !request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, mayLaunch)
            }
        }
    }

    /** Da chiamare quando il livello Android decide di far caricare la destinazione. */
    fun accept(verdict: Verdict) {
        val scope = verdict.newService ?: return
        val s = state
        state = s.copy(pending = scope, visited = s.visited + scope.domain)
    }

    // ---------------------------------------------------------------- pagina caricata

    /**
     * Registra la pagina principale effettivamente caricata. Restituisce false se la pagina non è
     * autorizzata (ultima rete di sicurezza: il chiamante deve ripristinare l'ultima pagina valida).
     */
    fun onMainFrameCommitted(url: String): Boolean {
        if (url == "about:blank") return true
        val parsed = UrlParser.parse(url) ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host ?: return false
        val s = state
        when {
            hostMatches(host, stream8Host) ->
                // Si torna alla home: la catena ricomincia, TMDB non e' piu' "gia' visto".
                state = s.copy(currentHost = host, lastService = null, pending = null, cascade = false)
            hostMatches(host, tmdbDomain) ->
                // Da qui in poi ogni pagina puo' aprire nuovi domini, ricorsivamente.
                state = s.copy(currentHost = host, pending = null, cascade = true)
            else -> {
                val scope = DomainScope.of(host) ?: return false
                // In cascata un redirect puo' portare a un dominio non ancora registrato
                // (catene JustWatch/Prime Video): il caricamento e' gia' stato autorizzato
                // da evaluate/isMainFrameRequestPermitted, qui si registra soltanto.
                if (scope.domain !in s.visited && !s.cascade) return false
                state = s.copy(
                    currentHost = host,
                    lastService = scope,
                    pending = null,
                    visited = if (scope.domain in s.visited) s.visited else s.visited + scope.domain,
                )
            }
        }
        return true
    }

    /** Un caricamento è fallito: un'eventuale autorizzazione pendente non vale più per i redirect. */
    fun onNavigationFailed() {
        state = state.copy(pending = null)
    }

    /** L'app sta caricando Stream8 (avvio o "Ricarica Stream8"). */
    fun onHomeRequested() {
        state = state.copy(currentHost = null, lastService = null, pending = null, cascade = false)
    }

    // ---------------------------------------------------------------- controlli di sicurezza

    fun isHostPermitted(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        if (hostMatches(h, stream8Host) || hostMatches(h, tmdbDomain)) return true
        val scope = DomainScope.of(h) ?: return false
        return scope.domain in state.visited
    }

    /** Rete di sicurezza per le richieste del frame principale non passate da shouldOverrideUrlLoading (es. POST). */
    fun isMainFrameRequestPermitted(url: String): Boolean {
        val parsed = UrlParser.parse(url) ?: return false
        if (parsed.scheme != "https") return false
        val host = parsed.host ?: return false
        // Con la cascata attiva il frame principale puo' attraversare piu' domini tramite
        // redirect che non passano da shouldOverrideUrlLoading.
        return state.cascade || isHostPermitted(host)
    }

    /**
     * Gli iframe possono caricare contenuti di terze parti (player, CDN): non sono una via di fuga perché
     * ogni navigazione del frame principale e ogni popup passano comunque da [evaluate].
     * Qui si vietano solo schemi non web (intent:, deep link, javascript:, data:) e http in chiaro.
     */
    fun isSubframeNavigationAllowed(url: String): Boolean {
        if (url == "about:blank" || url == "about:srcdoc") return true
        val parsed = UrlParser.parse(url) ?: return false
        return (parsed.scheme == "https" && parsed.host != null) || parsed.scheme == "blob"
    }

    // ---------------------------------------------------------------- stato salvato

    fun snapshot(): ArrayList<String> = ArrayList(state.visited)

    fun restore(visited: Collection<String>?, cascade: Boolean = false) {
        // Si accettano solo domini registrabili validi: uno stato manomesso non può autorizzare altro.
        val valid = visited.orEmpty().filter { DomainScope.of(it)?.domain == it }.toSet()
        state = state.copy(visited = valid, cascade = cascade)
    }

    // ---------------------------------------------------------------- interni

    private fun contextOf(s: State, redirect: Boolean): Context {
        if (redirect && s.pending != null) return Context(Origin.SERVICE, s.pending)
        val host = s.currentHost ?: return Context(Origin.NONE, null)
        return when {
            hostMatches(host, stream8Host) -> Context(Origin.STREAM8, null)
            hostMatches(host, tmdbDomain) -> Context(Origin.TMDB, s.lastService)
            else -> Context(Origin.SERVICE, DomainScope.of(host) ?: s.lastService)
        }
    }

    private fun openNewService(host: String, mayLaunch: Boolean): Verdict {
        // IP, nomi senza suffisso noto (localhost) e suffissi pubblici (co.uk) non sono servizi validi.
        val scope = DomainScope.of(host) ?: return deny(Reason.UNSUPPORTED_HOST)
        return Verdict(WebAction.LOAD_NEW_SERVICE, mayLaunch, Reason.NEW_SERVICE, scope)
    }

    private fun allow(reason: Reason) = Verdict(WebAction.LOAD, false, reason)
    private fun deny(reason: Reason) = Verdict(WebAction.BLOCK, false, reason)

    private companion object {
        val BLOCKED_SCHEMES = setOf(
            // schemi pericolosi o non applicativi
            "javascript", "data", "file", "content", "blob", "about", "view-source", "filesystem", "ftp", "ws", "wss",
            // deep link verso browser esterni: l'app non deve mai delegare la navigazione a un browser
            "googlechrome", "googlechromes", "firefox", "firefox-focus", "firefox-klar",
            "microsoft-edge", "microsoft-edge-http", "microsoft-edge-https",
            "opera-http", "opera-https", "brave",
        )
    }
}
