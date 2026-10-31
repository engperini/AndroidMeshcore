# MeshCore USB (AndroidMeshcore)

Rode um nó **[MeshCore](https://github.com/meshcore-dev/MeshCore) completo dentro do celular Android**.
Você pluga um **USB MeshStick** (CH341 + SX1262) no celular com um cabo **OTG** e já sai usando:
canais em grupo, mensagens diretas, contatos e configurações de rádio, tudo no mesmo app.
Não precisa gravar firmware, nem de Bluetooth, nem de um segundo app.

> [!WARNING]
> Use por sua conta e risco. Você é responsável por transmitir só em frequências e potências
> permitidas onde você está.

Este projeto é o port para Android do [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI). Ele usa o mesmo
firmware companion do MeshCore (`MyMesh`) e o mesmo driver SX1262 + CH341, compilados com o NDK. O app
fala com eles pelo protocolo companion oficial.

## Como funciona

```
┌──────────── celular Android ─────────────┐
│  UI (Compose): conversas, contatos, canais, config
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

## Funcionalidades

- 💬 **Canais em grupo**: o *Public* já vem configurado. Você pode criar **#hashtag** (todos que usam o mesmo
  `#nome` entram na mesma conversa), canal **privado novo** (chave aleatória) ou entrar num canal **com chave**
  (hex ou base64). Também dá para ver e copiar a chave para compartilhar.
- ✉️ **Mensagens diretas**, com status *enviando → enviado → entregue (✓✓)* pela confirmação (ACK) do
  destinatário, mais o tempo de ida e volta. Toque numa mensagem sem confirmação para reenviar.
- 👥 **Contatos** descobertos por advert, com busca, filtro (chat/repetidor/sala), saltos e "visto há". Toque e
  segure um contato para **redefinir rota**, **compartilhar** (zero-hop) ou **remover**. Também tem o modo
  "adicionar só manualmente", com a lista de nós descobertos e o botão **Adicionar**.
- 📡 **Rádio**: presets (Brasil 923.125/62.5/SF8/CR8, EU/UK, EUA) ou ajuste manual de frequência, BW, SF, CR e
  potência TX; **repetir pacotes** (o companion também funciona como repetidor); tamanho do hash de caminho
  (1/2/3 bytes; a MeshCore Brasil usa 2).
- 📢 **Advert** manual (flood ou zero-hop) e **advert automático** (30 min a 12 h).
- 📍 **Localização** no advert, digitada ou pelo GPS do celular.
- 🩺 **Diagnóstico do rádio**, o mesmo `radio-diag` do MeshCoreRPI: status do SX1262, RSSI/SNR, piso de ruído e
  contadores. Mais um **log de pacotes recebidos** (tipo, rota, SNR, RSSI).
- 🔔 Notificações de mensagens novas. O app abre sozinho quando você pluga o MeshStick.

## Hardware

| Rádio | Conexão | Status |
|---|---|---|
| USB MeshStick (CH341A `1a86:5512` + SX1262), por exemplo o Elecrow MeshStick | USB OTG | ✅ suportado |

Pinos, TCXO (DIO3) e chave de RF (DIO2) já vêm configurados para o MeshStick (CH341 D0/D2/D4/D6), como no
MeshCoreRPI.

Você precisa de:

- Celular Android 8.0 ou mais novo com **USB Host/OTG** (quase todos os atuais).
- Adaptador ou cabo **OTG** (USB-C → USB-A fêmea, por exemplo).
- **Antena** conectada ao MeshStick **antes** de ligar.

## Instalação

1. Baixe o `MeshCoreUSB.apk`, pela aba **Releases** ou pelo artefato da última execução em **Actions**.
2. Instale o APK. O Android vai pedir para permitir instalação de fontes desconhecidas.
3. Coloque a antena, conecte o MeshStick ao celular pelo OTG e aceite **abrir o MeshCore USB** e
   **permitir o acesso ao dispositivo USB**. Marque "usar sempre" para não perguntar de novo.
4. Na aba **Config**, confira os parâmetros de rádio da sua região (por padrão: Brasil 923.125 MHz, 62.5 kHz,
   SF8, CR8) e dê um nome ao seu nó.
5. Menu ⋮ → **Enviar advert (flood)** para ser descoberto. Depois é só abrir o canal *Public* e dar um oi 👋

> [!TIP]
> Alguns celulares desligam o OTG depois de um tempo sem uso, ou economizam bateria de forma agressiva. Se o
> rádio parar com a tela apagada, desative a otimização de bateria para o MeshCore USB.

## Compilar

Requisitos: JDK 17, Android SDK (platform 35), NDK `27.2.12479018` e CMake `3.22.1`.

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # APK com R8, assinado com a chave de debug
```

### Testes (firmware real, dois nós)

`tools/hosttest` compila um **nó de teste para PC** a partir das mesmas fontes do `libmeshcore.so`, com um
"rádio" UDP no localhost. O teste `TwoNodeMeshTest` sobe dois nós e usa o codificador/decodificador de
protocolo do app (`Protocol.kt`) para exercitar:

- advert e lista de contatos;
- DM com ACK;
- canal Public e canal #hashtag;
- parâmetros de rádio, localização, remoção de contato;
- adição manual de contato descoberto.

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
| `app/src/main/cpp/android/` | Camada Android: `Ch341Bridge` via usbdevfs, driver SX1262, target (bateria/relógio), `AndroidSerialInterface`, JNI |
| `app/src/main/java/.../core/` | `Protocol.kt` (frames companion), `MeshController.kt` (estado, fila de comandos, mensagens), `NativeMesh.kt` (JNI) |
| `app/src/main/java/.../service/` | Serviço em primeiro plano + USB (permissão, fd, wake lock, notificações) |
| `app/src/main/java/.../ui/` | Telas Compose |
| `tools/hosttest/` | Nó de teste para PC (mesmo firmware, rádio UDP) |

Limites de compilação (como no MeshCoreRPI): `MAX_CONTACTS=500`, `MAX_GROUP_CHANNELS=20`. Ficam em
`app/src/main/cpp/CMakeLists.txt`.

## Créditos e licença

- [MeshCore](https://github.com/meshcore-dev/MeshCore), de Scott Powell / rippleradios.com: MIT.
- [MeshCoreRPI](https://github.com/engperini/MeshCoreRPI): port nativo para Linux, driver SX1262 e CH341.
- [Arduino Crypto](https://github.com/rweather/arduinolibs), de Rhys Weatherley: MIT.
- ed25519, de Orson Peters: zlib.

Licença MIT, veja [LICENSE](LICENSE).
