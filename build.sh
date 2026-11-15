#!/usr/bin/env bash
# Builds app/build/outputs/apk/release/app-release.apk inside Docker (no local SDK needed).
set -euo pipefail
cd "$(dirname "$0")"
docker build -q -t sandclock-builder . > /dev/null
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -w /src \
  -e HOME=/src/.home -e GRADLE_USER_HOME=/src/.gradle-cache \
  sandclock-builder gradle --no-daemon -q assembleRelease
echo "Built: $PWD/app/build/outputs/apk/release/app-release.apk"
