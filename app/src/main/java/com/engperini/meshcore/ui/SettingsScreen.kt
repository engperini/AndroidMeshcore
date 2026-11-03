package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.BuildConfig
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.LoraController
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.core.RadioMode
import com.engperini.meshcore.core.hexToBytes
import com.engperini.meshcore.core.toHex
import com.engperini.meshcore.service.MeshService
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Region presets: pick the region, then the path hash size. Confirm with your local community. */
data class Region(val en: String, val pt: String, val freqMhz: Double, val bwKhz: Double, val sf: Int, val cr: Int, val hashBytes: Int = 1) {
    val label: String get() = tr(en, pt)
}

val REGIONS = listOf(
    Region("Brazil (MeshCore Brasil)", "Brasil (MeshCore Brasil)", 923.125, 62.5, 8, 8, hashBytes = 2),
    Region("USA / Canada (recommended)", "EUA / Canadá (recomendado)", 910.525, 62.5, 7, 5),
    Region("EU / UK (narrow)", "EU / UK (narrow)", 869.618, 62.5, 8, 8),
    Region("EU / UK (long range)", "EU / UK (longo alcance)", 869.525, 250.0, 11, 5),
    Region("Australia", "Austrália", 915.8, 250.0, 10, 5),
    Region("New Zealand", "Nova Zelândia", 917.375, 250.0, 11, 5),
)

val BANDWIDTHS = listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)

fun regionOf(freqKhz: Int, bwHz: Int, sf: Int, cr: Int): Region? = REGIONS.firstOrNull {
    abs(it.freqMhz * 1000 - freqKhz) < 2 && abs(it.bwKhz * 1000 - bwHz) < 60 && it.sf == sf && it.cr == cr
}

@Composable
fun SettingsScreen(onConnect: () -> Unit) {
    val link by MeshController.link.collectAsState()
    val mode by LoraController.mode.collectAsState()
    val running = link.state == LinkState.RUNNING
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ModeCard() }
        if (mode == RadioMode.LORA) {
            // Generic LoRa: parameters can be set before the radio is connected.
            item { LoraRadioCard() }
        } else if (mode == RadioMode.MESHTASTIC && running) {
            item { MtOwnerCard() }
            item { MtRadioCard() }
            item { MtChannelsCard() }
        } else if (running && mode == RadioMode.MESHCORE) {
            item { RadioCard() }
            item { IdentityCard() }
            item { AdvertCard() }
            item { ContactsPolicyCard() }
            item { LocationCard() }
            item { BackupCard() }
        } else {
            item {
                MeshCard {
                    CardTitle(tr("Radio", "Rádio"))
                    Muted(tr("Connect the USB radio to see and change the node settings.", "Conecte o rádio USB para ver e alterar as configurações do nó."))
                    Pill(tr("Connect", "Conectar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, onClick = onConnect)
                }
            }
        }
        item { LanguageCard() }
        item { ServiceCard(running) }
    }
}

