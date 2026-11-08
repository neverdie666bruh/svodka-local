#!/usr/bin/env bash
# Создаёт новую подпись только для этого приложения.
# Пароль не печатается. Файлы не коммитятся.
set -euo pipefail
DEST="${1:-/cursor/stores/self/signing}"
mkdir -p "${DEST}"
chmod 700 "${DEST}" 2>/dev/null || true
KS="${DEST}/svodka-local-release.jks"
PROPS="${DEST}/keystore.properties"
if [[ -f "${KS}" && -f "${PROPS}" ]]; then
  echo "Подпись уже есть: ${KS}"
  exit 0
fi
PASS="$(openssl rand -base64 32 | tr -d '/+=' | head -c 32)"
keytool -genkeypair -keystore "${KS}" -storetype JKS -alias svodka-local \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass "${PASS}" -keypass "${PASS}" \
  -dname "CN=Svodka Lokal, O=Independent, C=TJ" >/dev/null
umask 077
cat > "${PROPS}" <<EOF
storeFile=${KS}
storePassword=${PASS}
keyAlias=svodka-local
keyPassword=${PASS}
EOF
chmod 600 "${KS}" "${PROPS}" 2>/dev/null || true
cp "${PROPS}" "$(cd "$(dirname "$0")/.." && pwd)/keystore.properties"
chmod 600 "$(cd "$(dirname "$0")/.." && pwd)/keystore.properties" 2>/dev/null || true
echo "Создана новая подпись. Пароль записан только в ${PROPS}."
