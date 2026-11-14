package cc.poppi.stream8.adblock

import android.content.Context
import android.util.Log
import cc.poppi.stream8.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Aggiorna in background la lista di domini dell'adblocker (OISD Small).
 *
 * - All'avvio carica l'ultima lista salvata; nel frattempo resta attiva quella incorporata nell'APK.
 * - Se la copia salvata ha più di 7 giorni (o manca) scarica la lista aggiornata, ~1,3 MB.
 * - Un errore di rete, un timeout o un contenuto non valido lasciano intatta la lista corrente.
 * - Il download e il parsing avvengono fuori dal thread principale; la lista in memoria viene
 *   sostituita in modo atomico, quindi nessuna richiesta vede una lista a metà.
 *
 * OISD Small (https://oisd.nl) è pensata per il blocco a livello di dominio: poche decine di
 * migliaia di voci, aggiornata ogni giorno, con bassi falsi positivi. Non è EasyList: le regole
 * per percorso o cosmetiche sarebbero molto più pesanti e rischierebbero di rompere i player.
 */
class AdBlockListUpdater(
    context: Context,
    private val settings: AdBlockSettings,
    private val blocker: AdBlocker,
) {
    private val cacheFile = File(context.applicationContext.filesDir, AdBlocker.LIST_FILE)

    fun start() {
        Thread({ run() }, "adblock-list").apply { isDaemon = true }.start()
    }

    private fun run() {
        loadCached()
        if (!settings.enabled) return
        val now = System.currentTimeMillis()
        if (!AdBlockSchedule.needsRefresh(now, settings.lastUpdate, settings.lastAttempt)) return
        // Dopo un errore non si riprova a ogni avvio: evita download inutili su rete assente.
        settings.lastAttempt = now
        download()
    }

    private fun loadCached() {
        if (!cacheFile.isFile) return
        val domains = try {
            cacheFile.inputStream().use { AdBlocker.readDomains(it) }
        } catch (e: IOException) {
            return
        }
        if (domains.size < MIN_DOMAINS) return
        blocker.update(domains)
    }

    private fun download() {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(LIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Stream8/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "text/plain")
                settings.etag?.let { setRequestProperty("If-None-Match", it) }
            }
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    // La lista sul server non è cambiata: basta rinnovare la scadenza.
                    settings.lastUpdate = System.currentTimeMillis()
                    return
                }
                HttpURLConnection.HTTP_OK -> Unit
                else -> {
                    Log.w(TAG, "Lista adblock non aggiornata: HTTP $code")
                    return
                }
            }
            if (connection.url.protocol != "https") return
            if (connection.contentLengthLong > MAX_BYTES) return
            val bytes = readAtMost(connection.inputStream, MAX_BYTES) ?: return
            val domains = AdBlocker.readDomains(bytes.inputStream())
            if (domains.size < MIN_DOMAINS || domains.size > AdBlocker.MAX_DOMAINS) return
            if (!writeAtomically(domains)) return
            blocker.update(domains)
            settings.lastUpdate = System.currentTimeMillis()
            settings.etag = connection.getHeaderField("ETag")
            Log.i(TAG, "Lista adblock aggiornata: ${domains.size} domini")
        } catch (e: IOException) {
            Log.w(TAG, "Aggiornamento lista adblock non riuscito", e)
        } finally {
            connection?.disconnect()
        }
    }

    /** Legge al massimo [max] byte: una risposta più grande è un errore, non una lista da caricare. */
    private fun readAtMost(stream: InputStream, max: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                if (total > max) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    /** Scrive prima un file temporaneo e poi lo rinomina: la cache non resta mai troncata. */
    private fun writeAtomically(domains: List<String>): Boolean {
        val temp = File(cacheFile.parentFile, "${AdBlocker.LIST_FILE}.tmp")
        return try {
            temp.bufferedWriter().use { writer ->
                writer.write("# OISD Small — https://small.oisd.nl/domainswild\n")
                domains.forEach {
                    writer.write(it)
                    writer.newLine()
                }
            }
            if (temp.renameTo(cacheFile)) return true
            cacheFile.delete()
            if (temp.renameTo(cacheFile)) {
                true
            } else {
                temp.delete()
                false
            }
        } catch (e: IOException) {
            temp.delete()
            false
        }
    }

    private companion object {
        const val TAG = "AdBlock"
        const val LIST_URL = "https://small.oisd.nl/domainswild"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_BYTES = 8 * 1024 * 1024
        const val MIN_DOMAINS = 10_000
    }
}

/** Cadenza dell'aggiornamento, isolata per poterla verificare senza Android. */
internal object AdBlockSchedule {
    private val refreshMs: Long = TimeUnit.DAYS.toMillis(AdBlockSettings.REFRESH_DAYS.toLong())
    private val retryMs: Long = TimeUnit.HOURS.toMillis(6)

    fun needsRefresh(now: Long, lastUpdate: Long, lastAttempt: Long): Boolean =
        now - lastUpdate >= refreshMs && now - lastAttempt >= retryMs
}
