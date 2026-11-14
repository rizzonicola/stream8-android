package cc.poppi.stream8

import android.content.Context

/** File SharedPreferences condiviso da tutte le preferenze dell'app. */
internal const val SETTINGS_FILE = "settings"

/** Impostazioni persistenti. Lettura sicura da qualunque thread (SharedPreferences è thread-safe). */
class Prefs(context: Context) {
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    /** Il messaggio di importazione impostazioni compare solo al primo avvio. */
    var firstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    /** Lingua dell'interfaccia nativa rilevata dal sito (`it`, `en`, `fr`); null = automatica. */
    var uiLanguage: String?
        get() = prefs.getString(KEY_UI_LANGUAGE, null)
        set(value) = prefs.edit().putString(KEY_UI_LANGUAGE, value).apply()

    private companion object {
        const val KEY_FIRST_RUN = "first_run_done"
        const val KEY_UI_LANGUAGE = "ui_language"
    }
}
