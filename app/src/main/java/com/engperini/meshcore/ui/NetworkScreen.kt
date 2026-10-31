package com.engperini.meshcore.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.MeshController
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Live node statistics, signal charts, radio diagnostics and the received-packet log (like the Pi dashboard). */
@Composable
fun NetworkScreen() {
    val stats by MeshController.stats.collectAsState()
    val log by MeshController.packetLog.collectAsState()
    val diag by MeshController.diag.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) {
        while (true) {
            MeshController.refreshStats()
            MeshController.refreshDiag()
            delay(5000)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Recebidos", "${stats.recv}", Modifier.weight(1f), sub = "flood ${stats.recvFlood} • dir ${stats.recvDirect}")
                StatTile("Enviados", "${stats.sent}", Modifier.weight(1f), sub = "flood ${stats.sentFlood} • dir ${stats.sentDirect}")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val snrColor = when {
                    stats.lastSnr >= 0 -> Mesh.GREEN
                    stats.lastSnr >= -10 -> Mesh.AMBER
                    else -> Mesh.RED
                }
                StatTile("Último SNR", "%.1f dB".format(Locale.US, stats.lastSnr), Modifier.weight(1f), snrColor)
                StatTile("Último RSSI", "${stats.lastRssi} dBm", Modifier.weight(1f))
                StatTile("Ruído", "${stats.noiseFloor} dBm", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Uptime", uptime(stats.uptimeSecs), Modifier.weight(1f))
                StatTile("No ar TX / RX", "${stats.txAirSecs}s / ${stats.rxAirSecs}s", Modifier.weight(1f))
                StatTile("Erros RX", "${stats.recvErrors}", Modifier.weight(1f), if (stats.recvErrors > 0) Mesh.AMBER else Mesh.TEXT)
            }
        }
        item {
            MeshCard {
                CardTitle("Sinal dos pacotes recebidos")
                val recent = log.take(60).reversed()
                if (recent.size < 2) {
                    Muted("O gráfico aparece quando chegarem pacotes.", size = 12)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Legend("SNR (dB)", Mesh.ACCENT)
                        Legend("RSSI (dBm)", Mesh.AMBER)
                    }
                    LineChart(listOf(recent.map { it.snr } to Mesh.ACCENT, recent.map { it.rssi.toFloat() } to Mesh.AMBER))
                }
            }
        }
        item {
            MeshCard {
                CardTitle("Tipos de pacote")
                val counts = log.groupingBy { it.type }.eachCount().entries.sortedByDescending { it.value }
                if (counts.isEmpty()) Muted("Nenhum pacote ainda.", size = 12)
                val max = counts.maxOfOrNull { it.value } ?: 1
                counts.forEach { (type, n) -> Bar(type, n, max) }
                val routes = log.groupingBy { it.route }.eachCount()
                if (routes.isNotEmpty()) Muted(routes.entries.joinToString("  •  ") { "${it.key} ${it.value}" }, size = 12)
            }
        }
        item { DiagCard(diag) }
        item {
            MeshCard {
                CardTitle("Pacotes recebidos (${log.size})")
                if (log.isEmpty()) Muted("Nenhum pacote ainda.", size = 12)
                log.take(50).forEach { p ->
                    Text(
                        "${fmt.format(Date(p.time))} ${p.type.padEnd(9)} ${p.route.padEnd(8)} SNR %5.1f RSSI %4d %3dB".format(Locale.US, p.snr, p.rssi, p.len),
                        color = Mesh.TEXT, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagCard(diag: String) {
    MeshCard {
        CardTitle("Diagnóstico do rádio")
        val json = runCatching { JSONObject(diag) }.getOrNull()
        if (json == null || json.length() == 0) {
            Muted("Sem dados ainda.", size = 12)
            return@MeshCard
        }
        val labels = linkedMapOf(
            "driver" to "Driver", "status" to "Status SX1262", "freq" to "Frequência (Hz)", "bw" to "Banda (Hz)",
            "sf" to "SF", "cr" to "CR", "tx" to "TX (dBm)", "noise_floor" to "Piso de ruído", "dev_errors_hex" to "Erros do chip",
            "tcxo" to "TCXO (DIO3)", "dio2_rf" to "Chave RF (DIO2)",
        )
        labels.forEach { (k, l) -> if (json.has(k)) KeyValue(l, json.get(k).toString()) }
    }
}

@Composable
private fun Legend(text: String, color: Color) {
    Row {
        Box(Modifier.padding(top = 5.dp).width(12.dp).height(3.dp).background(color))
        Text(" $text", color = Mesh.MUTED, fontSize = 11.sp)
    }
}

@Composable
private fun Bar(label: String, n: Int, max: Int) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(90.dp), color = Mesh.MUTED, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Box(Modifier.weight(1f).padding(top = 3.dp)) {
            Box(
                Modifier.fillMaxWidth(n.toFloat() / max).height(12.dp).clip(RoundedCornerShape(4.dp))
                    .background(Mesh.ACCENT.copy(alpha = 0.7f)),
            )
        }
        Text(" $n", color = Mesh.TEXT, fontSize = 12.sp)
    }
}

/** Small multi-series line chart, each series scaled to its own range. */
@Composable
private fun LineChart(series: List<Pair<List<Float>, Color>>) {
    Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(12.dp)).background(Mesh.BG.copy(alpha = 0.6f))) {
        val pad = 8.dp.toPx()
        for (g in 1..3) {
            val y = pad + (size.height - 2 * pad) * g / 4f
            drawLine(Mesh.OUTLINE, Offset(pad, y), Offset(size.width - pad, y), 1f)
        }
        series.forEach { (values, color) ->
            if (values.size < 2) return@forEach
            val lo = values.min()
            val hi = values.max().let { if (it - lo < 1f) lo + 1f else it }
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = pad + (size.width - 2 * pad) * i / (values.size - 1)
                val y = size.height - pad - (size.height - 2 * pad) * (v - lo) / (hi - lo)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
