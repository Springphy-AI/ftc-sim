#!/usr/bin/env sh
# Starts the FTC simulator. Arguments are passed through, for example:
#   sh run-sim.sh --project ~/FtcRobotController
#   sh run-sim.sh --project ~/FtcRobotController --robot robots/tank.json
#   sh run-sim.sh --project ~/FtcRobotController --project2 ~/OtherFtcRobotController
# Or put project=... and robot=... in sim.properties and just run sh run-sim.sh.
cd "$(dirname "$0")" || exit 1

if [ -z "$JAVA_HOME" ] && ! command -v javac >/dev/null 2>&1; then
    for d in $(ls -d "$HOME"/.jdks/jdk-* 2>/dev/null | sort -r); do
        if [ -x "$d/bin/javac" ]; then JAVA_HOME="$d"; export JAVA_HOME; break; fi
    done
fi

sh ./gradlew -q :engine:installDist || exit 1
exec sh engine/build/install/ftc-sim/bin/ftc-sim "$@"
