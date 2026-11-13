package cc.poppi.stream8.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationPolicyTest {

    private val home = PolicyDefaults.HOME_URL

    // ---------------------------------------------------------------- helper

    private fun click(url: String) = NavRequest(url, NavKind.MAIN_FRAME, userGesture = true)
    private fun auto(url: String) = NavRequest(url, NavKind.MAIN_FRAME, userGesture = false)
    private fun redirect(url: String) = NavRequest(url, NavKind.MAIN_FRAME, userGesture = false, redirect = true)
    private fun popup(url: String, gesture: Boolean = true) = NavRequest(url, NavKind.POPUP, gesture)

    private fun onStream8() = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted(home)) }

    /** Stream8 → click su `url` → pagina caricata, come nell'uso reale. */
    private fun NavigationPolicy.openService(url: String) {
        val v = evaluate(click(url))
        assertEquals("apertura servizio $url", WebAction.LOAD_NEW_SERVICE, v.web)
        accept(v)
        assertTrue(onMainFrameCommitted(url))
    }

    private fun NavigationPolicy.assertLoads(req: NavRequest) =
        assertEquals(req.url, WebAction.LOAD, evaluate(req).web)

    private fun NavigationPolicy.assertBlocked(req: NavRequest) {
        val v = evaluate(req)
        assertEquals(req.url, WebAction.BLOCK, v.web)
    }

    // ---------------------------------------------------------------- dominio iniziale

    @Test
    fun `all'avvio e' autorizzato solo Stream8 (piu' TMDB), niente servizi esterni`() {
        val p = NavigationPolicy()
        p.assertLoads(click(home))
        p.assertLoads(click("https://stream8.poppi.cc/movie/1"))
        p.assertLoads(click("https://www.themoviedb.org/movie/1"))
        p.assertBlocked(click("https://sito.com/"))
    }

    @Test
    fun `da Stream8 i link interni e TMDB restano nella WebView senza tentare app`() {
        val p = onStream8()
        val internal = p.evaluate(click("https://stream8.poppi.cc/search?q=a"))
        assertEquals(WebAction.LOAD, internal.web)
        assertFalse(internal.mayLaunchApp)
        val tmdb = p.evaluate(click("https://www.themoviedb.org/movie/603"))
        assertEquals(WebAction.LOAD, tmdb.web)
        assertFalse(tmdb.mayLaunchApp)
    }

    // ---------------------------------------------------------------- apertura di un nuovo servizio

    @Test
    fun `Stream8 apre un nuovo servizio con un click reale`() {
        val v = onStream8().evaluate(click("https://sito.com/"))
        assertEquals(WebAction.LOAD_NEW_SERVICE, v.web)
        assertEquals(Reason.NEW_SERVICE, v.reason)
    }

    @Test
    fun `navigazione automatica da Stream8 verso un dominio esterno e' bloccata`() {
        val p = onStream8()
        val v = p.evaluate(auto("https://sito.com/"))
        assertEquals(WebAction.BLOCK, v.web)
        assertEquals(Reason.NO_USER_GESTURE, v.reason)
        assertFalse(v.mayLaunchApp)
        p.assertBlocked(redirect("https://sito.com/"))
    }

    // ---------------------------------------------------------------- sottodomini e percorsi

    @Test
    fun `il servizio autorizza dominio, sottodomini e tutti i percorsi`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        listOf(
            "https://sito.com/", "https://sito.com/login", "https://sito.com/a/b/c?x=1#y",
            "https://www.sito.com/", "https://app.sito.com/", "https://login.sito.com/",
            "https://a.b.c.sito.com/path", "https://sito.com:8443/",
        ).forEach { p.assertLoads(click(it)) }
        p.assertLoads(auto("https://app.sito.com/x")) // navigazione interna al servizio, anche automatica
    }

    @Test
    fun `host con maiuscole, punto finale o porta restano nello stesso dominio`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        p.assertLoads(click("HTTPS://WWW.SITO.COM./x"))
    }

    // ---------------------------------------------------------------- domini simili e contraffatti

    @Test
    fun `domini simili, contraffatti o ingannevoli sono bloccati`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        listOf(
            "https://sito1.com/",
            "https://altrosito.com/",
            "https://sito.com.evil.net/",
            "https://evilsito.com/",
            "https://sito-com.net/",
            "https://evil.com/sito.com",
            "https://evil.com/?u=https://sito.com",
            "https://sito.com@evil.com/",
            "https://sito.com\\@evil.com/",
            "https://sito.com:pass@evil.com/",
            "https://\u0455ito.com/", // 's' cirillica: dominio omografo
            "https://example.net/",
        ).forEach { p.assertBlocked(click(it)) }
    }

    @Test
    fun `un servizio non puo' contenere un sibling con lo stesso suffisso pubblico`() {
        val p = onStream8()
        p.openService("https://foo.co.uk/")
        p.assertLoads(click("https://www.foo.co.uk/"))
        p.assertBlocked(click("https://bar.co.uk/"))
        p.assertBlocked(click("https://co.uk/"))
    }

    @Test
    fun `IP, localhost e suffissi pubblici non sono servizi apribili`() {
        val p = onStream8()
        listOf("https://192.168.1.10/", "https://127.0.0.1:8096/", "https://localhost/", "https://co.uk/", "https://intranet/")
            .forEach { assertEquals(it, WebAction.BLOCK, p.evaluate(click(it)).web) }
    }

    // ---------------------------------------------------------------- eccezione TMDB

    @Test
    fun `TMDB e' sempre raggiungibile, anche da un servizio esterno`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        listOf(
            "https://themoviedb.org/", "https://www.themoviedb.org/movie/603",
            "https://api.themoviedb.org/3/x", "https://sub.deep.themoviedb.org/",
        ).forEach {
            p.assertLoads(click(it))
            p.assertLoads(auto(it))
        }
    }

    @Test
    fun `l'eccezione TMDB non vale per domini simili`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        listOf(
            "https://themoviedb.org.evil.com/", "https://notthemoviedb.org/", "https://themoviedb.org.sito1.com/",
            "https://themoviedb.com/", "https://tmdb.org/",
        ).forEach { p.assertBlocked(click(it)) }
    }

    @Test
    fun `image tmdb org e' una risorsa, non una destinazione di navigazione`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        p.assertBlocked(click("https://image.tmdb.org/t/p/w500/x.jpg"))
        assertFalse(p.isMainFrameRequestPermitted("https://image.tmdb.org/t/p/w500/x.jpg"))
    }

    // ---------------------------------------------------------------- cambio dinamico del dominio

    @Test
    fun `il dominio autorizzato cambia quando Stream8 apre un altro servizio, senza riavvio`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        p.assertBlocked(click("https://sito2.net/")) // sito.com non puo' aprire sito2.net

        assertTrue(p.onMainFrameCommitted(home)) // l'utente torna a Stream8 (Indietro)
        p.openService("https://sito2.net/") // e sceglie un altro servizio

        p.assertLoads(click("https://www.sito2.net/"))
        p.assertBlocked(click("https://sito.com/")) // il vecchio dominio non e' piu' autorizzato
        p.assertBlocked(click("https://www.sito.com/"))
    }

    @Test
    fun `un servizio esterno non puo' navigare verso Stream8, nemmeno via sottodominio dello stesso sito`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        val v = p.evaluate(click(home))
        assertEquals(WebAction.BLOCK, v.web)
        assertEquals(Reason.SERVICE_CANNOT_LEAVE, v.reason)

        // Caso limite: servizio sul dominio registrabile di Stream8 (poppi.cc) non deve riaprire Stream8.
        val q = onStream8()
        q.openService("https://cdn.poppi.cc/")
        assertEquals(WebAction.BLOCK, q.evaluate(click(home)).web)
    }

    // ---------------------------------------------------------------- app Android esterne

    @Test
    fun `da Stream8 un click reale puo' tentare di aprire un'app (https, intent, deep link)`() {
        val p = onStream8()
        listOf(
            "https://www.netflix.com/title/1",
            "intent://www.netflix.com/title/1#Intent;scheme=https;package=com.netflix.mediaclient;end",
            "netflix://title/1",
        ).forEach { assertTrue(it, p.evaluate(click(it)).mayLaunchApp) }
    }

    @Test
    fun `da Stream8 senza click reale nessuna app viene aperta`() {
        val p = onStream8()
        listOf("https://www.netflix.com/title/1", "intent://x.com/y#Intent;scheme=https;end", "netflix://title/1").forEach {
            assertFalse(it, p.evaluate(auto(it)).mayLaunchApp)
            assertFalse(it, p.evaluate(redirect(it)).mayLaunchApp)
            assertEquals(it, WebAction.BLOCK, p.evaluate(auto(it)).web)
        }
    }

    @Test
    fun `da TMDB un click reale puo' tentare di aprire un'app`() {
        val p = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted("https://www.themoviedb.org/movie/1")) }
        listOf(
            "https://www.netflix.com/title/1",
            "intent://www.youtube.com/watch#Intent;scheme=https;package=com.google.android.youtube;end",
            "spotify://track/1",
        ).forEach { assertTrue(it, p.evaluate(click(it)).mayLaunchApp) }
    }

    @Test
    fun `TMDB apre nuovi servizi e la cascata prosegue in modo ricorsivo`() {
        val p = onStream8()
        assertTrue(p.onMainFrameCommitted("https://www.themoviedb.org/movie/1")) // Stream8 -> TMDB
        assertTrue(p.cascadeEnabled)

        p.openService("https://sito.com/") // TMDB -> A: la cascata e' partita
        p.openService("https://altro.net/") // A -> B: un servizio puo' aprire un altro dominio
        p.assertLoads(click("https://www.altro.net/x"))
        p.openService("https://terzo.org/") // B -> C: e cosi' via, senza limiti di passaggi
        p.assertLoads(click("https://www.terzo.org/x"))
    }

    @Test
    fun `senza TMDB un servizio non puo' aprire nuovi domini`() {
        val p = onStream8()
        p.openService("https://sito.com/") // Stream8 -> A, nessun TMDB nella catena
        assertFalse(p.cascadeEnabled)

        val v = p.evaluate(click("https://altro.net/"))
        assertEquals(WebAction.BLOCK, v.web)
        assertEquals(Reason.OUTSIDE_SERVICE, v.reason)
        p.assertBlocked(popup("https://altro.net/"))
    }

    @Test
    fun `TMDB abilita la cascata anche se raggiunto da un servizio`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        assertTrue(p.onMainFrameCommitted("https://www.themoviedb.org/movie/1")) // A -> TMDB
        assertTrue(p.cascadeEnabled)

        p.openService("https://altro.net/") // TMDB -> B, poi B puo' proseguire
        p.openService("https://terzo.org/")
    }

    @Test
    fun `da TMDB i link esterni si aprono a cascata con un click o con un redirect del server`() {
        val p = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted("https://www.themoviedb.org/")) }
        p.openService("https://sito.com/") // click reale: nuovo servizio
        p.assertBlocked(auto("https://altro.net/")) // navigazione automatica senza redirect: no
        val v = p.evaluate(redirect("https://altro.net/"))
        assertEquals(WebAction.LOAD_NEW_SERVICE, v.web) // redirect del server dentro la cascata: si'
        assertFalse(v.mayLaunchApp)
        p.assertLoads(click("https://www.sito.com/watch")) // il dominio corrente resta navigabile
    }

    @Test
    fun `la catena TMDB - JustWatch - Prime Video attraversa piu' redirect senza altri click`() {
        val p = onStream8()
        assertTrue(p.onMainFrameCommitted("https://www.themoviedb.org/tv/1/watch"))

        // Click sul provider: si apre il dominio di tracciamento di JustWatch.
        val v = p.evaluate(click("https://click.justwatch.com/a?cx=1&r=https%3A%2F%2Fwww.primevideo.com%2Fdetail"))
        assertEquals(WebAction.LOAD_NEW_SERVICE, v.web)
        p.accept(v)

        // Catena di redirect del server: ogni salto attraversa un dominio nuovo.
        listOf(
            "https://primevideo-eu.pxf.io/1rKvQm?subId1=x",
            "https://www.ojrq.net/p/?return=x",
            "https://primevideo-eu.pxf.io/c/1206980/1721981/20021",
            "https://www.primevideo.com/detail/x",
        ).forEach { url ->
            val r = p.evaluate(redirect(url))
            assertEquals(url, WebAction.LOAD_NEW_SERVICE, r.web)
            assertFalse(url, r.mayLaunchApp) // un redirect non apre mai app esterne
            p.accept(r)
        }
        assertTrue(p.onMainFrameCommitted("https://www.primevideo.com/detail/x"))
        p.assertLoads(click("https://www.primevideo.com/"))
    }

    @Test
    fun `tornando a Stream8 la cascata si azzera`() {
        val p = onStream8()
        assertTrue(p.onMainFrameCommitted("https://www.themoviedb.org/"))
        p.openService("https://sito.com/")
        assertTrue(p.cascadeEnabled)

        assertTrue(p.onMainFrameCommitted(home)) // Indietro fino a Stream8
        assertFalse(p.cascadeEnabled)

        p.openService("https://altro.net/") // nuova catena senza TMDB
        p.assertBlocked(click("https://terzo.org/"))

        val q = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted("https://www.themoviedb.org/")) }
        q.onHomeRequested() // "Ricarica Stream8"
        assertFalse(q.cascadeEnabled)
    }

    @Test
    fun `lo stato salvato ripristina anche la cascata`() {
        val p = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted("https://www.themoviedb.org/")) }
        p.openService("https://sito.com/")
        val saved = p.snapshot()

        val restored = NavigationPolicy()
        restored.restore(saved, cascade = true)
        assertTrue(restored.onMainFrameCommitted("https://sito.com/"))
        assertEquals(WebAction.LOAD_NEW_SERVICE, restored.evaluate(click("https://altro.net/")).web)

        val withoutCascade = NavigationPolicy()
        withoutCascade.restore(saved)
        assertTrue(withoutCascade.onMainFrameCommitted("https://sito.com/"))
        assertEquals(WebAction.BLOCK, withoutCascade.evaluate(click("https://altro.net/")).web)
    }

    @Test
    fun `senza cascata un servizio esterno non puo' avviare app`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        listOf(
            "https://www.netflix.com/title/1",
            "intent://www.netflix.com/title/1#Intent;scheme=https;package=com.netflix.mediaclient;end",
            "intent://#Intent;component=com.victim/.Secret;end",
            "netflix://title/1",
            "market://details?id=com.netflix.mediaclient",
            "tel:123", "mailto:a@b.it",
        ).forEach {
            val v = p.evaluate(click(it))
            assertFalse(it, v.mayLaunchApp)
            assertEquals(it, WebAction.BLOCK, v.web)
        }
        // nemmeno i link di TMDB o del proprio dominio devono tentare app
        assertFalse(p.evaluate(click("https://www.themoviedb.org/x")).mayLaunchApp)
        assertFalse(p.evaluate(click("https://www.sito.com/x")).mayLaunchApp)
    }

    @Test
    fun `nella cascata TMDB anche i servizi possono aprire app con un click reale`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        assertTrue(p.onMainFrameCommitted("https://www.themoviedb.org/")) // la cascata parte da TMDB
        p.openService("https://primevideo.com/") // TMDB -> Prime Video

        listOf(
            "https://www.primevideo.com/detail/x", // App Link dello stesso servizio
            "https://primevideo.com/open", // link https del servizio
            "intent://www.primevideo.com/detail/x#Intent;scheme=https;package=com.amazon.avod.thirdpartyclient;end",
            "primevideo://detail/x",
        ).forEach { assertTrue(it, p.evaluate(click(it)).mayLaunchApp) }

        // Nella cascata anche un nuovo dominio puo' tentare l'app, ma solo con un click.
        assertTrue(p.evaluate(click("https://altro.net/")).mayLaunchApp)
        // Redirect e navigazioni automatiche non aprono mai app.
        assertFalse(p.evaluate(redirect("https://www.primevideo.com/a")).mayLaunchApp)
        assertFalse(p.evaluate(auto("https://www.primevideo.com/b")).mayLaunchApp)
    }

    @Test
    fun `schemi pericolosi, http e browser esterni sono sempre bloccati`() {
        val p = onStream8()
        listOf(
            "http://sito.com/", "javascript:alert(1)", "data:text/html,<b>x</b>", "file:///sdcard/x",
            "content://com.android.contacts/", "blob:https://sito.com/uuid", "about:blank", "view-source:https://sito.com/",
            "googlechrome://navigate?url=https://sito.com", "firefox://open-url?url=https://sito.com", "ftp://sito.com/",
        ).forEach {
            val v = p.evaluate(click(it))
            assertEquals(it, WebAction.BLOCK, v.web)
            assertFalse(it, v.mayLaunchApp)
        }
    }

    // ---------------------------------------------------------------- fallback WebView

    @Test
    fun `se non c'e' un'app compatibile si applica la whitelist della WebView`() {
        val p = onStream8()
        // https verso un servizio: l'app si tenta, ma il verdetto web resta "apri come nuovo servizio"
        val v = p.evaluate(click("https://sito.com/watch"))
        assertTrue(v.mayLaunchApp)
        assertEquals(WebAction.LOAD_NEW_SERVICE, v.web)

        // fallback di un intent://: valutato come click normale, senza ritentare l'app
        val target = IntentUrlParser.parse(
            "intent://sito.com/watch#Intent;scheme=https;package=com.sito.app;S.browser_fallback_url=https%3A%2F%2Fsito.com%2Fwatch;end",
        )!!
        assertEquals("https://sito.com/watch", target.fallbackUrl)
        assertEquals(WebAction.LOAD_NEW_SERVICE, p.evaluate(click(target.fallbackUrl!!)).web)
        // un fallback verso un dominio non lecito da un servizio resta bloccato
        p.openService("https://sito.com/")
        assertEquals(WebAction.BLOCK, p.evaluate(click("https://evil.com/fb")).web)
    }

    // ---------------------------------------------------------------- popup e iframe

    @Test
    fun `popup senza gesto dell'utente sono bloccati`() {
        val p = onStream8()
        val v = p.evaluate(popup("https://sito.com/", gesture = false))
        assertEquals(WebAction.BLOCK, v.web)
        assertEquals(Reason.POPUP_WITHOUT_GESTURE, v.reason)
    }

    @Test
    fun `popup da Stream8 con gesto apre il servizio, da un servizio solo il proprio dominio`() {
        val p = onStream8()
        assertEquals(WebAction.LOAD_NEW_SERVICE, p.evaluate(popup("https://sito.com/")).web)
        p.openService("https://sito.com/")
        assertEquals(WebAction.LOAD, p.evaluate(popup("https://www.sito.com/x")).web)
        assertEquals(WebAction.LOAD, p.evaluate(popup("https://www.themoviedb.org/x")).web)
        assertEquals(WebAction.BLOCK, p.evaluate(popup("https://ads.example.net/")).web)
        assertFalse(p.evaluate(popup("netflix://title/1")).mayLaunchApp)
    }

    @Test
    fun `negli iframe sono consentiti i contenuti https, non intent, deep link e http`() {
        val p = onStream8()
        assertTrue(p.isSubframeNavigationAllowed("https://player.cdn-esempio.net/embed/1"))
        assertTrue(p.isSubframeNavigationAllowed("about:blank"))
        assertFalse(p.isSubframeNavigationAllowed("intent://x#Intent;scheme=https;end"))
        assertFalse(p.isSubframeNavigationAllowed("netflix://title/1"))
        assertFalse(p.isSubframeNavigationAllowed("http://player.net/"))
        assertFalse(p.isSubframeNavigationAllowed("javascript:alert(1)"))
    }

    // ---------------------------------------------------------------- redirect, errori di rete, cronologia

    @Test
    fun `redirect dopo l'apertura di un servizio restano nel suo dominio`() {
        val p = onStream8()
        val v = p.evaluate(click("https://sito.com/"))
        p.accept(v) // autorizzato, non ancora caricato
        p.assertLoads(redirect("https://www.sito.com/")) // http->https, www, login...
        p.assertLoads(redirect("https://login.sito.com/sso"))
        p.assertBlocked(redirect("https://evil.com/")) // il redirect non puo' uscire dal dominio
        p.assertBlocked(redirect("https://sito1.com/"))
    }

    @Test
    fun `dopo un errore di rete l'autorizzazione pendente non vale piu' per i redirect`() {
        val p = onStream8()
        p.accept(p.evaluate(click("https://sito.com/")))
        p.onNavigationFailed() // errore di connessione
        p.assertBlocked(redirect("https://www.sito.com/"))
        // l'utente puo' ritentare con un nuovo click da Stream8
        assertEquals(WebAction.LOAD_NEW_SERVICE, p.evaluate(click("https://sito.com/")).web)
    }

    @Test
    fun `un servizio mai autorizzato da Stream8 non supera ne' la rete di sicurezza ne' la verifica della pagina`() {
        val p = onStream8()
        assertFalse(p.isMainFrameRequestPermitted("https://sito.com/"))
        assertFalse(p.onMainFrameCommitted("https://sito.com/"))
    }

    @Test
    fun `la pagina caricata viene verificata, anche per Indietro e Avanti nella cronologia`() {
        val p = onStream8()
        assertFalse(p.onMainFrameCommitted("https://evil.com/"))
        assertFalse(p.onMainFrameCommitted("http://stream8.poppi.cc/"))
        assertFalse(p.onMainFrameCommitted("javascript:alert(1)"))

        p.openService("https://sito.com/")
        assertTrue(p.onMainFrameCommitted(home)) // Indietro
        assertTrue(p.onMainFrameCommitted("https://sito.com/")) // Avanti: gia' autorizzato in questa sessione
        p.assertLoads(click("https://www.sito.com/x"))
    }

    @Test
    fun `rete di sicurezza sul frame principale`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        assertTrue(p.isMainFrameRequestPermitted("https://www.sito.com/x"))
        assertTrue(p.isMainFrameRequestPermitted("https://www.themoviedb.org/x"))
        assertTrue(p.isMainFrameRequestPermitted(home))
        assertFalse(p.isMainFrameRequestPermitted("https://sito1.com/"))
        assertFalse(p.isMainFrameRequestPermitted("http://sito.com/"))
        assertFalse(p.isMainFrameRequestPermitted("not a url"))
    }

    @Test
    fun `in cascata la rete di sicurezza e la verifica della pagina accettano i domini dei redirect`() {
        val p = NavigationPolicy().also { assertTrue(it.onMainFrameCommitted("https://www.themoviedb.org/")) }
        // Un redirect puo' raggiungere un dominio mai visto senza passare da shouldOverrideUrlLoading.
        assertTrue(p.isMainFrameRequestPermitted("https://primevideo-eu.pxf.io/x"))
        assertTrue(p.isMainFrameRequestPermitted("https://www.primevideo.com/detail/x"))
        // La pagina caricata viene comunque registrata come servizio della catena.
        assertTrue(p.onMainFrameCommitted("https://www.primevideo.com/detail/x"))
        p.assertLoads(click("https://www.primevideo.com/other"))
        p.openService("https://altro.net/") // e la cascata prosegue

        // Senza cascata la rete di sicurezza resta chiusa.
        val q = onStream8()
        assertFalse(q.isMainFrameRequestPermitted("https://primevideo-eu.pxf.io/x"))
    }

    @Test
    fun `lo stato salvato ripristina solo domini registrabili validi`() {
        val p = onStream8()
        p.openService("https://sito.com/")
        val saved = p.snapshot()

        val restored = NavigationPolicy()
        restored.restore(saved + listOf("co.uk", "localhost", "evil..com", ""))
        assertTrue(restored.isMainFrameRequestPermitted("https://sito.com/"))
        assertFalse(restored.isMainFrameRequestPermitted("https://foo.co.uk/"))
    }

    @Test
    fun `URL malformati sono bloccati`() {
        val p = onStream8()
        listOf("", " ", "https://", "https:///x", "https:sito.com", "https://sito.com:99999/", "https://sito.com:/", "not a url", "https://a b.com/")
            .forEach { assertEquals("'$it'", WebAction.BLOCK, p.evaluate(click(it)).web) }
    }
}
