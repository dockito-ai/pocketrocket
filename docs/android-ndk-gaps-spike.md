# Android NDK gaps spike

31 Oct 2026, on Shankar's phone (arm64, Android 16 / API 36, 64-bit only: `ro.product.cpu.abilist` is `arm64-v8a`). Branch `spike/ndk-gaps`. The question: how far can the NDK add-on (decision 50, `docs/android-build.md`, `docs/android-ndk-spike.md`) close its gaps? Everything below was run on the phone. Nothing in `pocketrocket/` or `setup/` changed. The installed shim and `~/.gradle/init.d` were not touched; the prototypes run from `~/.cache/ndk-gaps-spike/` and a `-I` init script.

## Result

| Gap | Verdict | Cost |
|---|---|---|
| 1. ndk-build | **Works.** PocketRocket's own `:app`, `:termux-shared`, `:terminal-emulator` build through Gradle with ndk-build | 1.2 MB fetched from AOSP, 0.7 MB on disk |
| 2. Other ABIs | **Works, with caveats.** armeabi-v7a, x86, x86_64 build and link through ndk-build and CMake. Not run: the phone has no 32-bit or x86 support | `ndk-multilib`: 72.9 MB download; 142 MB on disk if we keep only what we use, 433 MB if installed as a package |
| 3. `c++_static` | **Works.** `libc++_static.a` for all four ABIs is in `ndk-multilib`. An arm64 binary runs and catches exceptions | Inside gap 2's cost (104 MB of the 142 MB) |
| 4. Compile-only tasks | **Works.** With no NDK, `compileDebugKotlin` and 1006 unit tests pass; only native tasks fail, with a plain message | None |
| 5. C and C++ tile | **Works.** A CMake C++20 program and a make C program build and run | About 95 MB download, 583 MB on disk for the compiler set; the NDK add-on already has most of it |

## Prototypes

All in `spikes/ndk-gaps/`. Scripts take paths as arguments. Downloads went to `~/.cache/ndk-gaps-spike/`.

| File | What it does |
|---|---|
| `fetch-ndk-build.sh <out>` | Fetches Google's `build/` from AOSP `platform/ndk` at tag `ndk-r28c`, pinned, and checks its git tree hash |
| `fetch-termux-deb.sh <arch> <pkg> <out>` | Fetches any architecture's `.deb` from Termux's repo, checking the signed `InRelease`, then `Packages`, then the `.deb` |
| `make-shim.sh <out> <build-dir> <ndk-multilib-usr>` | Builds a fuller shim: ndk-build, four ABIs, both libc++ flavours, clang wrappers |
| `android.toolchain.cmake` | The add-on's CMake toolchain, widened to four ABIs and all `ANDROID_STL` values |
| `gen-stub.sh <lib> <target> <out>` | Makes a link-only stub of a system library for any ABI from the phone's arm64 copy |
| `pocketrocket-android-ndk.gradle` | What the Gradle hook could become: points native projects at the shim, drops ABIs it can't build, never throws while Gradle configures |
| `cc-demo/` | The two small C/C++ projects for gap 5 |

To reproduce:

```
spikes/ndk-gaps/fetch-ndk-build.sh ~/.cache/ndk-gaps-spike/src-build-git
apt-get download ndk-multilib   # in ~/.cache/ndk-gaps-spike/debs; then dpkg-deb -x into ../multilib
spikes/ndk-gaps/make-shim.sh ~/.cache/ndk-gaps-spike/shim ~/.cache/ndk-gaps-spike/src-build-git \
  ~/.cache/ndk-gaps-spike/multilib/data/data/com.termux/files/usr
./gradlew -I spikes/ndk-gaps/pocketrocket-android-ndk.gradle -Ppocketrocket.ndk=$HOME/.cache/ndk-gaps-spike/shim :app:assembleDebug
```

## 1. ndk-build: works

**What is fetched.** Google's `ndk-build` script, `build/core/*.mk`, `build/gmsl` and a few Python helpers. Plain text, Apache 2.0.

