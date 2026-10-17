#!/usr/bin/env bash
# Waits until a booted emulator has a default network: a fresh API 34 image brings its network up some seconds after
# boot completes, and a test started before that fails at its first connection. Never fails the job on its own: a
# network that is still missing shows in the test that needed it.
set -uo pipefail

for _ in $(seq 1 60); do
  if adb shell dumpsys connectivity 2>/dev/null | grep -qE 'Active default network: [0-9]+'; then
    echo "the emulator has a default network"
    sleep 5
    exit 0
  fi
  sleep 2
done
echo "the emulator has no default network yet; going on"
exit 0
