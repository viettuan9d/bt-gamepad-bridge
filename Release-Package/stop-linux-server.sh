#!/bin/bash
cd "$(dirname "$0")"
if [ "$EUID" -ne 0 ]; then
    sudo python3 linux_bridge_server.py --stop
else
    python3 linux_bridge_server.py --stop
fi