| | |
|---|---|
| Source | `https://android.googlesource.com/platform/ndk`, folder `build/` |
| Pin | tag `ndk-r28c` (NDK 28.2.13676358), commit `805836ef53231ef86dd5fcc5652a1c28d74f4bc4`, tree of `build/` `dd78e5f2c69d1dded3bece4debc182624c2effe3` |
| How | `git fetch --depth 1 --filter=blob:none <url> <commit>`, then `git archive` of `build/` |
| Size | About 1.2 MB over the network (`.git` 555 KB plus the blobs), 693 KB on disk, 4 s |
| Integrity | Git checks every object against its hash. The script then requires `FETCH_HEAD` to be the commit and `FETCH_HEAD:build` to be the pinned tree, so a changed file fails |

The gitiles `+archive/<commit>/build.tar.gz` is not stable byte for byte. Two downloads gave two SHA256s (`c10d7097…`, `ea35078e…`), so a tarball can't be pinned by its hash. Its contents do hash to the same git tree (`dd78e5f2…`, checked with `git add` and `git write-tree`), so a tarball route would also work if it checked the tree hash.

**What the shim adds to make it run.** Four small changes, all in `make-shim.sh`:

1. `ndk_bin_common.sh` rejects the host: `uname -m` on Android is `aarch64`, and Google's script knows only `arm64` (macOS). One `sed`: `arm64|aarch64) HOST_ARCH=arm64;;`.
2. The NDK's own build writes `build/core/{version,abis,platforms,system_libs}.mk` from `meta/*.json`. They aren't in the source tree, so the shim writes them. Without `NDK_ABI_<abi>_MIN_OS_VERSION`, make stops inside gmsl: `invalid second argument to 'wordlist' function: empty value`.
3. Shebangs point at `$PREFIX/bin/bash` and `sh`, and the root `ndk-build` is a two-line script that calls `build/ndk-build`.
4. `bin/clang` and `bin/clang++` are wrappers, not symlinks (below).

Termux's GNU make 4.4.1 runs the makefiles as they are. Google's NDK ships its own make; it wasn't needed.

**The clang wrapper.** ndk-build links without `--sysroot`. Termux's clang then uses `$PREFIX`, and arm64 "works" by picking up Termux's own crt files. The wrapper adds `--sysroot=<shim sysroot>` (a later `--sysroot` from the caller wins), `-resource-dir=<shim>` (for cross compiler-rt), `--ld-path=ld-no-rpath` (as the add-on does today) and `-Qunused-arguments`.

