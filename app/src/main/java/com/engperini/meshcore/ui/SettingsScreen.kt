package com.engperini.meshcore.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.toHex
import com.engperini.meshcore.service.MeshService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RadioPreset(val label: String, val freqMhz: String, val bwKhz: String, val sf: Int, val cr: Int)

// Confirm the parameters with your local community: every node must match exactly.
val PRESETS = listOf(
    RadioPreset("Brasil (MeshCore Brasil)", "923.125", "62.5", 8, 8),
    RadioPreset("EU/UK (narrow)", "869.618", "62.5", 8, 8),
    RadioPreset("EU 869.525 SF11", "869.525", "250", 11, 5),
    RadioPreset("EUA/Canadá", "910.525", "62.5", 7, 5),
)

val BANDWIDTHS = listOf("7.8", "10.4", "15.6", "20.8", "31.25", "41.7", "62.5", "125", "250", "500")

@Composable
fun SettingsScreen() {
    val link by MeshController.link.collectAsState()
    val running = link.state == LinkState.RUNNING
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (running) {
            IdentityCard()
            RadioCard()
            AdvertCard()
            LocationCard()
            DiagnosticsCard()
            PacketLogCard()
        } else {
            Section("Rádio") { Text("Conecte o MeshStick para ver e alterar as configurações do nó.") }
        }
        ServiceCard(running)
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun IdentityCard() {
    val self by MeshController.self.collectAsState()
    val device by MeshController.device.collectAsState()
    val clipboard = LocalClipboardManager.current
    var name by remember(self?.name) { mutableStateOf(self?.name ?: "") }

    Section("Identidade") {
        OutlinedTextField(
            value = name, onValueChange = { if (it.toByteArray().size <= 31) name = it },
            label = { Text("Nome do nó") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(enabled = name.isNotBlank() && name != self?.name, onClick = { MeshController.setName(name) }) { Text("Salvar nome") }
        self?.let { s ->
            Text("Chave pública", style = MaterialTheme.typography.labelMedium)
            Text(s.pubKey.toHex(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(s.pubKey.toHex())) }) { Text("Copiar chave") }
        }
        SwitchRow("Adicionar contatos só manualmente", self?.manualAddContacts == true) { MeshController.setManualAdd(it) }
        device?.let { Text("Firmware ${it.version} (${it.buildDate}) · ${it.maxContacts} contatos · ${it.maxChannels} canais", style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RadioCard() {
    val self by MeshController.self.collectAsState()
    val device by MeshController.device.collectAsState()
    val s = self ?: return
    var freq by remember(s.freqKhz) { mutableStateOf("%.3f".format(Locale.US, s.freqKhz / 1000.0)) }
    var bw by remember(s.bwHz) {
        mutableStateOf(BANDWIDTHS.firstOrNull { kotlin.math.abs(it.toDouble() * 1000 - s.bwHz) < 60 } ?: "%.1f".format(Locale.US, s.bwHz / 1000.0))
    }
    var sf by remember(s.sf) { mutableIntStateOf(s.sf) }
    var cr by remember(s.cr) { mutableIntStateOf(s.cr) }
    var tx by remember(s.txPower) { mutableStateOf(s.txPower.toString()) }
    var repeat by remember(device?.clientRepeat) { mutableStateOf(device?.clientRepeat == true) }

    Section("Rádio LoRa") {
        Text("Todos os nós precisam usar exatamente os mesmos parâmetros.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PRESETS.forEach { p ->
                FilterChip(
                    selected = freq == p.freqMhz && bw == p.bwKhz && sf == p.sf && cr == p.cr,
                    onClick = { freq = p.freqMhz; bw = p.bwKhz; sf = p.sf; cr = p.cr },
                    label = { Text(p.label) },
                )
            }
        }
        OutlinedTextField(value = freq, onValueChange = { freq = it }, label = { Text("Frequência (MHz)") }, singleLine = true)
        Text("Largura de banda (kHz)", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BANDWIDTHS.forEach { b -> FilterChip(selected = bw == b, onClick = { bw = b }, label = { Text(b) }) }
        }
        Text("Spreading factor", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (5..12).forEach { v -> FilterChip(selected = sf == v, onClick = { sf = v }, label = { Text("SF$v") }) }
        }
        Text("Coding rate", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (5..8).forEach { v -> FilterChip(selected = cr == v, onClick = { cr = v }, label = { Text("4/$v") }) }
        }
        OutlinedTextField(
            value = tx, onValueChange = { tx = it }, singleLine = true,
            label = { Text("Potência TX (dBm, máx ${s.maxTxPower})") },
        )
        SwitchRow("Repetir pacotes (modo repetidor do companion)", repeat) { repeat = it }
        val freqKhz = freq.replace(',', '.').toDoubleOrNull()?.let { (it * 1000).toInt() }
        val bwHz = bw.toDoubleOrNull()?.let { (it * 1000).toInt() }
        val txDbm = tx.toIntOrNull()
        Button(
            enabled = freqKhz != null && bwHz != null && txDbm != null,
            onClick = { MeshController.setRadio(freqKhz!!, bwHz!!, sf, cr, txDbm!!, repeat) },
        ) { Text("Aplicar parâmetros") }

        Text("Tamanho do hash de caminho", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (0..2).forEach { m ->
                FilterChip(
                    selected = device?.pathHashMode == m,
                    onClick = { MeshController.setPathHashMode(m) },
                    label = { Text("${m + 1} byte${if (m > 0) "s" else ""}") },
                )
            }
        }
        Text("MeshCore Brasil usa 2 bytes.", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdvertCard() {
    var minutes by remember { mutableIntStateOf(MeshController.autoAdvertMinutes) }
    var flood by remember { mutableStateOf(MeshController.autoAdvertFlood) }
    Section("Advert automático") {
        Text("Anuncia este nó periodicamente para que outros o encontrem.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Desligado", 30 to "30 min", 60 to "1 h", 180 to "3 h", 360 to "6 h", 720 to "12 h").forEach { (m, l) ->
                FilterChip(selected = minutes == m, onClick = { minutes = m; MeshController.setAutoAdvert(m, flood) }, label = { Text(l) })
            }
        }
        SwitchRow("Flood (alcança toda a malha)", flood) { flood = it; MeshController.setAutoAdvert(minutes, it) }
    }
}

@Composable
fun LocationCard() {
    val self by MeshController.self.collectAsState()
    val context = LocalContext.current
    val s = self ?: return
    var lat by remember(s.lat) { mutableStateOf(if (s.lat != 0.0) "%.6f".format(Locale.US, s.lat) else "") }
    var lon by remember(s.lon) { mutableStateOf(if (s.lon != 0.0) "%.6f".format(Locale.US, s.lon) else "") }
    var share by remember(s.advertLocPolicy) { mutableStateOf(s.advertLocPolicy != 0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) lastLocation(context)?.let { (a, b) -> lat = "%.6f".format(Locale.US, a); lon = "%.6f".format(Locale.US, b) }
    }

    Section("Localização") {
        SwitchRow("Incluir localização no advert", share) { share = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Text("Usar GPS do celular") }
            Button(onClick = {
                MeshController.setLocation(lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0, share)
            }) { Text("Salvar") }
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    } catch (_: SecurityException) {
        null
    }
}

@Composable
fun DiagnosticsCard() {
    val diag by MeshController.diag.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        while (true) {
            MeshController.refreshDiag()
            delay(5000)
        }
    }
    Section("Diagnóstico do rádio") {
        val json = runCatching { JSONObject(diag) }.getOrNull()
        if (json == null || json.length() == 0) {
            Text("Sem dados ainda.")
        } else {
            val labels = linkedMapOf(
                "driver" to "Driver", "status" to "Status SX1262", "freq" to "Frequência (Hz)", "bw" to "Banda (Hz)",
                "sf" to "SF", "cr" to "CR", "tx" to "TX (dBm)", "last_rssi" to "Último RSSI", "last_snr" to "Último SNR",
                "noise_floor" to "Piso de ruído", "packets_recv" to "Pacotes recebidos", "packets_sent" to "Pacotes enviados",
                "recv_errors" to "Erros de RX", "dev_errors_hex" to "Erros do chip",
            )
            labels.forEach { (k, l) ->
                if (json.has(k)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text(l, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(json.get(k).toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        OutlinedButton(onClick = { scope.launch { MeshController.refreshDiag() } }) { Text("Atualizar") }
    }
}

@Composable
fun PacketLogCard() {
    val log by MeshController.packetLog.collectAsState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Section("Pacotes recebidos (${log.size})") {
        if (log.isEmpty()) Text("Nenhum pacote ainda.", style = MaterialTheme.typography.bodySmall)
        log.take(40).forEach { p ->
            Text(
                "${fmt.format(Date(p.time))}  ${p.type.padEnd(9)} ${p.route.padEnd(8)} SNR %5.1f  RSSI %4d  %3dB".format(p.snr, p.rssi, p.len),
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun ServiceCard(running: Boolean) {
    val context = LocalContext.current
    Section("Serviço") {
        Text(
            "O rádio continua funcionando com a tela apagada enquanto o MeshStick estiver conectado.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (running) {
            OutlinedButton(onClick = {
                context.startService(Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP))
            }) { Text("Parar rádio") }
        }
    }
}
