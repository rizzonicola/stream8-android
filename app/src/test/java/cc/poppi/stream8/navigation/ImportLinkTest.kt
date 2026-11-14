package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportLinkTest {

    @Test
    fun `accetta un link di importazione Stream8`() {
        val url = "https://stream8.poppi.cc/import-settings?data=eyJ0aGVtZSI6ImRhcmsifQ"
        assertEquals(url, ImportLink.parse(url))
    }

    @Test
    fun `rifiuta un payload oltre il limite dell'URL`() {
        val huge = "https://stream8.poppi.cc/import-settings?data=" + "a".repeat(9000)
        assertNull(ImportLink.parse(huge))
    }

    @Test
    fun `rifiuta host, percorso, schema e payload non validi`() {
        listOf(
            "http://stream8.poppi.cc/import-settings?data=x",
            "https://evil.com/import-settings?data=x",
            "https://stream8.poppi.cc.evil.com/import-settings?data=x",
            "https://stream8.poppi.cc@evil.com/import-settings?data=x",
            "https://stream8.poppi.cc:8443/import-settings?data=x",
            "https://stream8.poppi.cc/altro?data=x",
            "https://stream8.poppi.cc/import-settings",
            "https://stream8.poppi.cc/import-settings?data=",
            "https://stream8.poppi.cc/import-settings?foo=x",
        ).forEach { assertNull(it, ImportLink.parse(it)) }
    }
}
