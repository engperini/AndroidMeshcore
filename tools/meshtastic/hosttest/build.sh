#!/usr/bin/env bash
# Builds and runs the Meshtastic PC test (same firmware sources and glue as the app, SimRadio radio).
set -euo pipefail
cd "$(dirname "$0")"
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build build -j"$(nproc)"
rm -rf build/data
build/mt_host build/data > build/events.txt
python3 check_events.py build/events.txt
