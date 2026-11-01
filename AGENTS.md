# AGENTS.md

## What This Repository Is

Android app that runs a full MeshCore companion node on the phone, with a USB MeshStick
(CH341A `1a86:5512` + SX1262) or a XIAO nRF52840 + Wio-SX1262 running the
[xiao-sx1262-usb-bridge](https://github.com/engperini/xiao-sx1262-usb-bridge) firmware as radio over USB OTG. Port of MeshCoreRPI (native Linux MeshCore).

## Layout

- `app/src/main/cpp/meshcore/`: vendored MeshCore core, companion firmware (`companion_radio/MyMesh.cpp`),
  ed25519 and rweather Crypto. Keep these close to upstream; Android specifics go in `android/`.
- `app/src/main/cpp/android/`: `RadioUsbBus` with `Ch341Bridge` and `XiaoBridge` (usbdevfs ioctls on the fd from
  `UsbDeviceConnection`; the XIAO speaks the bridge's framed CDC protocol, and the app asserts DTR),
  `SX1262LinuxRadio`, `target_android.cpp`, `AndroidSerialInterface`, `jni_bridge.cpp`.
- `app/src/main/java/com/engperini/meshcore/core/`: `Protocol.kt` (companion frames), `MeshController.kt`
  (state, serialized command queue, message history), `NativeMesh.kt` (JNI).
- `service/`: foreground service owning the USB connection; `ui/`: Compose screens in the MeshSDR "night sky" style
  (`Theme.kt`, `Components.kt`).
- `tools/hosttest/`: PC build of the same firmware with a UDP "radio", used by `TwoNodeMeshTest`.

## Rules

- All user-visible text is bilingual: `tr("English", "Português")` (`core/Lang.kt`), never a bare string.
  The README keeps an English and a Portuguese section in sync.

- The app is the companion client: every node feature goes through companion frames, as MyMesh defines them.
  Check frame layouts in `MyMesh.cpp` before changing `Protocol.kt`, and extend `TwoNodeMeshTest`.
- Compile-time limits (`MAX_CONTACTS`, `MAX_GROUP_CHANNELS`, radio defaults) live in `CMakeLists.txt` and
  `tools/hosttest/build.sh`; keep both in sync.
- Auto-added contacts are announced with `PUSH_ADVERT`; `PUSH_NEW_ADVERT` only means "discovered, not saved"
  (manual-add mode).

## Validate

```bash
tools/hosttest/build.sh
./gradlew testDebugUnitTest assembleRelease   # screenshots in app/build/screenshots (pt-*, en-*)
```
