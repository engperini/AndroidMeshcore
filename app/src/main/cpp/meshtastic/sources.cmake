# Meshtastic firmware source set, shared by the Android build (../CMakeLists.txt) and the PC test
# build (tools/meshtastic/hosttest). It mirrors the firmware's own [env:native-wasm] build: the
# portduino firmware with the browser/WASM guards (ARCH_PORTDUINO_WASM), which already leave out the
# Linux host services. Android code replaces the WASM glue (meshtastic/android/).
#
# Input: MT_DIR = this directory. Output: MT_SOURCES, MT_GLUE, MT_INCLUDES, MT_IDIRAFTER, MT_DEFINES.

set(MT_V ${MT_DIR}/vendor)
set(FW ${MT_V}/firmware/src)
set(PD ${MT_V}/lib/portduino)

# ---- firmware (same filter as [env:native-wasm] build_src_filter)
set(MT_FW_TOP
  main.cpp PowerFSM.cpp Power.cpp airtime.cpp sleep.cpp RedirectablePrint.cpp SerialConsole.cpp Observer.cpp
  FSCommon.cpp SafeFile.cpp MessageStore.cpp meshUtils.cpp UptimeClock.cpp memGet.cpp GpioLogic.cpp PowerMon.cpp
  SPILock.cpp xmodem.cpp DisplayFormatters.cpp detect/ScanI2C.cpp detect/ScanI2CTwoWire.cpp
  detect/ScanI2CConsumer.cpp power/PowerHAL.cpp gps/RTC.cpp buzz/buzz.cpp buzz/BuzzerFeedbackThread.cpp
  platform/portduino/PortduinoGlue.cpp platform/portduino/SimRadio.cpp
  platform/portduino/wasm/stubs/serializer_stub.cpp
)
list(TRANSFORM MT_FW_TOP PREPEND ${FW}/)
file(GLOB MT_FW_MESH ${FW}/mesh/*.cpp ${FW}/mesh/*.c)
list(FILTER MT_FW_MESH EXCLUDE REGEX "LR20x0Interface\\.cpp$")   # amalgamated into InterfacesTemplates.cpp
file(GLOB MT_FW_GEN ${FW}/mesh/generated/meshtastic/*.cpp)
file(GLOB MT_FW_MISC ${FW}/concurrency/*.cpp ${FW}/memory/*.cpp)
file(GLOB_RECURSE MT_FW_MODULES ${FW}/modules/*.cpp)
list(FILTER MT_FW_MODULES EXCLUDE REGEX "/modules/esp32/")
# I2C sensor drivers: no I2C bus on a phone (environmental and air-quality sensors are excluded anyway)
list(FILTER MT_FW_MODULES EXCLUDE REGEX "/modules/Telemetry/Sensor/")

# ---- portduino Arduino core: the same emcc-safe subset platform-wasm builds
set(MT_CORE
  ${PD}/ArduinoCore-API/api/Common.cpp ${PD}/ArduinoCore-API/api/Stream.cpp ${PD}/ArduinoCore-API/api/Print.cpp
  ${PD}/ArduinoCore-API/api/String.cpp ${PD}/ArduinoCore-API/api/IPAddress.cpp
  ${PD}/cores/portduino/PortduinoGPIO.cpp ${PD}/cores/portduino/PortduinoPrint.cpp ${PD}/cores/portduino/logging.cpp
  ${PD}/cores/portduino/Utility.cpp ${PD}/cores/portduino/itoa.cpp ${PD}/cores/portduino/dtostrf.c
  ${PD}/cores/portduino/FS/FS.cpp ${PD}/cores/portduino/FS/vfs_api.cpp ${PD}/cores/portduino/FS/PortduinoFS.cpp
  ${PD}/cores/portduino/linux/millis.cpp ${PD}/cores/portduino/linux/LinuxSerial.cpp
  ${PD}/cores/portduino/linux/LinuxHardwareI2C.cpp ${PD}/cores/portduino/linux/LinuxHardwareSPI.cpp
)

# ---- libraries
file(GLOB_RECURSE MT_RADIOLIB ${MT_V}/lib/RadioLib/src/*.cpp)
file(GLOB MT_CRYPTO ${MT_V}/lib/Crypto/*.cpp)
list(FILTER MT_CRYPTO EXCLUDE REGEX "Esp32\\.cpp$")
file(GLOB MT_NANOPB ${MT_V}/lib/nanopb/*.c)
set(MT_LIBS_SMALL
  ${MT_V}/lib/ArduinoThread/Thread.cpp ${MT_V}/lib/ArduinoThread/ThreadController.cpp
  ${MT_V}/lib/arduino-fsm/Fsm.cpp
)
file(GLOB MT_LIBS_MORE ${MT_V}/lib/OneButton/src/*.cpp ${MT_V}/lib/TinyGPSPlus/src/*.cpp ${MT_V}/lib/ErriezCRC32/src/*.c
  ${MT_V}/lib/esp8266-oled-ssd1306/src/*.cpp ${MT_V}/lib/Melopero_RV3028/src/*.cpp ${PD}/libraries/WiFi/src/*.cpp ${PD}/libraries/SPI/src/*.cpp
  ${PD}/libraries/Wire/src/*.cpp)

set(MT_SOURCES ${MT_FW_TOP} ${MT_FW_MESH} ${MT_FW_GEN} ${MT_FW_MISC} ${MT_FW_MODULES} ${MT_CORE}
  ${MT_RADIOLIB} ${MT_CRYPTO} ${MT_NANOPB} ${MT_LIBS_SMALL} ${MT_LIBS_MORE})

# Glue shared by the app and the PC test (the app adds mt_jni.cpp and its USB bridges).
set(MT_GLUE ${MT_DIR}/android/mt_glue.cpp ${MT_DIR}/android/pinedio_bus.cpp ${MT_DIR}/android/mt_api.cpp)

# Android/PC glue first: its emscripten.h shim and libpinedio-usb.h win over anything else.
set(MT_INCLUDES
  ${MT_DIR}/android
  ${FW}/platform/portduino/wasm/stubs
  ${FW}/platform/portduino/wasm/include
  ${FW} ${FW}/mesh ${FW}/mesh/generated ${FW}/gps ${FW}/buzz
  ${MT_V}/firmware/variants/native/portduino
  ${FW}/platform/portduino
  ${PD}/cores/portduino ${PD}/cores/portduino/FS ${PD}/ArduinoCore-API
  ${PD}/libraries/SPI/src ${PD}/libraries/Wire/src ${PD}/libraries/WiFi/src
  ${MT_V}/lib/RadioLib/src ${MT_V}/lib/Crypto ${MT_V}/lib/nanopb ${MT_V}/lib/ArduinoThread ${MT_V}/lib/arduino-fsm
  ${MT_V}/lib/OneButton/src ${MT_V}/lib/TinyGPSPlus/src ${MT_V}/lib/ErriezCRC32/src ${MT_V}/lib/esp8266-oled-ssd1306/src
  ${MT_V}/lib/Melopero_RV3028/src
)
# Arduino's String.h must not shadow <string.h>: added after the system headers.
set(MT_IDIRAFTER ${PD}/ArduinoCore-API/api)
# Compile options every source needs (the firmware's build forces these too).
set(MT_OPTIONS -w -fexceptions "SHELL:-include sys/time.h" "SHELL:-include ${MT_DIR}/android/userprefs.h"
  "SHELL:-idirafter ${MT_IDIRAFTER}")

set(MT_DEFINES
  ARDUINO=4403 PORTDUINO ARCH_PORTDUINO ARCH_PORTDUINO_WASM RADIOLIB_EEPROM_UNSUPPORTED
  # [env] build_flags
  USE_THREAD_NAMES TINYGPS_OPTION_NO_CUSTOM_FIELDS PB_ENABLE_MALLOC=1 PB_VALIDATE_UTF8=1 MAX_THREADS=40
  RADIOLIB_EXCLUDE_CC1101=1 RADIOLIB_EXCLUDE_NRF24=1 RADIOLIB_EXCLUDE_RF69=1 RADIOLIB_EXCLUDE_SX1231=1
  RADIOLIB_EXCLUDE_SX1233=1 RADIOLIB_EXCLUDE_SI443X=1 RADIOLIB_EXCLUDE_RFM2X=1 RADIOLIB_EXCLUDE_AFSK=1
  RADIOLIB_EXCLUDE_BELL=1 RADIOLIB_EXCLUDE_HELLSCHREIBER=1 RADIOLIB_EXCLUDE_MORSE=1 RADIOLIB_EXCLUDE_RTTY=1
  RADIOLIB_EXCLUDE_SSTV=1 RADIOLIB_EXCLUDE_AX25=1 RADIOLIB_EXCLUDE_DIRECT_RECEIVE=1 RADIOLIB_EXCLUDE_PAGER=1
  RADIOLIB_EXCLUDE_FSK4=1 RADIOLIB_EXCLUDE_APRS=1 RADIOLIB_EXCLUDE_ADSB=1 RADIOLIB_EXCLUDE_LORAWAN=1
  MESHTASTIC_EXCLUDE_DROPZONE=1 MESHTASTIC_EXCLUDE_REPLYBOT=1 MESHTASTIC_EXCLUDE_RANGETEST=1
  MESHTASTIC_EXCLUDE_REMOTEHARDWARE=1 MESHTASTIC_EXCLUDE_HEALTH_TELEMETRY=1 MESHTASTIC_EXCLUDE_POWERSTRESS=1
  MESHTASTIC_EXCLUDE_POWERMON=1 MESHTASTIC_EXCLUDE_GENERIC_THREAD_MODULE=1
  # [env:native-wasm]: a headless node, no screen / GPS / I2C / sensors / host services
  HAS_SCREEN=0 MESHTASTIC_EXCLUDE_SCREEN=1 MESHTASTIC_EXCLUDE_GPS=1 NO_GPS=1 NO_EXT_GPIO=1
  MESHTASTIC_EXCLUDE_I2C=1 MESHTASTIC_EXCLUDE_ACCELEROMETER=1 MESHTASTIC_EXCLUDE_MAGNETOMETER=1
  MESHTASTIC_EXCLUDE_AUDIO=1 MESHTASTIC_EXCLUDE_INPUTBROKER=1 MESHTASTIC_EXCLUDE_MQTT=1 MESHTASTIC_EXCLUDE_TZ=1
  MESHTASTIC_EXCLUDE_ENVIRONMENTAL_SENSOR=1 MESHTASTIC_EXCLUDE_DETECTIONSENSOR=1
  MESHTASTIC_EXCLUDE_EXTERNALNOTIFICATION=1 MESHTASTIC_EXCLUDE_CANNEDMESSAGES=1 MESHTASTIC_EXCLUDE_STOREFORWARD=1
  MESHTASTIC_EXCLUDE_SERIAL=1 MESHTASTIC_EXCLUDE_PAXCOUNTER=1 MESHTASTIC_EXCLUDE_WAYPOINT=1 MESHTASTIC_EXCLUDE_RTTTL=1
  # Android: the phone is the network side, so no WiFi stack (and no TCP API server) on the node
  MESHTASTIC_EXCLUDE_WIFI=1 MESHTASTIC_EXCLUDE_AIR_QUALITY_SENSOR=1 MESHTASTIC_EXCLUDE_POWER_TELEMETRY=1
  # version (bin/platformio-custom.py)
  APP_VERSION=2.8.3 APP_VERSION_SHORT=2.8.3 APP_ENV=android APP_REPO=engperini BUILD_EPOCH=1793000000
)
