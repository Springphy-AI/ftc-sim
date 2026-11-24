# AprilTag detector for PCs

FIRST's FTC SDK 12.0 finds AprilTags with `org.openftc:apriltag:2.1.0`: a thin Java wrapper plus
a native library (`libapriltag.so`) built for the Control Hub's ARM processor. A PC can't load
that library, so this folder holds the **same source code compiled for PCs**, with one fix to the
detector's thread pool (below). The simulator runs
FIRST's own `AprilTagProcessor` and the unmodified Java wrapper on top of it, exactly as on the
robot. `run-sim` and Gradle put this machine's folder on `java.library.path`.

| Folder | Library | Built |
|---|---|---|
| `windows-x86_64/` | `apriltag.dll` | Zig 0.16.0; needs only Windows' own C runtime (UCRT, Windows 10 and newer) |
| `linux-x86_64/` | `libapriltag.so` | Zig 0.16.0; glibc 2.17 or newer, C++ runtime built in |
| `linux-aarch64/` | `libapriltag.so` | Zig 0.16.0; glibc 2.17 or newer (not yet run on an ARM Linux machine) |
| `macos-x86_64/` | `libapriltag.dylib` | Zig 0.16.0, macOS 11+ (not yet run on an Intel Mac) |
| `macos-aarch64/` | `libapriltag.dylib` | Zig 0.16.0, macOS 11+, ad-hoc signed by the linker |

All five come from one `sh natives/apriltag/build.sh` on 2026-11-23, with the thread-pool fix.

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
- `patches/` fixes a race in the detector's thread pool (`common/workerpool.c`), which the
  pinned commit has. A worker thread woken without a run (POSIX lets `pthread_cond_wait` return
  by itself, and macOS does) could take a task while the next frame's tasks were being added, or
  count the run as done while other workers still ran theirs, and then call through freed memory:
  it crashed the JVM on macOS CI, in `worker_thread`. 0001 and 0002 are upstream's 2024 fix
  (`aa5951aa`, `c16bba61`), unchanged; 0003 is ours: upstream's fix still let one idle worker,
  woken twice, end a run early. With a stress test that wakes the workers all the time, the pinned
  code failed or miscounted, upstream's fix miscounted, and the three together count every run
  right, with no race for ThreadSanitizer. The tags found are the same: the simulator's AprilTag
  tests report the same positions, errors and decision margins to the last digit.

## Rebuilding

```sh
sh natives/apriltag/build.sh           # all five, with Zig (downloaded into build/, checksum-checked)
sh natives/apriltag/build.sh --local   # only this machine's platform, with gcc/g++ from PATH (macOS: Xcode's clang)
```

Zig (clang with libc++, like the Android NDK) compiles the sources as they are. `--local` with
gcc needs a few compiler flags, explained in `build.sh`, because gcc's C++ library differs from
the NDK's in which headers it pulls in and, on MinGW, in how it does threads. On Linux it links
gcc's C++ runtime statically; on macOS, Apple's clang links the system's libc++. The libraries
committed for macOS are the Zig builds; rebuild them with Zig, not `--local`.
