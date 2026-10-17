#!/usr/bin/env bash
# Links native/smoke/engine_smoke.cc against the libaria2 that build/build.sh made for <abi> in <out-dir>.
#
#   build/smoke.sh <abi> <out-dir>
set -euo pipefail

abi="$1"
out="$(cd "$2" && pwd)"
here="$(cd "$(dirname "$0")/.." && pwd)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
api=26
case "$abi" in
  arm64-v8a) triple=aarch64-linux-android ;;
  x86_64)    triple=x86_64-linux-android ;;
  *) echo "unknown ABI $abi" >&2; exit 1 ;;
esac
cxx="$ndk/toolchains/llvm/prebuilt/linux-x86_64/bin/${triple}${api}-clang++"
prefix="$out/prefix"
# aria2's libaria2.pc lists no static dependencies, so they are named here, in link order.
"$cxx" -std=c++11 -Os -fPIE -pie -I"$prefix/include" "$here/native/smoke/engine_smoke.cc" \
  -o "$out/engine_smoke" "$prefix/lib/libaria2.a" "$prefix/lib/libssl.a" "$prefix/lib/libcrypto.a" \
  "$prefix/lib/libexpat.a" -lz -ldl -llog -static-libstdc++
file "$out/engine_smoke"
