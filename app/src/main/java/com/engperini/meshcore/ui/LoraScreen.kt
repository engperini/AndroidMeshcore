package com.engperini.meshcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.LoraController
import com.engperini.meshcore.core.RawPacket
import com.engperini.meshcore.core.hexToBytes
import com.engperini.meshcore.core.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bytes as text: printable characters as they are, the rest as a middle dot. */
fun printable(data: ByteArray): String {
    val s = String(data, Charsets.UTF_8)
    return buildString { s.forEach { c -> append(if (c == '\n' || !c.isISOControl()) c else '·') } }
}

fun spacedHex(data: ByteArray): String = data.joinToString(" ") { "%02X".format(it) }

/** Generic LoRa mode: every packet heard with the configured parameters, and a box to send raw packets. */
@Composable
fun LoraScreen() {
    val packets by LoraController.packets.collectAsState()
    val config by LoraController.config.collectAsState()
    var asHex by rememberSaveable { mutableStateOf(false) }
    var sendHex by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val listState = rememberLazyListState()
    val rx = packets.filter { !it.outgoing }

    LaunchedEffect(packets.size) {
        if (packets.isNotEmpty()) listState.scrollToItem(0)   // newest, at the bottom
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), state = listState,
            // Anchored at the bottom: the keyboard pushes the latest packets up instead of covering them. Items are
            // listed bottom-up, so the newest packet comes first and the header card last (at the top).
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            items(packets.asReversed()) { p -> PacketRow(p, asHex, fmt) }
            if (packets.isEmpty()) {
                item { Empty(tr("No packets yet.\nWaiting for LoRa traffic on this channel.", "Nenhum pacote ainda.\nAguardando tráfego LoRa neste canal.")) }
            }
            item {
                MeshCard {
                    CardTitle(tr("Generic LoRa", "LoRa genérico")) {
                        if (packets.isNotEmpty()) Pill(tr("Clear", "Limpar"), style = PillStyle.TEXT) { LoraController.clear() }
                    }
                    Muted(config.summary, size = 12)
                    Muted(
                        tr(
                            "Shows every LoRa packet sent with these parameters, from any device. Change them in Settings.",
                            "Mostra todo pacote LoRa enviado com estes parâmetros, de qualquer aparelho. Altere em Configurações.",
                        ),
                        size = 12,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(tr("Received", "Recebidos"), "${rx.size}", Modifier.weight(1f))
                        StatTile(tr("Sent", "Enviados"), "${packets.count { it.outgoing && it.sent == true }}", Modifier.weight(1f))
                        val last = rx.lastOrNull()
                        StatTile(
                            tr("Last RSSI / SNR", "Último RSSI / SNR"),
                            last?.let { "%.0f / %.1f".format(Locale.US, it.rssi ?: 0f, it.snr ?: 0f) } ?: "–",
                            Modifier.weight(1.4f),
                        )
                    }
                    if (rx.size >= 2) {
                        val recent = rx.takeLast(60)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Legend("SNR (dB)", Mesh.ACCENT)
                            Legend("RSSI (dBm)", Mesh.AMBER)
                        }
                        LineChart(listOf(recent.map { it.snr ?: 0f } to Mesh.ACCENT, recent.map { it.rssi ?: 0f } to Mesh.AMBER))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeshChip(tr("Text", "Texto"), !asHex) { asHex = false }
                        MeshChip("Hex", asHex) { asHex = true }
                    }
                }
            }
        }
        // send bar
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val bytes = if (sendHex) input.filter { !it.isWhitespace() }.hexToBytes() else input.toByteArray(Charsets.UTF_8)
            val valid = bytes != null && bytes.isNotEmpty() && bytes.size <= 255
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MeshChip(tr("Text", "Texto"), !sendHex) { sendHex = false }
                MeshChip("Hex", sendHex) { sendHex = true }
                Muted(if (bytes != null) "${bytes.size}/255 B" else tr("invalid hex", "hex inválido"), size = 12)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    MeshField(input, { input = it }, if (sendHex) tr("Bytes in hex", "Bytes em hex") else tr("Packet text", "Texto do pacote"))
                }
                Pill(tr("Send", "Enviar"), style = PillStyle.PRIMARY, enabled = valid) {
                    LoraController.send(bytes!!)
                    input = ""
                }
            }
        }
    }
}

@Composable
private fun PacketRow(p: RawPacket, asHex: Boolean, fmt: SimpleDateFormat) {
    val head = buildString {
        append(fmt.format(Date(p.time)))
        append(if (p.outgoing) "  → " else "  ← ")
        append("${p.data.size} B")
        if (!p.outgoing) append("  RSSI %.0f  SNR %.1f".format(Locale.US, p.rssi ?: 0f, p.snr ?: 0f))
        if (p.outgoing) {
            append(
                when (p.sent) {
                    null -> tr("  sending…", "  enviando…")
                    true -> tr("  sent", "  enviado")
                    false -> tr("  failed", "  falhou")
                },
            )
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(if (p.outgoing) Mesh.ACCENT.copy(alpha = 0.10f) else Mesh.SURFACE.copy(alpha = 0.8f)).padding(10.dp),
    ) {
        Text(
            head, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            color = when {
                p.outgoing && p.sent == false -> Mesh.RED
                p.outgoing -> Mesh.ACCENT
                else -> Mesh.MUTED
            },
        )
        Text(if (asHex) spacedHex(p.data) else printable(p.data), color = Mesh.TEXT, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
