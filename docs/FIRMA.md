# Firma degli APK

Gli APK release devono essere firmati sempre con **la stessa chiave**: Android rifiuta di aggiornare un'app se la firma cambia.
Nessun keystore né password deve mai finire nel repository (`.gitignore` esclude `*.jks`, `*.keystore`, `*.p12`).

## 1. Crea il keystore (una sola volta)

Serve il JDK (comando `keytool`). Scegli password robuste e annotale in un gestore di password.

```bash
keytool -genkeypair -v \
  -keystore stream8-release.jks \
  -alias stream8 \
  -keyalg RSA -keysize 4096 -validity 10000
```

**Fai subito almeno due backup di `stream8-release.jks` e delle password in luoghi sicuri e separati dal repository.**
Se perdi la chiave non potrai più pubblicare aggiornamenti installabili sopra le versioni esistenti.

## 2. Codifica il keystore in Base64

```bash
base64 -w0 stream8-release.jks        # Linux
base64 -i stream8-release.jks         # macOS
certutil -encode stream8-release.jks out.b64   # Windows (usa il contenuto di out.b64 senza intestazioni)
```

## 3. Crea i Secrets su GitHub

Repository → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Valore |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | l'output Base64 del passo 2 (una riga) |
| `ANDROID_KEYSTORE_PASSWORD` | password del keystore |
| `ANDROID_KEY_ALIAS` | l'alias scelto (es. `stream8`) |
| `ANDROID_KEY_PASSWORD` | password della chiave |

Poi elimina dal computer i file temporanei con il Base64.

## 4. Pubblica

Crea il tag `vX.Y.Z` (vedi README). `release.yml` decodifica il keystore in una cartella temporanea del runner, compila,
verifica la firma con `apksigner verify`, e cancella il keystore a fine job. Se uno dei quattro Secrets manca il workflow
**si interrompe con un messaggio esplicativo** e non pubblica nulla.

## Firma in locale

```bash
export ANDROID_KEYSTORE_FILE=/percorso/stream8-release.jks
export ANDROID_KEYSTORE_PASSWORD=…
export ANDROID_KEY_ALIAS=stream8
export ANDROID_KEY_PASSWORD=…
./gradlew collectReleaseApks
```

Controlla il certificato: `apksigner verify --print-certs build/dist/Stream8-universal.apk`.
