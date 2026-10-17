#!/usr/bin/env bash
# Waits until a booted emulator reaches the runner's test server (10.0.2.2:8000) and resolves names: a fresh API 34
# image brings its network up some seconds after boot completes, and a test started before that fails at its first
# connection. The emulator's user-mode network does not answer pings from outside, so a connect and a lookup are the
# probes: ping prints its "PING host (address)" line once the name resolved, whether or not a reply comes.
set -uo pipefail

for _ in $(seq 1 60); do
  if adb shell 'toybox nc -w 3 10.0.2.2 8000 </dev/null' >/dev/null 2>&1 &&
    adb shell 'ping -c 1 -W 1 github.com 2>&1' | grep -q '^PING github.com ('; then
    echo "the emulator's network is up"
    exit 0
  fi
  sleep 2
done
echo "the emulator's network did not come up"
adb shell ip addr || true
exit 1