**Evidence.** Direct, then through Gradle (AGP 8.13.2, the repo's Gradle 9.2.1 wrapper):

```
$ shim/ndk-build NDK_PROJECT_PATH=null APP_BUILD_SCRIPT=terminal-emulator/src/main/jni/Android.mk \
    APP_ABI="arm64-v8a armeabi-v7a x86 x86_64" APP_PLATFORM=android-24 ...
[x86_64] Install        : libtermux.so => .../lib/x86_64/libtermux.so
real 0m3.562s
$ shim/ndk-build ... termux-shared/src/main/cpp/Android.mk NDK_APPLICATION_MK=.../Application.mk APP_ABI=all
real 0m15.493s   (C++, APP_STL := c++_static)

$ ./gradlew -I spikes/ndk-gaps/pocketrocket-android-ndk.gradle -Ppocketrocket.ndk=$HOME/.cache/ndk-gaps-spike/shim :app:assembleDebug
PocketRocket NDK: :app asks for NDK 29.0.14206865; using the phone's NDK (claims 28.2.13676358).
> Task :terminal-emulator:buildNdkBuildDebug[armeabi-v7a]
> Task :termux-shared:buildNdkBuildDebug[x86]
> Task :app:buildNdkBuildDebug[x86_64]
BUILD SUCCESSFUL in 5m
```

The 5 minutes include downloading the four Termux bootstrap zips that `:app` embeds, and all the Kotlin. After touching `termux.c`, `:terminal-emulator:assembleDebug` took 53 s, most of it Gradle.

The universal debug APK (138 MB, plus four split APKs) holds, per ABI, `libtermux.so`, `liblocal-socket.so`, `libtermux-bootstrap.so`:

```
lib/armeabi-v7a/liblocal-socket.so  505868 ARM          liblog.so,libc.so,libm.so,libdl.so, runpath=0
lib/x86/libtermux-bootstrap.so    29725464 Intel 80386  libc.so,libm.so,libdl.so, runpath=0
lib/x86_64/libtermux.so               9080 X86-64       libc.so,libm.so,libdl.so, runpath=0
```

No `RUNPATH`, no `data/data` strings, and `liblocal-socket.so` needs no `libc++_shared.so` (static). The arm64 libraries load on the phone with `dlopen(RTLD_NOW)`: `liblocal-socket.so: loaded`, `libtermux.so: loaded`, `libtermux-bootstrap.so: loaded`. The APK was not installed: it is `app.pocket` itself, the app this ran in.

AGP drives ndk-build with `-n` and builds from the commands it reads back. No Python call shows in its logs. Plain `ndk-build` calls Python for `compile_commands.json` and a few manifest reads; Termux's `python` was present, so a phone without it was not tested.

## 2. Other ABIs: works, with caveats

**Where the files come from.** Termux's `ndk-multilib` package (version 30, architecture `all`) has, for aarch64, arm, i686 and x86_64:

- `<triple>/lib/`: crt objects, `libunwind.a`, `libatomic.a`, `libc++_shared.so`, `libc++_static.a`, `libc++abi.a`, and `libc++_shared.a` (a linker script, `INPUT(-lc++_static -lc++abi)`);
- `opt/ndk-multilib/<triple>/lib/`: Google's stub `libc.so`, `libm.so`, `libdl.so`, `liblog.so`, `libandroid.so`, EGL, GLES 1-3, OpenSLES, OpenMAXAL, vulkan (API 24 stubs: `malloc` is a bare `ret`, and `getrandom` and `posix_spawn` are absent);
- `opt/ndk-multilib/cross-compiler-rt/`: `libclang_rt.builtins-<arch>-android.a` and the sanitizers for every arch.

The installed `ndk-sysroot` already has every ABI's `asm/` headers under `$PREFIX/include/<triple>/`. So the per-arch `ndk-sysroot` debs (about 2 MB each, fetched and checked) turned out not to be needed.

**Download and integrity.** `apt-get download ndk-multilib`: 72.9 MB in 17 s. Since the package is `all`, apt itself checks it: `InRelease` signed by Termux's key, `Packages` SHA256 from it, the `.deb` SHA256 from that. If another architecture's package is ever needed, `fetch-termux-deb.sh` does the same chain by hand with `gpgv` and apt's keyring:

```
gpgv: Good signature from "Termux Releases (Termux automatic builds) <contact@termux.dev>"
b4ecafce…a6  .../debs-arm/ndk-sysroot_30_arm.deb  (1989208 bytes)
```

**Disk.** Installed as a package it is 433 MB (414 MB measured extracted). The shim uses 142 MB of it: 104 MB is static libc++ and libc++abi for four ABIs, about 38 MB is everything else. The 94 MB of static `libc.a`/`libm.a` and most of the 198 MB `cross-compiler-rt` (sanitizers) go unused.

**Shim layout.** Google's own: `sysroot/usr/lib/<triple>/` per ABI. Termux's clang already searches `<sysroot>/usr/lib/<triple>/<api>` and `<sysroot>/usr/lib/<triple>` for `-L` and the crt objects once those folders exist. Compiler-rt for other arches is looked up in the resource dir, so the shim has its own resource dir: Termux's headers plus links to Termux's aarch64 runtimes and `ndk-multilib`'s cross ones. `meta/abis.json` lists four ABIs, so Gradle accepts them.

**Missing system libraries.** `ndk-multilib` has no stub for `libz`, `libjnigraphics`, `libmediandk`, `libnativewindow`, `libaaudio`, `libcamera2ndk`, `libbinder_ndk` or `libsync`. `gen-stub.sh` builds one for any ABI from the phone's arm64 library: same names, soname and default versions, empty bodies. Google makes its stubs from symbol lists the same way. `libz` for x86: 92 of 92 symbols, `Machine: Intel 80386`, `SONAME [libz.so]`.

**Evidence, CMake.** The earlier spike's `hello-ndk` native part (C++, tinyexpr through FetchContent), through the widened toolchain:

```
c++_static armeabi-v7a  ok  ARM          NEEDED:liblog.so,libm.so,libdl.so,libc.so, RUNPATH:0
c++_static x86          ok  Intel 80386  NEEDED:liblog.so,libm.so,libdl.so,libc.so, RUNPATH:0
c++_shared x86_64       ok  X86-64       NEEDED:liblog.so,libm.so,libc++_shared.so,libdl.so,libc.so, RUNPATH:0
```

All 8 combinations of 4 ABIs × 2 STLs built.

**Caveats.**

- **Nothing but arm64 was run.** The phone is 64-bit only and there is no emulator on it. The files are well formed, link with `--no-undefined`, and need only the expected libraries. Running them needs another device or the Mac's emulators.
- **API 24 stubs block newer APIs.** With `minSdk` 28, `getrandom` fails: `ld.lld: error: undefined symbol: getrandom`. Google's NDK has stubs per API level. Fix tested: `gen-stub.sh /system/lib64/libc.so x86_64-none-linux-android24` makes a libc with 1451 symbols and versions `LIBC` to `LIBC_V`, `LIBC_36`, `LIBC_37` (private and platform versions left out), and `getrandom@LIBC_P` then links. That matches today's arm64 add-on, which links against the phone's own libraries: what the phone has is what links. Neither stops a too-new symbol for an older `minSdk` the way Google's per-API stubs do. Symbols that exist only on 32-bit or x86 libc would be missing; none were hit.
- **The prototype changes arm64 too.** arm64 now links against the API 24 stubs, not `/system/lib64`. That removes private symbols but adds the `getrandom` problem above. Pick one policy for all ABIs (see Recommendation).
- **Google's own CMake toolchain file does not work on the shim**, so ours stays. It requires `Pkg.Desc = Android NDK` in `source.properties`. Its legacy path then leaves the host tag empty, because CMake on Termux reports `CMAKE_HOST_SYSTEM_NAME` as `Android`, not `Linux`. Its new path hands over to CMake's own Android support, which fell back to `$PREFIX/bin/cc`.
- riscv64 is not served: `ndk-multilib` has no riscv64 libraries. The Gradle hook drops it with a warning.
- Headers are Termux's, patched by Termux. `paths.h`, `pwd.h`, `stdio.h`, `sys/cdefs.h` and `utmp.h` carry Termux paths, for example `_PATH_BSHELL "/data/data/app.pocket/files/usr/bin/sh"`. That's the same as today's add-on. Only code using those macros is affected.

## 3. c++_static: works

Termux's clang links C++ with `-lc++_shared`. With `-static-libstdc++` (what ndk-build's `stl.mk` and our toolchain pass for `c++_static`) that becomes `-Bstatic -lc++_shared -Bdynamic`. That finds `libc++_shared.a`, which pulls in `libc++_static.a` and `libc++abi.a`. Google's NDK does the same.

