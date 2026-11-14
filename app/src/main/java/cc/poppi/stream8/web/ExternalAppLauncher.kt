package cc.poppi.stream8.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import cc.poppi.stream8.navigation.BrowserTargets
import cc.poppi.stream8.navigation.IntentUrlParser
import cc.poppi.stream8.navigation.UrlParser

/**
 * Apre app Android esterne. Viene chiamato quando la policy restituisce AppLaunch.DIRECT
 * (pagina Stream8/TMDB o servizio della cascata, con click reale) oppure AppLaunch.CONFIRM,
 * ma solo dopo che l'utente ha accettato la conferma.
 *
 * Non apre mai un browser e non mostra selettori di app:
 *  - https: solo app che gestiscono quel link come App Link (non browser);
 *  - intent://: l'Intent viene ricostruito da zero (VIEW + BROWSABLE, nessun componente, selector o extra);
 *  - deep link personalizzati: VIEW + BROWSABLE.
 */
class ExternalAppLauncher(private val activity: Activity) {

    sealed interface Result {
        data object Launched : Result
        data class NoApp(val fallbackUrl: String?) : Result
    }

    fun launch(url: String): Result {
        val scheme = UrlParser.parse(url)?.scheme ?: return Result.NoApp(null)
        return when (scheme) {
            "https" -> if (openAppLink(url)) Result.Launched else Result.NoApp(null)
            "intent" -> launchFromIntentUrl(url)
            else -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                if (launchExternal(intent)) Result.Launched else Result.NoApp(null)
            }
        }
    }

    private fun launchFromIntentUrl(url: String): Result {
        val target = IntentUrlParser.parse(url) ?: return Result.NoApp(null)
        val launched = if (target.packageName != null) {
            // Il package è scelto dalla pagina: non basta la lista statica dei browser noti.
            if (isBrowserPackage(target.packageName)) {
                false
            } else {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.dataUri))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setPackage(target.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
                }
                start(intent)
            }
        } else if (target.scheme == "https") {
            openAppLink(target.dataUri)
        } else {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.dataUri)).addCategory(Intent.CATEGORY_BROWSABLE)
            launchExternal(intent)
        }
        return if (launched) Result.Launched else Result.NoApp(target.fallbackUrl)
    }

    /** Apre l'URL https solo con un'app non-browser (App Link). Restituisce false se non esiste. */
    private fun openAppLink(url: String): Boolean =
        launchExternal(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

    /**
     * Avvia un Intent esterno scartando i browser. Per http/https usa il flag di sistema
     * (Android 11+) o la lista dei gestori; per gli altri schemi sceglie un gestore visibile
     * non-browser, perché Android non applica il flag ai web intent.
     */
    private fun launchExternal(intent: Intent): Boolean {
        val scheme = intent.data?.scheme
        val isWeb = scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)
        return if (isWeb) launchWebIntent(intent) else launchCustomScheme(intent)
    }

    private fun launchWebIntent(intent: Intent): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            start(intent)
        } else {
            val pkg = findNonBrowserHandler(intent) ?: return false
            intent.setPackage(pkg)
            start(intent)
        }
    }

    /**
     * Android non applica REQUIRE_NON_BROWSER agli schemi non web. Si sceglie un gestore
     * visibile non-browser; se la package visibility nasconde tutti i gestori, non c'è
     * modo di sapere se siano browser e si tenta comunque l'avvio.
     */
    private fun launchCustomScheme(intent: Intent): Boolean {
        val handlers = activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .filter { it != activity.packageName }
            .distinct()
        if (handlers.isEmpty()) return start(intent)
        val nonBrowser = handlers.firstOrNull { !isBrowserPackage(it) } ?: return false
        intent.setPackage(nonBrowser)
        return start(intent)
    }

    /** Un browser è un'app che gestisce un URL https generico; la lista statica copre i casi non visibili. */
    private fun isBrowserPackage(packageName: String): Boolean =
        packageName in BrowserTargets.PACKAGES || packageName in browserPackages()

    @Suppress("DEPRECATION")
    /** Il probe deve restare allineato al blocco <queries> del manifest (Android 11+). */
    private fun browserPackages(): Set<String> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        return activity.packageManager.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /** Sotto Android 11: i gestori del link che non gestiscono anche un URL generico non sono browser. */
    private fun findNonBrowserHandler(intent: Intent): String? {
        val browsers = browserPackages()
        return activity.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != activity.packageName && it !in BrowserTargets.PACKAGES && it !in browsers }
    }

    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}
