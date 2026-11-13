package cc.poppi.stream8.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockerTest {

    private val blocker = AdBlocker(listOf("doubleclick.net", "ads.example.org", "image.tmdb.org", "themoviedb.org", "stream8.poppi.cc"))
    private val page = "sito.com"

    @Test
    fun `blocca il dominio in lista e i suoi sottodomini`() {
        assertTrue(blocker.shouldBlock("doubleclick.net", page))
        assertTrue(blocker.shouldBlock("ad.doubleclick.net", page))
        assertTrue(blocker.shouldBlock("a.b.c.doubleclick.net", page))
        assertTrue(blocker.shouldBlock("ADS.Example.ORG.", page))
    }

    @Test
    fun `non blocca domini simili ne' il dominio padre di una voce`() {
        assertFalse(blocker.shouldBlock("notdoubleclick.net", page))
        assertFalse(blocker.shouldBlock("doubleclick.net.evil.com", page))
        assertFalse(blocker.shouldBlock("example.org", page))
        assertFalse(blocker.shouldBlock("cdn.example.org", page))
    }

    @Test
    fun `non blocca mai Stream8 e TMDB, anche se in lista`() {
        assertFalse(blocker.shouldBlock("image.tmdb.org", page))
        assertFalse(blocker.shouldBlock("www.themoviedb.org", page))
        assertFalse(blocker.shouldBlock("stream8.poppi.cc", page))
    }

    @Test
    fun `non blocca le richieste first-party`() {
        assertFalse(blocker.shouldBlock("ads.doubleclick.net", "www.doubleclick.net"))
        assertFalse(blocker.shouldBlock("doubleclick.net", "doubleclick.net"))
        assertTrue(blocker.shouldBlock("doubleclick.net", null))
    }

    @Test
    fun `parseLine gestisce commenti, righe vuote e formato hosts`() {
        assertNull(AdBlocker.parseLine("# commento"))
        assertNull(AdBlocker.parseLine("   "))
        assertNull(AdBlocker.parseLine("nodots"))
        assertNull(AdBlocker.parseLine("not a domain!"))
        assertEquals("ads.com", AdBlocker.parseLine("ads.com # tracker"))
        assertEquals("ads.com", AdBlocker.parseLine("0.0.0.0 ADS.com"))
    }

    @Test
    fun `fromStream carica una lista e ignora le righe non valide`() {
        val list = "# test\nads.one.com\n\nbroken line here!\n0.0.0.0 ads.two.net\n"
        val b = AdBlocker.fromStream(list.byteInputStream())
        assertEquals(2, b.size)
        assertTrue(b.shouldBlock("x.ads.one.com", page))
        assertTrue(b.shouldBlock("ads.two.net", page))
    }
}
