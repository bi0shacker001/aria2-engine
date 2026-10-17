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
libs="$(PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" pkg-config --static --libs libaria2)"
# shellcheck disable=SC2086
"$cxx" -std=c++11 -Os -fPIE -pie -I"$prefix/include" "$here/native/smoke/engine_smoke.cc" \
  -o "$out/engine_smoke" -L"$prefix/lib" $libs -static-libstdc++
file "$out/engine_smoke"
