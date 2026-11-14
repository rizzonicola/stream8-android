package cc.poppi.stream8.navigation

/**
 * Valori condivisi da policy, adblocker e interfaccia. Se cambia il sito, si cambia solo qui.
 */
object PolicyDefaults {
    const val STREAM8_HOST = "stream8.poppi.cc"
    const val HOME_URL = "https://" + STREAM8_HOST + "/"
    const val TMDB_DOMAIN = "themoviedb.org"

    /** Solo risorse (immagini): `tmdb.org` non è un sottodominio di `themoviedb.org`. */
    const val TMDB_IMAGE_HOST = "image.tmdb.org"

    fun isStream8(host: String): Boolean = hostMatches(host, STREAM8_HOST)

    fun isTmdb(host: String): Boolean = hostMatches(host, TMDB_DOMAIN)
}
