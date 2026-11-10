#!/bin/bash
cd "$(dirname "$0")"
modprobe uinput 2>/dev/null || sudo modprobe uinput
if [ "$EUID" -ne 0 ]; then
    sudo python3 linux_bridge_server.py --daemon
else
    python3 linux_bridge_server.py --daemon
fi