```
$ shim/.../bin/clang++ --target=aarch64-none-linux-android24 -static-libstdc++ -fPIE -pie ex.cpp -o ex-static && ./ex-static
caught: vector
123
NEEDED: [libdl.so] [libm.so] [libc.so]
```

The binary is 5.1 MB, unstripped. armeabi-v7a, i686 and x86_64 executables link the same way. PocketRocket's own `termux-shared` uses `APP_STL := c++_static` and builds for all four ABIs. `ANDROID_STL` now defaults to `c++_static`, as in Google's toolchain. `c++_shared` still works, and the plugin packs `libc++_shared.so` from `sysroot/usr/lib/<triple>/`.

One thing not compared: `liblocal-socket.so` (x86) exports 835 `std::` symbols from the static libc++. Whether Google's NDK hides them in the same build was not checked.

For arm64 only, `c++_static` costs 29 MB (`libc++_static.a` 24.5 MB, `libc++abi.a` 4.9 MB).

## 4. Compile-only tasks: works

**Today.** Even `compileDebugKotlin` stops while Gradle configures:

```
$ ./gradlew :terminal-emulator:compileDebugKotlin
> PocketRocket's Android NDK tool has no ndk-build. Move :app to CMake, or build it off the phone.
> [CXX1201] ABIs [armeabi-v7a, x86, x86_64] are not supported for platform. Supported ABIs are [arm64-v8a].
BUILD FAILED in 3s
```

