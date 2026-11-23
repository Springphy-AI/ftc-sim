#!/usr/bin/env sh
# Rebuilds the AprilTag detector libraries in this folder.
#
# FIRST's FTC SDK 12.0 finds AprilTags with org.openftc:apriltag:2.1.0, which is C/C++ code
# (OpenFTC EOCV-AprilTag-Plugin v2.1.0 + the AprilRobotics apriltag commit that release pins)
# compiled for the Control Hub's ARM processor. This script compiles the same source files, with
# the same compiler settings, for the PCs the simulator runs on. Two things differ from the robot
# build, both outside the detector itself:
#   - <android/log.h> is replaced by shim/android/log.h, so the JNI glue's debug lines are dropped;
#   - on Windows, AprilTag's own common/pthreads_cross.c supplies the threads Android has built in.
#
# Needs curl, tar, and unzip (Windows host) or xz (Linux/macOS host), plus a JDK for jni.h.
#
#   sh natives/apriltag/build.sh           all five platforms. Downloads Zig (a self-contained
#                                          C/C++ cross compiler) into build/; nothing is installed.
#   sh natives/apriltag/build.sh --local   only this machine's platform, with gcc/g++ (or $CC/$CXX)
#                                          from PATH, e.g. MinGW-w64 on Windows, gcc on Linux, the
#                                          Xcode command line tools' clang on macOS.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
work="$root/build/apriltag-natives"
mode=zig
if [ "${1:-}" = "--local" ]; then mode=local; fi

PLUGIN_COMMIT=ea4043af90c779bf6d93e0a02867bc5761439d23    # OpenFTC/EOCV-AprilTag-Plugin tag v2.1.0
APRILTAG_COMMIT=6319d842ec8253aaf8017aeb9876d30bc7cce4b2  # AprilRobotics/apriltag, pinned by v2.1.0
ZIG_VERSION=0.16.0

mkdir -p "$work"
cd "$work"

# ---- JDK headers (copied so no path with spaces reaches the compiler) ----------------------
if [ -z "${JAVA_HOME:-}" ] || [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    JAVA_HOME=""
    for d in "$HOME"/.jdks/jdk-*; do   # glob order is ascending, so the newest JDK wins
        if [ -f "$d/include/jni.h" ]; then JAVA_HOME="$d"; fi
    done
    if [ -z "$JAVA_HOME" ] && command -v java >/dev/null 2>&1; then
        JAVA_HOME=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')
    fi
fi
if [ ! -f "$JAVA_HOME/include/jni.h" ]; then
    echo "No JDK with include/jni.h found. Set JAVA_HOME to a JDK." >&2
    exit 1
fi
rm -rf jni && mkdir -p jni/windows jni/unix
cp "$JAVA_HOME/include/jni.h" jni/
cp "$here/shim/unix/jni_md.h" jni/unix/
# Windows' jni_md.h only depends on the compiler, so it can be written here on any host.
cat > jni/windows/jni_md.h <<'EOF'
#ifndef _JAVASOFT_JNI_MD_H_
#define _JAVASOFT_JNI_MD_H_
#define JNIEXPORT __declspec(dllexport)
#define JNIIMPORT __declspec(dllimport)
#define JNICALL __stdcall
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
#endif
EOF
rm -rf shim && cp -R "$here/shim" shim

# ---- Zig ----------------------------------------------------------------------------------
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_os=windows ;;
    Linux) host_os=linux ;;
    Darwin) host_os=macos ;;
    *) echo "Unsupported host OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64|amd64) host_arch=x86_64 ;;
    arm64|aarch64) host_arch=aarch64 ;;
    *) echo "Unsupported host CPU: $(uname -m)" >&2; exit 1 ;;
esac
host="$host_arch-$host_os"
if [ "$mode" = zig ]; then
case "$host" in
    x86_64-windows) zig_sha=68659eb5f1e4eb1437a722f1dd889c5a322c9954607f5edcf337bc3684a75a7e ;;
    x86_64-linux)   zig_sha=70e49664a74374b48b51e6f3fdfbf437f6395d42509050588bd49abe52ba3d00 ;;
    aarch64-linux)  zig_sha=ea4b09bfb22ec6f6c6ceac57ab63efb6b46e17ab08d21f69f3a48b38e1534f17 ;;
    x86_64-macos)   zig_sha=0387557ed1877bc6a2e1802c8391953baddba76081876301c522f52977b52ba7 ;;
    aarch64-macos)  zig_sha=b23d70deaa879b5c2d486ed3316f7eaa53e84acf6fc9cc747de152450d401489 ;;
    *) echo "No Zig download for $host" >&2; exit 1 ;;
esac
zig_name="zig-$host-$ZIG_VERSION"
if [ "$host_os" = windows ]; then zig_ext=zip; zig="./$zig_name/zig.exe"; else zig_ext=tar.xz; zig="./$zig_name/zig"; fi
if [ ! -x "$zig" ]; then
    echo "Downloading Zig $ZIG_VERSION for $host"
    curl -fL --progress-bar -o "$zig_name.$zig_ext" "https://ziglang.org/download/$ZIG_VERSION/$zig_name.$zig_ext"
    if command -v sha256sum >/dev/null 2>&1; then got=$(sha256sum "$zig_name.$zig_ext" | cut -d' ' -f1)
    else got=$(shasum -a 256 "$zig_name.$zig_ext" | cut -d' ' -f1); fi
    if [ "$got" != "$zig_sha" ]; then echo "Zig download checksum mismatch: $got" >&2; exit 1; fi
    if [ "$zig_ext" = zip ]; then unzip -q "$zig_name.$zig_ext"; else tar -xJf "$zig_name.$zig_ext"; fi
    rm -f "$zig_name.$zig_ext"
