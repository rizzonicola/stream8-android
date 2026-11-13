package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadErrorsTest {
    @Test
    fun `errori di rete vengono classificati per il messaggio e il pulsante Riprova`() {
        assertEquals(LoadErrorKind.OFFLINE, LoadErrors.classify(-2)) // ERROR_HOST_LOOKUP
        assertEquals(LoadErrorKind.OFFLINE, LoadErrors.classify(-6)) // ERROR_CONNECT
        assertEquals(LoadErrorKind.OFFLINE, LoadErrors.classify(-7)) // ERROR_IO
        assertEquals(LoadErrorKind.TIMEOUT, LoadErrors.classify(-8)) // ERROR_TIMEOUT
        assertEquals(LoadErrorKind.TLS, LoadErrors.classify(-11)) // ERROR_FAILED_SSL_HANDSHAKE
        assertEquals(LoadErrorKind.OTHER, LoadErrors.classify(-1))
        assertEquals(LoadErrorKind.OTHER, LoadErrors.classify(0))
    }
}
