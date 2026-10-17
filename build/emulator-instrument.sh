#!/usr/bin/env bash
# Installs the test APK on the booted emulator and runs the on-device tests with am instrument. Steadier next to an
# emulator than Gradle's connected task, whose own adb connection was the "Unable to connect to adb daemon".
#
#   build/emulator-instrument.sh <test-apk> [<server-base>]
#
# <server-base> is where the emulator reaches the runner's test server (default http://10.0.2.2:8000).
set -uo pipefail

apk="$1"
base="${2:-http://10.0.2.2:8000}"
runner="io.github.bi0shacker001.aria2engine.test/androidx.test.runner.AndroidJUnitRunner"
here="$(cd "$(dirname "$0")" && pwd)"

# adb can drop in the first moments after the runner's boot check: restart its server and wait, a few times.
for attempt in 1 2 3 4 5; do
  if adb start-server >/dev/null 2>&1 && timeout 60 adb wait-for-device && [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  echo "adb is not ready (attempt $attempt)" >&2
  adb kill-server >/dev/null 2>&1 || true
  sleep 5
done
"$here/wait-network.sh" || exit 1

adb install -r -t "$apk" || { sleep 5; adb install -r -t "$apk"; } || exit 1
status=1
for attempt in 1 2; do
  adb logcat -c || true
  adb shell am instrument -w -e serverBase "$base" "$runner" | tee instrument.txt
  if grep -q '^OK (' instrument.txt; then status=0; break; fi
  # A run that never started its tests (the instrumentation died, adb dropped) is tried once more; a test failure is not.
  if grep -qE '^Tests run:|^FAILURES' instrument.txt; then break; fi
  echo "the instrumentation did not run its tests (attempt $attempt)" >&2
  sleep 5
done
if [ "$status" -ne 0 ]; then
  adb logcat -d | grep -iE 'aria2|sandbox|F DEBUG|AndroidRuntime|TestRunner|Exception' | tail -200
fi
exit "$status"
