package cc.poppi.stream8.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import cc.poppi.stream8.BuildConfig
import cc.poppi.stream8.adblock.AdBlocker
import cc.poppi.stream8.navigation.AppLaunch
import cc.poppi.stream8.navigation.ImportLink
import cc.poppi.stream8.navigation.LoadErrorKind
import cc.poppi.stream8.navigation.LoadErrors
import cc.poppi.stream8.navigation.NavKind
import cc.poppi.stream8.navigation.NavRequest
import cc.poppi.stream8.navigation.NavigationPolicy
import cc.poppi.stream8.navigation.PolicyDefaults
import cc.poppi.stream8.navigation.Reason
import cc.poppi.stream8.navigation.UrlParser
import cc.poppi.stream8.navigation.Verdict
import cc.poppi.stream8.navigation.WebAction
import java.io.ByteArrayInputStream

/**
 * Configura la WebView e applica [NavigationPolicy] su tre livelli indipendenti:
 *  A. shouldOverrideUrlLoading / popup (onCreateWindow): decisione completa, con apertura di app esterne;
 *  B. shouldInterceptRequest sul frame principale: rete di sicurezza per ciò che A non vede (es. POST);
 *  C. doUpdateVisitedHistory: verifica della pagina realmente caricata, con ripristino dell'ultima pagina valida.
 * Le risorse incorporate (immagini, script, XHR, video, iframe) NON sono bloccate dalla whitelist di
 * navigazione: le filtra solo l'adblocker, se attivo.
 */
