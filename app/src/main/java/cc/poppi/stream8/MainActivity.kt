package cc.poppi.stream8

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
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
import cc.poppi.stream8.adblock.AdBlockListUpdater
import cc.poppi.stream8.navigation.ImportLink
import cc.poppi.stream8.navigation.LoadErrorKind
import cc.poppi.stream8.navigation.NavigationPolicy
import cc.poppi.stream8.navigation.PolicyDefaults
import cc.poppi.stream8.navigation.Reason
import cc.poppi.stream8.navigation.UrlParser
import cc.poppi.stream8.navigation.hostMatches
import cc.poppi.stream8.web.WebController
import java.io.IOException

/** Unica schermata: Stream8 a tutto schermo in una WebView. Nessuna barra, nessun elemento superfluo. */
class MainActivity : ComponentActivity(), WebController.Ui {

    private val policy = NavigationPolicy()
    private lateinit var prefs: Prefs
    private lateinit var controller: WebController
    private lateinit var webView: WebView

    private lateinit var webLayer: FrameLayout
    private lateinit var root: FrameLayout
    private lateinit var topBar: View
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var errorView: LinearLayout
    private lateinit var errorText: TextView

    /** Colore del tema della pagina, applicato alle aree delle barre di sistema. */
    private var barColor = Color.BLACK

    /** Altezza dell'area dell'intaglio/barra di stato su cui disegnare la fascia. */
    private var topInset = 0

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
        prefs = Prefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildLayout()
        barColor = getColor(R.color.background)
        topBar.setBackgroundColor(barColor)
        applySystemBarIcons()

        // Dopo la morte del processo: stato di navigazione e domini dei servizi già autorizzati.
        policy.restore(
            savedInstanceState?.getStringArrayList(KEY_VISITED),
            cascade = savedInstanceState?.getBoolean(KEY_CASCADE, false) ?: false,
        )

        val adBlocker = loadAdBlocker()
        controller = WebController(this, policy, adBlocker, prefs, this)
        if (adBlocker != null) AdBlockListUpdater(this, prefs, adBlocker).start()
        attachNewWebView()
        onBackPressedDispatcher.addCallback(this, backCallback)

