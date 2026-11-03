package com.engperini.meshcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.LORA_PRESETS
import com.engperini.meshcore.core.LoraConfig
import com.engperini.meshcore.core.LoraController
import com.engperini.meshcore.core.RadioMode
import com.engperini.meshcore.core.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Which protocol the radio runs. Switching keeps every mode's data. */
@Composable
fun ModeCard() {
    val mode by LoraController.mode.collectAsState()
    MeshCard {
        CardTitle(tr("Mode", "Modo"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioMode.entries.forEach { m -> MeshChip(m.label, mode == m, Modifier.weight(1f)) { LoraController.setMode(m) } }
        }
        Muted(
            when (mode) {
                RadioMode.MESHCORE -> tr(
                    "MeshCore node on the phone: chats, contacts, map and admin.",
                    "Nó MeshCore no celular: conversas, contatos, mapa e administração.",
                )
                RadioMode.LORA -> tr(
                    "Raw LoRa packets with free parameters, e.g. for your own sensors and telemetry. " +
                        "The MeshCore node pauses and keeps its identity, contacts and messages.",
                    "Pacotes LoRa crus com parâmetros livres, por exemplo para seus sensores e telemetria. " +
                        "O nó MeshCore pausa e mantém identidade, contatos e mensagens.",
                )
            },
            size = 12,
        )
    }
}

private val LORA_BANDWIDTHS_KHZ = listOf(7.81, 10.42, 15.63, 20.83, 31.25, 41.67, 62.5, 125.0, 250.0, 500.0)

private fun khzLabel(k: Double): String =
    if (k == k.toLong().toDouble()) k.toLong().toString() else "%.2f".format(Locale.US, k).trimEnd('0').trimEnd('.')

/** Generic LoRa parameters: presets, frequency, bandwidth, SF, CR, sync word, preamble and power. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoraRadioCard() {
    val c by LoraController.config.collectAsState()
    var freq by remember(c) { mutableStateOf("%.3f".format(Locale.US, c.freqKhz / 1000.0)) }
    var bwKhz by remember(c) { mutableStateOf(LORA_BANDWIDTHS_KHZ.minByOrNull { abs(it * 1000 - c.bwHz) } ?: 125.0) }
    var sf by remember(c) { mutableIntStateOf(c.sf) }
    var cr by remember(c) { mutableIntStateOf(c.cr) }
    var sync by remember(c) { mutableStateOf("%02X".format(c.syncWord)) }
    var preamble by remember(c) { mutableStateOf(c.preamble.toString()) }
    var tx by remember(c) { mutableFloatStateOf(c.txDbm.toFloat()) }
    var menu by remember { mutableStateOf(false) }

    val freqMhz = freq.replace(',', '.').toDoubleOrNull()
    val syncVal = sync.trim().removePrefix("0x").removePrefix("0X").toIntOrNull(16)
    val preVal = preamble.toIntOrNull()
    val valid = freqMhz != null && freqMhz in 137.0..1020.0 && syncVal != null && syncVal in 0..0xFF && preVal != null && preVal in 6..65535

    MeshCard {
        CardTitle(tr("Generic LoRa radio", "Rádio LoRa genérico"))
        Muted(tr("Only devices with exactly the same parameters hear each other.", "Só aparelhos com exatamente os mesmos parâmetros se ouvem."), size = 12)

        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mesh.SURFACE_2)
                    .border(1.dp, Mesh.OUTLINE, RoundedCornerShape(14.dp)).clickable { menu = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(LORA_PRESETS.firstOrNull { it.config == c }?.label ?: tr("Custom", "Personalizado"), color = Mesh.TEXT, fontWeight = FontWeight.SemiBold)
                    Muted(c.summary, size = 12)
                }
                Text("▾", color = Mesh.ACCENT, fontSize = 18.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                LORA_PRESETS.forEach { p ->
                    DropdownMenuItem(
                        text = { Column { Text(p.label); Muted(p.config.summary, size = 11) } },
                        onClick = { menu = false; LoraController.setConfig(p.config) },
                    )
                }
            }
        }

        MeshField(freq, { freq = it }, tr("Frequency (MHz)", "Frequência (MHz)"), isError = freqMhz == null)
        Muted(tr("Bandwidth (kHz)", "Largura de banda (kHz)"), size = 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LORA_BANDWIDTHS_KHZ.forEach { b -> MeshChip(khzLabel(b), bwKhz == b) { bwKhz = b } }
        }
        Muted("Spreading factor", size = 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (5..12).forEach { v -> MeshChip("SF$v", sf == v) { sf = v } }
        }
        Muted("Coding rate", size = 12)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (5..8).forEach { v -> MeshChip("4/$v", cr == v) { cr = v } } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { MeshField(sync, { sync = it }, tr("Sync word (hex)", "Sync word (hex)"), isError = syncVal == null) }
            Box(Modifier.weight(1f)) { MeshField(preamble, { preamble = it }, tr("Preamble", "Preâmbulo"), isError = preVal == null) }
        }
        Muted(tr("0x12 private, 0x34 LoRaWAN, 0x2B Meshtastic.", "0x12 privada, 0x34 LoRaWAN, 0x2B Meshtastic."), size = 12)
        Text(tr("Transmit power: ${tx.roundToInt()} dBm", "Potência de transmissão: ${tx.roundToInt()} dBm"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Slider(
            tx, { tx = it }, valueRange = -9f..22f, steps = 30,
            colors = SliderDefaults.colors(thumbColor = Mesh.ACCENT, activeTrackColor = Mesh.ACCENT, inactiveTrackColor = Mesh.SURFACE_2,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        Pill(tr("Apply", "Aplicar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, enabled = valid) {
            LoraController.setConfig(
                LoraConfig(
                    freqKhz = (freqMhz!! * 1000).roundToInt(), bwHz = (bwKhz * 1000).roundToInt(), sf = sf, cr = cr,
                    syncWord = syncVal!!, txDbm = tx.roundToInt(), preamble = preVal!!,
                ),
            )
        }
        Muted(
            tr("Transmit only on frequencies and powers that are legal where you are.", "Transmita só em frequências e potências permitidas onde você está."),
            size = 12,
        )
    }
}
