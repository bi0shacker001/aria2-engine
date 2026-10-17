# aria2-engine: design

aria2 (built as libaria2) as the download engine of droidtop and Enginehost, in a process of its own. The owner
(2026-10-16 and 2026-10-17, Droidtop/tracker#577): "We'll use the libaria2 library internally in droidtop AND in
enginehost, and centralize downloads."; "it's important for security that we make sure plugin downloads are
properly isolated, so the downloader should probably have some isolation. We don't want an injection attack to
dump binary into our main process"; "No, the Aria2 RPC port is still available to droidtop. We just don't let
plugins access it directly, we broker it". The host app's own specification has the full model (droidtop:
docs/SPEC.md 12a "One download engine" and docs/plugin-api.md 5.7).

## Layers

1. **libaria2**, from aria2's release tarball as shipped (`vendor/`, never edited), cross-compiled with the NDK
   for arm64-v8a and x86_64 against OpenSSL, expat and the NDK's zlib (`build/build.sh`).
2. **The engine service**: an Android isolated service that runs one libaria2 session under the sandbox
   library's lockdown (bi0shacker001/sandbox). It drives the session from a thread of its own and reports
   events. (Next.)
3. **The JNI layer and a Kotlin API** for the host app's main process: add (HTTP, FTP, torrent, magnet,
   metalink), pause, resume, remove, global and per-download limits, and progress and completion events, over a
   typed binder interface with caller checks. aria2's JSON-RPC is enabled for the host only (loopback, a secret
   per run). (Next.)

## Network

The engine's sockets are the sandbox's brokered sockets (`SBX_LOCK_NET_BROKERED`): the host's broker makes,
connects and accepts them. Names resolve with `getaddrinfo` through netd's `dnsproxyd`, which the broker
connects, so aria2 is built without c-ares (which would send its own DNS packets). Connection loss pauses every
download; the network's return resumes it from what is on disk, under the host's network rules.

## Files

The engine opens no path itself. Under the lockdown every path call traps to the sandbox's handler, and the
host's broker opens it under that download's rules: a download writes only its destination and aria2's
`.aria2` control file beside it, inside the folder it was given. TLS certificates are the platform's own store,
read-only (`SSL_CERT_DIR=/system/etc/security/cacerts`, already in OpenSSL's hashed-directory form).

## Not built in

- c-ares (see Network).
- sqlite: aria2 uses it only to read browser cookie databases (`--load-cookies`); hosts pass cookies as headers.
- libssh2 (SFTP), GnuTLS, libxml2 (expat parses Metalink).

## Testing

`build/smoke.sh` links `native/smoke/engine_smoke.cc`, which drives libaria2 directly (session, add, run, events).
CI runs it on the emulator (API 30 and 34) over HTTP from the runner and over HTTPS with the platform's
certificates.
