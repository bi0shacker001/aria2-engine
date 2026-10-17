#!/usr/bin/env bash
# Runs the smoke test on a booted emulator: <artifact-dir> holds the x86_64 build (engine_smoke and aria2c).
# The runner serves served/blob.bin (5 MiB) on port 8000, which the emulator reaches over adb reverse as 127.0.0.1
# (its own network may be dead; HTTPS needs the internet and is skipped without it).
set -euo pipefail

art="$1"
adb reverse tcp:8000 tcp:8000
smoke="$(find "$art" -name engine_smoke -type f | head -1)"
aria2c="$(find "$art" -name aria2c -type f | head -1)"
adb push "$smoke" /data/local/tmp/engine_smoke
adb push "$aria2c" /data/local/tmp/aria2c
adb shell chmod 755 /data/local/tmp/engine_smoke /data/local/tmp/aria2c
adb shell /data/local/tmp/aria2c --version | head -12

status=0
echo "== HTTP from the runner"
adb shell 'cd /data/local/tmp && rm -rf dl && mkdir dl && ./engine_smoke http://127.0.0.1:8000/blob.bin /data/local/tmp/dl 5242880' || status=1
if ! adb shell ping -c1 -W3 github.com >/dev/null 2>&1; then
  echo "== HTTPS skipped: the emulator has no internet"
  exit "$status"
fi
echo "== HTTPS with the platform's certificates (one PEM bundle: Android names them by OpenSSL's old hash)"
adb shell 'cd /data/local/tmp && rm -rf dls && mkdir dls && cat /system/etc/security/cacerts/* > ca.pem && ./engine_smoke https://github.com/aria2/aria2/releases/download/release-1.37.0/aria2-1.37.0.tar.xz /data/local/tmp/dls 1705256 /data/local/tmp/ca.pem' || status=1
exit "$status"
