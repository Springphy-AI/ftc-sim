#!/usr/bin/env sh
# Starts the simulator, with these arguments, when VS Code connects to the codespace
# (devcontainer.json's postAttachCommand), unless a simulator already answers at port 8080:
# opening the codespace again, in another tab, would start a second one on the next port.
if curl -s -o /dev/null --max-time 2 http://localhost:8080/; then
    echo "The simulator is already running: its page is port 8080 in the Ports tab."
    echo "To start it again with other options, stop it (Ctrl+C in its terminal) and run sh run-sim.sh."
    exit 0
fi
exec sh "$(dirname "$0")/../run-sim.sh" "$@"
