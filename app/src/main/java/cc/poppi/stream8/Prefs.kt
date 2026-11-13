package cc.poppi.stream8

import android.content.Context

/** Impostazioni persistenti. Lettura sicura da qualunque thread (SharedPreferences è thread-safe). */
class Prefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Adblocker attivo di default; si può disattivare completamente dalle Impostazioni. */
    var adblockEnabled: Boolean
        get() = prefs.getBoolean(KEY_ADBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_ADBLOCK, value).apply()

    /** Il messaggio di importazione impostazioni compare solo al primo avvio. */
    var firstRunDone: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, false)
        set(value) = prefs.edit().putBoolean(KEY_FIRST_RUN, value).apply()

    private companion object {
        const val KEY_ADBLOCK = "adblock_enabled"
        const val KEY_FIRST_RUN = "first_run_done"
    }
}
