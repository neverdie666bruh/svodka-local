#!/usr/bin/env bash
set -euo pipefail
if [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/platforms" ]]; then
  ANDROID_HOME="${ANDROID_SDK_ROOT}"
elif [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/platforms" ]]; then
  ANDROID_HOME="${ANDROID_HOME}"
else
  ANDROID_HOME="${RUNNER_TEMP:-/tmp}/android-sdk"
  mkdir -p "${ANDROID_HOME}/cmdline-tools"
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf /tmp/cmdline-tools
  unzip -q /tmp/cmdtools.zip -d /tmp
  mkdir -p "${ANDROID_HOME}/cmdline-tools/latest"
  mv /tmp/cmdline-tools/* "${ANDROID_HOME}/cmdline-tools/latest/"
fi
yes | "${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_HOME}" --licenses >/dev/null || true
"${ANDROID_HOME}/cmdline-tools/latest/bin/sdkmanager" --sdk_root="${ANDROID_HOME}" "platforms;android-35" "build-tools;35.0.0" "platform-tools"
echo "sdk.dir=${ANDROID_HOME}" > local.properties
echo "ANDROID_HOME=${ANDROID_HOME}" >> "${GITHUB_ENV:-/dev/null}"
