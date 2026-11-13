package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentUrlParserTest {

    private val netflix =
        "intent://www.netflix.com/title/80100172#Intent;scheme=https;package=com.netflix.mediaclient;" +
            "S.browser_fallback_url=https%3A%2F%2Fwww.netflix.com%2Ftitle%2F80100172%3Fa%3Db%2Bc;end"

    @Test
    fun `estrae schema, dati, package e fallback`() {
        val t = IntentUrlParser.parse(netflix)!!
        assertEquals("https", t.scheme)
        assertEquals("https://www.netflix.com/title/80100172", t.dataUri)
        assertEquals("com.netflix.mediaclient", t.packageName)
        assertEquals("https://www.netflix.com/title/80100172?a=b+c", t.fallbackUrl) // il '+' non diventa spazio
    }

    @Test
    fun `senza package e senza fallback`() {
        val t = IntentUrlParser.parse("intent://sito.com/x#Intent;scheme=https;end")!!
        assertNull(t.packageName)
        assertNull(t.fallbackUrl)
    }

    @Test
    fun `component, selector, azione ed extra vengono ignorati`() {
        val t = IntentUrlParser.parse(
            "intent://sito.com/x#Intent;scheme=https;component=com.victim/.Secret;action=android.intent.action.CALL;" +
                "SEL;S.extra=1;i.n=2;end",
        )!!
        assertEquals("https://sito.com/x", t.dataUri)
        assertNull(t.packageName)
    }

    @Test
    fun `il fallback deve essere https`() {
        listOf("http%3A%2F%2Fa.com%2F", "javascript%3Aalert(1)", "market%3A%2F%2Fdetails%3Fid%3Dx", "intent%3A%2F%2Fa%23Intent%3Bend")
            .forEach {
                val t = IntentUrlParser.parse("intent://sito.com/x#Intent;scheme=https;S.browser_fallback_url=$it;end")!!
                assertNull(it, t.fallbackUrl)
            }
    }

    @Test
    fun `rifiuta forme non valide, schemi pericolosi e browser`() {
        listOf(
            "intent:sito.com/x#Intent;scheme=https;end", // senza //
            "intent://sito.com/x#Intent;scheme=https", // senza end
            "intent://sito.com/x", // senza marker
            "intent://sito.com/x#Intent;end", // senza scheme
            "intent://x#Intent;scheme=javascript;end",
            "intent://x#Intent;scheme=file;end",
            "intent://x#Intent;scheme=http;end",
            "intent://x#Intent;scheme=intent;end",
            "intent://x#Intent;scheme=https;package=com.android.chrome;end", // mai un browser
            "intent://x#Intent;scheme=https;package=org.mozilla.firefox;end",
            "intent://x#Intent;scheme=https;package=not a package;end",
            "intent://x y#Intent;scheme=https;end",
            "https://sito.com/",
        ).forEach { assertNull("'$it'", IntentUrlParser.parse(it)) }
    }
}
