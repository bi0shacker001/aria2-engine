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
2. **The engine service** (`EngineService`, `:aria2`, `android:isolatedProcess`): it confines itself to the
   host's broker with the sandbox library's lockdown (bi0shacker001/sandbox, `vendor/sandbox`), then runs one
   libaria2 session on one thread (`EngineThread`; libaria2 is not thread-safe), which alternates a turn of the
   session's loop with the commands queued from binder threads. Below Android 11 the same engine runs as
   `EngineOpenService` (see Processes). A stopped engine ends its process, because the lockdown and libaria2 start
   once per process.
3. **The JNI layer** (`native/engine`, `libaria2engine.so`, in the engine process only) and **the broker**
   (`native/broker`, `libaria2broker.so`, in the host's process; it never loads libaria2).
4. **The Kotlin API** (`DownloadEngine`) for the host's main process: start, add (HTTP, HTTPS, FTP, torrent,
   magnet, Metalink), pause, resume, remove, global and per-download limits, status, and download events, over a
   typed binder interface (`IEngine`) whose every method checks that the caller is the host's own UID and the
   process that started the session. aria2's JSON-RPC is on for the host as well: loopback only, through the
   broker, with a secret made for each run.

## Processes

The owner: "We have our own sandbox library, remember? That's the bare minimum. Isolate it as a separate process if
we CAN, but if not, sandboxing it ourselves is better than nothing". So the engine always runs in a process of its
own under the sandbox library's lockdown and the host's broker, on every Android version:

- **Android 11 (API 30) and later:** `EngineService`, an isolated process under a UID of its own. Its paths outside
  the engine's folder stay the kernel's, where that UID can reach nothing of the host's.
- **Android 8 to 10 (API 26 to 29):** `EngineOpenService` (`:aria2_open`), a process under the host's UID, because
  an isolated process there cannot use a socket it was handed. The lockdown sends every path it names to the
  broker, not only the engine's folder. The broker answers that folder, the platform's read-only code and data, and
  the host's own APKs and native libraries, read-only; never the host's data or shared storage. Its sockets are the
  broker's, as in the isolated engine. It becomes the isolated engine when the sandbox's byte relay covers these
  versions.

The order (the owner: "all my devices run 11 or later, so once we've tested it with just our sandbox, we should build
the android isolation path"): the sandboxed engine first, on every version, Android 11 and later included. Then the
isolated one, kept in the library and chosen per host with `DownloadEngine(androidIsolation = true)`, which defaults
to off until it has passed its own rig check.

The host's `Application` also runs in `:aria2_open`, so a host must start nothing of its own there.

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

## Options

`EngineConfig` decides what libaria2 may do, whatever the caller asks, the host included:

- **Refused:** every option that runs a program (`on-download-complete` and the other hooks) or names a file
  outside the engine's folder (a session, cookie jar, log, netrc or certificate file, `input-file`,
  `conf-path`). Nothing the engine downloads is ever run.
- **Forced:** certificate checks on; RPC listening on loopback only; DHT state files under the engine's folder.
- **A download's `dir`** must be under the engine's folder, `/aria2/downloads`; any other is replaced by it.
- **RPC** is off unless the host gives it a secret, and a run-time change cannot turn it on or move it.
- **Defaults:** torrents don't seed (`seed-time=0`) unless the host asks, and files are not preallocated.

## Identity

The owner: "What if we just report a semi-sanitized android webview version?" and "The exception is for torrents
(don't wanna be suspected of tracker rule evasion)". So:
- **HTTP(S) and FTP:** these downloads report the device's WebView user agent in Chrome's reduced form:
  - no `; wv`;
  - `Linux; Android 10; K` as the platform;
  - only Chrome's major version, for example `Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like
    Gecko) Chrome/141.0.0.0 Mobile Safari/537.36`.
  It is recomputed when WebView updates (`UserAgent`, `DownloadEngine.userAgent`).
- **A request's own agent:** a request that carries its own `user-agent` (a site session's) keeps it.
- **BitTorrent:** a torrent, a magnet or a Metalink is never given one. aria2's `user-agent` would also go to HTTP
  trackers, so trackers and peers see aria2's own identity (`peer-agent`, `peer-id-prefix` and its tracker
  agent).

## Releases

A tag `v<version>` publishes `aria2-engine-<version>.aar` with its SHA-256 as a GitHub release. droidtop and
Enginehost bundle that AAR, pinned by digest.

## Not built in

- c-ares (see Network).
- sqlite: aria2 uses it only to read browser cookie databases (`--load-cookies`); hosts pass cookies as headers.
- libssh2 (SFTP), GnuTLS, libxml2 (expat parses Metalink).

## Testing

- **On the JVM:** `EngineConfigTest`, `UserAgentTest` and `DownloadStatusTest`.
- **On the emulator** (API 28 under the host's UID; 30 and 34 isolated), `EngineServiceTest` exercises the whole path: the isolated process, the
  lockdown, the broker's files and sockets, and libaria2. It checks:
  - HTTP from the runner into the host's folder;
  - HTTPS with the platform's certificates;
  - that a hook option is refused;
  - that a folder outside the engine's is not reachable.
- **The smoke test:** `build/smoke.sh` links `native/smoke/engine_smoke.cc`, which drives libaria2 directly (session, add, run, events).
CI runs it on the emulator (API 30 and 34) over HTTP from the runner and over HTTPS with the platform's
certificates.
