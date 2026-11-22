#!/usr/bin/env bash
# Готує інструменти збірки без Android SDK (dl.google.com може бути недоступний).
# Потрібні: Ubuntu 24.04, JDK 17+, Gradle 8+, доступ до archive.ubuntu.com,
# repo.maven.apache.org та storage.googleapis.com.
set -euo pipefail
TOOLCHAIN="${HAMIR_TOOLCHAIN:-/opt/hamir-toolchain}"
R8_VERSION="8.13.25"
mkdir -p "$TOOLCHAIN"
if ! command -v aapt2 >/dev/null || ! command -v zipalign >/dev/null || ! command -v apksigner >/dev/null; then
  apt-get update
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends aapt zipalign apksigner dexdump zip
fi
if [ ! -s "$TOOLCHAIN/r8.jar" ]; then
  curl -fsSL -o "$TOOLCHAIN/r8.jar" "https://storage.googleapis.com/r8-releases/raw/$R8_VERSION/r8lib.jar"
fi
echo "Toolchain ready in $TOOLCHAIN"
