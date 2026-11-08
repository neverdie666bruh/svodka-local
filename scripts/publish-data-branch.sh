#!/usr/bin/env bash
# Пишет только текстовый JSON в ветку data. Без force-push, без артефактов и без кэша.
set -euo pipefail
if [[ -z "${GITHUB_TOKEN:-}" || -z "${GITHUB_REPOSITORY:-}" ]]; then
  echo "Нужны GITHUB_TOKEN и GITHUB_REPOSITORY." >&2
  exit 1
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
cleanup() { rm -rf "${WORK}"; }
trap cleanup EXIT
REMOTE="https://x-access-token:${GITHUB_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
if git ls-remote --heads "${REMOTE}" data | grep -q 'refs/heads/data$'; then
  git clone --branch data --single-branch --depth 1 "${REMOTE}" "${WORK}/data"
else
  mkdir -p "${WORK}/data/public" "${WORK}/data/state"
fi
ARGS=(--data-dir "${WORK}/data" --sources "${ROOT}/sources/sources.json" --rules "${ROOT}/sources/rules.json")
if [[ "${SVODKA_FORCE:-false}" == "true" ]]; then
  ARGS+=(--force)
fi
set +e
(
  cd "${ROOT}"
  ./gradlew --no-daemon --no-build-cache :collector:run --args="${ARGS[*]}"
)
code=$?
set -e
if [[ "${code}" != "0" && "${code}" != "2" ]]; then
  exit "${code}"
fi
cd "${WORK}/data"
if [[ ! -d .git ]]; then
  git init -b data
  git remote add origin "${REMOTE}"
fi
git add -A -- public state
if [[ -f README.md ]]; then
  git add -- README.md
fi
if git diff --cached --name-only | grep -E '(^|/)([^/]+\.(jks|apk)|keystore\.properties|RUNBOOK)'; then
  echo "В ветку data попал запрещённый файл." >&2
  exit 1
fi
if git diff --cached --quiet; then
  echo "Изменений нет."
  exit 0
fi
git -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    -c user.name="github-actions[bot]" \
    commit -m "digest: $(TZ=Asia/Dushanbe date +%F)"
git push --no-force origin HEAD:refs/heads/data
echo "Ветка data обновлена без перезаписи истории."
