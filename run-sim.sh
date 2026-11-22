#!/usr/bin/env sh
# Starts the FTC simulator. Arguments are passed through, for example:
#   sh run-sim.sh --project ~/FtcRobotController
#   sh run-sim.sh --project ~/FtcRobotController --robot robots/tank.json
#   sh run-sim.sh --project ~/FtcRobotController --project2 ~/OtherFtcRobotController
# Or put project=... and robot=... in sim.properties and just run sh run-sim.sh.

# A relative --project is relative to where this was started, not to the simulator's folder.
FTCSIM_CALLER_DIR=$(pwd)
export FTCSIM_CALLER_DIR
cd "$(dirname "$0")" || exit 1

# The major Java version of a JDK folder (8 for 1.8), from its release file or its javac; 0 if unknown.
jdk_major() {
    v=$(sed -n 's/^JAVA_VERSION="\{0,1\}\([0-9][0-9._]*\).*/\1/p' "$1/release" 2>/dev/null | head -n 1)
    [ -n "$v" ] || v=$("$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9._]*\).*/\1/p' | head -n 1)
    case "$v" in 1.*) v=${v#1.} ;; esac
    v=${v%%[._]*}
    echo "${v:-0}"
}

# The major version of the javac on PATH, 0 if none. `javac -version` (not just `command -v
# javac`) because macOS ships a /usr/bin/javac stub that exists even when no JDK is installed.
path_javac_major() {
    v=$(javac -version 2>&1 | sed -n 's/^javac \([0-9][0-9._]*\).*/\1/p' | head -n 1)
    case "$v" in 1.*) v=${v#1.} ;; esac
    v=${v%%[._]*}
    echo "${v:-0}"
}

# A JDK 17 or newer: JAVA_HOME, then javac on PATH, then the newest by version in ~/.jdks (macOS
# JDKs keep bin/ under Contents/Home). Folder names don't tell the version (temurin-11 sorts
# after corretto-21), so each JDK's own release file does.
if [ -n "$JAVA_HOME" ] && { [ ! -x "$JAVA_HOME/bin/javac" ] || [ "$(jdk_major "$JAVA_HOME")" -lt 17 ]; }; then
    echo "JAVA_HOME ($JAVA_HOME) is not a JDK 17 or newer; looking for one." >&2
    unset JAVA_HOME
fi
if [ -z "$JAVA_HOME" ] && [ "$(path_javac_major)" -lt 17 ]; then
    best= best_v=0
    old_ifs=$IFS
    IFS='
'   # folder names may contain spaces: split the list on newlines only
    for d in $(ls -d "$HOME"/.jdks/* 2>/dev/null | sort); do
        for h in "$d" "$d/Contents/Home"; do
            [ -x "$h/bin/javac" ] || continue
            v=$(jdk_major "$h")
            if [ "$v" -ge 17 ] && [ "$v" -ge "$best_v" ]; then best=$h best_v=$v; fi
        done
    done
    IFS=$old_ifs
    if [ -z "$best" ]; then
        echo "No JDK 17 or newer found. Install one (for example Eclipse Temurin 21) or set JAVA_HOME." >&2
        exit 1
    fi
    JAVA_HOME=$best
    export JAVA_HOME
fi
# (for the tests: the JDK chosen, without building or starting anything)
if [ -n "$FTCSIM_PRINT_JAVA_HOME" ]; then echo "${JAVA_HOME:-javac on PATH}"; exit 0; fi

sh ./gradlew -q :engine:installDist || exit 1
exec sh engine/build/install/ftc-sim/bin/ftc-sim "$@"
