#!/usr/bin/env bash
# Скачивает закреплённый официальный дистрибутив Gradle и проверяет SHA-256.
set -euo pipefail
exec python3 "$(cd "$(dirname "$0")" && pwd)/bootstrap-gradle.py" "$@"