class WebController(
    private val activity: Activity,
    private val policy: NavigationPolicy,
    private val adBlocker: AdBlocker,
    private val isAdBlockEnabled: () -> Boolean,
    private val ui: Ui,
) {
    interface Ui {
        fun onLoadStarted()
        fun onProgress(percent: Int)
        fun onLoadFinished()
        fun onLoadError(kind: LoadErrorKind)
        fun onBlocked(url: String, reason: Reason)
        /** Chiede conferma prima di aprire un'app esterna. Il callback va invocato esattamente una volta. */
        fun confirmExternalApp(url: String, onDecision: (Boolean) -> Unit)
        fun onThemeColor(color: Int)
        /** Valore grezzo letto dal sito; la risoluzione della lingua spetta all'interfaccia. */
        fun onUiLanguage(language: String?)
        fun onMainFrameHost(host: String?)
        fun onRenderProcessGone()
        fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun hideCustomView()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pageSampler = object : Runnable {
        override fun run() {
            sampleThemeColor()
            sampleUiLanguage()
            mainHandler.postDelayed(this, PAGE_SAMPLE_MS)
        }
    }
    private val launcher = ExternalAppLauncher(activity)
    private var lastGoodUrl: String? = null
    private var lastRecoveryAt = 0L
    private lateinit var webView: WebView
    private var container: FrameLayout? = null

    /** Controllo automatico della pagina nera: un solo tentativo per caricamento. */
    private var pendingBlankCheck: Runnable? = null
    private var recoveryFallback: Runnable? = null
    private var pageGeneration = 0
    private var pageFailed = false

    // ------------------------------------------------------------------ ciclo di vita

    /** Crea la WebView e la inserisce dietro il resto del layout. */
    fun attach(container: FrameLayout) {
        this.container = container
        val wv = createWebView()
        container.addView(wv, 0, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /** Rimuove e distrugge la WebView corrente; [container] resta per un'eventuale ricreazione. */
    fun detach() {
        if (!::webView.isInitialized) return
        stopPageSampling()
        cancelBlankCheck()
        cancelRecoveryFallback()
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    /** Dopo un crash del renderer: nuova WebView e ritorno alla home. */
    fun recover() {
        val target = container ?: return
        detach()
        attach(target)
        loadHome()
    }

    /** Tasto Indietro nella cronologia della WebView; false se non c'è una pagina precedente. */
    fun handleBack(): Boolean {
        if (!::webView.isInitialized || !webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun onHostResume() {
        if (!::webView.isInitialized) return
        webView.onResume()
        startPageSampling()
        scheduleBlankCheck()
    }

    fun onHostPause() {
        if (!::webView.isInitialized) return
        stopPageSampling()
        webView.onPause()
        CookieManager.getInstance().flush() // la sessione sopravvive alla chiusura dell'app
    }

    /** Sposta la pagina sotto la fascia superiore quando è visibile. */
    fun setContentTopInset(offset: Int) {
        if (!::webView.isInitialized) return
        val params = webView.layoutParams as FrameLayout.LayoutParams
        if (params.topMargin == offset) return
        params.topMargin = offset
        webView.requestLayout()
    }

    /** Salva stato della WebView e della navigazione per la morte del processo. */
    fun saveSession(outState: Bundle) {
        outState.putStringArrayList(KEY_VISITED, policy.snapshot())
        outState.putBoolean(KEY_CASCADE, policy.cascadeEnabled)
        if (!::webView.isInitialized) return
        if (webView.copyBackForwardList().size > MAX_SAVED_HISTORY) return
        outState.putBundle(KEY_WEB, Bundle().also { webView.saveState(it) })
    }

    /**
     * Ripristina policy, cronologia e pagina corrente nell'ordine corretto. False se non
     * c'è una pagina ripristinabile o autorizzata: in quel caso il chiamante carica la home.
     */
    fun restoreSession(saved: Bundle?): Boolean {
        policy.restore(
            saved?.getStringArrayList(KEY_VISITED),
            cascade = saved?.getBoolean(KEY_CASCADE, false) == true,
        )
        val webState = saved?.getBundle(KEY_WEB) ?: return false
        if (!::webView.isInitialized || webView.restoreState(webState) == null) return false
        return restorePageFromHistory()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val wv = WebView(activity)
        wv.setBackgroundColor(Color.BLACK)
        with(wv.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // Nessun accesso a file locali né a content provider; nessun bridge JavaScript.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW // HTTPS obbligatorio
            // window.open() solo dopo un gesto reale; i popup sono comunque filtrati in onCreateWindow.
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(wv, true) // necessari ai player incorporati negli iframe
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        webView = wv
        lastGoodUrl = null
        lastRecoveryAt = 0L
        pageGeneration = 0
        pageFailed = false
        cancelBlankCheck()
        cancelRecoveryFallback()
        return wv
    }

    fun loadHome() {
        loadAsRoot(PolicyDefaults.HOME_URL)
    }

    /** Valida e carica un link di importazione Stream8; false se il link non è valido. */
    fun loadImport(url: String): Boolean {
        val target = ImportLink.parse(url) ?: return false
        loadAsRoot(target)
        return true
    }

    private fun loadAsRoot(url: String) {
        policy.onHomeRequested()
        lastGoodUrl = null
        webView.loadUrl(url)
    }

    /**
     * Dopo restoreState la WebView non emette un nuovo commit: riallinea policy e
     * interfaccia alla pagina ripristinata. False se la pagina non è autorizzata.
     */
    private fun restorePageFromHistory(): Boolean {
        val url = webView.copyBackForwardList().currentItem?.url ?: webView.url ?: return false
        if (url == "about:blank" || !policy.onMainFrameCommitted(url)) return false
        lastGoodUrl = url
        ui.onMainFrameHost(UrlParser.parse(url)?.host)
        return true
    }

    // Il tema e la lingua del sito cambiano senza navigazione: si leggono dalla pagina
    // e si applicano dal lato nativo.
    private fun startPageSampling() {
        mainHandler.removeCallbacks(pageSampler)
        mainHandler.post(pageSampler)
    }

    private fun stopPageSampling() {
        mainHandler.removeCallbacks(pageSampler)
    }

    private fun sampleThemeColor() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript(THEME_COLOR_SCRIPT) { value ->
            parseThemeColor(value)?.let { ui.onThemeColor(it) }
        }
    }

    private fun parseThemeColor(value: String?): Int? {
        val raw = jsonString(value)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.startsWith("#")) return runCatching { Color.parseColor(raw) }.getOrNull()
        val match = CSS_RGB.matchEntire(raw) ?: return null
        val r = match.groupValues[1].toInt()
        val g = match.groupValues[2].toInt()
        val b = match.groupValues[3].toInt()
        if (r > 255 || g > 255 || b > 255) return null
        // Un body trasparente non è un colore di tema: meglio lasciare quello attuale.
        val alpha = match.groupValues[4].toDoubleOrNull() ?: 1.0
        if (alpha <= 0.0) return null
        return Color.rgb(r, g, b)
    }

    /** evaluateJavascript restituisce JSON: una stringa arriva tra virgolette. */
    private fun jsonString(value: String?): String? =
        value?.trim()?.removeSurrounding("\"")

    /**
     * Legge la lingua scelta nelle impostazioni del sito. Solo su Stream8: su un
     * dominio esterno `localStorage` appartiene a quel sito e non a Stream8.
     */
    private fun sampleUiLanguage() {
        if (!::webView.isInitialized) return
        val host = policy.currentHost ?: return
        if (!PolicyDefaults.isStream8(host)) return
        webView.evaluateJavascript(LANGUAGE_SCRIPT) { value ->
            ui.onUiLanguage(jsonString(value))
        }
    }

    fun retry() {
        pageFailed = false
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") loadHome() else webView.reload()
    }

    // ------------------------------------------------------ correzioni lato app

    private fun isStream8Url(url: String?): Boolean =
        url != null && UrlParser.parse(url)?.host?.let { PolicyDefaults.isStream8(it) } == true

    private fun cancelBlankCheck() {
        pendingBlankCheck?.let { mainHandler.removeCallbacks(it) }
        pendingBlankCheck = null
    }

    private fun cancelRecoveryFallback() {
        recoveryFallback?.let { mainHandler.removeCallbacks(it) }
        recoveryFallback = null
    }

    /**
     * Dopo il caricamento verifica che React abbia disegnato qualcosa. Se la
     * shell servita dal service worker punta a chunk JS spariti, la pagina
     * resta nera: qui la cache viene ripulita e la pagina ricaricata una volta.
     * Il controllo è solo su Stream8 e non tocca i siti esterni.
     */
    private fun scheduleBlankCheck() {
        cancelBlankCheck()
        if (!::webView.isInitialized || pageFailed) return
        if (!isStream8Url(webView.url)) return
        val check = Runnable {
            pendingBlankCheck = null
            if (!::webView.isInitialized || webView.isLoading || pageFailed) return@Runnable
            if (!isStream8Url(webView.url)) return@Runnable
            webView.evaluateJavascript(BLANK_PAGE_SCRIPT) { value ->
                if (jsonString(value) == "blank" || jsonString(value) == "missing") recoverBlankPage()
            }
        }
        pendingBlankCheck = check
        mainHandler.postDelayed(check, BLANK_CHECK_DELAY_MS)
    }

    private fun recoverBlankPage() {
        if (!::webView.isInitialized) return
        // Un solo tentativo automatico: se anche il reload fallisce, si mostra
        // l'errore nativo con "Riprova" invece di ricaricare all'infinito.
        pageFailed = true
        // Cache HTTP (l'HTML di fallback può essere stato salvato sotto un URL .js).
        webView.clearCache(true)
        val generation = pageGeneration
        webView.evaluateJavascript(RECOVER_SCRIPT) { value ->
            when (jsonString(value)) {
                "started" -> scheduleRecoveryFallback(generation)
                else -> ui.onLoadError(LoadErrorKind.OTHER)
            }
        }
    }

    /** Se lo script non riesce ad avviare il reload, l'errore nativo resta visibile. */
    private fun scheduleRecoveryFallback(generation: Int) {
        cancelRecoveryFallback()
        val fallback = Runnable {
            recoveryFallback = null
            if (pageGeneration == generation) ui.onLoadError(LoadErrorKind.OTHER)
        }
        recoveryFallback = fallback
        mainHandler.postDelayed(fallback, RECOVERY_FALLBACK_MS)
    }

    // ------------------------------------------------------------------ decisione

    /**
     * Applica la policy. Restituisce true se la navigazione è stata gestita (annullare il caricamento
     * originale), false se WebView può procedere.
     */
    private fun handleNavigation(url: String, kind: NavKind, gesture: Boolean, redirect: Boolean): Boolean {
        val verdict = policy.evaluate(NavRequest(url, kind, gesture, redirect))
        return when (verdict.appLaunch) {
            AppLaunch.DIRECT -> launchOrApply(verdict, url, kind, gesture)
            AppLaunch.CONFIRM -> {
                ui.confirmExternalApp(url) { confirmed ->
                    if (confirmed) launchOrApply(verdict, url, kind, gesture)
                }
                true
            }
            AppLaunch.NONE -> applyWebAction(verdict, url, forceLoad = kind == NavKind.POPUP)
        }
    }

    /**
     * Prova ad aprire l'app. Se non esiste, per un intent:// si prova il fallback e in ogni
     * caso si applica il verdetto web (caricamento o blocco).
     */
    private fun launchOrApply(verdict: Verdict, url: String, kind: NavKind, gesture: Boolean): Boolean {
        when (val result = launcher.launch(url)) {
            ExternalAppLauncher.Result.Launched -> return true
            is ExternalAppLauncher.Result.NoApp -> {
                // Nessuna app: per un intent:// si prova il fallback, valutato con le stesse regole.
                val fallback = result.fallbackUrl
                if (fallback != null) {
                    val v = policy.evaluate(NavRequest(fallback, kind, gesture, false))
                        .copy(appLaunch = AppLaunch.NONE)
                    return applyWebAction(v, fallback, forceLoad = true)
                }
            }
        }
        return applyWebAction(verdict, url, forceLoad = kind == NavKind.POPUP)
    }

    private fun applyWebAction(verdict: Verdict, url: String, forceLoad: Boolean): Boolean {
        if (verdict.web == WebAction.BLOCK) {
            ui.onBlocked(url, verdict.reason)
            return true
        }
        policy.accept(verdict)
        if (forceLoad) {
            mainHandler.post { webView.loadUrl(url) }
            return true
        }
        return false
    }

    /** Ripristino dopo una pagina non autorizzata arrivata al frame principale (rete di sicurezza). */
    private fun recoverFromUnauthorizedPage(url: String) {
        val now = SystemClock.uptimeMillis()
        // Evita un ciclo di ripristini se la pagina non autorizzata continua a ricaricarsi.
        if (now - lastRecoveryAt < 1000) return
        lastRecoveryAt = now
        policy.onNavigationFailed()
        ui.onBlocked(url, Reason.OUTSIDE_SERVICE)
        webView.stopLoading()
        webView.loadUrl(lastGoodUrl ?: PolicyDefaults.HOME_URL)
    }

    private fun blockedResponse() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
    )

    // ------------------------------------------------------------------ WebViewClient

    private inner class Client : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (request == null) return true
            val url = request.url.toString()
            // Iframe: caricamenti web consentiti, schemi non web (intent:, deep link) no.
            if (!request.isForMainFrame) return !policy.isSubframeNavigationAllowed(url)
            return handleNavigation(url, NavKind.MAIN_FRAME, request.hasGesture(), request.isRedirect)
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request == null) return null
            val url = request.url
            if (request.isForMainFrame) {
                if (!policy.isMainFrameRequestPermitted(url.toString())) {
                    val blocked = url.toString()
                    mainHandler.post { recoverFromUnauthorizedPage(blocked) }
                    return blockedResponse()
                }
                return null
            }
            val host = url.host
            if (host != null && isAdBlockEnabled() && adBlocker.shouldBlock(host, policy.currentHost)) {
                return blockedResponse()
            }
            return null
        }

        override fun onPageCommitVisible(view: WebView?, url: String?) {
            // Il link "Salta al contenuto" del sito è già nel DOM: correggiamo
            // il CSS prima del primo disegno, così non resta una striscia in alto.
            if (view != null && isStream8Url(url ?: view.url)) {
                view.evaluateJavascript(CLIENT_FIXES_SCRIPT, null)
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            pageGeneration++
            pageFailed = false
            cancelBlankCheck()
            cancelRecoveryFallback()
            ui.onLoadStarted()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            ui.onLoadFinished()
            sampleThemeColor()
            sampleUiLanguage()
            if (isStream8Url(url ?: view?.url)) {
                // Seconda iniezione: copre i casi in cui il commit non è passato.
                view?.evaluateJavascript(CLIENT_FIXES_SCRIPT, null)
                scheduleBlankCheck()
            }
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url == null) return
            if (policy.onMainFrameCommitted(url)) {
                if (url != "about:blank") lastGoodUrl = url
                ui.onMainFrameHost(UrlParser.parse(url)?.host)
            } else {
                recoverFromUnauthorizedPage(url)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request == null || error == null || !request.isForMainFrame) return
            policy.onNavigationFailed()
            pageFailed = true
            cancelBlankCheck()
            ui.onLoadError(LoadErrors.classify(error.errorCode))
        }

        // I certificati non validi non vengono MAI accettati.
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            handler?.cancel()
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            ui.onRenderProcessGone()
            return true
        }
    }

    // ------------------------------------------------------------------ WebChromeClient

    private inner class Chrome : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            ui.onProgress(newProgress)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || callback == null) return
            ui.showCustomView(view, callback)
        }

        override fun onHideCustomView() {
            ui.hideCustomView()
        }

        override fun getDefaultVideoPoster(): Bitmap? =
            Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        // Unica permission concessa: licenze DRM (EME) e solo a pagine autorizzate. Camera, microfono ecc.: negati.
        override fun onPermissionRequest(request: PermissionRequest?) {
            if (request == null) return
            val host = request.origin.host
            val allowed = if (host != null && policy.isHostPermitted(host)) {
                request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }.toTypedArray()
            } else {
                emptyArray()
            }
            if (allowed.isEmpty()) request.deny() else request.grant(allowed)
        }

        /**
         * target="_blank" e window.open(): la nuova finestra non viene mai mostrata. Una WebView temporanea
         * (mai collegata alla UI) serve solo a leggere l'URL richiesto, che passa poi dalla policy come
         * qualunque altra navigazione; se autorizzato si carica nella WebView principale.
         */
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(activity)
            temp.settings.javaScriptEnabled = false
            temp.settings.allowFileAccess = false
            temp.settings.allowContentAccess = false
            temp.webViewClient = PopupClient(temp, isUserGesture)
            transport.webView = temp
            resultMsg.sendToTarget()
            mainHandler.postDelayed({ destroyPopup(temp) }, POPUP_TIMEOUT_MS)
            return true
        }
    }

    private inner class PopupClient(private val temp: WebView, private val gesture: Boolean) : WebViewClient() {
        private var captured = false

        private fun capture(url: String) {
            if (captured || url == "about:blank") return
            captured = true
            mainHandler.post {
                destroyPopup(temp)
                handleNavigation(url, NavKind.POPUP, gesture, redirect = false)
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            request?.let { capture(it.url.toString()) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (request != null && request.isForMainFrame) capture(request.url.toString())
            return blockedResponse()
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) capture(url)
            view?.stopLoading()
        }
    }

    private fun destroyPopup(temp: WebView) {
        try {
            temp.stopLoading()
            temp.webViewClient = WebViewClient()
            temp.destroy()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Pulizia della WebView temporanea non riuscita", e)
        }
    }

    private companion object {
        const val TAG = "WebController"
        const val POPUP_TIMEOUT_MS = 15_000L
        const val PAGE_SAMPLE_MS = 1_000L
        const val MAX_SAVED_HISTORY = 15
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val KEY_VISITED = "visited_scopes"
        const val KEY_CASCADE = "cascade_enabled"
        const val KEY_WEB = "web_state"

        /** Colore calcolato dal browser per il body, es. `rgb(18, 18, 18)` o `rgba(0, 0, 0, 0.5)`. */
        val CSS_RGB = Regex("""rgba?\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})(?:\s*,\s*(0|1|0?\.\d+))?\s*\)""")

        /**
         * Legge il colore del tema che il sito pubblica in `meta[name=theme-color]`
         * (aggiornato a ogni cambio tema); se manca, usa lo sfondo del body.
         * Nessun bridge JavaScript: è una lettura sola andata.
         */
        const val THEME_COLOR_SCRIPT = """(function(){var m=document.querySelector('meta[name="theme-color"]');if(m&&m.content)return m.content;var s=getComputedStyle(document.body);return s?s.backgroundColor:'';})()"""

        /**
         * Legge la lingua salvata dal sito, senza risolverla: `auto`, i valori non
         * supportati e l'assenza dell'impostazione diventano null in [NativeLocale].
         * Nessun bridge JS.
         */
        const val LANGUAGE_SCRIPT = """(function(){try{var s=JSON.parse(localStorage.getItem('stream8_settings_v1')||'{}');return s.language||'';}catch(e){return '';}})()"""

        const val BLANK_CHECK_DELAY_MS = 3_000L
        const val RECOVERY_FALLBACK_MS = 6_000L

        /**
         * Nasconde il link "Salta al contenuto" del sito con la tecnica
         * "visually hidden": nella WebView il vecchio `translateY(-160%)`
         * lasciava una striscia visibile in alto. Resta raggiungibile con Tab.
         * shortcut: si può togliere quando il sito pubblica il proprio fix CSS.
         */
        val CLIENT_FIXES_SCRIPT = """
            (function(){
              if (document.getElementById('stream8-app-fixes')) return;
              var style = document.createElement('style');
              style.id = 'stream8-app-fixes';
              style.textContent = 'a[href="#main"]{position:fixed!important;width:1px!important;height:1px!important;overflow:hidden!important;clip:rect(0 0 0 0)!important;clip-path:inset(50%)!important;white-space:nowrap!important;transform:none!important}a[href="#main"]:focus-visible{width:auto!important;height:auto!important;overflow:visible!important;clip:auto!important;clip-path:none!important}';
              (document.head || document.documentElement).appendChild(style);
            })()
        """.trimIndent()

        /**
         * `blank` = React non ha disegnato nulla (il link "Salta al contenuto"
         * da solo non conta). `missing` = manca addirittura #root.
         */
        val BLANK_PAGE_SCRIPT = """
            (function(){
              var root = document.getElementById('root');
              if (!root) return 'missing';
              var skip = root.querySelector('a[href="#main"]');
              var text = (root.innerText || '').trim();
              if (skip) {
                var skipText = (skip.innerText || '').trim();
                if (skipText) text = text.replace(skipText, '').trim();
              }
              if (text) return 'ok';
              if (root.querySelector('main,header,nav,img,svg,video,canvas,input,button,select,textarea,[role],[aria-label]')) return 'ok';
              return 'blank';
            })()
        """.trimIndent()

        /**
         * Svuota la Cache Storage del sito e disregistra il service worker,
         * poi ricarica. La navigazione ricade così sulla rete invece che sulla
         * vecchia shell. Il timestamp evita più reload automatici ravvicinati.
         * Ritorna subito 'started' (il reload è asincrono), 'skip' se il
         * tentativo è troppo recente o 'error' se il browser non collabora.
         */
        val RECOVER_SCRIPT = """
            (function(){
              try {
                var key = 'stream8_app_recovery_at';
                var last = Number(sessionStorage.getItem(key) || 0);
                if (Date.now() - last < 60000) return 'skip';
                sessionStorage.setItem(key, String(Date.now()));
                function done(){ try { location.reload(); } catch (e) {} }
                try {
                  if (window.caches && caches.keys) {
                    caches.keys().then(function(keys){
                      return Promise.all(keys.map(function(k){ return caches.delete(k); }));
                    }).catch(function(){}).then(function(){
                      if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
                        return navigator.serviceWorker.getRegistrations().then(function(regs){
                          return Promise.all(regs.map(function(r){ return r.unregister(); }));
                        }).catch(function(){});
                      }
                    }).then(done);
                  } else {
                    done();
                  }
                } catch (e) { done(); }
                return 'started';
              } catch (e) { return 'error'; }
            })()
        """.trimIndent()
    }
}
