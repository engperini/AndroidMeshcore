# Ideia: SX1262 interno via slot microSD (Samsung linha A)

Status: só anotada, nada implementado. Não é o caminho atual do app (USB OTG + CH341/MeshStick).

## Proposta

Soldar um Wio-SX1262 nos contatos do slot microSD de um Samsung linha A antigo, rádio fica
permanentemente dentro do chassi. Rodar com kernel genérico/root (ex.: postmarketOS) e um
programa nativo falando com o SX1262 por `/dev/spidevX.Y`.

| Wio-SX1262 | Slot microSD                 |
|------------|------------------------------|
| VCC        | pino 4 (VDD, 3,3 V)          |
| GND        | pino 6 (VSS) ou carcaça      |
| MOSI       | pino 3 (CMD)                 |
| MISO       | pino 7 (DAT0)                |
| SCK        | pino 5 (CLK)                 |
| NSS/CS     | pino 2 (DAT3/CD)             |
| BUSY       | test point do botão de volume |

- NRESET e DIO1 desconectados (reset por comando, RX por polling de `GetIrqStatus`).
- DIO2 já controla a chave de antena dentro do módulo Wio.
- Kapton no módulo e na placa; módulo deitado acima da bateria.
- Cabo coaxial fino no U.FL até antena FPC 915 MHz colada por dentro da tampa traseira.

## Pontos a validar antes de soldar (revisão)

O resumo original é otimista em alguns pontos:

1. **Os pinos do microSD não são SPI.** Estão ligados ao controlador SDHCI/MMC do SoC.
   `CONFIG_SPI_SPIDEV` sozinho não cria nada: é preciso device tree próprio desabilitando o
   controlador SD e remuxando esses pads como GPIO (pinctrl), e então `spi-gpio` (bit-bang) +
   `spidev`. Conferir no datasheet/DT do SoC (Exynos 7870/7884, etc.) se os pads aceitam função GPIO.
2. **BUSY no botão de volume não compartilha bem.** O botão puxa a linha para GND com pull-up;
   o BUSY do SX1262 é saída push-pull. Os dois brigam: com BUSY alto, apertar volume vira curto
   na saída; com BUSY baixo, o botão fica “morto”; e um clique durante uma transação faz o driver
   ler BUSY errado. O kernel não consegue “diferenciar” a origem. Melhor: outro GPIO livre
   (test point sem uso, LED, sensor ausente) ou não usar BUSY e esperar tempos fixos do datasheet
   (mais lento e frágil, mas possível). Se for usar o pino do volume, perder a função do botão.
3. **VDD do slot é chaveado** pelo driver MMC (regulador desliga sem cartão / em suspend). Com o
   controlador SD desabilitado no DT, deixar o regulador `always-on`. Checar corrente: TX a
   22 dBm puxa ~120 mA.
4. **DAT3/CD** às vezes tem pull/detecção de cartão no próprio slot; confirmar que não atrapalha o CS.
5. **No app atual**, isso seria um novo backend de rádio (spidev + GPIO no lugar de `Ch341Bridge`
   em `app/src/main/cpp/android/`), exigindo root ou rodar o firmware nativo direto no Linux
   (estilo MeshCoreRPI). App Android sem root não acessa `/dev/spidev*`.
