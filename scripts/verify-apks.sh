#!/usr/bin/env bash
# Verifica gli APK elencati in <cartella>/expected-apks.txt (generato da collectReleaseApks).
#   REQUIRE_SIGNED=1 (default)  richiede una firma valida (apksigner); 0 la salta (build di CI senza chiavi)
#   EXPECTED_VERSION=1.2.3      se impostata, controlla anche versionName
# Esce con codice != 0 se manca un APK o se un controllo fallisce.
set -euo pipefail

DIST="${1:-build/dist}"
REQUIRE_SIGNED="${REQUIRE_SIGNED:-1}"
EXPECTED_VERSION="${EXPECTED_VERSION:-}"
EXPECTED_LIST="$DIST/expected-apks.txt"
APP_ID_FILE="$DIST/expected-app-id.txt"

if [ ! -f "$EXPECTED_LIST" ]; then
  echo "::error::manca $EXPECTED_LIST: esegui prima ./gradlew collectReleaseApks" >&2
  exit 1
fi
if [ ! -f "$APP_ID_FILE" ]; then
  echo "::error::manca $APP_ID_FILE: esegui prima ./gradlew collectReleaseApks" >&2
  exit 1
fi
APP_ID="$(head -n1 "$APP_ID_FILE")"
FILES=()
while IFS= read -r line; do FILES+=("$line"); done < "$EXPECTED_LIST"
ABIS=()
for f in "${FILES[@]}"; do
  if [ "$f" != "Stream8-universal.apk" ]; then
    abi="${f#Stream8-}"
    ABIS+=("${abi%.apk}")
  fi
done

# unzip non è sempre installato (es. NixOS); Python 3 basta per integrità ed elenco dei file.
if command -v unzip >/dev/null 2>&1; then
  zip_test() { unzip -tq "$1" >/dev/null 2>&1; }
  zip_list() { unzip -Z1 "$1"; }
elif command -v python3 >/dev/null 2>&1; then
  zip_test() {
    python3 -c 'import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    sys.exit(1 if z.testzip() else 0)' "$1"
  }
  zip_list() {
    python3 -c 'import sys, zipfile
print("\n".join(zipfile.ZipFile(sys.argv[1]).namelist()))' "$1"
  }
else
  echo "::error::serve unzip o python3 per verificare gli APK" >&2
  exit 1
fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT=""
if [ -n "$SDK" ] && [ -d "$SDK/build-tools" ]; then
  BT="$SDK/build-tools/$(ls -1 "$SDK/build-tools" | sort -V | tail -n1)"
fi
APKSIGNER="$BT/apksigner"
AAPT2="$BT/aapt2"

fail=0
err() { echo "::error::$*"; fail=1; }

for f in "${FILES[@]}"; do
  p="$DIST/$f"
  echo "== $f"
  if [ ! -s "$p" ]; then err "APK mancante o vuoto: $f"; continue; fi
  if ! zip_test "$p"; then err "Archivio APK corrotto: $f"; continue; fi

  if [ "$REQUIRE_SIGNED" = "1" ]; then
    if [ -x "$APKSIGNER" ]; then
      "$APKSIGNER" verify "$p" >/dev/null 2>&1 || err "Firma assente o non valida: $f"
    else
      err "apksigner non trovato (ANDROID_HOME non impostato?)"
    fi
  fi

  if [ -x "$AAPT2" ]; then
    badging="$("$AAPT2" dump badging "$p" 2>/dev/null | head -n1 || true)"
    case "$badging" in *"name='$APP_ID'"*) ;; *) err "$f: applicationId diverso da $APP_ID";; esac
    if [ -n "$EXPECTED_VERSION" ]; then
      case "$badging" in *"versionName='$EXPECTED_VERSION'"*) ;; *) err "$f: versionName diverso da $EXPECTED_VERSION";; esac
    fi
  fi

  libs="$(zip_list "$p" | grep '^lib/' | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)"
  if [ "$f" = "Stream8-universal.apk" ]; then
    for l in $libs; do
      case " ${ABIS[*]} " in *" $l "*) ;; *) err "$f contiene una ABI non prevista: $l";; esac
    done
    if [ -n "$libs" ]; then
      for a in "${ABIS[@]}"; do
        case " $libs " in *" $a "*) ;; *) err "$f: manca la ABI $a";; esac
      done
    fi
  else
    abi="${f#Stream8-}"; abi="${abi%.apk}"
    for l in $libs; do
      [ "$l" = "$abi" ] || err "$f contiene librerie native di un'altra ABI: $l"
    done
  fi
  echo "   dimensione: $(wc -c < "$p") byte; librerie native: ${libs:-nessuna (app senza codice nativo)}"
done

if [ "$fail" -ne 0 ]; then echo "Verifica APK FALLITA"; exit 1; fi
echo "Verifica APK superata: ${#FILES[@]} APK presenti e coerenti."
