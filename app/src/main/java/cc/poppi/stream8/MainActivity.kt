package cc.poppi.stream8

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cc.poppi.stream8.adblock.AdBlocker
import cc.poppi.stream8.navigation.LoadErrorKind
import cc.poppi.stream8.navigation.NavigationPolicy
import cc.poppi.stream8.navigation.Reason
import cc.poppi.stream8.navigation.UrlParser
import cc.poppi.stream8.web.WebController
import java.io.IOException

/** Unica schermata: Stream8 a tutto schermo in una WebView. Nessuna barra, nessun elemento superfluo. */
class MainActivity : ComponentActivity(), WebController.Ui {

    private val policy = NavigationPolicy()
    private lateinit var controller: WebController
    private lateinit var webView: WebView

    private lateinit var webLayer: FrameLayout
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var lastToastAt = 0L

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                customView != null -> hideCustomView()
                webView.canGoBack() -> webView.goBack()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildLayout()

        // Dopo la morte del processo: stato di navigazione e domini dei servizi già autorizzati.
        policy.restore(savedInstanceState?.getStringArrayList(KEY_VISITED))

        controller = WebController(this, policy, loadAdBlocker(), Prefs(this), this)
        attachNewWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)

        val saved = savedInstanceState?.getBundle(KEY_WEB)
        if (saved == null || webView.restoreState(saved) == null) controller.loadHome()
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        webLayer = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            visibility = View.GONE
        }
        errorView = buildErrorView()
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vert)
            setBackgroundResource(R.drawable.bg_floating_button)
            contentDescription = getString(R.string.cd_settings)
            alpha = 0.35f
            setOnClickListener { openSettings() }
        }

        webLayer.addView(progressBar, FrameLayout.LayoutParams(MATCH, dp(3), Gravity.TOP))
        webLayer.addView(errorView, FrameLayout.LayoutParams(MATCH, MATCH))
        webLayer.addView(
            settingsButton,
            FrameLayout.LayoutParams(dp(36), dp(36), Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(12), 0, 0, dp(12))
            },
        )

        fullscreenContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        root.addView(webLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(fullscreenContainer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)

        // Barre di sistema, notch e tastiera: il contenuto si adatta (la tastiera non copre i campi di testo).
        ViewCompat.setOnApplyWindowInsetsListener(webLayer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
    }

    private fun buildErrorView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(getColor(R.color.background))
        setPadding(dp(32), dp(32), dp(32), dp(32))
        visibility = View.GONE
        isClickable = true // l'overlay assorbe i tocchi, non arrivano alla pagina sottostante

        errorText = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        addView(errorText)
        addView(
            Button(context).apply {
                setText(R.string.retry)
                setOnClickListener {
                    errorView.visibility = View.GONE // non `visibility`: dentro apply sarebbe il Button
                    controller.retry()
                }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(24)
            },
        )
        addView(Button(context).apply {
            setText(R.string.open_settings)
            setOnClickListener { openSettings() }
        })
    }

    private fun attachNewWebView() {
        webView = controller.createWebView()
        webLayer.addView(webView, 0, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun loadAdBlocker(): AdBlocker? = try {
        assets.open("adblock-domains.txt").use { AdBlocker.fromStream(it) }
    } catch (e: IOException) {
        null // senza lista l'adblocker semplicemente non filtra nulla
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ------------------------------------------------------------------ WebController.Ui

    override fun onLoadStarted() {
        errorView.visibility = View.GONE
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
    }

    override fun onProgress(percent: Int) {
        progressBar.progress = percent
        progressBar.visibility = if (percent >= 100) View.GONE else View.VISIBLE
    }

    override fun onLoadFinished() {
        progressBar.visibility = View.GONE
    }

    override fun onLoadError(kind: LoadErrorKind) {
        errorText.setText(
            when (kind) {
                LoadErrorKind.OFFLINE -> R.string.error_offline
                LoadErrorKind.TIMEOUT -> R.string.error_timeout
                LoadErrorKind.TLS -> R.string.error_tls
                LoadErrorKind.OTHER -> R.string.error_other
            },
        )
        progressBar.visibility = View.GONE
        errorView.visibility = View.VISIBLE
    }

    override fun onBlocked(url: String, reason: Reason) {
        val now = SystemClock.uptimeMillis()
        if (now - lastToastAt < 2500) return // le pagine che tentano reindirizzamenti a raffica non invadono lo schermo
        lastToastAt = now
        val message = when (reason) {
            Reason.EXTERNAL_APP -> getString(R.string.no_app)
            Reason.APP_LAUNCH_FORBIDDEN -> getString(R.string.blocked_app)
            else -> getString(R.string.blocked_navigation, UrlParser.parse(url)?.host ?: "")
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRenderProcessGone() {
        runOnUiThread {
            controller.destroyWebView()
            attachNewWebView()
            controller.loadHome()
        }
    }

    override fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        fullscreenContainer.visibility = View.VISIBLE
        webLayer.visibility = View.INVISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun hideCustomView() {
        val view = customView ?: return
        customView = null
        fullscreenContainer.removeView(view)
        fullscreenContainer.visibility = View.GONE
        webLayer.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ------------------------------------------------------------------ ciclo di vita

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RELOAD_HOME, false)) controller.loadHome()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(KEY_VISITED, policy.snapshot())
        // Con cronologie molto lunghe lo stato supererebbe il limite delle transazioni Binder: non si salva.
        if (webView.copyBackForwardList().size <= MAX_SAVED_HISTORY) {
            val webState = Bundle()
            webView.saveState(webState)
            outState.putBundle(KEY_WEB, webState)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        applyImmersive()
    }

    override fun onPause() {
        webView.onPause()
        android.webkit.CookieManager.getInstance().flush() // la sessione sopravvive alla chiusura dell'app
        super.onPause()
    }

    override fun onDestroy() {
        hideCustomView()
        controller.destroyWebView()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    /** Tasto Menu (telecomandi Android TV, tastiere): apre le Impostazioni. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun applyImmersive() {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_RELOAD_HOME = "cc.poppi.stream8.RELOAD_HOME"
        private const val KEY_VISITED = "visited_scopes"
        private const val KEY_WEB = "web_state"
        private const val MAX_SAVED_HISTORY = 15
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
