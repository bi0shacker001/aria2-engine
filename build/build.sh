#!/usr/bin/env bash
# Cross-compiles libaria2 and what it needs for one Android ABI with the NDK.
#
#   build/build.sh <abi> <out-dir>      abi: arm64-v8a | x86_64
#
# Needs ANDROID_NDK_HOME (or ANDROID_NDK_LATEST_HOME, as GitHub's runners set). Produces <out-dir>/prefix with
# lib/libaria2.a, include/aria2/aria2.h, OpenSSL's and expat's static libraries, and bin/aria2c. Every upstream
# source is checked against vendor/SOURCES before it is used; aria2 is built from its release tarball as shipped,
# with its own configure (no file of it is edited).
set -euo pipefail

abi="$1"
out="$(mkdir -p "$2" && cd "$2" && pwd)"
here="$(cd "$(dirname "$0")/.." && pwd)"
ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
[ -n "$ndk" ] || { echo "set ANDROID_NDK_HOME" >&2; exit 1; }
api=26

case "$abi" in
  arm64-v8a) triple=aarch64-linux-android; openssl_target=android-arm64 ;;
  x86_64)    triple=x86_64-linux-android;  openssl_target=android-x86_64 ;;
  *) echo "unknown ABI $abi" >&2; exit 1 ;;
esac

toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
export PATH="$toolchain/bin:$PATH"
export CC="$toolchain/bin/${triple}${api}-clang"
export CXX="$toolchain/bin/${triple}${api}-clang++"
export AR="$toolchain/bin/llvm-ar"
export RANLIB="$toolchain/bin/llvm-ranlib"
export STRIP="$toolchain/bin/llvm-strip"
prefix="$out/prefix"
work="$out/work"
mkdir -p "$prefix" "$work"
jobs="$(nproc)"

# source <name>: the archive for <name> from vendor/SOURCES, fetched if it is a URL, checked against its digest.
source_archive() {
  local name="$1" line version digest where file
  line="$(grep -E "^$name " "$here/vendor/SOURCES")"
  read -r _ version digest where <<<"$line"
  if [[ "$where" == http* ]]; then
    file="$work/$(basename "$where")"
    [ -f "$file" ] || curl -sSfL --retry 3 -o "$file" "$where"
  else
    file="$here/$where"
  fi
  echo "$digest  $file" | sha256sum -c --quiet - >&2
  echo "$file"
}

unpack() { # unpack <archive> -> the directory it made
  local archive="$1" dir
  dir="$work/$(basename "$archive" | sed -E 's/\.tar\.(gz|xz)$//')"
  rm -rf "$dir"
  tar -xf "$archive" -C "$work"
  echo "$dir"
}

# --- OpenSSL: static, no engines or tests; certificates come from the platform's store at run time (SSL_CERT_DIR).
openssl_dir="$(unpack "$(source_archive openssl)")"
(
  cd "$openssl_dir"
  export ANDROID_NDK_ROOT="$ndk"
  # OpenSSL's android-* targets pick the NDK's clang and its target flags themselves.
  export CC=clang CXX=clang++
  ./Configure "$openssl_target" -D__ANDROID_API__="$api" no-shared no-tests no-docs no-engine no-module \
    --prefix="$prefix" --libdir=lib >/dev/null
  make -j"$jobs" build_libs >/dev/null
  make install_dev >/dev/null
)

# --- expat: static, the library only.
expat_dir="$(unpack "$(source_archive expat)")"
(
  cd "$expat_dir"
  ./configure --host="$triple" --prefix="$prefix" --enable-static --disable-shared \
    --without-docbook --without-examples --without-tests --without-xmlwf >/dev/null
  make -j"$jobs" >/dev/null
  make install >/dev/null
)

# --- aria2: libaria2 (static) and aria2c. No c-ares: names resolve with getaddrinfo, which the sandbox's broker
# serves through netd (docs/design.md, "Network"). No sqlite: cookies come as headers, not browser files.
aria2_dir="$(unpack "$(source_archive aria2)")"
(
  cd "$aria2_dir"
  # Our changes to aria2, each a patch in vendor/patches named after the tarball it applies to (vendor/SOURCES).
  for p in "$here"/vendor/patches/aria2-1.37.0-*.patch; do
    patch -p1 --forward --quiet < "$p"
  done
  ./configure --host="$triple" --build=x86_64-pc-linux-gnu --prefix="$prefix" \
    --enable-libaria2 --enable-static --disable-shared --disable-nls \
    --with-openssl --without-gnutls --without-libnettle --without-libgmp --without-libgcrypt \
    --with-libexpat --without-libxml2 --without-libcares --without-sqlite3 --without-libssh2 --with-libz \
    --without-appletls --without-wintls \
    CFLAGS="-Os -fPIC" CXXFLAGS="-Os -fPIC" CPPFLAGS="-I$prefix/include" \
    LDFLAGS="-L$prefix/lib -static-libstdc++" \
    PKG_CONFIG_LIBDIR="$prefix/lib/pkgconfig" ZLIB_CFLAGS="-DARIA2_ENGINE_NDK_ZLIB" ZLIB_LIBS="-lz" \
    > "$out/aria2-configure.log" || { tail -40 "$out/aria2-configure.log"; exit 1; }
  grep -E "^(OpenSSL|LibExpat|LibZ|LibCares|SQLite3|Libaria2|Bittorrent|Metalink|Epoll):" "$out/aria2-configure.log" || true
  make -j"$jobs" >/dev/null
  make install-strip >/dev/null
)

ls -la "$prefix/lib" "$prefix/bin"
