# Hardware

**English** · [Português](#português)

Two ways to give the phone a LoRa radio over USB, both running the MeshCore node **on the phone**:

| | USB LoRa stick (this folder) | XIAO kit |
|---|---|---|
| Inside | CH341F + Waveshare Core1262-HF | Seeed XIAO nRF52840 + Wio-SX1262 |
| Microcontroller | **none** | nRF52840, as a USB-SPI bridge |
| Firmware to flash | **none** | [xiao-sx1262-usb-bridge](https://github.com/engperini/xiao-sx1262-usb-bridge) |
| Works with | this app | this app (bridge firmware) or the official MeshCore app (MeshCore firmware) |
| Status | **v0.1 design, not built yet** | tested |

Both use the same case design: a straight USB-C male-male adapter inside a small 3D-printed box.

## USB LoRa stick (`usb-stick/`)

Same wiring as the USB MeshStick, so the app's CH341 driver works unchanged:

| CH341F | SX1262 module |
|---|---|
| D0 | NSS |
| D2 | RESET |
| D4 | BUSY |
| D6 | DIO1 |
| D3 / D5 / D7 | SCK / MOSI / MISO |
| (DIO2) | TXEN = DIO2, RXEN = not DIO2 (74LVC1G04) |

- The CH341F runs on its **internal clock** (no crystal) and starts in SPI mode, `1a86:5512`,
  because SDA is tied low.
- 3.3 V from an ME6211 LDO (500 mA). The module's TCXO is powered from DIO3, as the app expects.
- Green LED: on when the phone has configured the stick.
- Board: 29 × 22 mm, 2 layers, 1.6 mm. Everything is on top except the radio module, which is
  **hand-soldered on the back** (castellated pads). The antenna plugs into the module's own IPEX.

### Ordering at JLCPCB

1. **PCB:** upload `usb-stick/fab/usb-stick-gerbers.zip` (2 layers, 1.6 mm, any colour).
2. **Assembly (top side):** upload `usb-stick-bom.csv` and `usb-stick-cpl.csv`. In the placement preview,
   check the rotation of U1 (pin 1 dot), U2, U3, D1 and J1, and fix any that JLCPCB shows turned.
3. **By hand:** solder the Waveshare **Core1262-HF** (850–930 MHz) on the back, with its IPEX towards the far
   end (the silkscreen shows the pin names and the IPEX side). Check the orientation against the module's
   own pinout photo before soldering.
4. Connect the antenna, plug the stick into the phone through the straight USB-C male-male adapter, open the
   app.

### Rebuilding

The board is generated from code: `gen_board.py` (parts and netlist), `route.py` (a small two-layer
negotiated router), `drc.py` (KiCad DRC) and `fab.py` (JLCPCB files). `build.sh` runs them all. You can also
open `usb-stick.kicad_pcb` in KiCad 7+ and edit it by hand.

### Known limits of v0.1

- It uses a **female** USB-C socket plus the adapter, because JLCPCB's parts library has no board-mount
  male plug. A male plug would make it shorter (v0.2).
- The Core1262-HF (22 × 19 mm) sets the size. A smaller SX1262 module would shrink it further.
- The orientation of the module's pins was worked out from Waveshare's photos: double-check it.
- Not built or tested yet. Treat v0.1 as a prototype.

## Cases (`cases/`)

OpenSCAD sources with the measurements at the top, and ready STL files:

| File | For |
|---|---|
| `case_stick_base.stl`, `case_stick_lid.stl` | the USB LoRa stick |
| `case_xiao_base.stl`, `case_xiao_lid.stl` | the XIAO nRF52840 + Wio-SX1262 kit |

- Import the STL files in Bambu Studio (or any slicer). PLA or PETG, 0.2 mm layers, no supports: print the
  base open side up and the lid flat side down.
- **Measure your adapter and boards first.** Straight USB-C adapters vary; the defaults are typical sizes.
  Change the numbers at the top of `case_*.scad`, then export again in OpenSCAD (F6, then *Export as STL*), or
  run `openscad -o case_stick_base.stl -D 'part="base"' case_stick.scad`.
- The adapter sits behind a thin front lip, so it does not come out when you unplug from the phone. The lid
  is a press fit (`fit` in `case_lib.scad`). The rear slot is for the antenna cable.

---

## Português

Duas formas de dar ao celular um rádio LoRa pela USB, as duas com o nó MeshCore rodando **no celular**:

| | Pendrive LoRa (esta pasta) | Kit XIAO |
|---|---|---|
| Dentro | CH341F + Waveshare Core1262-HF | Seeed XIAO nRF52840 + Wio-SX1262 |
| Microcontrolador | **nenhum** | nRF52840, como ponte USB-SPI |
| Firmware para gravar | **nenhum** | [xiao-sx1262-usb-bridge](https://github.com/engperini/xiao-sx1262-usb-bridge) |
| Funciona com | este app | este app (firmware da ponte) ou o app oficial do MeshCore (firmware MeshCore) |
| Situação | **projeto v0.1, ainda não fabricado** | testado |

As duas usam a mesma ideia de caixinha: um adaptador USB-C macho-macho reto dentro de uma caixa impressa em 3D.

## Pendrive LoRa (`usb-stick/`)

Mesma ligação do USB MeshStick, então o driver do CH341 no app funciona sem mudança:

| CH341F | Módulo SX1262 |
|---|---|
| D0 | NSS |
| D2 | RESET |
| D4 | BUSY |
| D6 | DIO1 |
| D3 / D5 / D7 | SCK / MOSI / MISO |
| (DIO2) | TXEN = DIO2, RXEN = inverso do DIO2 (74LVC1G04) |

- O CH341F usa o **clock interno** (sem cristal) e liga no modo SPI, `1a86:5512`, porque o SDA está no GND.
- 3,3 V de um regulador ME6211 (500 mA). O TCXO do módulo é alimentado pelo DIO3, como o app espera.
- LED verde: acende quando o celular reconhece o pendrive.
- Placa: 29 × 22 mm, 2 camadas, 1,6 mm. Tudo fica em cima, menos o módulo de rádio, que é **soldado à mão
  embaixo** (pads castelados). A antena encaixa no IPEX do próprio módulo.

### Como encomendar na JLCPCB

1. **Placa:** envie `usb-stick/fab/usb-stick-gerbers.zip` (2 camadas, 1,6 mm, qualquer cor).
2. **Montagem (lado de cima):** envie `usb-stick-bom.csv` e `usb-stick-cpl.csv`. Na prévia da montagem,
   confira a rotação de U1 (bolinha do pino 1), U2, U3, D1 e J1 e corrija o que aparecer girado.
3. **À mão:** solde o Waveshare **Core1262-HF** (850–930 MHz) embaixo, com o IPEX virado para a ponta oposta
   ao USB (a serigrafia mostra os nomes dos pinos e o lado do IPEX). Confira a orientação com a foto da
   pinagem do módulo antes de soldar.
4. Ligue a antena, conecte o pendrive ao celular pelo adaptador USB-C macho-macho reto e abra o app.

### Como refazer

A placa é gerada por código: `gen_board.py` (componentes e ligações), `route.py` (um roteador negociado de
duas camadas), `drc.py` (DRC do KiCad) e `fab.py` (arquivos da JLCPCB). O `build.sh` roda tudo. Também dá
para abrir o `usb-stick.kicad_pcb` no KiCad 7+ e editar à mão.

### Limites conhecidos da v0.1

- Usa conector USB-C **fêmea** mais o adaptador, porque a biblioteca de peças da JLCPCB não tem plugue macho
  de placa. Um plugue macho deixaria mais curto (v0.2).
- O Core1262-HF (22 × 19 mm) define o tamanho. Um módulo SX1262 menor diminuiria mais.
- A orientação dos pinos do módulo foi deduzida das fotos da Waveshare: confira antes.
- Ainda não foi fabricado nem testado. Trate a v0.1 como protótipo.

## Caixinhas (`cases/`)

Fontes em OpenSCAD com as medidas no topo, e arquivos STL prontos:

| Arquivo | Para |
|---|---|
| `case_stick_base.stl`, `case_stick_lid.stl` | o pendrive LoRa |
| `case_xiao_base.stl`, `case_xiao_lid.stl` | o kit XIAO nRF52840 + Wio-SX1262 |

- Importe os STL no Bambu Studio (ou outro fatiador). PLA ou PETG, camadas de 0,2 mm, sem suporte: a base
  com a abertura para cima e a tampa com o lado liso para baixo.
- **Meça antes o seu adaptador e as placas.** Os adaptadores USB-C retos variam; os valores padrão são
  típicos. Ajuste os números no topo do `case_*.scad` e exporte de novo no OpenSCAD (F6, depois *Export as
  STL*), ou rode `openscad -o case_stick_base.stl -D 'part="base"' case_stick.scad`.
- O adaptador fica atrás de um lábio fino na frente, para não sair quando você puxa do celular. A tampa
  encaixa por pressão (`fit` em `case_lib.scad`). O rasgo de trás é para o cabo da antena.