@Composable
private fun RadioCard() {
    val self by MeshController.self.collectAsState()
    val device by MeshController.device.collectAsState()
    val s = self ?: return
    val current = regionOf(s.freqKhz, s.bwHz, s.sf, s.cr)

    var region by remember(s.freqKhz, s.bwHz, s.sf, s.cr) { mutableStateOf(current) }
    var hashBytes by remember(device?.pathHashMode) { mutableIntStateOf((device?.pathHashMode ?: 0) + 1) }
    var tx by remember(s.txPower) { mutableFloatStateOf(s.txPower.toFloat()) }
    var advanced by remember { mutableStateOf(current == null) }
    var menu by remember { mutableStateOf(false) }
    // Advanced (manual) values.
    var freq by remember(s.freqKhz) { mutableStateOf("%.3f".format(Locale.US, s.freqKhz / 1000.0)) }
    var bw by remember(s.bwHz) { mutableStateOf(BANDWIDTHS.minByOrNull { abs(it * 1000 - s.bwHz) } ?: 62.5) }
    var sf by remember(s.sf) { mutableIntStateOf(s.sf) }
    var cr by remember(s.cr) { mutableIntStateOf(s.cr) }
    var repeat by remember(device?.clientRepeat) { mutableStateOf(device?.clientRepeat == true) }

    MeshCard {
        CardTitle(tr("LoRa radio", "Rádio LoRa"))
        Muted(tr("Every node in the mesh must use exactly the same parameters.", "Todos os nós da malha precisam usar exatamente os mesmos parâmetros."), size = 12)

        Text(tr("1. Region", "1. Região"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mesh.SURFACE_2)
                    .border(1.dp, Mesh.OUTLINE, RoundedCornerShape(14.dp)).clickable { menu = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(region?.label ?: tr("Custom", "Personalizado"), color = Mesh.TEXT, fontWeight = FontWeight.SemiBold)
                    val r = region
                    Muted(
                        if (r != null) "%.3f MHz • %s kHz • SF%d • CR%d".format(Locale.US, r.freqMhz, trimNum(r.bwKhz), r.sf, r.cr)
                        else "%.3f MHz • %s kHz • SF%d • CR%d".format(Locale.US, s.freqKhz / 1000.0, trimNum(s.bwHz / 1000.0), s.sf, s.cr),
                        size = 12,
                    )
                }
                Text("▾", color = Mesh.ACCENT, fontSize = 18.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                REGIONS.forEach { r ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(r.label)
                                Muted("%.3f MHz • %s kHz • SF%d".format(Locale.US, r.freqMhz, trimNum(r.bwKhz), r.sf), size = 11)
                            }
                        },
                        onClick = { menu = false; region = r; hashBytes = r.hashBytes; advanced = false },
                    )
                }
                DropdownMenuItem(text = { Text(tr("Custom…", "Personalizado…")) }, onClick = { menu = false; region = null; advanced = true })
            }
        }

        Text(tr("2. Path bytes (hash per hop)", "2. Bytes do caminho (hash por salto)"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..3).forEach { b -> MeshChip(bytesLabel(b), hashBytes == b, Modifier.weight(1f)) { hashBytes = b } }
        }
        Muted(tr("Use the same as your community. MeshCore Brasil uses 2 bytes.", "Use o mesmo da sua comunidade. A MeshCore Brasil usa 2 bytes."), size = 12)

        Text(tr("3. Transmit power: ${tx.roundToInt()} dBm", "3. Potência de transmissão: ${tx.roundToInt()} dBm"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Slider(
            tx, { tx = it }, valueRange = -9f..s.maxTxPower.toFloat(), steps = s.maxTxPower + 8,
            colors = SliderDefaults.colors(thumbColor = Mesh.ACCENT, activeTrackColor = Mesh.ACCENT, inactiveTrackColor = Mesh.SURFACE_2,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )

        if (advanced) {
            Text(tr("Advanced", "Avançado"), color = Mesh.ACCENT, fontWeight = FontWeight.Medium)
            MeshField(freq, { freq = it }, tr("Frequency (MHz)", "Frequência (MHz)"))
            Muted(tr("Bandwidth (kHz)", "Largura de banda (kHz)"), size = 12)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BANDWIDTHS.forEach { b -> MeshChip(trimNum(b), bw == b) { bw = b } }
            }
            Muted("Spreading factor", size = 12)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (5..12).forEach { v -> MeshChip("SF$v", sf == v) { sf = v } }
            }
            Muted("Coding rate", size = 12)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (5..8).forEach { v -> MeshChip("4/$v", cr == v) { cr = v } } }
            MeshSwitchRow(tr("Repeat packets", "Repetir pacotes"), repeat, tr("The companion also works as a repeater", "O companion também funciona como repetidor")) { repeat = it }
        } else {
            Pill(tr("Advanced: frequency, BW, SF, CR, repeater", "Avançado: frequência, BW, SF, CR, repetidor"), Modifier.fillMaxWidth(), PillStyle.TEXT) { advanced = true }
        }

        val r = region
        val freqKhz = if (r != null && !advanced) (r.freqMhz * 1000).roundToInt() else freq.replace(',', '.').toDoubleOrNull()?.let { (it * 1000).roundToInt() }
        val bwHz = if (r != null && !advanced) (r.bwKhz * 1000).roundToInt() else (bw * 1000).roundToInt()
        val sfV = if (r != null && !advanced) r.sf else sf
        val crV = if (r != null && !advanced) r.cr else cr
        Pill(tr("Apply", "Aplicar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, enabled = freqKhz != null) {
            MeshController.setRadio(freqKhz!!, bwHz, sfV, crV, tx.roundToInt(), repeat)
            if (hashBytes - 1 != device?.pathHashMode) MeshController.setPathHashMode(hashBytes - 1)
        }
    }
}

