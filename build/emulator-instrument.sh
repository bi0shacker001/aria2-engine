#!/usr/bin/env bash
# Installs the test APK on the booted emulator and runs the on-device tests with am instrument. Steadier next to an
# emulator than Gradle's connected task, whose own adb connection was the "Unable to connect to adb daemon".
#
#   build/emulator-instrument.sh <test-apk> [<server-base>]
#
# <server-base> is where the device reaches the runner's test server. The default, http://127.0.0.1:8000, goes over
# adb reverse: the CI emulators' own network has been dead ("Machine is not on the network", no route to 10.0.2.2, no
# DNS), and loopback needs none.
set -uo pipefail

apk="$1"
base="${2:-}"
runner="io.github.bi0shacker001.aria2engine.test/androidx.test.runner.AndroidJUnitRunner"
here="$(cd "$(dirname "$0")" && pwd)"

# adb can drop in the first moments after the runner's boot check: restart its server and wait, a few times.
for attempt in 1 2 3 4 5; do
  if adb start-server >/dev/null 2>&1 && timeout 60 adb wait-for-device && [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; then break; fi
  echo "adb is not ready (attempt $attempt)" >&2
  adb kill-server >/dev/null 2>&1 || true
  sleep 5
done
# The tests that need the internet skip themselves when the device has none; say which it is.
adb shell ping -c1 -W3 github.com 2>&1 | head -2 || true
if [ -z "$base" ]; then
  # The emulator's own network, when it works, is steadier for a 5 MiB file than adb reverse (API 28 dropped the
  # tunnel mid-file); when it is dead, loopback over adb reverse needs none.
  if adb shell ping -c1 -W3 10.0.2.2 >/dev/null 2>&1; then base=http://10.0.2.2:8000; else base=http://127.0.0.1:8000; fi
fi
echo "test server: $base"
case "$base" in
  http://127.0.0.1:*) port="${base##*:}"; adb reverse "tcp:$port" "tcp:$port" || { sleep 3; adb reverse "tcp:$port" "tcp:$port"; } || exit 1 ;;
esac

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
