package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLaunchTargetTest {

    @Test
    fun `un link https mostra l'host`() {
        assertEquals("www.netflix.com", AppLaunchTarget.label("https://www.netflix.com/title/1"))
    }

    @Test
    fun `un intent mostra l'host del data uri`() {
        assertEquals(
            "www.netflix.com",
            AppLaunchTarget.label("intent://www.netflix.com/title/1#Intent;scheme=https;package=com.netflix.mediaclient;end"),
        )
    }

    @Test
    fun `un intent senza host mostra il package`() {
        assertEquals(
            "com.example.app",
            AppLaunchTarget.label("intent://watch#Intent;scheme=example;package=com.example.app;end"),
        )
    }

    @Test
    fun `un deep link mostra lo schema`() {
        assertEquals("netflix", AppLaunchTarget.label("netflix://title/1"))
    }

    @Test
    fun `un url non analizzabile resta breve`() {
        assertEquals(80, AppLaunchTarget.label("x".repeat(200)).length)
    }
}
