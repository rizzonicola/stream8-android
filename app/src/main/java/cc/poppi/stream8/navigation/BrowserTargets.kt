package cc.poppi.stream8.navigation

/**
 * Browser che l'app non deve mai aprire: né tramite deep link (`googlechrome://…`) né tramite
 * package esplicito in un `intent://`. Un link https gestito solo da un browser non deve mai
 * diventare una navigazione esterna.
 */
internal object BrowserTargets {
    val SCHEMES = setOf(
        "googlechrome", "googlechromes", "firefox", "firefox-focus", "firefox-klar",
        "microsoft-edge", "microsoft-edge-http", "microsoft-edge-https",
        "opera-http", "opera-https", "brave",
    )

    val PACKAGES = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary", "org.chromium.chrome",
        "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
        "com.brave.browser", "com.microsoft.emmx", "com.opera.browser", "com.opera.mini.native",
        "com.sec.android.app.sbrowser", "com.android.browser", "com.duckduckgo.mobile.android",
        "com.vivaldi.browser", "com.kiwibrowser.browser", "org.chromium.webview_shell",
    )
}
