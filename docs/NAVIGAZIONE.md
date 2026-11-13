# Regole di navigazione

La decisione è presa da `NavigationPolicy` (logica pura, senza dipendenze Android, coperta da test unitari) a partire dalla
**pagina principale effettivamente caricata** (l'*origine*) e dalla richiesta (URL di destinazione, tipo, gesto reale
dell'utente, redirect sì/no).

## Origini

| Origine | Quando |
|---|---|
| `STREAM8` | pagina caricata su `stream8.poppi.cc` (o sottodomini) |
| `TMDB` | pagina su `themoviedb.org` (o sottodomini) |
| `SERVICE` | qualunque altra pagina, aperta in precedenza da Stream8 |
| `NONE` | nulla caricato ancora (avvio) |

## Matrice di decisione (destinazione HTTPS)

| Origine → destinazione | Gesto reale | Esito | Tenta app esterna |
|---|---|---|---|
| NONE → Stream8 / TMDB | – | carica | no |
| NONE → altro | – | **blocca** | no |
| STREAM8 → Stream8 / TMDB | – | carica | no |
| STREAM8 → altro dominio (nome valido) | sì | **apre come nuovo servizio** (parte una nuova catena, senza cascata) | **sì**, prima della WebView |
| STREAM8 → altro dominio | no / redirect | **blocca** | no |
| STREAM8 → IP, `localhost`, suffisso pubblico | – | **blocca** | no |
| TMDB → TMDB / Stream8 | – | carica | no |
| TMDB → dominio del servizio aperto | sì | carica | sì |
| TMDB → altro dominio | sì | **apre come nuovo servizio** (avvia la cascata) | **sì**, prima della WebView |
| TMDB → altro dominio | redirect del server | **apre come nuovo servizio** (avvia la cascata) | no |
| TMDB → altro dominio | no (né gesto né redirect) | **blocca** | no |
| SERVICE con cascata attiva → dominio del servizio | sì | carica | **sì**, prima della WebView |
| SERVICE con cascata attiva → dominio del servizio | no | carica | no |
| SERVICE con cascata attiva → altro dominio (nome valido) | sì | **apre come nuovo servizio** (la cascata prosegue) | **sì**, prima della WebView |
| SERVICE con cascata attiva → altro dominio | redirect del server | **apre come nuovo servizio** (la cascata prosegue) | no |
| SERVICE con cascata attiva → altro dominio | no (né gesto né redirect) | **blocca** | no |
| SERVICE senza cascata → qualunque altro | – | **blocca** | **no** |
| SERVICE → TMDB | – | carica | **no** |
| SERVICE → Stream8 | – | **blocca** | no |

Per `intent://` e deep link (`netflix://…`): l'esito web è sempre *blocca*; l'app si tenta da STREAM8, TMDB e dai servizi
della cascata TMDB con gesto reale (mai da un servizio senza cascata, da navigazioni automatiche o da redirect). `http://`, `javascript:`, `data:`, `file:`, `content:`,
`blob:`, `about:`, `ftp:` e i deep link verso browser sono sempre bloccati. I popup (`window.open`, `target="_blank"`) seguono
le stesse regole e **richiedono** un gesto reale.

## Cascata TMDB

La cascata parte quando viene caricata una pagina TMDB (anche raggiunta da un servizio) e resta attiva finché si resta
nella catena: da quel momento **TMDB e ogni servizio aperto possono aprire nuovi domini con un click reale o tramite i
redirect del server** (es. TMDB → JustWatch → Prime Video), e i nuovi domini possono fare altrettanto, senza limiti di
passaggi. Nella cascata anche i servizi raggiunti possono aprire la propria app (App Link `https`, `intent://` o deep link)
con un tocco reale. Senza TMDB nella catena, un servizio può navigare solo nel proprio dominio (più TMDB) e non può aprire
app: un link verso un altro sito viene bloccato.

La cascata si azzera quando si torna a Stream8 (Indietro/Avanti o "Ricarica Stream8"): la catena successiva riparte da
Stream8 e, se non passa da TMDB, resta senza cascata. Senza cascata i redirect restano nel dominio che Stream8 ha appena
aperto (es. `sito.com` → `www.sito.com` → `login.sito.com`) e un redirect verso un altro dominio è bloccato; le
navigazioni automatiche senza gesto e senza redirect sono sempre bloccate. Dopo un errore di rete l'autorizzazione
pendente decade.

## Dominio registrabile e sottodomini

Il dominio di un servizio è il suo dominio registrabile (eTLD+1) secondo la Public Suffix List. Un host appartiene al servizio se è
uguale ad esso o termina con `.` + dominio. Esempi per `sito.com`:

| URL | Esito |
|---|---|
| `https://sito.com/`, `/login`, `www.`, `app.`, `login.`, `a.b.c.sito.com` | consentito |
| `https://sito1.com/`, `altrosito.com`, `sito.com.evil.net`, `evilsito.com` | bloccato |
| `https://sito.com@evil.com/`, `https://sito.com\@evil.com/` | bloccato (URL non valido) |
| `https://ѕito.com/` (s cirillica → `xn--…`) | bloccato |

## Tre livelli di difesa (`WebController`)

1. **Decisione** — `shouldOverrideUrlLoading` (link, JavaScript, redirect) e `onCreateWindow` (popup). Qui avvengono l'apertura di app e il cambio di dominio.
2. **Rete di sicurezza** — `shouldInterceptRequest` sul frame principale: respinge richieste verso domini mai autorizzati che il livello 1 non vede (es. invio di form `POST`) e ripristina l'ultima pagina valida; con la cascata attiva lascia passare i domini attraversati dai redirect.
3. **Verifica della pagina caricata** — `doUpdateVisitedHistory` registra l'origine effettiva e verifica anche Indietro/Avanti: la cronologia ammette solo domini autorizzati in questa sessione (con la cascata attiva registra i nuovi domini raggiunti dai redirect).

## Risorse incorporate

La whitelist riguarda le **navigazioni**. Immagini, CSS, JavaScript, font, API, XHR/Fetch, video e iframe provenienti da CDN o
domini tecnici diversi **non** sono bloccati (li filtra solo l'adblocker, se attivo). Negli iframe sono vietati soltanto gli
schemi non web (`intent:`, deep link, `javascript:`, `data:`) e l'HTTP in chiaro; una navigazione che coinvolge il frame
principale o un popup passa sempre dalla policy, quindi un iframe non può usarla per aggirare le regole.
