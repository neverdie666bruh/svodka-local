#!/usr/bin/env bash
# Проверяет видимость репозитория встроенным GITHUB_TOKEN.
# Это не ключ модели и не личный токен.
set -euo pipefail
if [[ -z "${GITHUB_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "Нужны GITHUB_TOKEN и GITHUB_REPOSITORY из среды Actions." >&2
  exit 1
fi
visibility="$(
  curl -fsS \
    -H "Authorization: Bearer ${GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${GITHUB_REPOSITORY}" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("visibility",""))'
)"
if [[ "${visibility}" != "public" ]]; then
  echo "Отказ: видимость «${visibility}». Приватный стандартный runner расходует включённые минуты и затем может тарифицироваться." >&2
  exit 1
fi
echo "Репозиторий публичный."
