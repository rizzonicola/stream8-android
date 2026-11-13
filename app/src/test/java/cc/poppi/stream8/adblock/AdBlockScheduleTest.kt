package cc.poppi.stream8.adblock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockScheduleTest {

    private val now = 1_000_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `scarica una lista assente o piu' vecchia di sette giorni`() {
        assertTrue(AdBlockSchedule.needsRefresh(now, lastUpdate = 0L, lastAttempt = 0L))
        assertTrue(AdBlockSchedule.needsRefresh(now, now - 8 * day, lastAttempt = 0L))
    }

    @Test
    fun `non riscarica una lista recente`() {
        assertFalse(AdBlockSchedule.needsRefresh(now, now - 6 * day, lastAttempt = 0L))
        assertFalse(AdBlockSchedule.needsRefresh(now, now - 7 * day + 1, lastAttempt = 0L))
    }

    @Test
    fun `dopo un errore aspetta sei ore prima di riprovare`() {
        val oldList = now - 8 * day
        assertFalse(AdBlockSchedule.needsRefresh(now, oldList, lastAttempt = now - 1 * 60 * 60 * 1000L))
        assertTrue(AdBlockSchedule.needsRefresh(now, oldList, lastAttempt = now - 7 * 60 * 60 * 1000L))
    }

    @Test
    fun `la lista aggiornata di recente non viene riscaricata anche se l'ultimo tentativo e' vecchio`() {
        assertFalse(AdBlockSchedule.needsRefresh(now, now - day, lastAttempt = now - 30 * day))
    }
}
