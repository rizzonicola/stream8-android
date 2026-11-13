# Stream8 Android

App Android nativa per [Stream8](https://stream8.poppi.cc/): un involucro leggero (WebView) che mostra il sito
**originale** a tutto schermo, con una politica di navigazione rigorosa e un adblocker opzionale.

Questo repository è **indipendente** da [`rizzonicola/stream8`](https://github.com/rizzonicola/stream8): l'app non
ricostruisce il sito in Kotlin, carica `https://stream8.poppi.cc/`.

> **Stato della verifica:** test unitari e build debug/release firmata eseguiti localmente il 13/11/2026 con JDK 17,
> Gradle 8.9 e Android SDK 35: tutti verdi. La release GitHub `v0.0.2` è la prima verifica end-to-end dei workflow:
> vedi [Stato della verifica](#stato-della-verifica).

## Cosa fa

- Apre direttamente Stream8, senza barre né elementi superflui. Tasto Indietro, rotazione, tastiera e video a schermo intero gestiti.
- Mantiene cookie, Local Storage e sessioni; barra di caricamento discreta; errore di connessione con pulsante **Riprova**.
- Usa la cache del service worker del sito: la shell si apre subito dalla cache e si aggiorna in background; i dati dinamici restano sempre in rete.
- Quando da Stream8 si apre un servizio esterno, **solo quel dominio** (con i suoi sottodomini) diventa navigabile; TMDB resta sempre raggiungibile. Quando entra in gioco TMDB la navigazione procede **a cascata** su nuovi domini, ricorsivamente. Dettagli in [docs/NAVIGAZIONE.md](docs/NAVIGAZIONE.md).
- Può aprire app Android installate (es. un'app di streaming) **solo** da pagine Stream8 e TMDB, **solo** con un tocco reale dell'utente.
- Solo HTTPS, certificati sempre verificati, nessun bridge JavaScript, nessun accesso a file locali, nessun browser esterno.
- Adblocker leggero a elenco di domini, disattivabile.
- Smartphone, tablet e Android TV (banner, `LEANBACK_LAUNCHER`, touch non obbligatorio, tasto Menu per le impostazioni).

Permessi richiesti: soltanto `INTERNET`. `minSdk` 24 (Android 7.0), `targetSdk` 35.

## Impostazioni

Si aprono dal piccolo pulsante semitrasparente (⋮) in basso a sinistra, dal tasto **Menu** (telecomandi TV/tastiere) o
dallo shortcut "Impostazioni" tenendo premuta l'icona dell'app. Il pulsante ⋮ apre un menu con **Impostazioni**,
**Importa impostazioni** (link di condivisione `/import-settings?data=…`) e **Info e licenze**. Le Impostazioni contengono:
interruttore adblocker, **Ricarica Stream8**, versione e riepilogo delle regole di navigazione.

Al **primo avvio** l'app chiede una sola volta se importare le impostazioni da un link condiviso oppure continuare
normalmente. Il link viene aperto nella WebView: la conferma e l'applicazione restano gestite dal sito Stream8.

## Installazione

1. Scarica l'APK dalla pagina **Releases**: `Stream8-universal.apk` va bene per quasi tutti.
   - `Stream8-arm64-v8a.apk`: telefoni/tablet a 64 bit recenti · `Stream8-armeabi-v7a.apk`: dispositivi a 32 bit · `Stream8-x86_64.apk`: emulatori/Chromebook.
2. Verifica l'integrità con `SHA256SUMS.txt` (`sha256sum -c SHA256SUMS.txt`).
3. Abilita l'installazione da origini sconosciute per l'app che usi per aprire il file e installa.

Gli aggiornamenti sono firmati con la stessa chiave: si installano sopra la versione precedente senza perdere i dati.
Un APK firmato con una chiave diversa **non** può aggiornare quello esistente.

## Compilazione locale

Requisiti: JDK 17, Android SDK (piattaforma 35, build-tools 35.0.0) con `ANDROID_HOME` impostato.

```bash
gradle wrapper --gradle-version 8.10.2   # una sola volta, vedi nota sotto
./gradlew testDebugUnitTest              # test unitari
./gradlew assembleDebug                  # APK di debug
./gradlew collectReleaseApks             # i 4 APK release in build/dist/
```

> **Nota sul Gradle Wrapper:** `gradle/wrapper/gradle-wrapper.jar` è un file binario che non è stato possibile
> includere. Il comando `gradle wrapper` lo genera (e sostituisce `gradlew`/`gradlew.bat` con le versioni ufficiali):
> poi committa i tre file. I workflow **non** ne dipendono: usano `gradle/actions/setup-gradle` con Gradle 8.10.2.

Senza chiave di firma gli APK release sono *non firmati* (`-unsigned`): servono solo come verifica di compilazione.
Per firmarli in locale esporta `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`
prima di lanciare `collectReleaseApks`. Per cambiare versione: `-PappVersionName=1.2.3`.

## Generazione degli APK

`./gradlew collectReleaseApks` compila la release con gli **ABI split** (`arm64-v8a`, `armeabi-v7a`, `x86_64`, più l'APK
universale; nessun `x86` a 32 bit) e copia i file in `build/dist/` con questi nomi, **fallendo se ne manca uno**:

`Stream8-arm64-v8a.apk` · `Stream8-armeabi-v7a.apk` · `Stream8-x86_64.apk` · `Stream8-universal.apk`

`scripts/verify-apks.sh` controlla il contenuto reale: esistenza, integrità dello zip, firma (`apksigner`), applicationId,
versionName e che nessun APK per-ABI contenga librerie native di un'altra architettura (e che l'universale le contenga tutte e tre, se presenti).

`versionCode = MAJOR*10000 + MINOR*100 + PATCH` (quindi `1.2.3` → `10203`; MINOR e PATCH ≤ 99), `applicationId = cc.poppi.stream8`
(stabile: cambialo **prima** della prima release se vuoi un altro identificatore, mai dopo).

## GitHub Actions

| Workflow | Quando | Cosa fa |
|---|---|---|
| `ci.yml` | push su `main`, pull request, avvio manuale | verifica configurazione Gradle, test unitari, lint, build debug, build release non firmata + verifica dei 4 APK |
| `release.yml` | tag `vX.Y.Z`, avvio manuale | test, build release **firmata**, verifica APK, SHA-256, GitHub Release con i 4 APK e `SHA256SUMS.txt` |

Nessuna release viene pubblicata a ogni commit. `release.yml` **si ferma con un errore esplicativo** se mancano i Secrets
di firma: non pubblica mai APK non firmati. Fallisce anche se manca uno dei quattro APK (`fail_on_unmatched_files`).

### Configurazione della firma

Servono quattro **Secrets** (Settings → Secrets and variables → Actions): `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. Procedura completa, con i comandi per creare il
keystore e codificarlo, in [docs/FIRMA.md](docs/FIRMA.md). **Usa sempre la stessa chiave** e conservane un backup:
se la perdi non potrai più pubblicare aggiornamenti installabili sopra le versioni esistenti.

### Creare una release

```bash
git tag v1.0.0
git push origin v1.0.0
```

oppure Actions → *Release* → *Run workflow* e inserisci la versione (es. `1.0.0`): il tag viene creato dal workflow.

## Gestione dinamica dei domini

Riassunto (tabella completa e casi limite in [docs/NAVIGAZIONE.md](docs/NAVIGAZIONE.md)):

- All'avvio è autorizzato `stream8.poppi.cc` (e TMDB).
- Se **Stream8** apre `sito.com` con un tocco reale, il dominio autorizzato diventa `sito.com` + tutti i suoi sottodomini; tutti i percorsi sono consentiti. Nessun riavvio: tornando a Stream8 e scegliendo `sito2.net`, il dominio autorizzato diventa `sito2.net`.
- Senza TMDB nella catena, da `sito.com` si può andare solo su `sito.com`, i suoi sottodomini e TMDB. `sito1.com`, `altrosito.com`, `sito.com.evil.net`, `sito.com@evil.com` e i domini omografi sono bloccati.
- Quando viene caricata una pagina **TMDB**, la navigazione va **a cascata**: TMDB e ogni servizio aperto possono aprire nuovi domini con un tocco reale **o tramite i redirect del server** (es. TMDB → JustWatch → Prime Video), e i nuovi domini possono fare altrettanto, senza limiti di passaggi. Tornando a Stream8 la cascata si azzera: la catena successiva riparte da zero.
- Il confronto è per etichette del dominio (mai per sottostringa) sul **dominio registrabile** calcolato con la **Public Suffix List** (incorporata in Guava `InternetDomainName`): `foo.co.uk` non autorizza `bar.co.uk`. Gli URL sono analizzati da un parser rigoroso che rifiuta userinfo (`@`), backslash, spazi, IP e host non validi.
- Per tornare a Stream8 da un servizio si usa **Indietro** (la navigazione in cronologia è ammessa solo verso pagine già autorizzate nella sessione) oppure "Ricarica Stream8" nelle Impostazioni.

## Cascata TMDB

`themoviedb.org`, `www.themoviedb.org` e tutti i sottodomini autentici sono sempre raggiungibili, qualunque servizio sia aperto.
Non vale per domini simili (`themoviedb.org.evil.com`, `notthemoviedb.org`, `tmdb.org`…). `image.tmdb.org` è un host **solo per
risorse** (immagini): non è una destinazione di navigazione e non viene mai filtrato dall'adblocker.

La cascata vale solo per le **navigazioni web** e per l'apertura di app dai servizi raggiunti: l'apertura di app Android resta
consentita da Stream8, da TMDB e dai servizi della cascata (sempre solo con un tocco reale), mai da un servizio senza TMDB
nella catena e mai da redirect o navigazioni automatiche. I redirect restano chiusi verso nuovi domini finché la cascata non
è attiva (quindi mai da soli Stream8); una volta partita da TMDB, i redirect possono attraversare più domini senza altri
tocchi (es. JustWatch → Prime Video).

## Apertura delle applicazioni Android

L'apertura di app esterne è consentita **esclusivamente** se la pagina attualmente caricata è Stream8, TMDB oppure un servizio
della **cascata TMDB** (non un servizio aperto direttamente da Stream8) **e** il link è stato toccato dall'utente (gesto reale;
non redirect né navigazioni automatiche):

- link `https`: si apre solo un'app non-browser che gestisce quel link come App Link (Android 11+: `FLAG_ACTIVITY_REQUIRE_NON_BROWSER`; Android 7–10: ricerca dei gestori non-browser); niente selettore;
- `intent://`: l'Intent è **ricostruito** dall'app (azione VIEW, categoria BROWSABLE, nessun componente/selector/extra, mai un browser) a partire da scheme, package e `browser_fallback_url`; `Intent.parseUri` non viene usato;
- deep link personalizzati (`netflix://…`): VIEW + BROWSABLE; deep link verso browser (`googlechrome://…`) bloccati.

Se non esiste un'app compatibile **non** si apre mai un browser: si applica la whitelist della WebView (aperto internamente se
autorizzato, altrimenti bloccato). Da un servizio esterno (`sito.com`) nulla di tutto ciò è possibile, anche con Netflix installato.
La decisione si basa sulla **pagina principale realmente caricata**, mai sul referrer, su parametri JavaScript o sul dominio dichiarato dal link.

## Adblocker

Filtro a elenco di domini (`app/src/main/assets/adblock-domains.txt`), attivo di default, disattivabile dalle Impostazioni.
Blocca solo richieste di **risorse e iframe** il cui host (o dominio padre) è in elenco. Non blocca mai: Stream8, TMDB, richieste
first-party, né la navigazione principale. L'elenco contiene solo domini pubblicitari/tracker, non CDN, API, autenticazione, immagini o video.
L'elenco è **statico e incorporato nell'APK**: non è dinamico, non si aggiorna da solo e non contatta servizi esterni; cambia
solo con una nuova versione dell'app. Per aggiornarlo modifica il file (un dominio per riga, `#` per i commenti; accetta anche
il formato `hosts`) e ricompila.

**Limiti:** è un elenco di partenza minimo e non promette il blocco totale degli annunci; non fa filtri cosmetici né blocca
annunci serviti dallo stesso dominio del contenuto.

## Struttura

```
app/src/main/java/cc/poppi/stream8/
  navigation/   NavigationPolicy, UrlParser, DomainScope, IntentUrl, LoadError   (logica pura, testata)
  adblock/      AdBlocker                                                          (logica pura, testata)
  web/          WebController (WebView + 3 livelli di difesa), ExternalAppLauncher
  MainActivity, SettingsActivity, Prefs
app/src/test/   test unitari JVM (JUnit 4)
.github/workflows/ci.yml, release.yml · scripts/verify-apks.sh · docs/
```

## Limiti conosciuti

- **Servizi su rete locale o in HTTP**: HTTPS è obbligatorio e gli indirizzi IP sono rifiutati. Un server Jellyfin/Plex raggiunto con `http://192.168.x.x:8096` (o con un certificato non valido) **non si apre**. Funzionano nomi di dominio con certificato valido (porta qualsiasi).
- **Login su un altro dominio** (OAuth/SSO, es. un servizio che autentica su un dominio diverso dal proprio) è bloccato dalla regola "un servizio non può aprire altri domini", **a meno che** TMDB sia già nella catena: in quel caso il click reale apre il nuovo dominio e la cascata prosegue.
- **Redirector**: se un link di Stream8 passa da un dominio intermedio che poi reindirizza al servizio vero, il secondo salto è bloccato (i redirect devono restare nel dominio aperto da Stream8).
- **Frame di origine**: l'API WebView non indica quale frame ha avviato una navigazione. La fiducia è basata sulla pagina principale: un iframe di terze parti dentro una pagina Stream8/TMDB potrebbe far partire un'apertura di app *solo* con un vero tocco dell'utente. Gli iframe non possono comunque navigare altrove senza passare dalla policy.
- **Iframe di terze parti** sono consentiti (servono ai player); possono caricare contenuti da qualunque host HTTPS, ma non uscire dal frame.
- Un `window.open()` eseguito dopo un'attesa lunga può perdere il gesto utente (limite di WebView) e viene bloccato.
- **Cookie di terze parti** sono abilitati (necessari ai player incorporati).
- **APK per architettura**: l'app non contiene codice nativo (la WebView è quella di sistema), quindi i tre APK per architettura sono funzionalmente equivalenti all'universale; sono prodotti perché richiesti. Per l'uso normale basta l'universale.
- **Public Suffix List**: è quella inclusa nella versione di Guava in uso (`33.3.1-android`); si aggiorna aggiornando Guava.
- Download di file e selettore file non sono supportati (non servono a Stream8).
- Licenza: non ancora scelta. Il progetto originale è GPL-3.0.

## Stato della verifica

| Verifica | Esito |
|---|---|
| Compilazione Gradle/Kotlin/APK | **Eseguita** localmente (Gradle 8.9, JDK 17, Android SDK 35): debug e release firmata OK |
| Test unitari (50 casi) | **Eseguiti** localmente: tutti PASS |
| Workflow GitHub Actions | Sintassi YAML valida; prima esecuzione end-to-end con la release `v0.0.2` |
| `scripts/verify-apks.sh` | **Eseguito** sui 4 APK reali: presenza, zip, firma, applicationId, versionName OK |
| XML e risorse Android | Compilati da AAPT2; icona adattiva e fallback PNG verificati nell'APK |
| APK firmati | **Sì**, localmente con la chiave di release; certificato `CN=Scripta` |
