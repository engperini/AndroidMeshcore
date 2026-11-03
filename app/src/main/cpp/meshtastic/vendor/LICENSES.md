# Third-party code in this directory

Fetched by `tools/meshtastic/vendor.sh` at the revisions it pins (see each `COMMIT` file). Licenses:

| Directory | Project | License |
|---|---|---|
| `firmware/` | [Meshtastic firmware](https://github.com/meshtastic/firmware) | GPL-3.0 (`firmware/LICENSE`) |
| `lib/portduino/` | [framework-portduino](https://github.com/meshtastic/framework-portduino), with the Arduino core API | GPL-3.0 / LGPL-2.1 (`lib/portduino/LICENSE`) |
| `lib/RadioLib/` | [RadioLib](https://github.com/jgromes/RadioLib) | MIT (`lib/RadioLib/license.txt`) |
| `lib/Crypto/` | [Arduino Crypto](https://github.com/rweather/arduinolibs) | MIT (in each source file's header) |
| `lib/nanopb/` | [nanopb](https://github.com/nanopb/nanopb) | zlib (`lib/nanopb/LICENSE.txt`) |
| `lib/ArduinoThread/` | [ArduinoThread](https://github.com/ivanseidel/ArduinoThread) | MIT (`LICENSE.txt`) |
| `lib/arduino-fsm/` | [arduino-fsm](https://github.com/jonblack/arduino-fsm) | MIT (`LICENSE`) |
| `lib/OneButton/` | [OneButton](https://github.com/mathertel/OneButton) | BSD (`LICENSE`) |
| `lib/TinyGPSPlus/` | [TinyGPSPlus](https://github.com/mikalhart/TinyGPSPlus) | LGPL-2.1 (in the source headers) |
| `lib/ErriezCRC32/` | [ErriezCRC32](https://github.com/Erriez/ErriezCRC32) | MIT (`LICENSE`) |
| `lib/esp8266-oled-ssd1306/` | [esp8266-oled-ssd1306](https://github.com/ThingPulse/esp8266-oled-ssd1306) | MIT (`license`) |
| `lib/Melopero_RV3028/` | [Melopero RV3028](https://github.com/melopero/Melopero_RV-3028_Arduino_Library) | MIT (`LICENSE`) |

The app links them into `libmeshtastic.so`, so the APK as a whole is distributed under GPL-3.0.
