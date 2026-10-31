#!/usr/bin/env bash
# Builds the host test node from the same sources the Android app uses.
set -euo pipefail
cd "$(dirname "$0")"
C=../../app/src/main/cpp
mkdir -p build/ed25519
for f in $C/meshcore/lib/ed25519/*.c; do
  cc -O1 -w -c "$f" -o "build/ed25519/$(basename "${f%.c}").o"
done
c++ -std=gnu++17 -O1 -w \
  -I include -I $C/android/include -I $C/android -I $C/meshcore/crypto -I $C/meshcore/lib/ed25519 \
  -I $C/meshcore/src -I $C/meshcore/src/helpers -I $C/meshcore/companion_radio \
  -DARDUINO=1 -DRP2040_PLATFORM=1 -DMESH_PACKET_LOGGING=0 -DMESH_DEBUG=0 -DMAX_CONTACTS=500 \
  -DMAX_GROUP_CHANNELS=20 -DMAX_NEIGHBOURS=100 -DOFFLINE_QUEUE_SIZE=64 -DLORA_FREQ=923.125 -DLORA_BW=62.5 \
  -DLORA_SF=8 -DLORA_CR=8 -DLORA_TX_POWER=20 -DMAX_LORA_TX_POWER=22 '-DFIRMWARE_VERSION="v1.14.0-android"' \
  host_node.cpp $C/android/AndroidSerialInterface.cpp \
  $C/meshcore/src/{Packet,Dispatcher,Mesh,Utils,Identity}.cpp \
  $C/meshcore/src/helpers/{StaticPoolPacketManager,BaseChatMesh,TxtDataHelpers,AdvertDataHelpers,IdentityStore,TransportKeyStore}.cpp \
  $C/meshcore/companion_radio/{DataStore,MyMesh}.cpp \
  $C/meshcore/crypto/*.cpp build/ed25519/*.o \
  -o build/host_node
echo "built tools/hosttest/build/host_node"
