#!/usr/bin/env bash
# Verifica i 4 APK in <cartella> (default: build/dist).
#   REQUIRE_SIGNED=1 (default)  richiede una firma valida (apksigner); 0 la salta (build di CI senza chiavi)
#   EXPECTED_VERSION=1.2.3      se impostata, controlla anche versionName
# Esce con codice != 0 se manca un APK o se un controllo fallisce.
set -euo pipefail

DIST="${1:-build/dist}"
REQUIRE_SIGNED="${REQUIRE_SIGNED:-1}"
EXPECTED_VERSION="${EXPECTED_VERSION:-}"
APP_ID="cc.poppi.stream8"
ABIS=(arm64-v8a armeabi-v7a x86_64)
FILES=(Stream8-arm64-v8a.apk Stream8-armeabi-v7a.apk Stream8-x86_64.apk Stream8-universal.apk)

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
  if ! unzip -tq "$p" >/dev/null 2>&1; then err "Archivio APK corrotto: $f"; continue; fi

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

  libs="$(unzip -Z1 "$p" | grep '^lib/' | cut -d/ -f2 | sort -u | tr '\n' ' ' || true)"
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
echo "Verifica APK superata: 4/4 APK presenti e coerenti."
