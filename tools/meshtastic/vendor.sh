#!/usr/bin/env bash
# Copies the Meshtastic firmware (the source set of its own "native-wasm" build) and the libraries it
# needs into app/src/main/cpp/meshtastic/vendor, at fixed commits. Run it again to move to a new
# firmware version (update FW_REF and the library refs from the firmware's platformio.ini files).
# Everything under vendor/ is upstream code; Android-specific code lives in meshtastic/android/.
set -euo pipefail

FW_REF=11f8de309c25e80dfa817d12e6d75d778d68ebb5       # meshtastic/firmware, develop (2.8.x)
declare -A LIBS=(
  [portduino]="https://github.com/meshtastic/framework-portduino 214bf605c02c2c26d2fb963132c4bce77f416d81"
  [RadioLib]="https://github.com/jgromes/RadioLib 5878f990bdb4c95c75494a21562c0c5a49659c6f"
  [Crypto]="https://github.com/meshtastic/Crypto 1c817c2f27aa4593e07d0f7e13b3dbf8d980bcae"
  [nanopb]="https://github.com/nanopb/nanopb refs/tags/0.4.92"
  [ArduinoThread]="https://github.com/meshtastic/ArduinoThread b841b0415721f1341ea41cccfb4adccfaf951567"
  [arduino-fsm]="https://github.com/meshtastic/arduino-fsm 7db3702bf0cfe97b783d6c72595e3f38e0b19159"
  [OneButton]="https://github.com/meshtastic/OneButton fa352d668c53f290cfa480a5f79ad422cd828c70"
  [TinyGPSPlus]="https://github.com/meshtastic/TinyGPSPlus 71a82db35f3b973440044c476d4bcdc673b104f4"
  [ErriezCRC32]="https://github.com/Erriez/ErriezCRC32 refs/tags/1.0.1"
  [esp8266-oled-ssd1306]="https://github.com/meshtastic/esp8266-oled-ssd1306 bb93fd40685f22e850330ea162f1dd154a4e0685"
  [WiFi]="https://github.com/Meshtastic/WiFi 92b064179165ea6333d26d3ddd23299ce6f4b048"   # portduino's libraries/WiFi submodule
  [Melopero_RV3028]="https://github.com/melopero/Melopero_RV-3028_Arduino_Library refs/tags/1.2.0"
)

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
DEST="$ROOT/app/src/main/cpp/meshtastic/vendor"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

fetch() {   # name url ref
  mkdir -p "$WORK/$1"
  git -C "$WORK/$1" init -q
  git -C "$WORK/$1" fetch -q --depth 1 "$2" "$3"
  git -C "$WORK/$1" checkout -q FETCH_HEAD
}

fetch firmware https://github.com/meshtastic/firmware "$FW_REF"
for name in "${!LIBS[@]}"; do
  read -r url ref <<<"${LIBS[$name]}"
  fetch "$name" "$url" "$ref"
done

rm -rf "$DEST"
mkdir -p "$DEST/firmware" "$DEST/lib"

# Firmware: src/ (minus the parts no portduino build uses) and the native variant headers.
FW="$WORK/firmware"
# The whole src/ tree (sources.cmake picks what is compiled; many headers are included regardless),
# minus the microcontroller platforms.
rsync -a --exclude 'platform/esp32' --exclude 'platform/nrf52' --exclude 'platform/rp2xx0' --exclude 'platform/stm32wl' \
  --exclude 'nimble' "$FW/src/" "$DEST/firmware/src/"
mkdir -p "$DEST/firmware/variants/native/portduino"
cp "$FW"/variants/native/portduino/*.h "$DEST/firmware/variants/native/portduino/"
cp "$FW"/LICENSE "$FW"/version.properties "$DEST/firmware/"
git -C "$FW" rev-parse HEAD > "$DEST/firmware/COMMIT"

# Libraries: sources and license only.
copylib() {   # name subdir...
  local name=$1; shift
  mkdir -p "$DEST/lib/$name"
  for sub in "$@"; do rsync -a "$WORK/$name/$sub" "$DEST/lib/$name/"; done
  cp "$WORK/$name"/LICENSE* "$WORK/$name"/license* "$DEST/lib/$name/" 2>/dev/null || true
  git -C "$WORK/$name" rev-parse HEAD > "$DEST/lib/$name/COMMIT"
}
copylib portduino cores ArduinoCore-API libraries
copylib RadioLib src
copylib Crypto .
cp "$WORK/nanopb"/pb*.h "$WORK/nanopb"/pb*.c "$WORK/nanopb"/LICENSE.txt "$DEST/lib/" 2>/dev/null; mkdir -p "$DEST/lib/nanopb" && mv "$DEST"/lib/pb*.* "$DEST"/lib/LICENSE.txt "$DEST/lib/nanopb/" && git -C "$WORK/nanopb" rev-parse HEAD > "$DEST/lib/nanopb/COMMIT"
copylib ArduinoThread .
copylib arduino-fsm .
copylib OneButton src
copylib TinyGPSPlus src
copylib ErriezCRC32 src
copylib esp8266-oled-ssd1306 src
copylib Melopero_RV3028 src
rsync -a "$WORK/WiFi/" "$DEST/lib/portduino/libraries/WiFi/" --exclude .git   # the core's WiFi submodule
find "$DEST/lib" -name '.git' -prune -exec rm -rf {} + 2>/dev/null || true
find "$DEST/lib" -type d \( -name examples -o -name extras -o -name test -o -name tests -o -name docs \) -prune -exec rm -rf {} +
find "$DEST" -name 'cmake-build-*' -prune -exec rm -rf {} +
du -sh "$DEST"
