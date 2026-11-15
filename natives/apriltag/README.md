# AprilTag detector for PCs

FIRST's FTC SDK 12.0 finds AprilTags with `org.openftc:apriltag:2.1.0`: a thin Java wrapper plus
a native library (`libapriltag.so`) built for the Control Hub's ARM processor. A PC can't load
that library, so this folder holds the **same source code compiled for PCs**. The simulator runs
FIRST's own `AprilTagProcessor` and the unmodified Java wrapper on top of it, exactly as on the
robot. `run-sim` and Gradle put this machine's folder on `java.library.path`.

| Folder | Library | Built |
|---|---|---|
| `windows-x86_64/` | `apriltag.dll` | yes (MinGW-w64 gcc 13.2, `--local`) |
| `linux-x86_64/` | `libapriltag.so` | yes (gcc 13.3, `--local`; needs glibc 2.38+) |
| `linux-aarch64/` | `libapriltag.so` | not yet: run `build.sh` (Zig) |
| `macos-x86_64/` | `libapriltag.dylib` | not yet: run `build.sh` (Zig) |
| `macos-aarch64/` | `libapriltag.dylib` | not yet: run `build.sh` (Zig) |

Without a library for its platform the simulator still runs; AprilTag detection reports that the
detector is missing, and the tests that need it are skipped.

## Source

- JNI glue: [OpenFTC/EOCV-AprilTag-Plugin](https://github.com/OpenFTC/EOCV-AprilTag-Plugin) tag
  `v2.1.0` (`ea4043af`), the release FTC SDK 12.0 depends on. MIT, see
  `LICENSE-eocv-apriltag-plugin.txt`.
- Detector: [AprilRobotics/apriltag](https://github.com/AprilRobotics/apriltag) at `6319d842`,
  the commit that release pins as its submodule. BSD-2-Clause, see `LICENSE-apriltag.md`.

The list of files and the optimisation level come from the plugin's own `CMakeLists.txt`. What
differs from the robot build is outside the detector:

- `<android/log.h>` is replaced by `shim/android/log.h`; the glue's debug log lines are dropped.
- Windows has no built-in POSIX threads, so AprilTag's own `common/pthreads_cross.c` is added there.

## Rebuilding

```sh
sh natives/apriltag/build.sh           # all five, with Zig (downloaded into build/, checksum-checked)
sh natives/apriltag/build.sh --local   # only this machine's platform, with gcc/g++ from PATH
```

Zig (clang with libc++, like the Android NDK) compiles the sources as they are. `--local` with
gcc needs a few compiler flags, explained in `build.sh`, because gcc's C++ library differs from
the NDK's in which headers it pulls in and, on MinGW, in how it does threads.
