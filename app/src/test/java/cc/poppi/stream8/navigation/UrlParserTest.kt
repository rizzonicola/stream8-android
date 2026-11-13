package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParserTest {

    @Test
    fun `normalizza schema, maiuscole, punto finale e porta`() {
        val p = UrlParser.parse("HTTPS://WWW.Sito.COM.:8443/Path?Q=1#F")
        assertNotNull(p)
        assertEquals("https", p!!.scheme)
        assertEquals("www.sito.com", p.host)
        assertEquals(8443, p.port)
    }

    @Test
    fun `gli IDN diventano punycode, quindi non coincidono con l'host ASCII`() {
        assertEquals("xn--bcher-kva.de", UrlParser.parse("https://bücher.de/")!!.host)
        // 's' cirillica al posto della 's' latina: l'host non puo' coincidere con "sito.com"
        assertTrue(UrlParser.parse("https://\u0455ito.com/")!!.host!!.startsWith("xn--"))
    }

    @Test
    fun `userinfo, backslash, spazi e caratteri di controllo sono rifiutati`() {
        listOf(
            "https://a.com@b.com/", "https://a.com:x@b.com/", "https://a.com\\@b.com/", "https://a.com\\b.com/",
            " https://a.com/", "https://a.com/ ", "https://a.com/\n", "https://a.com/\u0000",
        ).forEach { assertNull("'$it'", UrlParser.parse(it)) }
    }

    @Test
    fun `IP, host vuoti, porte non valide e forme senza doppio slash sono rifiutati`() {
        listOf(
            "https://127.0.0.1/", "https://192.168.0.1:8443/", "https://[::1]/", "https://0x7f.1/", "https://1.2.3.4./",
            "https:///x", "https://", "https:/a.com", "https:a.com", "https://a.com:0/", "https://a.com:65536/",
            "https://a.com:/", "https://a..com/", "https://-a.com/", "https://a.com-/",
        ).forEach { assertNull("'$it'", UrlParser.parse(it)) }
    }

    @Test
    fun `schemi non web sono riconosciuti senza host`() {
        val p = UrlParser.parse("intent://x.com/y#Intent;scheme=https;end")!!
        assertEquals("intent", p.scheme)
        assertNull(p.host)
        assertEquals("netflix", UrlParser.parse("netflix://title/1")!!.scheme)
        assertEquals("javascript", UrlParser.parse("javascript:alert(1)")!!.scheme)
        assertNull(UrlParser.parse("://x"))
        assertNull(UrlParser.parse("1abc://x"))
    }
}
