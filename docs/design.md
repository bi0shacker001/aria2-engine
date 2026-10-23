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

**The internet only (2026-10-22, Droidtop/tracker#577; droidtop's owner: "Block local network").** A torrent's
peers and web seeds, a Metalink's mirrors and any redirect the host did not walk itself are addresses no host
checked, so the broker puts every `connect` to `net_scope` (native/broker/net_scope.c) and refuses the device
itself and the network it is on: loopback, private, carrier NAT (100.64.0.0/10), link-local, multicast, reserved
and unspecified addresses, also as IPv4-mapped or NAT64 IPv6, and anything it cannot read. A system socket (the
resolver, fwmarkd) is unaffected, and so are listening and accepting. A host turns it off with
`DownloadEngine(localNetwork = true)`: the on-device tests do, for their server on the runner.

**Passes (v0.1.2; droidtop's owner: block torrent traffic across the local network, not a person's own servers).**
The broker cannot tell which download a connect is for, so the host vouches for addresses instead: when it walked an
HTTP download itself to a server on the local network or a tailnet (a NAS, `nas.tailnet.ts.net` on 100.64.0.0/10),
it calls `DownloadEngine.allowLocal(address, port)` before adding it and `revokeLocal` when it ends. A connect to
exactly that address and port is let through; every other local address stays refused, so a torrent's peers, web
seeds and a Metalink's mirrors still never reach the network. Passes are counted, so two downloads from one server
do not close each other's. While a pass is open, a peer at that very address and port could connect too: it would
have to be the person's own server, during their own download.

**What the host vouches for (v0.1.3; droidtop's owner: "droidtop can vouch for all local links, not just
torrents").** Every address the host itself handed over may get a pass: a direct link; a torrent's trackers and web
seeds and a Metalink's mirrors, when the file or magnet came from the host. The engine reads those files, never the
host, and [status] reports what it found: each file's `uris` (web seeds, mirrors) and a torrent's `announceList`.
A host that wants to vouch for them adds the download with aria2's `pause` option, reads the status, gives its
passes, then [resume]s it, so the engine contacts nothing before the passes exist. What the engine finds on its own
(DHT, PEX, the peers a tracker answers with, a redirect the host did not walk) never gets one. **Not covered:** a
UDP datagram sent without a connect names its address in memory the broker never sees (the sandbox's `sbx.h`), so
DHT packets can still go to a local address; they carry no host data.

## Files

The engine opens no path itself. Under the lockdown every path call traps to the sandbox's handler, and the
host's broker opens it under that download's rules: a download writes only its destination and aria2's
`.aria2` control file beside it, inside the folder it was given. TLS certificates are the platform's own system CAs,
which the host writes at every start as one PEM file in the engine's state folder (`CaBundle`). Android names its
CA files by OpenSSL's old subject hash, which OpenSSL 3 does not look up, so the directory itself will not do. The
engine forces that bundle as `ca-certificate`: no caller can name another.

## Options

`EngineConfig` decides what libaria2 may do, whatever the caller asks, the host included:

- **Refused:** every option that runs a program (`on-download-complete` and the other hooks) or names a file
  outside the engine's folder (a session, cookie jar, log, netrc or certificate file, `input-file`,
  `conf-path`). Nothing the engine downloads is ever run.
- **Forced:** certificate checks on; RPC listening on loopback only; DHT state files under the engine's folder;
  libaria2's log at `.state/engine.log` in the engine's folder (`warn` unless the host asks for another level),
  whose end a failed start reports.
- **A download's `dir`** must be under the engine's folder, `/aria2/downloads`; any other is replaced by it.
- **RPC** is off unless the host gives it a secret, and a run-time change cannot turn it on or move it.
- **A start that fails** after the lockdown ends the engine's process, since the lockdown holds for its life:
  the host's next start binds a fresh one.
- **Defaults:** torrents don't seed (`seed-time=0`) unless the host asks, and files are not preallocated.
- **`byte-range=START-END`** (ours, not upstream's; `vendor/patches/aria2-1.37.0-byte-range.patch`): one download is
  the bytes START to END (inclusive) of a remote HTTP(S) file, for a store whose files are ranges of one big file
  (GOG's Gen 1 `main.bin`). Every request asks for its part of that range, including the first, which upstream sends
  without a Range; only a `206` whose Content-Range is exactly that part is taken, so a server that ignores the range
  fails the download (aria2's "cannot resume", 8) rather than writing the whole file; and the download's length is the
  range's. A resume asks for the rest of the range. Use it with `split=1`.

## Patches to aria2

aria2 is vendored as its release tarball, checked against its digest (`vendor/SOURCES`). What this repository changes
in it is a patch per change in `vendor/patches`, named after the tarball it applies to, which `build/build.sh` applies
after unpacking; nothing else edits aria2's source. A new aria2 release means checking each patch still applies.

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
  - that a `byte-range` download is its own file of exactly those bytes;
  - that a folder outside the engine's is not reachable.
- **The smoke test:** `build/smoke.sh` links `native/smoke/engine_smoke.cc`, which drives libaria2 directly (session, add, run, events).
CI runs it on the emulator (API 30 and 34) over HTTP from the runner and over HTTPS with the platform's
certificates.