The CXX1201 comes from the plugin, not our hook. It is raised during configuration for any ABI missing from `meta/abis.json`, from `ndk.abiFilters` or, in `:app`, from `splits.abi`.

**Prototype** (`pocketrocket-android-ndk.gradle`). It never throws during configuration:

- A native project with no `ndkPath` gets the shim's path and revision, whatever `ndkVersion` it pins, with a one-line note. Google's NDK is never downloaded, and a pin is not an error.
- ABIs the shim can't build are removed from `ndk.abiFilters` and from `splits.abi`, with a warning.
- With no shim at all (`-Ppocketrocket.ndk=none` stands in for "add-on not installed"), it points at a placeholder folder holding only `source.properties` and `meta/`. Then only `configure*/build*CMake|NdkBuild*` and `externalNativeBuild*` fail, when they run.

```
$ ./gradlew -I .../pocketrocket-android-ndk.gradle -Ppocketrocket.ndk=none :terminal-emulator:compileDebugJavaWithJavac \
    :termux-shared:compileDebugJavaWithJavac :terminal-emulator:testDebugUnitTest :pocketrocket:compileDebugKotlin :pocketrocket:testDebugUnitTest
BUILD SUCCESSFUL in 2m 36s     terminal-emulator: 145 tests, 0 failures; pocketrocket: 861 tests, 0 failures

$ ./gradlew ... -Ppocketrocket.ndk=none :terminal-emulator:assembleDebug
> Task :terminal-emulator:configureNdkBuildDebug[arm64-v8a] FAILED
> PocketRocket: :terminal-emulator has C/C++ code, and the Android NDK add-on is not installed (Settings > Tools > Android NDK).
  Kotlin and Java tasks still work (compileDebugKotlin, testDebugUnitTest).
```

Init scripts given with `-I` run before `init.d`, so this one set `ndkPath` first and the installed hook stepped aside.

## 5. A C and C++ tile: works

Sizes are apt's figures from Termux's index, without shared libraries already on the phone.

| Package | Download | On disk | In the NDK add-on? |
|---|---|---|---|
| clang (with clangd, clang-format, clang-tidy) | 30.7 MB | 191 MB | yes |
| libllvm | 30.7 MB | 174 MB | yes (dependency) |
| llvm (llvm-ar, objdump, …) | 14.9 MB | 72.3 MB | yes (dependency) |
| libcompiler-rt | 3.0 MB | 34.8 MB | yes |
| ndk-sysroot | 2.0 MB | 29.1 MB | yes |
| lld | 2.8 MB | 20.8 MB | yes |
| libc++ | 0.3 MB | 1.5 MB | yes |
| cmake | 10.6 MB | 57.2 MB | yes |
| ninja | 0.08 MB | 0.3 MB | yes |
| make | 0.24 MB | 1.0 MB | no (installed on this phone already) |
| pkg-config | 0.03 MB | 0.14 MB | no (needs glib) |
| lldb | 5.5 MB | 27.5 MB | no (needs python) |
| gdb | 4.1 MB | 17.4 MB | no (needs guile, python) |
| ccache | 0.56 MB | 2.3 MB | no |
| autoconf, automake, libtool, bison, flex | 2.3 MB | 13.3 MB | no |

