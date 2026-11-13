package cc.poppi.stream8.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
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
import androidx.annotation.RequiresApi
import cc.poppi.stream8.BuildConfig
import cc.poppi.stream8.Prefs
import cc.poppi.stream8.adblock.AdBlocker
import cc.poppi.stream8.navigation.LoadErrorKind
import cc.poppi.stream8.navigation.LoadErrors
import cc.poppi.stream8.navigation.NavKind
import cc.poppi.stream8.navigation.NavRequest
import cc.poppi.stream8.navigation.NavigationPolicy
import cc.poppi.stream8.navigation.PolicyDefaults
import cc.poppi.stream8.navigation.Reason
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
    private val adBlocker: AdBlocker?,
    private val prefs: Prefs,
    private val ui: Ui,
) {
    interface Ui {
        fun onLoadStarted()
        fun onProgress(percent: Int)
        fun onLoadFinished()
        fun onLoadError(kind: LoadErrorKind)
        fun onBlocked(url: String, reason: Reason)
        fun onRenderProcessGone()
        fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback)
        fun hideCustomView()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val launcher = ExternalAppLauncher(activity)
    private var lastGoodUrl: String? = null
    private var lastRecoveryAt = 0L

    lateinit var webView: WebView
        private set

    // ------------------------------------------------------------------ ciclo di vita

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(): WebView {
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
        return wv
    }

    fun destroyWebView() {
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    fun loadHome() {
        policy.onHomeRequested()
        webView.loadUrl(PolicyDefaults.HOME_URL)
    }

    fun retry() {
        val current = webView.url
        if (current.isNullOrBlank() || current == "about:blank") loadHome() else webView.reload()
    }

    // ------------------------------------------------------------------ decisione

    /**
     * Applica la policy. Restituisce true se la navigazione è stata gestita (annullare il caricamento
     * originale), false se WebView può procedere.
     */
    private fun handleNavigation(url: String, kind: NavKind, gesture: Boolean, redirect: Boolean): Boolean {
        val verdict = policy.evaluate(NavRequest(url, kind, gesture, redirect))

        if (verdict.mayLaunchApp) {
            when (val result = launcher.launch(url)) {
                ExternalAppLauncher.Result.Launched -> return true
                is ExternalAppLauncher.Result.NoApp -> {
                    // Nessuna app: per un intent:// si prova il fallback, valutato con le stesse regole.
                    val fallback = result.fallbackUrl
                    if (fallback != null) {
                        val v = policy.evaluate(NavRequest(fallback, kind, gesture, false)).copy(mayLaunchApp = false)
                        return applyWebAction(v, fallback, forceLoad = true)
                    }
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
            val blocker = adBlocker
            val host = url.host
            if (blocker != null && host != null && prefs.adblockEnabled && blocker.shouldBlock(host, policy.currentHost)) {
                return blockedResponse()
            }
            return null
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            ui.onLoadStarted()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            ui.onLoadFinished()
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            if (url == null) return
            if (policy.onMainFrameCommitted(url)) {
                if (url != "about:blank") lastGoodUrl = url
            } else {
                recoverFromUnauthorizedPage(url)
            }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request == null || error == null || !request.isForMainFrame) return
            policy.onNavigationFailed()
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
            if (request != null && request.isForMainFrame) {
                capture(request.url.toString())
                return blockedResponse() // nessuna richiesta di rete dalla finestra temporanea
            }
            return blockedResponse()
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (url != null) capture(url)
            view?.stopLoading()
        }
    }

    private fun destroyPopup(temp: WebView) {
        runCatching {
            temp.stopLoading()
            temp.webViewClient = WebViewClient()
            temp.destroy()
        }
    }

    private companion object {
        const val POPUP_TIMEOUT_MS = 15_000L
    }
}
