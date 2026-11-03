# MeshCommunity (AndroidMeshcore)

**English** · [Português](#português)

Run a **full [MeshCore](https://github.com/meshcore-dev/MeshCore) node inside your Android phone**. Plug a
**USB MeshStick** (CH341 + SX1262) into the phone with an **OTG** cable and start using it: group channels, direct
messages, contacts, rooms, repeater admin, map and radio settings, all in one app. No firmware flashing, no
Bluetooth, no second app.

**One radio, several protocols, no flashing:** the protocol runs on the phone, so the same USB radio switches
between modes in Settings: **MeshCore**, **generic LoRa** and **Meshtastic** (the Meshtastic firmware itself, built for
Android). One protocol runs at a time; each keeps its own identity, nodes and messages.

<p align="center">
  <img src="docs/hardware.jpg" alt="Android phone with the USB MeshStick on an OTG cable, MeshCommunity showing the radio diagnostics" width="360"><br>
  <sub>The real thing: an Android phone + a USB MeshStick (CH341 + SX1262) on an OTG adapter, receiving the MeshCore Brasil mesh.</sub>
</p>

> [!WARNING]
> Use at your own risk. You are responsible for transmitting only on frequencies and power levels that are legal
> where you are.

This is the Android port of [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI). It runs the same MeshCore
companion firmware (`MyMesh`) and the same SX1262 + CH341 driver, built with the NDK. The app talks to them with
the official companion protocol.

## How it works

```
┌──────────── Android phone ───────────────┐
│  UI (Compose): chats, contacts, map, network, settings
│        │ companion protocol frames
│  libmeshcore.so (NDK)                    │
│    MyMesh (MeshCore companion firmware)  │
│    SX1262 driver ── Ch341Bridge (usbdevfs)│
└──────────────────┬───────────────────────┘
                   │ USB OTG
            USB MeshStick (CH341A 1a86:5512 + SX1262)
```

- The MeshCore node runs **on the phone**. The MeshStick is only the radio, driven over USB (CH341 SPI/GPIO).
- Identity, contacts and channels are stored by the app; message history lives in SQLite on the phone.
- A foreground service keeps the radio running with the screen off.

## Screens

<p align="center">
  <img src="docs/screens/en-1-conversas.jpg" width="200" alt="Chats: channels and direct messages">
  <img src="docs/screens/en-7-chat-direto.jpg" width="200" alt="Direct message with delivery confirmation">
  <img src="docs/screens/en-2-contatos.jpg" width="200" alt="Contacts filtered by type">
</p>
<p align="center">
  <img src="docs/screens/en-9-admin-repetidor.jpg" width="200" alt="Remote repeater admin">
  <img src="docs/screens/en-4-rede.jpg" width="200" alt="Network: stats and charts">
  <img src="docs/screens/en-5-config.jpg" width="200" alt="Radio settings by region">
</p>

Same look as [MeshSDR-Android](https://github.com/engperini/MeshSDR-Android): dark "night sky" theme, translucent
cards and cyan accents. The screens above are rendered by the tests (`ScreenshotTest`) with sample data.

## Features

The app is in **English and Portuguese**: it follows the phone's language and can be switched in
**Settings → Language**.

### Meshtastic mode

**Settings → Mode → Meshtastic** runs the [Meshtastic firmware](https://github.com/meshtastic/firmware) (2.8, its
portduino/WASM build) on the phone, with the same USB radio (MeshStick, USB LoRa stick or XIAO bridge). The node talks
to other Meshtastic devices on the air like any Meshtastic radio.

- 💬 **Chats**: the node's channels (the primary channel shows its preset name, e.g. *LongFast*) and direct messages.
  Your messages show when a node was heard passing them on (implicit ACK), and direct messages turn *delivered ✓✓*
  with the destination's ACK.
- 📡 **Nodes**: every node heard, with hops, SNR, battery and last heard; **Traceroute** shows the path out and back
  with the SNR of each hop. ⋮ → **Announce node** sends your NodeInfo now.
- 🗺️ **Map**: nodes that share a position; **Share my position** sends the phone's location as your node's.
- ⚙️ **Settings**: long and short name, **region** (Meshtastic does not transmit until it is set; in Brazil most
  communities use **ANZ**), modem preset, hop limit, power, and channels (name and key, add/delete).
- The node runs in its own Android process; a node restart (after some settings) takes a few seconds. Its data stays
  in the app's storage and is kept apart from MeshCore's.

- 💬 **Chats**: group channels and direct messages in one list, with unread counts and the last message.
  - **Channels**: *Public* comes preconfigured. Create a **#hashtag** channel (everyone using the same `#name` joins
    the same chat), a **new private** channel (random key) or join one **with a key**. To share, long-press the
    channel → **QR code**. Your channel messages show **how many repeaters were heard repeating them** (tap to see
    which).
  - **Direct messages**: *sending → sent → delivered ✓✓* from the recipient's ACK, with the round-trip time. Tap an
    unconfirmed message to resend it.
  - **Rooms (room servers)**: log in from the chat itself; posts show their author.
- 👥 **Contacts**: search, filters (chat, repeater, room, sensor), sorting (recent, name, hops) and "last seen".
  The contact screen shows the route (repeaters on the path), with **Discover route** (out and back, with
  repeater names), **Ping** for repeaters (round trip and SNR at each hop), **Use this route**, **Reset**, **QR code**, zero-hop share and remove. In "add manually" mode, heard nodes wait in
  **Discovered** with an Add button.
- 🛠️ **Remote admin** of repeaters, rooms and sensors:
  - admin or guest **login** (the password can be remembered);
  - **status**: battery, uptime, queue, packets, flood/direct, airtime, RSSI/SNR and noise;
  - encrypted **CLI terminal**, with shortcuts (`ver`, `clock`, `neighbors`, `get radio`…);
  - every request shows its state: **waiting** (direct or by flood, and for how long), **answered in N s**, or
    **no answer**, with a hint (e.g. a command without an admin login gets no reply).
- 🔳 **`meshcore://` QR codes**, compatible with the official app: show yours, a contact's or a channel's, scan them
  with the camera, or open a `meshcore://` link from another app.
- 🗺️ **Map** of contacts with GPS, on OpenStreetMap with no API key (like MeshCoreRPI's web map), in night mode.
- 📊 **Network**, like the Pi web dashboard:
  - node stats: received/sent, flood/direct, SNR, RSSI, noise, uptime, airtime, errors;
  - SNR/RSSI chart and packet counts by type;
  - **radio diagnostics** (`radio-diag`) and the received-packet log.
- ⚙️ **Settings**:
  - **Radio in 3 steps**: pick the **region** (Brazil, USA/Canada, EU/UK, Australia, New Zealand), then the **path
    bytes** (1/2/3; MeshCore Brasil uses 2) and the **power**. Frequency, BW, SF, CR and repeat mode are under
    *Advanced*.
  - **Advert**: manual (flood or zero-hop) and automatic (1 h, 3 h, 12 h).
  - **Contacts**: add automatically or manually, by type, and replace the oldest when the list is full.
  - **Telemetry**: who can read your battery (nobody, favourites, everyone).
  - **Location** in the advert (typed or from the phone's GPS).
  - **Identity backup**: export and restore the private key.
  - **Language**: system, English or Português.
- 📻 **Modes** (Settings → Mode), one at a time on the same radio, each keeping its own data:
  - **MeshCore**: the node described above.
  - **Generic LoRa**: raw LoRa packets with free parameters (frequency, bandwidth 7.8–500 kHz, SF5–12, CR,
    sync word, preamble, power), for your own sensors and telemetry. Every packet heard shows up as text or
    hex with RSSI/SNR and a signal chart, and you can send packets. Presets listen to MeshCore and Meshtastic
    channels. The MeshCore node pauses and comes back as it was.
  - **Meshtastic**: planned, the same way the MeshCore firmware runs on the phone.
- 🔔 New-message notifications, the radio keeps running with the screen off, and the app opens by itself when you
  plug in the MeshStick.

## Compared with the official MeshCore app and MeshCoreRPI

| Feature | Official MeshCore app | MeshCoreRPI (Pi) | MeshCommunity (this app) |
|---|---|---|---|
| How it reaches the radio | BLE/USB to a node running firmware | SPI/USB on the Pi; clients over TCP | **USB OTG straight to the MeshStick, the node runs on the phone** |
| Public, #hashtag and private channels | ✅ | via a client | ✅ |
| Direct messages with ACK and resend | ✅ | via a client | ✅ |
| Rooms (room servers) | ✅ | via a client | ✅ login + authors |
| Repeater admin (login, status, CLI) | ✅ | ✅ (local web/CLI) | ✅ |
| Discover / set route | ✅ | — | ✅ |
| `meshcore://` QR codes | ✅ | — | ✅ show, scan, open links |
| Contact map | ✅ | ✅ (web map) | ✅ OpenStreetMap |
| Radio presets by region | ✅ | README | ✅ region → bytes → power |
| Path hash size | ✅ | ✅ | ✅ |
| Automatic advert | hourly | ✅ (minutes) | ✅ |
| Stats, charts, packet log | partial | ✅ (web dashboard) | ✅ Network tab |
| Radio diagnostics (`radio-diag`) | — | ✅ | ✅ |
| Identity backup (private key) | ✅ | — | ✅ |
| English and Portuguese | ✅ | — | ✅ |
| Ping (trace) to a repeater, SNR per hop | ✅ | — | ✅ |
| Sensor telemetry | ✅ | — | not yet |

## Hardware

| Radio | Connection | Status |
|---|---|---|
| USB MeshStick (CH341A `1a86:5512` + SX1262), e.g. the Elecrow MeshStick | USB OTG | ✅ supported, field-tested |
| Seeed **XIAO nRF52840 + Wio-SX1262** kit with the [xiao-sx1262-usb-bridge](https://github.com/engperini/xiao-sx1262-usb-bridge) firmware | USB OTG | ✅ supported |
| **USB LoRa stick** (CH341F + Wio-SX1262, no MCU), in [`hardware/`](hardware/) | USB OTG | 🧪 v0.2 design, not built yet |

Pins, TCXO (DIO3) and RF switch (DIO2) are preset for the MeshStick (CH341 D0/D2/D4/D6), as in MeshCoreRPI.

The XIAO kit has a microcontroller, but here it only works as a USB-to-SPI bridge, like the MeshStick's CH341: the
node still runs on the phone. Flash the bridge once (double-press reset, copy the `.uf2` to the `XIAO-SENSE` drive),
then plug it in. The bridge tells the app how its radio is wired (1.8 V TCXO, DIO2 and RXEN RF switch).

**Future hardware:** [`hardware/`](hardware/) has a no-MCU USB LoRa stick (KiCad board with the JLCPCB order files)
and 3D-printable cases for the stick and for the XIAO kit, each with a straight USB-C male-male adapter inside.

You need:

- An Android 8.0+ phone with **USB host/OTG** (almost all current phones).
- An **OTG** adapter or cable (e.g. USB-C → USB-A female).
- An **antenna** on the MeshStick (or the XIAO's Wio-SX1262) **before** powering it.

## Install

1. Download `MeshCoreUSB.apk` from **Releases** or from the latest run in **Actions**.
2. Install it. Android will ask you to allow installs from unknown sources.
3. Attach the antenna, plug the MeshStick into the phone with OTG, and allow **MeshCommunity to open** and to
   **access the USB device**. Tick "always" so it doesn't ask again.
4. In **Settings** → **LoRa radio**, pick your **region** (default: Brazil 923.125 MHz, 62.5 kHz, SF8, CR8), the
   **path bytes** (Brazil: 2) and tap **Apply**. Under **Identity**, give your node a name.
5. Menu ⋮ → **Send advert (flood)** to be discovered. Then open the *Public* channel and say hi 👋

To update, install the new APK over the old one: identity, contacts and messages are kept as long as both APKs have
the same signature. Before changing phones or reinstalling, use **Settings → Identity backup → Export**.

> [!TIP]
> Some phones switch OTG off after a while or save battery aggressively. If the radio stops with the screen off,
> turn off battery optimisation for MeshCommunity.

## Build

Requirements: JDK 17, Android SDK (platform 35), NDK `27.2.12479018` and CMake `3.22.1`.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # R8-minified APK
```

Signing: every build, local or CI, is signed with the public test key committed in `app/meshcore-debug.jks`
(as in MeshSDR), so new APKs install over the previous one and keep your identity. It is public on purpose: anyone
can build and contribute. A fork can use its own key with `MESHCORE_KEYSTORE_FILE`, `MESHCORE_KEYSTORE_PASSWORD`,
`MESHCORE_KEY_ALIAS` and `MESHCORE_KEY_PASSWORD`.

### Tests (real firmware, two nodes)

`tools/hosttest` builds a **PC test node** from the same sources as `libmeshcore.so`, with a UDP "radio" on
localhost. `TwoNodeMeshTest` starts two nodes and drives them with the app's protocol code (`Protocol.kt`):

- adverts and the contact list;
- direct messages with ACK;
- Public and #hashtag channels;
- radio parameters, location, contact removal;
- manual add of a discovered contact;
- `meshcore://` links, saved routes, stats, auto-add rules, key backup and route discovery.

`ScreenshotTest` / `ScreenshotTestEn` (Robolectric) render the main screens in Portuguese and English to
`app/build/screenshots/`.

```bash
tools/hosttest/build.sh
./gradlew testDebugUnitTest
```

The `.github/workflows/android.yml` workflow runs the tests and builds the APK on every push. On `v*` tags it
attaches the APK to the release.

## Layout

| Path | What it is |
|---|---|
| `app/src/main/cpp/meshcore/` | MeshCore core (`src/`), companion firmware (`companion_radio/`), ed25519 and Crypto (rweather), copied from MeshCoreRPI |
| `app/src/main/cpp/android/` | Android layer: USB radio buses over usbdevfs (`Ch341Bridge` for the MeshStick, `XiaoBridge` for the XIAO), SX1262 driver, target (battery/clock), `AndroidSerialInterface`, JNI |
| `app/src/main/java/.../core/` | `Protocol.kt` (companion frames), `MeshController.kt` (state, command queue, messages), `NativeMesh.kt` (JNI), `Lang.kt` (EN/PT) |
| `app/src/main/java/.../service/` | Foreground service + USB (permission, fd, wake lock, notifications) |
| `app/src/main/java/.../ui/` | Compose screens |
| `app/src/main/cpp/meshtastic/` | Meshtastic engine: the firmware and its libraries in `vendor/` (fetched by `tools/meshtastic/vendor.sh` at fixed revisions, GPL-3.0), the Android glue in `android/` (radio over the same USB buses, events/requests API, JNI) |
| `app/src/main/java/.../mt/`, `core/MeshtasticController.kt` | Meshtastic engine process (`MtEngineService`) and the app's side of it |
| `tools/hosttest/` | PC test node (same firmware, UDP radio) |
| `tools/meshtastic/hosttest/` | PC test of the Meshtastic engine (same sources and glue, simulated radio) |

Compile-time limits (as in MeshCoreRPI): `MAX_CONTACTS=500`, `MAX_GROUP_CHANNELS=20`, in
`app/src/main/cpp/CMakeLists.txt`.

## Credits and license

- [MeshCore](https://github.com/meshcore-dev/MeshCore) by Scott Powell / rippleradios.com: MIT.
- [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI): native Linux port, SX1262 and CH341 driver.
- [Arduino Crypto](https://github.com/rweather/arduinolibs) by Rhys Weatherley: MIT.
- ed25519 by Orson Peters: zlib.
- [Meshtastic firmware](https://github.com/meshtastic/firmware) by the Meshtastic developers: **GPL-3.0**, with
  [RadioLib](https://github.com/jgromes/RadioLib) (MIT), [portduino](https://github.com/meshtastic/framework-portduino)
  (GPL-3.0), [nanopb](https://github.com/nanopb/nanopb) (zlib) and the other libraries in
  `app/src/main/cpp/meshtastic/vendor/`, each with its license file.

This repository's own code is under the MIT license, see [LICENSE](LICENSE). The Meshtastic engine in
`app/src/main/cpp/meshtastic/` is GPL-3.0, and so the **APK, which includes it, is distributed under GPL-3.0**; its
complete source is this repository.

---

# Português

[English](#meshcommunity-androidmeshcore) · **Português**

Rode um nó **[MeshCore](https://github.com/meshcore-dev/MeshCore) completo dentro do celular Android**.
Você pluga um **USB MeshStick** (CH341 + SX1262) no celular com um cabo **OTG** e já sai usando:
canais em grupo, mensagens diretas, contatos, salas, administração de repetidores, mapa e configurações de rádio,
tudo no mesmo app. Não precisa gravar firmware, nem de Bluetooth, nem de um segundo app.

**Um rádio, vários protocolos, sem gravar firmware:** o protocolo roda no celular, então o mesmo rádio USB troca de
modo nas Configurações: **MeshCore**, **LoRa genérico** e **Meshtastic** (o próprio firmware Meshtastic, compilado para
Android). Um protocolo roda por vez; cada um mantém sua identidade, nós e mensagens.

<p align="center">
  <img src="docs/hardware.jpg" alt="Celular Android com o USB MeshStick conectado por cabo OTG" width="300"><br>
  <sub>Funcionando de verdade: celular Android + USB MeshStick num adaptador OTG, recebendo a malha MeshCore Brasil.</sub>
</p>

> [!WARNING]
> Use por sua conta e risco. Você é responsável por transmitir só em frequências e potências
> permitidas onde você está.

Este projeto é o port para Android do [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI). Ele usa o mesmo
firmware companion do MeshCore (`MyMesh`) e o mesmo driver SX1262 + CH341, compilados com o NDK. O app
fala com eles pelo protocolo companion oficial.

## Como funciona

```
┌──────────── celular Android ─────────────┐
│  UI (Compose): conversas, contatos, mapa, rede, config
│        │ frames do protocolo companion
│  libmeshcore.so (NDK)                    │
│    MyMesh (firmware companion MeshCore)  │
│    SX1262 driver ── Ch341Bridge (usbdevfs)│
└──────────────────┬───────────────────────┘
                   │ USB OTG
            USB MeshStick (CH341A 1a86:5512 + SX1262)
```

- O nó MeshCore roda **no celular**. O MeshStick é só o rádio, controlado via USB (SPI/GPIO do CH341).
- A identidade, os contatos e os canais ficam salvos no app. O histórico de mensagens fica em SQLite no celular.
- Um serviço em primeiro plano mantém o rádio ativo com a tela apagada.

## Telas

<p align="center">
  <img src="docs/screens/pt-1-conversas.jpg" width="200" alt="Conversas: canais e mensagens diretas">
  <img src="docs/screens/pt-7-chat-direto.jpg" width="200" alt="Mensagem direta com confirmação de entrega">
  <img src="docs/screens/pt-2-contatos.jpg" width="200" alt="Contatos com filtro por tipo">
</p>
<p align="center">
  <img src="docs/screens/pt-9-admin-repetidor.jpg" width="200" alt="Administração remota de repetidor">
  <img src="docs/screens/pt-4-rede.jpg" width="200" alt="Rede: estatísticas e gráficos">
  <img src="docs/screens/pt-5-config.jpg" width="200" alt="Configuração por região">
</p>

Mesmo visual do [MeshSDR-Android](https://github.com/engperini/MeshSDR-Android): tema escuro "céu noturno", cards
translúcidos e destaques em ciano. As telas acima são renderizadas pelos testes (`ScreenshotTest`) com dados de exemplo.

## Funcionalidades

O app está em **português e inglês**: segue o idioma do celular e pode ser trocado em **Config → Idioma**.

### Modo Meshtastic

**Config → Modo → Meshtastic** roda o [firmware Meshtastic](https://github.com/meshtastic/firmware) (2.8, a versão
portduino/WASM) no celular, com o mesmo rádio USB (MeshStick, pendrive LoRa USB ou ponte XIAO). O nó conversa no ar com
outros aparelhos Meshtastic como qualquer rádio Meshtastic.

- 💬 **Conversas**: os canais do nó (o primário mostra o nome do preset, ex.: *LongFast*) e mensagens diretas. Suas
  mensagens mostram quando um nó foi ouvido repassando (ACK implícito), e as diretas ficam *entregue ✓✓* com o ACK do
  destino.
- 📡 **Nós**: todos os nós ouvidos, com saltos, SNR, bateria e quando foi ouvido; o **Traceroute** mostra o caminho de
  ida e volta com o SNR de cada salto. ⋮ → **Anunciar nó** envia seu NodeInfo na hora.
- 🗺️ **Mapa**: os nós que compartilham posição; **Compartilhar posição** envia a localização do celular como a do seu nó.
- ⚙️ **Config**: nome longo e curto, **região** (o Meshtastic não transmite até ela ser definida; no Brasil a maioria
  das comunidades usa **ANZ**), preset do modem, limite de saltos, potência e canais (nome e chave, adicionar/excluir).
- O nó roda num processo Android próprio; um reinício do nó (depois de algumas configurações) leva alguns segundos. Os
  dados ficam no armazenamento do app, separados dos do MeshCore.


- 💬 **Conversas**: canais em grupo e mensagens diretas numa lista só, com não lidas e a última mensagem.
  - **Canais**: o *Public* já vem configurado. Você pode criar **#hashtag** (todos que usam o mesmo `#nome` entram
    na mesma conversa), canal **privado novo** (chave aleatória) ou entrar num canal **com chave**. Para compartilhar,
    toque e segure o canal → **QR code**. Suas mensagens no canal mostram **quantas repetidoras foram ouvidas
    repassando** (toque para ver quais).
  - **Mensagens diretas**: status *enviando → enviado → entregue ✓✓* pela confirmação (ACK) do destinatário, com o
    tempo de ida e volta. Toque numa mensagem sem confirmação para reenviar.
  - **Salas (room servers)**: login pelo próprio chat. As mensagens aparecem com o nome do autor.
- 👥 **Contatos**: busca, filtro (chat, repetidor, sala, sensor), ordenação (recentes, nome, saltos) e "visto há".
  O detalhe do contato mostra a rota (repetidores do caminho) e tem os botões **Descobrir rota** (ida e volta,
  com os nomes dos repetidores), **Ping** para repetidores (ida e volta e SNR em cada salto), **Usar esta rota**, **Redefinir**, **QR code**, compartilhar zero-hop e remover. No modo "adicionar manualmente",
  os nós ouvidos ficam em **Descobertos** com o botão Adicionar.
- 🛠️ **Administração remota** de repetidores, salas e sensores:
  - **login** de admin ou convidado (dá para lembrar a senha);
  - **status**: bateria, uptime, fila, pacotes, flood/direto, tempo no ar, RSSI/SNR e ruído;
  - **terminal CLI** criptografado, com atalhos (`ver`, `clock`, `neighbors`, `get radio`…);
  - cada pedido mostra em que pé está: **aguardando** (direto ou por flood, e por quanto tempo), **respondeu em
    N s** ou **sem resposta**, com uma dica (por exemplo, comando sem login de admin não recebe resposta).
- 🔳 **QR codes `meshcore://`**, compatíveis com o app oficial: mostre o seu, o de um contato ou o de um canal.
  Escaneie com a câmera, ou abra um link `meshcore://` vindo de outro app.
- 🗺️ **Mapa** dos contatos com GPS, em OpenStreetMap sem chave de API (como o mapa web do MeshCoreRPI), em modo
  noturno.
- 📊 **Rede**, como o painel web do Pi:
  - estatísticas do nó: recebidos/enviados, flood/direto, SNR, RSSI, ruído, uptime, tempo no ar, erros;
  - gráfico de SNR/RSSI e contagem por tipo de pacote;
  - **diagnóstico do rádio** (`radio-diag`) e log de pacotes recebidos.
- ⚙️ **Configurações**:
  - **Rádio em 3 passos**: escolha a **região** (Brasil, EUA/Canadá, EU/UK, Austrália, Nova Zelândia), depois os
    **bytes do caminho** (1/2/3; a MeshCore Brasil usa 2) e a **potência**. Frequência, BW, SF, CR e o modo
    repetidor ficam em *Avançado*.
  - **Advert** manual (flood ou zero-hop) e automático (1 h, 3 h, 12 h).
  - **Contatos**: adicionar automaticamente ou manualmente, por tipo, e substituir o mais antigo quando a lista
    encher.
  - **Telemetria**: com quem compartilhar a bateria (ninguém, favoritos, todos).
  - **Localização** no advert (digitada ou pelo GPS).
  - **Backup da identidade**: exportar e restaurar a chave privada.
  - **Idioma**: sistema, English ou Português.
- 📻 **Modos** (Configurações → Modo), um por vez no mesmo rádio, cada um com seus próprios dados:
  - **MeshCore**: o nó descrito acima.
  - **LoRa genérico**: pacotes LoRa crus com parâmetros livres (frequência, largura de banda de 7,8 a 500 kHz,
    SF5–12, CR, sync word, preâmbulo, potência), para seus sensores e telemetria. Cada pacote recebido aparece em
    texto ou hex com RSSI/SNR e gráfico de sinal, e dá para enviar pacotes. Há predefinições para ouvir canais
    MeshCore e Meshtastic. O nó MeshCore pausa e volta como estava.
  - **Meshtastic**: planejado, do mesmo jeito que o firmware MeshCore roda no celular.
- 🔔 Notificações de mensagens novas, rádio ativo com a tela apagada, e o app abre sozinho quando você pluga o
  MeshStick.

## Comparação com o app oficial do MeshCore e com o MeshCoreRPI

| Funcionalidade | App oficial MeshCore | MeshCoreRPI (Pi) | MeshCommunity (este app) |
|---|---|---|---|
| Como conecta ao rádio | BLE/USB com um nó que roda firmware | SPI/USB no Pi; clientes por TCP | **USB OTG direto no MeshStick, nó roda no celular** |
| Canais Public, #hashtag e privados | ✅ | via cliente | ✅ |
| Mensagens diretas com ACK e reenvio | ✅ | via cliente | ✅ |
| Salas (room servers) | ✅ | via cliente | ✅ login + autores |
| Admin de repetidor (login, status, CLI) | ✅ | ✅ (web/CLI local) | ✅ |
| Descobrir/definir rota | ✅ | — | ✅ |
| QR codes `meshcore://` | ✅ | — | ✅ mostrar, escanear, abrir links |
| Mapa de contatos | ✅ | ✅ (mapa web) | ✅ OpenStreetMap |
| Presets de rádio por região | ✅ | README | ✅ região → bytes → potência |
| Tamanho do hash de caminho | ✅ | ✅ | ✅ |
| Advert automático | horário | ✅ (minutos) | ✅ |
| Estatísticas, gráficos, log de pacotes | parcial | ✅ (painel web) | ✅ aba Rede |
| Diagnóstico do rádio (`radio-diag`) | — | ✅ | ✅ |
| Backup da identidade (chave privada) | ✅ | — | ✅ |
| Inglês e português | ✅ | — | ✅ |
| Ping (trace) até um repetidor, SNR por salto | ✅ | — | ✅ |
| Telemetria de sensores | ✅ | — | ainda não |

## Hardware

| Rádio | Conexão | Status |
|---|---|---|
| USB MeshStick (CH341A `1a86:5512` + SX1262), por exemplo o Elecrow MeshStick | USB OTG | ✅ suportado |
| Kit Seeed **XIAO nRF52840 + Wio-SX1262** com o firmware [xiao-sx1262-usb-bridge](https://github.com/engperini/xiao-sx1262-usb-bridge) | USB OTG | ✅ suportado |
| **Pendrive LoRa** (CH341F + Wio-SX1262, sem MCU), em [`hardware/`](hardware/) | USB OTG | 🧪 projeto v0.2, ainda não fabricado |

Pinos, TCXO (DIO3) e chave de RF (DIO2) já vêm configurados para o MeshStick (CH341 D0/D2/D4/D6), como no
MeshCoreRPI.

O kit XIAO tem um microcontrolador, mas aqui ele só faz a ponte USB→SPI, como o CH341 do MeshStick: o nó continua
rodando no celular. Grave a ponte uma vez (reset duas vezes, copie o `.uf2` para o drive `XIAO-SENSE`) e depois é
só plugar. A ponte informa ao app como o rádio está ligado (TCXO de 1,8 V, chave de RF por DIO2 e RXEN).

**Hardware futuro:** a pasta [`hardware/`](hardware/) tem um pendrive LoRa sem MCU (placa em KiCad com os arquivos
para pedir na JLCPCB) e caixinhas para imprimir em 3D, para o pendrive e para o kit XIAO, cada uma com um adaptador
USB-C macho-macho reto dentro.

Você precisa de:

- Celular Android 8.0 ou mais novo com **USB Host/OTG** (quase todos os atuais).
- Adaptador ou cabo **OTG** (USB-C → USB-A fêmea, por exemplo).
- **Antena** conectada ao MeshStick (ou ao Wio-SX1262 do XIAO) **antes** de ligar.

## Instalação

1. Baixe o `MeshCoreUSB.apk`, pela aba **Releases** ou pelo artefato da última execução em **Actions**.
2. Instale o APK. O Android vai pedir para permitir instalação de fontes desconhecidas.
3. Coloque a antena, conecte o MeshStick ao celular pelo OTG e aceite **abrir o MeshCommunity** e
   **permitir o acesso ao dispositivo USB**. Marque "usar sempre" para não perguntar de novo.
4. Na aba **Config** → **Rádio LoRa**, escolha a **região** (padrão: Brasil 923.125 MHz, 62.5 kHz, SF8, CR8), os
   **bytes do caminho** (Brasil: 2) e toque em **Aplicar**. Em **Identidade**, dê um nome ao seu nó.
5. Menu ⋮ → **Enviar advert (flood)** para ser descoberto. Depois é só abrir o canal *Public* e dar um oi 👋

Para atualizar, instale o APK novo por cima: a identidade, os contatos e as mensagens são mantidos, desde que os
dois APKs tenham a mesma assinatura. Antes de trocar de celular ou reinstalar, faça **Config → Backup da
identidade → Exportar**.

> [!TIP]
> Alguns celulares desligam o OTG depois de um tempo sem uso, ou economizam bateria de forma agressiva. Se o
> rádio parar com a tela apagada, desative a otimização de bateria para o MeshCommunity.

## Compilar

Requisitos: JDK 17, Android SDK (platform 35), NDK `27.2.12479018` e CMake `3.22.1`.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # APK com R8
```

Assinatura: todo build, local ou no CI, é assinado com a chave pública de teste que está em
`app/meshcore-debug.jks` (como no MeshSDR). Assim os APKs novos instalam por cima e mantêm a sua identidade. Ela é
pública de propósito, para qualquer pessoa compilar e contribuir. Um fork pode usar a própria chave com
`MESHCORE_KEYSTORE_FILE`, `MESHCORE_KEYSTORE_PASSWORD`, `MESHCORE_KEY_ALIAS` e `MESHCORE_KEY_PASSWORD`.

### Testes (firmware real, dois nós)

`tools/hosttest` compila um **nó de teste para PC** a partir das mesmas fontes do `libmeshcore.so`, com um
"rádio" UDP no localhost. O teste `TwoNodeMeshTest` sobe dois nós e usa o codificador/decodificador de
protocolo do app (`Protocol.kt`) para exercitar:

- advert e lista de contatos;
- DM com ACK;
- canal Public e canal #hashtag;
- parâmetros de rádio, localização, remoção de contato;
- adição manual de contato descoberto;
- links `meshcore://`, rota salva, estatísticas, regras de auto-adicionar, backup da chave e descoberta de rota.

`ScreenshotTest` / `ScreenshotTestEn` (Robolectric) renderizam as telas principais em português e inglês em `app/build/screenshots/`.

```bash
tools/hosttest/build.sh
./gradlew testDebugUnitTest
```

O workflow `.github/workflows/android.yml` roda os testes e gera o APK a cada push. Em tags `v*`, ele anexa o
APK à release.

## Estrutura

| Caminho | O que é |
|---|---|
| `app/src/main/cpp/meshcore/` | Núcleo MeshCore (`src/`), firmware companion (`companion_radio/`), ed25519 e Crypto (rweather), copiados do MeshCoreRPI |
| `app/src/main/cpp/android/` | Camada Android: barramentos USB do rádio via usbdevfs (`Ch341Bridge` para o MeshStick, `XiaoBridge` para o XIAO), driver SX1262, target (bateria/relógio), `AndroidSerialInterface`, JNI |
| `app/src/main/java/.../core/` | `Protocol.kt` (frames companion), `MeshController.kt` (estado, fila de comandos, mensagens), `NativeMesh.kt` (JNI) |
| `app/src/main/java/.../service/` | Serviço em primeiro plano + USB (permissão, fd, wake lock, notificações) |
| `app/src/main/java/.../ui/` | Telas Compose |
| `app/src/main/cpp/meshtastic/` | Motor Meshtastic: o firmware e suas bibliotecas em `vendor/` (baixados por `tools/meshtastic/vendor.sh` em revisões fixas, GPL-3.0), a cola Android em `android/` (rádio pelos mesmos barramentos USB, API de eventos/pedidos, JNI) |
| `app/src/main/java/.../mt/`, `core/MeshtasticController.kt` | Processo do motor Meshtastic (`MtEngineService`) e o lado do app |
| `tools/hosttest/` | Nó de teste para PC (mesmo firmware, rádio UDP) |
| `tools/meshtastic/hosttest/` | Teste do motor Meshtastic no PC (mesmas fontes e cola, rádio simulado) |

Limites de compilação (como no MeshCoreRPI): `MAX_CONTACTS=500`, `MAX_GROUP_CHANNELS=20`. Ficam em
`app/src/main/cpp/CMakeLists.txt`.

## Créditos e licença

- [MeshCore](https://github.com/meshcore-dev/MeshCore), de Scott Powell / rippleradios.com: MIT.
- [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI): port nativo para Linux, driver SX1262 e CH341.
- [Arduino Crypto](https://github.com/rweather/arduinolibs), de Rhys Weatherley: MIT.
- ed25519, de Orson Peters: zlib.
- [Firmware Meshtastic](https://github.com/meshtastic/firmware), dos desenvolvedores do Meshtastic: **GPL-3.0**, com
  [RadioLib](https://github.com/jgromes/RadioLib) (MIT), [portduino](https://github.com/meshtastic/framework-portduino)
  (GPL-3.0), [nanopb](https://github.com/nanopb/nanopb) (zlib) e as outras bibliotecas em
  `app/src/main/cpp/meshtastic/vendor/`, cada uma com seu arquivo de licença.

O código próprio deste repositório está sob a licença MIT, veja [LICENSE](LICENSE). O motor Meshtastic em
`app/src/main/cpp/meshtastic/` é GPL-3.0, por isso o **APK, que o inclui, é distribuído sob a GPL-3.0**; o código-fonte
completo é este repositório.
