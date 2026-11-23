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

# Whether folder $1 is a JDK (not a JRE) of Java 17 or newer.
jdk17() {
    [ -n "$1" ] && [ -x "$1/bin/javac" ] && [ "$(jdk_major "$1")" -ge 17 ]
}

# The folder of the JDK whose javac is on PATH, as that javac says (macOS's /usr/bin/javac is a stub
# that exists even when no JDK is installed, and Linux's is a link); nothing if there is none.
path_jdk() {
    javac -J-XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p' | head -n 1
}

# Android Studio's own JDK (jbr), where its installers put it; FTCSIM_ANDROID_STUDIO_JBR, a list
# separated by ":", replaces these (for the tests).
android_studio_jdks() {
    if [ -n "${FTCSIM_ANDROID_STUDIO_JBR+set}" ]; then
        printf '%s\n' "$FTCSIM_ANDROID_STUDIO_JBR" | tr ':' '\n'
        return
    fi
    printf '%s\n' "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
        "$HOME/android-studio/jbr" /opt/android-studio/jbr /usr/local/android-studio/jbr \
        /snap/android-studio/current/jbr "$HOME/.local/share/JetBrains/Toolbox/apps/android-studio/jbr"
}

# A JDK 17 or newer: JAVA_HOME, then the javac on PATH, then the newest by version of the JDKs in
# ~/.jdks and Android Studio's (macOS JDKs keep bin/ under Contents/Home). Folder names don't tell
# the version (temurin-11 sorts after corretto-21), so each JDK's own release file does. JAVA_HOME
# is always set to the one chosen: Gradle and the simulator's start script run its java.
if [ -n "$JAVA_HOME" ] && ! jdk17 "$JAVA_HOME"; then
    echo "JAVA_HOME ($JAVA_HOME) is not a JDK 17 or newer; looking for one." >&2
    unset JAVA_HOME
fi
if [ -z "$JAVA_HOME" ]; then
    h=$(path_jdk)
    if jdk17 "$h"; then JAVA_HOME=$h; fi
fi
if [ -z "$JAVA_HOME" ]; then
    best= best_v=0
    old_ifs=$IFS
    IFS='
'   # folder names may contain spaces: split the lists on newlines only
    for d in $(ls -d "$HOME"/.jdks/* 2>/dev/null | sort) $(android_studio_jdks); do
        for h in "$d" "$d/Contents/Home"; do
            jdk17 "$h" || continue
            v=$(jdk_major "$h")
            if [ "$v" -ge "$best_v" ]; then best=$h best_v=$v; fi
        done
    done
    IFS=$old_ifs
    if [ -z "$best" ]; then
        echo "No JDK 17 or newer found. Install one (for example Eclipse Temurin 21) or set JAVA_HOME." >&2
        exit 1
    fi
    JAVA_HOME=$best
fi
export JAVA_HOME
# (for the tests: the JDK chosen, without building or starting anything)
if [ -n "$FTCSIM_PRINT_JAVA_HOME" ]; then echo "$JAVA_HOME"; exit 0; fi

# Builds the simulator and copies it to build/run/<id>, a folder no later build rewrites under it.
sh ./gradlew -q :engine:prepareRun || exit 1
run=$(cat build/run/current 2>/dev/null)
if [ -z "$run" ] || [ ! -f "build/run/$run/bin/ftc-sim" ]; then
    echo "The simulator's build is not in build/run; run sh run-sim.sh again." >&2
    exit 1
fi
exec sh "build/run/$run/bin/ftc-sim" "$@"
