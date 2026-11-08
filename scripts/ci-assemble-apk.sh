#!/usr/bin/env bash
# Собирает только подписанный release APK. Debug и ключ в артефакт не кладёт.
set -euo pipefail
if [[ -z "${SVODKA_LOCAL_KEYSTORE_B64:-}" || -z "${SVODKA_LOCAL_KEYSTORE_PASSWORD:-}" || -z "${SVODKA_LOCAL_KEY_ALIAS:-}" || -z "${SVODKA_LOCAL_KEY_PASSWORD:-}" ]]; then
  echo "Нет secrets подписи. Debug APK не загружается." >&2
  exit 1
fi
KS="${RUNNER_TEMP:-/tmp}/svodka-local-release.jks"
cleanup() {
  rm -f "${KS}" keystore.properties
}
trap cleanup EXIT
umask 077
echo "${SVODKA_LOCAL_KEYSTORE_B64}" | base64 -d > "${KS}"
cat > keystore.properties <<EOF
storeFile=${KS}
storePassword=${SVODKA_LOCAL_KEYSTORE_PASSWORD}
keyAlias=${SVODKA_LOCAL_KEY_ALIAS}
keyPassword=${SVODKA_LOCAL_KEY_PASSWORD}
EOF
chmod 600 keystore.properties "${KS}" || true
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
bash "${ROOT}/scripts/bootstrap-gradle.sh"
./gradlew --no-daemon --no-build-cache :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/svodka-local-release.apk
if compgen -G 'dist/*' | grep -E '\.(jks|properties)$'; then
  echo "Рядом с APK лежит ключ." >&2
  exit 1
fi
