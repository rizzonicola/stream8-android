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

    /** Ultimo aggiornamento riuscito della lista adblock (0 = mai). */
    var adblockLastUpdate: Long
        get() = prefs.getLong(KEY_ADBLOCK_UPDATE, 0L)
        set(value) = prefs.edit().putLong(KEY_ADBLOCK_UPDATE, value).apply()

    /** Ultimo tentativo di aggiornamento, riuscito o no: evita retry a raffica. */
    var adblockLastAttempt: Long
        get() = prefs.getLong(KEY_ADBLOCK_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_ADBLOCK_ATTEMPT, value).apply()

    /** ETag dell'ultima lista scaricata, per non riscaricarla se non è cambiata. */
    var adblockEtag: String?
        get() = prefs.getString(KEY_ADBLOCK_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ADBLOCK_ETAG, value).apply()

    private companion object {
        const val KEY_ADBLOCK = "adblock_enabled"
        const val KEY_FIRST_RUN = "first_run_done"
        const val KEY_ADBLOCK_UPDATE = "adblock_last_update"
        const val KEY_ADBLOCK_ATTEMPT = "adblock_last_attempt"
        const val KEY_ADBLOCK_ETAG = "adblock_etag"
    }
}
