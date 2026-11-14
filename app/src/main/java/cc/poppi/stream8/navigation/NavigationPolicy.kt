package cc.poppi.stream8.navigation

enum class NavKind { MAIN_FRAME, POPUP }

/** Come il livello Android deve gestire l'apertura di un'app esterna per una richiesta. */
enum class AppLaunch {
    /** Nessun tentativo di apertura. */
    NONE,

    /** Apertura immediata: origine fidata (Stream8, TMDB o servizio della cascata TMDB). */
    DIRECT,

    /** Apertura solo dopo una conferma esplicita: servizio fuori dalla cascata TMDB. */
    CONFIRM,
}

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
 * @param appLaunch se [AppLaunch.DIRECT] il livello Android può aprire subito un'app esterna;
 *   se [AppLaunch.CONFIRM] deve prima chiedere conferma all'utente; se [AppLaunch.NONE] non deve
 *   tentare alcuna apertura. In caso di mancata apertura si applica [web].
 */
data class Verdict(
    val web: WebAction,
    val appLaunch: AppLaunch,
    val reason: Reason,
    internal val newService: DomainScope? = null,
) {
    val mayLaunchApp: Boolean get() = appLaunch == AppLaunch.DIRECT
    val confirmAppLaunch: Boolean get() = appLaunch == AppLaunch.CONFIRM
}

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
class NavigationPolicy {
    private enum class Site { STREAM8, TMDB, OTHER }

    /** Da dove parte la richiesta; [Service.scope] è il dominio autorizzato, se noto. */
    private sealed interface Origin {
        data object None : Origin
        data object Stream8 : Origin
        data class Tmdb(val lastService: DomainScope?) : Origin
        data class Service(val scope: DomainScope?) : Origin
    }

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

        val origin = originOf(state, request.redirect)
        // Le app si aprono direttamente da Stream8/TMDB e, nella cascata avviata da TMDB,
        // anche dai servizi raggiunti. Fuori dalla cascata un servizio può solo chiedere
        // conferma: è la valvola per provider non elencati e server Jellyfin.
        val trusted = when (origin) {
            Origin.None -> false
            Origin.Stream8, is Origin.Tmdb -> true
            is Origin.Service -> state.cascade
        }
        // Solo con un click reale: non automatismi, non redirect.
        val direct = trusted && request.userGesture && !request.redirect
        val confirm = origin is Origin.Service && !state.cascade &&
            request.userGesture && !request.redirect

        when (parsed.scheme) {
            "https" -> Unit
            "http" -> return deny(Reason.INSECURE_SCHEME)
            in UrlSyntax.BLOCKED_SCHEMES -> return deny(Reason.FORBIDDEN_SCHEME)
            else -> return when { // intent: e deep link personalizzati
                direct -> Verdict(WebAction.BLOCK, AppLaunch.DIRECT, Reason.EXTERNAL_APP)
                confirm -> Verdict(WebAction.BLOCK, AppLaunch.CONFIRM, Reason.EXTERNAL_APP)
                trusted -> deny(Reason.NO_USER_GESTURE)
                else -> deny(Reason.APP_LAUNCH_FORBIDDEN)
            }
        }

        val host = parsed.host ?: return deny(Reason.MALFORMED_URL)
        val site = siteOf(host)

        return when (origin) {
            Origin.None ->
                if (site == Site.STREAM8 || site == Site.TMDB) allow(Reason.SAME_SITE) else deny(Reason.NO_ORIGIN)

            is Origin.Service -> when {
                site == Site.STREAM8 -> deny(Reason.SERVICE_CANNOT_LEAVE)
                site == Site.TMDB -> allow(Reason.TMDB)
                // Nella cascata anche i link https dello stesso servizio possono aprire la sua app.
                origin.scope?.contains(host) == true ->
                    Verdict(WebAction.LOAD, if (direct) AppLaunch.DIRECT else AppLaunch.NONE, Reason.SAME_SERVICE)
                // Fuori dalla cascata un nuovo dominio resta chiuso nella WebView, ma un click
                // reale può chiedere conferma per aprire l'app che gestisce quel link (provider
                // non elencati, Jellyfin, App Link di app installate).
                !state.cascade -> when {
                    !request.userGesture || request.redirect -> deny(Reason.OUTSIDE_SERVICE)
                    else -> confirmNewService(host)
                }
                // Se TMDB e' gia' nella catena, i servizi possono aprirne altri con un click reale
                // o tramite i redirect della catena (es. JustWatch -> Prime Video).
                !request.userGesture && !request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, if (direct) AppLaunch.DIRECT else AppLaunch.NONE)
            }

