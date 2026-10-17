# aria2-engine

aria2 (libaria2) as an isolated Android download engine, shared by droidtop and Enginehost: an NDK build of
libaria2, an isolated engine service under the sandbox library's lockdown, a JNI layer and a small Kotlin API.
See [docs/design.md](docs/design.md).

Build one ABI (needs the NDK in `ANDROID_NDK_HOME`):

    build/build.sh arm64-v8a out/arm64-v8a
    build/smoke.sh arm64-v8a out/arm64-v8a

## Licence

GPL-3.0-or-later (see LICENSE). aria2, vendored unmodified in `vendor/`, is GPL-2.0-or-later with an exception
for linking OpenSSL; OpenSSL is Apache-2.0; expat is MIT.