fi
export ZIG_GLOBAL_CACHE_DIR="$work/zig-cache"
export ZIG_LOCAL_CACHE_DIR="$work/zig-cache"
fi

# ---- Sources at the pinned commits --------------------------------------------------------
fetch() { # github-repo commit dir
    if [ ! -f "$3/.complete" ]; then
        rm -rf "$3" && mkdir -p "$3"
        curl -fsSL "https://github.com/$1/archive/$2.tar.gz" -o "$3.tar.gz"
        tar -xzf "$3.tar.gz" -C "$3" --strip-components=1
        rm -f "$3.tar.gz"
        touch "$3/.complete"
    fi
}
fetch OpenFTC/EOCV-AprilTag-Plugin "$PLUGIN_COMMIT" plugin
fetch AprilRobotics/apriltag "$APRILTAG_COMMIT" apriltag

# The file list comes from the plugin's own CMakeLists.txt, so it is exactly the robot's.
# Its paths are relative to plugin/apriltag/, where apriltag/ is the AprilRobotics submodule.
sources=$(sed -n -E 's/^[[:space:]]*(src\/main\/cpp\/[^[:space:])]*\.cpp|apriltag\/[^[:space:])]*\.c)[)]*[[:space:]]*$/\1/p' plugin/apriltag/CMakeLists.txt)
[ -n "$sources" ] || { echo "Could not read the source list from the plugin's CMakeLists.txt" >&2; exit 1; }

# ---- Build --------------------------------------------------------------------------------
build() { # platform zig-target library-file
    platform=$1; target=$2; lib=$3
    obj="obj/$platform"
    rm -rf "$obj" && mkdir -p "$obj" "out/$platform"
    if [ "${platform%%-*}" = windows ]; then jni_md=jni/windows; extra="apriltag/common/pthreads_cross.c"; libs=""
    else jni_md=jni/unix; extra=""; libs="-lm -lpthread"; fi
    includes="-Ishim -Ijni -I$jni_md -Iapriltag -Iapriltag/common"
    pic="-fPIC"
    if [ "${platform%%-*}" = windows ]; then pic=""; fi   # DLLs are relocatable anyway; clang rejects -fPIC there
    if [ "$mode" = zig ]; then
        # clang + libc++, like the Android NDK build.
        cc="$zig cc -target $target"; cxx="$zig c++ -target $target"
    else
        # gcc + libstdc++. Two header differences from the NDK's libc++ need compiler flags:
        #  - libstdc++ does not bring in <math.h> through <iostream>, and the JNI glue calls
        #    hypot/sqrt without including it, so it is forced in;
        #  - on MinGW, libstdc++ itself uses MinGW's pthreads, which clash with AprilTag's own
        #    Windows threads (common/pthreads_cross.*) by type and by symbol name. The C files
        #    keep pthreads_cross, as AprilTag does on Windows, under private symbol names; the two
        #    C++ JNI files take MinGW's pthread types. They never touch the one struct field of
        #    that type (the mutex at the end of apriltag_detector_t), so both agree on all they use.
        cc="${CC:-gcc}"; cxx="${CXX:-g++} -include math.h"
        if [ "${platform%%-*}" = windows ]; then
            for f in pthread_create pthread_join pthread_detach pthread_mutex_init pthread_mutex_destroy \
                     pthread_mutex_lock pthread_mutex_unlock pthread_cond_init pthread_cond_destroy \
                     pthread_cond_wait pthread_cond_timedwait pthread_cond_signal pthread_cond_broadcast sched_yield; do
                cc="$cc -D$f=atpc_$f"
            done
            cxx="$cxx -D__CPTHREAD_H__ -include pthread.h"
            libs="-static"                                 # no MinGW runtime DLLs to ship
        elif [ "${platform%%-*}" = linux ]; then
            libs="$libs -static-libstdc++ -static-libgcc"  # no libstdc++ version dependency
        fi                                                 # (macOS: Apple clang and its libc++, part of the OS)
    fi
    objs=""
    for s in $sources $extra; do
        case "$s" in
            src/main/cpp/*) file="plugin/apriltag/$s" ;;
            apriltag/*) file="$s" ;;
        esac
        o="$obj/$(echo "$s" | tr '/' '_').o"
        case "$s" in
            *.cpp) $cxx -std=c++11 -O3 $pic $includes -c "$file" -o "$o" ;;
            *.c)   $cc -O3 $pic $includes -c "$file" -o "$o" ;;
        esac
        objs="$objs $o"
    done
    $cxx -shared -O3 -o "out/$platform/$lib" $objs $libs
    mkdir -p "$here/$platform"
    cp "out/$platform/$lib" "$here/$platform/$lib"
    echo "  $platform/$lib"
}

echo "Building AprilTag $APRILTAG_COMMIT + JNI glue (EOCV-AprilTag-Plugin v2.1.0)"
if [ "$mode" = local ]; then
    case "$host_os" in
        windows) build "$host_os-$host_arch" "" apriltag.dll ;;
        linux) build "$host_os-$host_arch" "" libapriltag.so ;;
        macos) build "$host_os-$host_arch" "" libapriltag.dylib ;;
    esac
else
    build windows-x86_64 x86_64-windows-gnu    apriltag.dll
    build linux-x86_64   x86_64-linux-gnu.2.17  libapriltag.so
    build linux-aarch64  aarch64-linux-gnu.2.17 libapriltag.so
    build macos-x86_64   x86_64-macos.11.0      libapriltag.dylib
    build macos-aarch64  aarch64-macos.11.0     libapriltag.dylib
fi

cp apriltag/LICENSE.md "$here/LICENSE-apriltag.md"
echo "Done."