            Origin.Stream8 -> when {
                site == Site.STREAM8 -> allow(Reason.SAME_SITE)
                site == Site.TMDB -> allow(Reason.TMDB)
                !request.userGesture || request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, if (direct) AppLaunch.DIRECT else AppLaunch.NONE)
            }

            is Origin.Tmdb -> when {
                site == Site.TMDB -> allow(Reason.TMDB)
                site == Site.STREAM8 -> allow(Reason.SAME_SITE)
                origin.lastService?.contains(host) == true ->
                    Verdict(WebAction.LOAD, if (direct) AppLaunch.DIRECT else AppLaunch.NONE, Reason.SAME_SERVICE)
                // TMDB e' l'origine della cascata: puo' aprire nuovi servizi con un click reale
                // o tramite un redirect del server (es. provider -> JustWatch), e questi altri ancora.
                !request.userGesture && !request.redirect -> deny(Reason.NO_USER_GESTURE)
                else -> openNewService(host, if (direct) AppLaunch.DIRECT else AppLaunch.NONE)
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
        when (siteOf(host)) {
            Site.STREAM8 ->
                // Si torna alla home: la catena ricomincia, TMDB non e' piu' "gia' visto".
                state = s.copy(currentHost = host, lastService = null, pending = null, cascade = false)
            Site.TMDB ->
                // Da qui in poi ogni pagina puo' aprire nuovi domini, ricorsivamente.
                state = s.copy(currentHost = host, pending = null, cascade = true)
            Site.OTHER -> {
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

    fun isHostPermitted(host: String): Boolean = when (siteOf(host)) {
        Site.STREAM8, Site.TMDB -> true
        Site.OTHER -> {
            val scope = DomainScope.of(host) ?: return false
            scope.domain in state.visited
        }
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

    private fun siteOf(host: String): Site = when {
        PolicyDefaults.isStream8(host) -> Site.STREAM8
        PolicyDefaults.isTmdb(host) -> Site.TMDB
        else -> Site.OTHER
    }

    private fun originOf(s: State, redirect: Boolean): Origin {
        if (redirect && s.pending != null) return Origin.Service(s.pending)
        val host = s.currentHost ?: return Origin.None
        return when (siteOf(host)) {
            Site.STREAM8 -> Origin.Stream8
            Site.TMDB -> Origin.Tmdb(s.lastService)
            Site.OTHER -> Origin.Service(DomainScope.of(host) ?: s.lastService)
        }
    }

    private fun openNewService(host: String, appLaunch: AppLaunch): Verdict {
        // IP, nomi senza suffisso noto (localhost) e suffissi pubblici (co.uk) non sono servizi validi.
        val scope = DomainScope.of(host) ?: return deny(Reason.UNSUPPORTED_HOST)
        return Verdict(WebAction.LOAD_NEW_SERVICE, appLaunch, Reason.NEW_SERVICE, scope)
    }

    /**
     * Fuori dalla cascata la WebView non può aprire un nuovo dominio, ma un click reale può
     * chiedere conferma per aprire l'app esterna che gestisce il link. La destinazione deve
     * comunque essere un servizio valido: IP, localhost e suffissi pubblici non possono
     * diventare un invito ad aprire un'app.
     */
    private fun confirmNewService(host: String): Verdict {
        if (DomainScope.of(host) == null) return deny(Reason.UNSUPPORTED_HOST)
        return Verdict(WebAction.BLOCK, AppLaunch.CONFIRM, Reason.EXTERNAL_APP)
    }

    private fun allow(reason: Reason) = Verdict(WebAction.LOAD, AppLaunch.NONE, reason)
    private fun deny(reason: Reason) = Verdict(WebAction.BLOCK, AppLaunch.NONE, reason)
}
