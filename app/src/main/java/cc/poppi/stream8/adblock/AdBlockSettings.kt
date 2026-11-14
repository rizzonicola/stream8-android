package cc.poppi.stream8.adblock

import android.content.Context
import cc.poppi.stream8.SETTINGS_FILE

/**
 * Stato persistente dell'adblocker. Usa lo stesso file delle preferenze generali
 * e le stesse chiavi già in uso, così un aggiornamento non perde le impostazioni.
 */
class AdBlockSettings(context: Context) {
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(SETTINGS_FILE, Context.MODE_PRIVATE)

    /** Adblocker attivo di default; si può disattivare completamente dalle Impostazioni. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Ultimo aggiornamento riuscito della lista (0 = mai). */
    var lastUpdate: Long
        get() = prefs.getLong(KEY_LAST_UPDATE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE, value).apply()

    /** Ultimo tentativo di aggiornamento, riuscito o no: evita retry a raffica. */
    var lastAttempt: Long
        get() = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ATTEMPT, value).apply()

    /** ETag dell'ultima lista scaricata, per non riscaricarla se non è cambiata. */
    var etag: String?
        get() = prefs.getString(KEY_ETAG, null)
        set(value) = prefs.edit().putString(KEY_ETAG, value).apply()

    companion object {
        /** Cadenza del controllo di aggiornamento della lista. */
        const val REFRESH_DAYS = 7

        private const val KEY_ENABLED = "adblock_enabled"
        const val KEY_LAST_UPDATE = "adblock_last_update"
        const val KEY_LAST_ATTEMPT = "adblock_last_attempt"
        const val KEY_ETAG = "adblock_etag"
    }
}
