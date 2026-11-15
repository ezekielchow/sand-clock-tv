#!/usr/bin/env bash
# Builds app/build/outputs/apk/release/app-release.apk inside Docker (no local SDK needed).
# Version code = number of commits, so each new build installs as an update.
set -euo pipefail
cd "$(dirname "$0")"
CODE=$(git rev-list --count HEAD 2>/dev/null || echo 1)
NAME="dev-$(git rev-parse --short HEAD 2>/dev/null || echo local)"
docker build -q -t sandclock-builder . > /dev/null
docker run --rm -u "$(id -u):$(id -g)" -v "$PWD":/src -w /src \
  -e HOME=/src/.home -e GRADLE_USER_HOME=/src/.gradle-cache \
  sandclock-builder gradle --no-daemon -q assembleRelease -PversionCode="$CODE" -PversionName="$NAME"
echo "Built version $NAME ($CODE): $PWD/app/build/outputs/apk/release/app-release.apk"