        val saved = savedInstanceState?.getBundle(KEY_WEB)
        val restored = saved != null && webView.restoreState(saved) != null
        if (!restored) {
            // Al primo avvio si decide prima di caricare la home: se l'utente
            // importa, il link è la prima pagina e non viene sovrascritto dalla
            // navigazione iniziale ancora in corso.
            if (savedInstanceState == null && !prefs.firstRunDone) showImportDialog(firstRun = true)
            else controller.loadHome()
        } else if (savedInstanceState?.getBoolean(KEY_TOP_BAR_VISIBLE, false) == true) {
            // Dopo la morte del processo la pagina esterna viene ripristinata senza
            // un nuovo commit: la fascia deve tornare visibile subito.
            topBar.visibility = View.VISIBLE
            applyContentTopInset()
        }
    }

    // ------------------------------------------------------------------ layout

    private fun buildLayout() {
        root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.background)) }

        webLayer = FrameLayout(this)
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = false
            visibility = View.GONE
        }
        // Fascia superiore dell'intaglio/barra di stato: stesso colore del tema.
        // Fuori da Stream8 occupa l'area dell'inset e la pagina inizia sotto di
        // essa; su Stream8 è nascosta e la pagina usa env(safe-area-inset-top).
        topBar = View(this).apply {
            setBackgroundColor(getColor(R.color.background))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE // su Stream8 la pagina arriva fino in alto; fuori da Stream8 si mostra
        }
        errorView = buildErrorView()
        val settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vert)
            setBackgroundResource(R.drawable.bg_floating_button)
            contentDescription = getString(R.string.cd_settings)
            alpha = 0.35f
            setOnClickListener { showMenu(it) }
        }

        webLayer.addView(topBar, FrameLayout.LayoutParams(MATCH, 0, Gravity.TOP))
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
            // La fascia superiore, quando visibile, è un elemento del layout: la
            // pagina parte sotto di essa (topMargin della WebView). Su Stream8 la
            // fascia è nascosta e la pagina usa env(safe-area-inset-top) del sito.
            // In basso resta il padding nativo per barra di navigazione e tastiera.
            v.setPadding(bars.left, 0, bars.right, maxOf(bars.bottom, ime.bottom))
            topInset = bars.top
            (topBar.layoutParams as FrameLayout.LayoutParams).height = topInset
            topBar.requestLayout()
            applyContentTopInset()
            insets
        }
    }

    /** Sposta la pagina sotto la fascia quando è visibile: non deve mai coprirla. */
    private fun applyContentTopInset() {
        val offset = if (topBar.visibility == View.VISIBLE) topInset else 0
        if (::webView.isInitialized) {
            val params = webView.layoutParams as FrameLayout.LayoutParams
            if (params.topMargin != offset) {
                params.topMargin = offset
                webView.requestLayout()
            }
        }
        val progressParams = progressBar.layoutParams as FrameLayout.LayoutParams
        if (progressParams.topMargin != offset) {
            progressParams.topMargin = offset
            progressBar.requestLayout()
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

    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SETTINGS, 0, R.string.menu_settings)
            menu.add(0, MENU_IMPORT, 1, R.string.menu_import)
            menu.add(0, MENU_INFO, 2, R.string.menu_info)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SETTINGS -> openSettings()
                    MENU_IMPORT -> showImportDialog(firstRun = false)
                    MENU_INFO -> showInfo()
                }
                true
            }
            show()
        }
    }

    /** Link di condivisione delle impostazioni; il sito mostra poi la conferma di importazione. */
    private fun showImportDialog(firstRun: Boolean) {
        val input = EditText(this).apply {
            hint = getString(R.string.import_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.import_title)
            .setMessage(if (firstRun) R.string.import_first_run_message else R.string.import_message)
            .setView(container)
            .setPositiveButton(R.string.import_action, null)
            .setNegativeButton(if (firstRun) R.string.continue_without_import else android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = ImportLink.parse(input.text.toString())
                if (url == null) {
                    input.error = getString(R.string.import_invalid)
                } else {
                    prefs.firstRunDone = true
                    dialog.dismiss()
                    controller.loadImport(url)
                }
            }
            if (firstRun) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                    prefs.firstRunDone = true
                    dialog.dismiss()
                    controller.loadHome()
                }
            }
        }
        // Al primo avvio la scelta è obbligatoria: solo "Importa" o "Continua senza importare".
        dialog.setCancelable(!firstRun)
        dialog.show()
    }

    private fun showInfo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.info_title)
            .setMessage(R.string.info_body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Icone scure sui temi chiari, chiare sui temi scuri. */
    private fun applySystemBarIcons() {
        val darkIcons = Color.luminance(barColor) > 0.5f
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }

    /** Il sito aggiorna `meta[name=theme-color]` a ogni cambio tema: la barra lo segue. */
    override fun onThemeColor(color: Int) {
        if (color == barColor) return
        barColor = color
        root.setBackgroundColor(color)
        topBar.setBackgroundColor(color)
        applySystemBarIcons()
    }

    /** Fascia a tema solo nelle pagine esterne (TMDB, servizi, ecc.), non su Stream8. */
    override fun onMainFrameHost(host: String?) {
        val external = host != null && !hostMatches(host, PolicyDefaults.STREAM8_HOST)
        val target = if (external) View.VISIBLE else View.GONE
        if (topBar.visibility == target) return
        topBar.visibility = target
        applyContentTopInset()
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
        outState.putBoolean(KEY_CASCADE, policy.cascadeEnabled)
        outState.putBoolean(KEY_TOP_BAR_VISIBLE, topBar.visibility == View.VISIBLE)
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
        controller.startThemeSampling()
        applyImmersive()
    }

    override fun onPause() {
        controller.stopThemeSampling()
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
        applySystemBarIcons()
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        const val EXTRA_RELOAD_HOME = "cc.poppi.stream8.RELOAD_HOME"
        private const val KEY_VISITED = "visited_scopes"
        private const val KEY_CASCADE = "cascade_enabled"
        private const val KEY_TOP_BAR_VISIBLE = "top_bar_visible"
        private const val KEY_WEB = "web_state"
        private const val MAX_SAVED_HISTORY = 15
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val MENU_SETTINGS = 1
        private const val MENU_IMPORT = 2
        private const val MENU_INFO = 3
    }
}
