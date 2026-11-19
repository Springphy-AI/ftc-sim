#!/usr/bin/env sh
# Starts the FTC simulator. Arguments are passed through, for example:
#   sh run-sim.sh --project ~/FtcRobotController
#   sh run-sim.sh --project ~/FtcRobotController --robot robots/tank.json
#   sh run-sim.sh --project ~/FtcRobotController --project2 ~/OtherFtcRobotController
# Or put project=... and robot=... in sim.properties and just run sh run-sim.sh.
cd "$(dirname "$0")" || exit 1

# `javac -version` (not just `command -v javac`) because macOS ships a /usr/bin/javac stub
# that exists even when no JDK is installed. Every folder in ~/.jdks counts, newest name first;
# macOS JDKs keep bin/ under Contents/Home. The list is split on newlines only: a home folder
# name can contain spaces.
if [ -z "$JAVA_HOME" ] && ! javac -version >/dev/null 2>&1; then
    old_ifs=$IFS
    IFS='
'
    for d in $(ls -d "$HOME"/.jdks/* 2>/dev/null | sort -r); do
        for h in "$d" "$d/Contents/Home"; do
            if [ -x "$h/bin/javac" ]; then JAVA_HOME="$h"; export JAVA_HOME; break 2; fi
        done
    done
    IFS=$old_ifs
fi

sh ./gradlew -q :engine:installDist || exit 1
exec sh engine/build/install/ftc-sim/bin/ftc-sim "$@"
