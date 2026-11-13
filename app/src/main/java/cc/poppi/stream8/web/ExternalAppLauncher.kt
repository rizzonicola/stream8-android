package cc.poppi.stream8.web

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import cc.poppi.stream8.navigation.IntentUrlParser
import java.util.Locale

/**
 * Apre app Android esterne. Viene chiamato SOLO se la policy ha dato mayLaunchApp = true
 * (pagina Stream8/TMDB + click reale dell'utente).
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
        val scheme = url.substringBefore(':').lowercase(Locale.ROOT)
        return when (scheme) {
            "https" -> if (openAppLink(url)) Result.Launched else Result.NoApp(null)
            "intent" -> launchFromIntentUrl(url)
            else -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                if (start(intent)) Result.Launched else Result.NoApp(null)
            }
        }
    }

    private fun launchFromIntentUrl(url: String): Result {
        val target = IntentUrlParser.parse(url) ?: return Result.NoApp(null)
        val launched = if (target.packageName != null) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.dataUri))
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(target.packageName)
            start(intent)
        } else if (target.scheme == "https") {
            openAppLink(target.dataUri)
        } else {
            start(Intent(Intent.ACTION_VIEW, Uri.parse(target.dataUri)).addCategory(Intent.CATEGORY_BROWSABLE))
        }
        return if (launched) Result.Launched else Result.NoApp(target.fallbackUrl)
    }

    /** Apre l'URL https solo con un'app non-browser (App Link). Restituisce false se non esiste. */
    private fun openAppLink(url: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: fallisce con ActivityNotFoundException se il link è gestito solo da browser.
            intent.addFlags(Intent.FLAG_ACTIVITY_REQUIRE_NON_BROWSER)
            start(intent)
        } else {
            val pkg = findNonBrowserHandler(intent) ?: return false
            intent.setPackage(pkg)
            start(intent)
        }
    }

    /** Android 7-10: i gestori del link che non gestiscono anche un URL generico non sono browser. */
    @Suppress("DEPRECATION")
    private fun findNonBrowserHandler(intent: Intent): String? {
        val pm = activity.packageManager
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val browsers = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.toSet()
        return pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it !in browsers && it != activity.packageName }
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
