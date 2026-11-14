package cc.poppi.stream8

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Lingua dell'interfaccia nativa.
 *
 * Il sito salva la scelta in localStorage (`stream8_settings_v1`) e risolve
 * `auto` con `navigator.language`, con fallback inglese. L'app legge quel
 * valore e lo applica alle sole schermate native: la WebView resta nella
 * lingua di sistema, così `auto` continua a funzionare come sul sito.
 */
object NativeLocale {
    private const val FALLBACK = "en"

    // Aggiungere una lingua qui e in app/src/main/res/values-<lang>.
    private val SUPPORTED = setOf("it", "en", "fr")

    /** Restituisce `it`, `en` o `fr`; null per valori assenti o non supportati. */
    fun normalize(tag: String?): String? {
        val language = tag
            ?.trim()
            ?.replace('_', '-')
            ?.substringBefore('-')
            ?.lowercase(Locale.ROOT)
            ?: return null
        return language.takeIf { it in SUPPORTED }
    }

    /** Lingua salvata dal sito, altrimenti quella di sistema (fallback inglese). */
    fun current(context: Context): String =
        normalize(Prefs(context).uiLanguage) ?: systemDefault(context)

    private fun systemDefault(context: Context): String =
        normalize(context.resources.configuration.locales[0].language) ?: FALLBACK

    /** Contesto con le risorse nella lingua corrente, per testi e dialoghi nativi. */
    fun localized(context: Context): Context {
        val locale = Locale.forLanguageTag(current(context))
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }
}
