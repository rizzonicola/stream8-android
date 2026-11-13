package cc.poppi.stream8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeLocaleTest {

    @Test
    fun `normalizza le lingue supportate e rimuove la regione`() {
        assertEquals("it", NativeLocale.normalize("it"))
        assertEquals("en", NativeLocale.normalize("en-US"))
        assertEquals("fr", NativeLocale.normalize("FR"))
        assertEquals("en", NativeLocale.normalize("en_GB"))
    }

    @Test
    fun `rifiuta lingue non supportate e valori assenti`() {
        assertNull(NativeLocale.normalize("de"))
        assertNull(NativeLocale.normalize("auto"))
        assertNull(NativeLocale.normalize(""))
        assertNull(NativeLocale.normalize(null))
    }
}