private fun trimNum(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

@Composable
private fun IdentityCard() {
    val self by MeshController.self.collectAsState()
    val device by MeshController.device.collectAsState()
    val clipboard = LocalClipboardManager.current
    var name by remember(self?.name) { mutableStateOf(self?.name ?: "") }
    var qr by remember { mutableStateOf(false) }
    val s = self ?: return

    MeshCard {
        CardTitle(tr("Identity", "Identidade"))
        MeshField(name, { if (it.toByteArray().size <= 31) name = it }, tr("Node name", "Nome do nó"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("Save name", "Salvar nome"), Modifier.weight(1f), PillStyle.PRIMARY, enabled = name.isNotBlank() && name != s.name) { MeshController.setName(name) }
            Pill(tr("My QR code", "Meu QR code"), Modifier.weight(1f)) { qr = true }
        }
        Text(s.pubKey.toHex(), color = Mesh.MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Pill(tr("Copy public key", "Copiar chave pública"), Modifier.fillMaxWidth(), PillStyle.TEXT) { clipboard.setText(AnnotatedString(s.pubKey.toHex())) }
        device?.let { Muted("Firmware ${it.version} (${it.buildDate}) • ${it.maxContacts} ${tr("contacts", "contatos")} • ${it.maxChannels} ${tr("channels", "canais")}", size = 12) }
    }
    if (qr) QrDialog(s.name, tr("Scan in MeshCore to add me", "Escaneie no MeshCore para me adicionar"), Protocol.contactUri(s.name, s.pubKey, Protocol.ADV_TYPE_CHAT)) { qr = false }
}

@Composable
private fun AdvertCard() {
    var minutes by remember { mutableIntStateOf(MeshController.autoAdvertMinutes) }
    var flood by remember { mutableStateOf(MeshController.autoAdvertFlood) }
    MeshCard {
        CardTitle("Advert")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("Send (flood)", "Enviar (flood)"), Modifier.weight(1f)) { MeshController.sendAdvert(true) }
            Pill(tr("Zero-hop", "Zero-hop"), Modifier.weight(1f)) { MeshController.sendAdvert(false) }
        }
        Text(tr("Automatic", "Automático"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to tr("Off", "Off"), 60 to "1 h", 180 to "3 h", 720 to "12 h").forEach { (m, l) ->
                MeshChip(l, minutes == m, Modifier.weight(1f)) { minutes = m; MeshController.setAutoAdvert(m, flood) }
            }
        }
        MeshSwitchRow(tr("Flood (reaches the whole mesh)", "Flood (alcança toda a malha)"), flood) { flood = it; MeshController.setAutoAdvert(minutes, it) }
    }
}

