cmake_minimum_required(VERSION 3.22.1)
project(meshcore_android CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(MC ${CMAKE_CURRENT_SOURCE_DIR}/meshcore)
set(AND ${CMAKE_CURRENT_SOURCE_DIR}/android)

file(GLOB CRYPTO_SRC ${MC}/crypto/*.cpp)
file(GLOB ED25519_SRC ${MC}/lib/ed25519/*.c)

add_library(meshcore SHARED
  ${MC}/src/Packet.cpp
  ${MC}/src/Dispatcher.cpp
  ${MC}/src/Mesh.cpp
  ${MC}/src/Utils.cpp
  ${MC}/src/Identity.cpp
  ${MC}/src/helpers/StaticPoolPacketManager.cpp
  ${MC}/src/helpers/BaseChatMesh.cpp
  ${MC}/src/helpers/TxtDataHelpers.cpp
  ${MC}/src/helpers/AdvertDataHelpers.cpp
  ${MC}/src/helpers/IdentityStore.cpp
  ${MC}/src/helpers/TransportKeyStore.cpp
  ${MC}/companion_radio/DataStore.cpp
  ${MC}/companion_radio/MyMesh.cpp
  ${AND}/Ch341Bridge.cpp
  ${AND}/XiaoBridge.cpp
  ${AND}/SX1262LinuxRadio.cpp
  ${AND}/target_android.cpp
  ${AND}/AndroidSerialInterface.cpp
  ${AND}/jni_bridge.cpp
  ${CRYPTO_SRC}
  ${ED25519_SRC}
)

# Same include order as the Raspberry Pi native build: host shims first.
target_include_directories(meshcore PRIVATE
  ${AND}/include
  ${AND}
  ${MC}/crypto
  ${MC}/lib/ed25519
  ${MC}/src
  ${MC}/src/helpers
  ${MC}/companion_radio
)

# Compile-time limits, as in the RaspberryPiMC_native_companion target.
# Radio defaults: MeshCore Brasil (923.125 MHz, 62.5 kHz, SF8, CR8).
target_compile_definitions(meshcore PRIVATE
  ARDUINO=1
  RP2040_PLATFORM=1
  MESH_PACKET_LOGGING=0
  MESH_DEBUG=0
  MAX_CONTACTS=500
  MAX_GROUP_CHANNELS=20
  MAX_NEIGHBOURS=100
  OFFLINE_QUEUE_SIZE=64
  LORA_FREQ=923.125
  LORA_BW=62.5
  LORA_SF=8
  LORA_CR=8
  LORA_TX_POWER=20
  MAX_LORA_TX_POWER=22
  FIRMWARE_VERSION="v1.14.0-android"
  ENABLE_PRIVATE_KEY_EXPORT=1
  ENABLE_PRIVATE_KEY_IMPORT=1
)

target_compile_options(meshcore PRIVATE -Wno-unused-parameter -Wno-sign-compare -fexceptions)
target_link_libraries(meshcore log)