The core set (clang to ninja, plus make and pkg-config) is about 95 MB to download and 583 MB on disk. lldb adds 27.5 MB. Everything except make, pkg-config, a debugger and ccache is already in the NDK add-on.

Proof, with the compilers already on the phone (`spikes/ndk-gaps/cc-demo`):

```
$ cmake -S cmake-cpp -B .../cmake-cpp -G Ninja -DCMAKE_BUILD_TYPE=Release && cmake --build .../cmake-cpp   # C++20, ranges, map, exceptions
real 0m5.820s
$ printf 'a b a c b a\n' | .../wordcount
a 3
b 2
c 1
exceptions work
$ make OUT=.../make-c && .../primes 200000
cc -O2 -Wall -Wextra -o .../primes primes.c -lm
17984 primes below 200000
```

No debugger was run: lldb and gdb are not installed here and weren't installed for this spike.

## Recommendation

1. **Split the tiles.** A general **C and C++** tile: clang, lld, llvm, cmake, ninja, make, pkg-config, with lldb as an option. The **Android NDK** tile then needs C and C++ and adds only the shim, ndk-build and the ndk-multilib files. Uninstalling C and C++ removes the NDK tile first, the way Android build does today.
2. **ndk-build in the NDK tile.** The install runs `fetch-ndk-build.sh`'s steps, pinned to `ndk-r28c` and checked against the tree hash. It writes the generated `.mk` files and the one-line host patch into `ndk-shim/build`. The tile drops "no ndk-build" from its limits.
3. **Four ABIs and `c++_static`.** `apt-get download ndk-multilib` (apt checks it), then extract only what the shim uses into the shim folder: 142 MB, or 38 MB without static libc++. Don't install the package (433 MB). Record the version, so Updates can redo it on a new Termux release. Change `meta/abis.json`, the toolchain file and the Gradle hook to four ABIs. Make `c++_static` the default and drop our `-DANDROID_STL=c++_shared` injection.
4. **One stub policy for every ABI.** Use `gen-stub.sh` stubs made from the phone's own libraries for every ABI, arm64 included (libc, libm, libdl, liblog, libandroid, libz, …). That keeps today's "what the phone has" behaviour, adds no private symbols, and covers libraries ndk-multilib lacks. Document that an older `minSdk` is not checked at link time.
5. **Gradle hook.** Replace the throws with the prototype's approach: remap any `ndkVersion` to the shim, drop unbuildable ABIs with a warning, fail only native tasks. Ship a hook with the **Android build** tool too, pointing at the placeholder when the NDK tile is absent. That stops the 2.4 GB download for native projects without the NDK tile, and Kotlin-only tasks keep working.
6. **Clang wrappers** replace the `bin/clang` symlinks (sysroot, resource dir, no-rpath linker), so ndk-build and CMake behave the same.

Still to check: run armeabi-v7a, x86 and x86_64 output on a device or emulator (the Mac); Google's NDK on the same project, to compare exported symbols and sizes; ndk-build on a phone without Python; the Xiaomi's timings.

## Spike notes

- `$ANDROID_HOME/ndk/29.0.14206865` (Google's x86_64 NDK r29, 2.4 GB) appeared during this spike, written 21:58:18 to 21:58:31. The spike's first Gradle run was at about 22:03, and the Gradle daemons running then had started before it (about 21:39 and 21:54). So another session's build downloaded it, not this spike. It was left in place. It can't run on the phone; delete it when nothing needs it.
- The shim is built with symlinks into the extracted `ndk-multilib`, so it is 1.4 MB itself.
- Untouched: `~/.gradle/init.d/pocketrocket-android-ndk.gradle` (md5 `ce105b6e…`, 14:12) and `$ANDROID_HOME/ndk-shim`.