@Composable
private fun ContactsPolicyCard() {
    val self by MeshController.self.collectAsState()
    val autoAdd by MeshController.autoAdd.collectAsState()
    val s = self ?: return
    val cfg = autoAdd?.first ?: 0
    val maxHops = autoAdd?.second ?: 0
    fun setBit(bit: Int, on: Boolean) = MeshController.setAutoAdd(if (on) cfg or bit else cfg and bit.inv(), maxHops)

    MeshCard {
        CardTitle(tr("Contacts and telemetry", "Contatos e telemetria"))
        MeshSwitchRow(tr("Add new nodes automatically", "Adicionar novos nós automaticamente"), !s.manualAddContacts, tr("Off: they show up in Contacts → Discovered", "Desligado: aparecem em Contatos → Descobertos")) {
            MeshController.setManualAdd(!it)
        }
        if (s.manualAddContacts) {
            Muted(tr("Even in manual mode, add automatically:", "Mesmo no modo manual, adicionar automaticamente:"), size = 12)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MeshChip("Chat", cfg and Protocol.AUTOADD_CHAT != 0) { setBit(Protocol.AUTOADD_CHAT, cfg and Protocol.AUTOADD_CHAT == 0) }
                MeshChip(tr("Repeater", "Repetidor"), cfg and Protocol.AUTOADD_REPEATER != 0) { setBit(Protocol.AUTOADD_REPEATER, cfg and Protocol.AUTOADD_REPEATER == 0) }
                MeshChip(tr("Room", "Sala"), cfg and Protocol.AUTOADD_ROOM != 0) { setBit(Protocol.AUTOADD_ROOM, cfg and Protocol.AUTOADD_ROOM == 0) }
            }
        }
        MeshSwitchRow(tr("Replace the oldest when full", "Substituir o mais antigo quando cheio"), cfg and Protocol.AUTOADD_OVERWRITE_OLDEST != 0) {
            setBit(Protocol.AUTOADD_OVERWRITE_OLDEST, it)
        }
        val base = s.telemetryModes and 0x03
        Text(tr("Share battery (telemetry) with", "Compartilhar bateria (telemetria) com"), color = Mesh.TEXT, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to tr("Nobody", "Ninguém"), 1 to tr("Favourites", "Favoritos"), 2 to tr("Everyone", "Todos")).forEach { (m, l) ->
                MeshChip(l, base == m, Modifier.weight(1f)) { MeshController.setTelemetryBase(m) }
            }
        }
        Muted(tr("\"Everyone\" lets others discover the route to you.", "\"Todos\" permite que outros descubram a rota até você."), size = 12)
    }
}

@Composable
private fun LocationCard() {
    val self by MeshController.self.collectAsState()
    val context = LocalContext.current
    val s = self ?: return
    var lat by remember(s.lat) { mutableStateOf(if (s.lat != 0.0) "%.6f".format(Locale.US, s.lat) else "") }
    var lon by remember(s.lon) { mutableStateOf(if (s.lon != 0.0) "%.6f".format(Locale.US, s.lon) else "") }
    var share by remember(s.advertLocPolicy) { mutableStateOf(s.advertLocPolicy != 0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) lastLocation(context)?.let { (a, b) -> lat = "%.6f".format(Locale.US, a); lon = "%.6f".format(Locale.US, b) }
    }

    MeshCard {
        CardTitle(tr("Location", "Localização"))
        MeshSwitchRow(tr("Include location in the advert", "Incluir localização no advert"), share) { share = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { MeshField(lat, { lat = it }, "Latitude") }
            Box(Modifier.weight(1f)) { MeshField(lon, { lon = it }, "Longitude") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("Phone GPS", "GPS do celular"), Modifier.weight(1f)) {
                launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            Pill(tr("Save", "Salvar"), Modifier.weight(1f), PillStyle.PRIMARY) {
                MeshController.setLocation(lat.toDoubleOrNull() ?: 0.0, lon.toDoubleOrNull() ?: 0.0, share)
            }
        }
    }
}

@SuppressLint("MissingPermission")
internal fun lastLocation(context: Context): Pair<Double, Double>? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }?.let { it.latitude to it.longitude }
    } catch (_: SecurityException) {
        null
    }
}

