#!/usr/bin/env bash
# Сверяет сертификат уже собранного release APK.
# Ожидаемый SHA-256 задаёт workflow, не поле ручного запуска и не ввод пользователя.
# Пароли и ключ этот шаг не читает и не печатает.
set -euo pipefail

expected="$(printf '%s' "${EXPECTED_CERT_SHA256:-}" | tr -d ' :' | tr 'A-F' 'a-f')"
if [[ ! "${expected}" =~ ^[0-9a-f]{64}$ ]]; then
  echo "В workflow не задан SHA-256 сертификата из 64 hex." >&2
  exit 1
fi

apk="dist/svodka-local-release.apk"
if [[ ! -f "${apk}" ]]; then
  echo "Нет ${apk}. Артефакт не загружается." >&2
  exit 1
fi

mapfile -t apks < <(find dist -type f -name '*.apk' | sort)
if [[ "${#apks[@]}" -ne 1 || "${apks[0]}" != "${apk}" ]]; then
  echo "В dist должен лежать ровно один APK: ${apk}." >&2
  exit 1
fi

apksigner="${ANDROID_HOME:-}/build-tools/35.0.0/apksigner"
if [[ ! -x "${apksigner}" ]]; then
  echo "Нет apksigner в build-tools 35.0.0." >&2
  exit 1
fi

set +e
cert_out="$("${apksigner}" verify --print-certs "${apk}" 2>&1)"
code=$?
set -e
printf '%s\n' "${cert_out}"
if [[ "${code}" -ne 0 ]]; then
  echo "apksigner отклонил подпись APK." >&2
  exit 1
fi

CERT_TEXT="${cert_out}" python3 - "${expected}" <<'PY'
import os
import re
import sys

expected = sys.argv[1]
text = os.environ.get("CERT_TEXT", "")
digests = [
    re.sub(r"[^0-9a-fA-F]", "", item).lower()
    for item in re.findall(r"certificate SHA-256 digest:\s*([0-9A-Fa-f:]+)", text)
]
if digests != [expected]:
    shown = ", ".join(digests) if digests else "нет"
    print(f"Сертификат APK не совпал с отпечатком из workflow. Найдено: {shown}", file=sys.stderr)
    sys.exit(1)
print("Сертификат APK совпал с SHA-256, записанным в workflow.")
PY