@Composable
private fun BackupCard() {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var exported by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    MeshCard {
        CardTitle(tr("Identity backup", "Backup da identidade"))
        Muted(tr("The private key is your identity on the mesh. Keep it to restore the node after reinstalling the app or changing phones.", "A chave privada é a sua identidade na malha. Guarde-a para restaurar o nó após reinstalar o app ou trocar de celular."), size = 12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(tr("Export", "Exportar"), Modifier.weight(1f)) { scope.launch { exported = MeshController.exportPrivateKey()?.toHex() ?: "" } }
            Pill(tr("Restore", "Restaurar"), Modifier.weight(1f), PillStyle.OUTLINED) { importing = true }
        }
    }
    exported?.let { key ->
        AlertDialog(
            onDismissRequest = { exported = null },
            containerColor = Mesh.SURFACE_2,
            title = { Text(tr("Private key", "Chave privada")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (key.isEmpty()) Text(tr("The node did not export the key.", "O nó não exportou a chave.")) else {
                        Text(tr("Don't share it: whoever has this key can impersonate you.", "Não compartilhe: quem tiver esta chave pode se passar por você."), color = Mesh.AMBER, fontSize = 13.sp)
                        Text(key, color = Mesh.TEXT, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = { if (key.isNotEmpty()) Pill(tr("Copy", "Copiar"), style = PillStyle.PRIMARY) { clipboard.setText(AnnotatedString(key)); exported = null } },
            dismissButton = { Pill(tr("Close", "Fechar"), style = PillStyle.TEXT) { exported = null } },
        )
    }
    if (importing) {
        val bytes = input.hexToBytes()
        AlertDialog(
            onDismissRequest = { importing = false },
            containerColor = Mesh.SURFACE_2,
            title = { Text(tr("Restore identity", "Restaurar identidade")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Replaces this node's current identity.", "Substitui a identidade atual deste nó."), color = Mesh.AMBER, fontSize = 13.sp)
                    MeshField(input, { input = it }, tr("Private key (128 hex)", "Chave privada (128 hex)"), singleLine = false, isError = input.isNotBlank() && bytes?.size != 64)
                }
            },
            confirmButton = {
                Pill(tr("Restore", "Restaurar"), style = PillStyle.PRIMARY, enabled = bytes?.size == 64) {
                    MeshController.importPrivateKey(bytes!!)
                    importing = false
                    input = ""
                }
            },
            dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT) { importing = false } },
        )
    }
}

@Composable
private fun LanguageCard() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }
    var choice by remember { mutableStateOf(prefs.getString("language", null)) }
    MeshCard {
        CardTitle(tr("Language", "Idioma"))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(null to tr("System", "Sistema"), "en" to "English", "pt" to "Português").forEach { (code, label) ->
                MeshChip(label, choice == code, Modifier.weight(1f)) {
                    choice = code
                    prefs.edit().putString("language", code).apply()
                    com.engperini.meshcore.core.Lang.apply(code)
                }
            }
        }
    }
}

@Composable
private fun ServiceCard(running: Boolean) {
    val context = LocalContext.current
    val device by MeshController.device.collectAsState()
    MeshCard {
        CardTitle(tr("Service", "Serviço"))
        Muted(tr("The radio keeps working with the screen off while the USB radio is connected.", "O rádio continua funcionando com a tela apagada enquanto o rádio USB estiver conectado."), size = 12)
        if (running) {
            Pill(tr("Stop radio", "Parar rádio"), Modifier.fillMaxWidth(), PillStyle.OUTLINED) {
                context.startService(Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP))
            }
        }
        Muted("MeshCommunity ${BuildConfig.VERSION_NAME}${device?.let { " • firmware ${it.version}" } ?: ""} • github.com/engperini/AndroidMeshcore", size = 11)
    }
}
