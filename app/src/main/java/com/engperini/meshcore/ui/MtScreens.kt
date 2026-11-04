package com.engperini.meshcore.ui

import android.util.Base64
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MT_PRESETS
import com.engperini.meshcore.core.MT_REGIONS
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.MeshtasticController
import com.engperini.meshcore.core.MtChannel
import com.engperini.meshcore.core.MtNode
import com.engperini.meshcore.core.tr
import com.engperini.meshcore.data.ConvKey
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.roundToInt

// Meshtastic text payloads hold up to ~200 bytes of UTF-8.
private const val MT_MAX_TEXT_BYTES = 200

private fun nodeColor(n: MtNode?): Color = when (n?.role) {
    2, 3, 4 -> Mesh.AMBER // router / router-client / repeater
    else -> Mesh.ACCENT
}

/** Reminder shown until the node has a region: Meshtastic does not transmit without one. */
@Composable
private fun RegionWarning() {
    val lora by MeshtasticController.lora.collectAsState()
    if (lora != null && lora!!.region == 0) {
        MeshCard {
            CardTitle(tr("Choose your region", "Escolha sua região"))
            Muted(
                tr(
                    "Meshtastic does not transmit until the region is set. Settings → Meshtastic radio.",
                    "O Meshtastic não transmite até a região ser definida. Config → Rádio Meshtastic.",
                ),
                size = 12,
            )
        }
    }
}

// ---- Chats ----

@Composable
fun MtChatsScreen(nav: Nav) {
    val channels by MeshtasticController.channels.collectAsState()
    val lora by MeshtasticController.lora.collectAsState()
    val nodes by MeshtasticController.nodes.collectAsState()
    val version by MeshController.messagesVersion.collectAsState()
    val summaries = remember(version) { MeshController.messages.summaries() }

    val directs = summaries.keys.filter { it.startsWith("mtd:") }
        .map { key ->
            val num = key.removePrefix("mtd:").toLong(16)
            Triple(Conversation(key, nodes[num]?.title ?: "!%08x".format(num), false, prefixHex = "%08x".format(num)), nodes[num], summaries[key])
        }
        .sortedByDescending { it.third?.lastTime ?: 0L }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        item { RegionWarning() }
        item { SectionHeader(tr("Channels", "Canais")) }
        item {
            MeshCard {
                val used = channels.filter { it.enabled }
                if (used.isEmpty()) Muted(tr("Loading the node's channels…", "Carregando os canais do nó…"))
                used.forEach { ch ->
                    val title = ch.title(lora)
                    val s = summaries[ch.convKey]
                    ListRow(
                        title = "# $title",
                        subtitle = s?.let { "${it.lastSender}: ${it.lastText}" }
                            ?: if (ch.role == 1) tr("Primary channel", "Canal primário") else tr("Channel ${ch.index}", "Canal ${ch.index}"),
                        avatar = { MeshAvatar(title, Mesh.ACCENT) },
                        onClick = { nav.openChat(Conversation(ch.convKey, title, true, channelIndex = ch.index)) },
                        trailing = { TimeAndBadge(s?.lastTime, s?.unread ?: 0) },
                    )
                }
            }
        }
        item { SectionHeader(tr("Direct messages", "Mensagens diretas")) }
        item {
            MeshCard {
                if (directs.isEmpty()) Muted(tr("Open a node in Nodes to send it a direct message.", "Abra um nó em Nós para enviar uma mensagem direta."))
                directs.forEach { (conv, node, s) ->
                    ListRow(
                        title = conv.title,
                        subtitle = s?.lastText ?: "",
                        avatar = { MeshAvatar(node?.short ?: conv.title, nodeColor(node)) },
                        onClick = { nav.openChat(conv) },
                        trailing = { TimeAndBadge(s?.lastTime, s?.unread ?: 0) },
                    )
                }
            }
        }
    }
}

@Composable
fun MtChatScreen(conv: Conversation, nav: Nav) {
    val version by MeshController.messagesVersion.collectAsState()
    val channels by MeshtasticController.channels.collectAsState()
    val lora by MeshtasticController.lora.collectAsState()
    val nodes by MeshtasticController.nodes.collectAsState()
    val link by MeshController.link.collectAsState()
    val messages = remember(version, conv.key) { MeshController.messages.messages(conv.key) }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var heardBy by remember { mutableStateOf<com.engperini.meshcore.data.Message?>(null) }
    val listState = rememberLazyListState()

    val channel = channels.firstOrNull { conv.isChannel && it.convKey == conv.key }
    val num = if (conv.isChannel) null else conv.prefixHex.toLongOrNull(16)
    val node = num?.let { nodes[it] }

    DisposableEffect(conv.key) {
        MeshController.activeConversation = conv.key
        onDispose { MeshController.activeConversation = null }
    }
    LaunchedEffect(version) {
        MeshController.markRead(conv.key)
        if (messages.isNotEmpty()) listState.scrollToItem(0)   // newest, at the bottom
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = Mesh.TEXT) }
            val title = channel?.title(lora) ?: node?.title ?: conv.title
            MeshAvatar(if (conv.isChannel) title else node?.short ?: title, if (conv.isChannel) Mesh.ACCENT else nodeColor(node), 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text((if (conv.isChannel) "# " else "") + title, color = Mesh.TEXT, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                val sub = when {
                    conv.isChannel -> "Meshtastic • " + tr("channel ${channel?.index ?: conv.channelIndex}", "canal ${channel?.index ?: conv.channelIndex}")
                    node != null -> "Meshtastic • ${node.id}" + (node.hops?.let { " • " + hopsCount(it) } ?: "")
                    else -> "Meshtastic"
                }
                Muted(sub, size = 12, maxLines = 1)
            }
            if (num != null) {
                IconButton(onClick = { nav.push(Route.MtNode(num)) }) { Icon(Icons.Default.Info, tr("Details", "Detalhes"), tint = Mesh.MUTED) }
            }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, tr("Clear", "Limpar"), tint = Mesh.MUTED) }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            // Anchored at the bottom, as chat apps are: when the keyboard opens the latest messages move up with it.
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) item { Empty(tr("No messages yet. Say hi! 👋", "Nenhuma mensagem ainda. Diga oi! 👋")) }
            items(messages.asReversed(), key = { it.id }) { m ->
                Bubble(m, showSender = conv.isChannel) { if (m.outgoing && m.repeaters.isNotEmpty()) heardBy = m }
            }
        }

        val bytes = input.toByteArray().size
        val canSend = input.isNotBlank() && link.state == LinkState.RUNNING && (channel != null || num != null)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MeshField(
                    input, { if (it.toByteArray().size <= MT_MAX_TEXT_BYTES) input = it }, tr("Message", "Mensagem"),
                    singleLine = false, supporting = "$bytes/$MT_MAX_TEXT_BYTES",
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Mesh.ACCENT, contentColor = Mesh.ON_ACCENT,
                    disabledContainerColor = Mesh.SURFACE_2, disabledContentColor = Mesh.MUTED),
                onClick = {
                    val text = input.trim()
                    if (channel != null) MeshtasticController.sendChannel(channel, text) else if (num != null) MeshtasticController.sendDirect(num, text)
                    input = ""
                },
            ) { Icon(Icons.AutoMirrored.Filled.Send, tr("Send", "Enviar")) }
        }
    }

    heardBy?.let { m ->
        AlertDialog(
            onDismissRequest = { heardBy = null },
            containerColor = Mesh.SURFACE_2,
            title = { Text(repeatersLabel(m.repeaters.size)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Muted(
                        tr(
                            "Your node heard this message being passed on (Meshtastic implicit ACK):",
                            "Seu nó ouviu esta mensagem sendo repassada (ACK implícito do Meshtastic):",
                        ),
                        size = 12,
                    )
                    m.repeaters.forEach { Text(if (it == "mesh") tr("a node of the mesh", "um nó da malha") else it, color = Mesh.TEXT, fontSize = 14.sp) }
                }
            },
            confirmButton = { Pill("OK", style = PillStyle.TEXT) { heardBy = null } },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Mesh.SURFACE_2,
            title = { Text(tr("Clear history?", "Limpar histórico?")) },
            text = { Text(tr("Deletes this chat's messages on this phone only.", "Apaga as mensagens desta conversa somente neste celular.")) },
            confirmButton = { Pill(tr("Clear", "Limpar"), style = PillStyle.PRIMARY) { confirmClear = false; MeshController.clearConversation(conv.key) } },
            dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT) { confirmClear = false } },
        )
    }
}

// ---- Nodes ----

private fun nodeSubtitle(n: MtNode): String = buildList {
    add(n.id)
    n.hops?.let { add(if (it == 0) tr("direct", "direto") else hopsCount(it)) }
    if (n.snr != 0f && n.hops == 0) add("SNR %.1f".format(Locale.US, n.snr))
    n.battery?.let { add(if (it > 100) tr("powered", "na tomada") else "$it%") }
    if (n.viaMqtt) add("MQTT")
}.joinToString(" • ")

@Composable
fun MtNodesScreen(nav: Nav) {
    val nodes by MeshtasticController.nodes.collectAsState()
    val my by MeshtasticController.myNum.collectAsState()
    val sorted = nodes.values.filter { it.num != my }.sortedByDescending { it.lastHeard }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        item { RegionWarning() }
        my?.let { nodes[it] }?.let { me ->
            item { SectionHeader(tr("This node", "Este nó")) }
            item {
                MeshCard {
                    ListRow(me.title, nodeSubtitle(me), avatar = { MeshAvatar(me.short, Mesh.GREEN) }, onClick = { nav.push(Route.MtNode(me.num)) })
                }
            }
        }
        item { SectionHeader(tr("Nodes heard (${sorted.size})", "Nós ouvidos (${sorted.size})")) }
        item {
            MeshCard {
                if (sorted.isEmpty()) Muted(tr("No other node heard yet. Nodes announce themselves every few hours, or when they start.", "Nenhum outro nó ouvido ainda. Os nós se anunciam a cada poucas horas, ou ao ligar."))
                sorted.forEach { n ->
                    ListRow(
                        title = n.title,
                        subtitle = nodeSubtitle(n),
                        avatar = { MeshAvatar(n.short, nodeColor(n)) },
                        onClick = { nav.push(Route.MtNode(n.num)) },
                        trailing = { Muted(ago(n.lastHeard), size = 11) },
                    )
                }
            }
        }
    }
}

@Composable
fun MtNodeScreen(num: Long, nav: Nav) {
    val nodes by MeshtasticController.nodes.collectAsState()
    val my by MeshtasticController.myNum.collectAsState()
    val traces by MeshtasticController.traces.collectAsState()
    val tracing by MeshtasticController.tracing.collectAsState()
    val link by MeshController.link.collectAsState()
    val n = nodes[num]
    var confirmRemove by remember { mutableStateOf(false) }
    val isMe = num == my

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = Mesh.TEXT) }
            MeshAvatar(n?.short ?: "?", if (isMe) Mesh.GREEN else nodeColor(n), 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(n?.title ?: "!%08x".format(num), color = Mesh.TEXT, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Muted("Meshtastic • " + (n?.id ?: "!%08x".format(num)), size = 12, maxLines = 1)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!isMe) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill(tr("Message", "Mensagem"), Modifier.weight(1f), PillStyle.PRIMARY) {
                            nav.openChat(Conversation(ConvKey.mtDirect(num), n?.title ?: "!%08x".format(num), false, prefixHex = "%08x".format(num)))
                        }
                        Pill(
                            if (num in tracing) tr("Tracing…", "Rastreando…") else "Traceroute", Modifier.weight(1f), PillStyle.OUTLINED,
                            enabled = link.state == LinkState.RUNNING && num !in tracing,
                        ) { MeshtasticController.traceroute(num) }
                    }
                }
            }
            traces[num]?.let { t ->
                item {
                    MeshCard {
                        CardTitle("Traceroute")
                        fun hopLine(from: String, to: String, snr: Float?) = "$from → $to" + (snr?.takeIf { !it.isNaN() }?.let { " (SNR %.1f dB)".format(Locale.US, it) } ?: "")
                        val me = MeshtasticController.self?.title ?: tr("This node", "Este nó")
                        val dest = n?.title ?: "!%08x".format(num)
                        val there = listOf(me) + t.route.map(MeshtasticController::nodeName) + dest
                        Muted(tr("Towards", "Ida"), size = 12)
                        there.zipWithNext().forEachIndexed { i, (a, b) -> Text(hopLine(a, b, t.snrTowards.getOrNull(i)), color = Mesh.TEXT, fontSize = 13.sp) }
                        if (t.snrBack.isNotEmpty() || t.routeBack.isNotEmpty()) {
                            val back = listOf(dest) + t.routeBack.map(MeshtasticController::nodeName) + me
                            Muted(tr("Back", "Volta"), size = 12)
                            back.zipWithNext().forEachIndexed { i, (a, b) -> Text(hopLine(a, b, t.snrBack.getOrNull(i)), color = Mesh.TEXT, fontSize = 13.sp) }
                        }
                        t.roundTripMs?.let { Muted(tr("Round trip: %.1f s", "Ida e volta: %.1f s").format(Locale.US, it / 1000.0), size = 12) }
                    }
                }
            }
            if (n != null) {
                item {
                    MeshCard {
                        CardTitle(tr("Details", "Detalhes"))
                        KeyValue(tr("Node number", "Número do nó"), "!%08x (%d)".format(num, num))
                        if (n.shortName.isNotBlank()) KeyValue(tr("Short name", "Nome curto"), n.shortName)
                        if (!isMe) KeyValue(tr("Last heard", "Ouvido"), ago(n.lastHeard))
                        n.hops?.let { KeyValue(tr("Distance", "Distância"), if (it == 0) tr("direct", "direto") else hopsCount(it)) }
                        if (!isMe && n.snr != 0f) KeyValue("SNR", "%.1f dB".format(Locale.US, n.snr))
                        n.battery?.let { KeyValue(tr("Battery", "Bateria"), if (it > 100) tr("powered", "na tomada") else "$it%") }
                        n.voltage?.let { KeyValue(tr("Voltage", "Tensão"), "%.2f V".format(Locale.US, it)) }
                        n.channelUtil?.let { KeyValue(tr("Channel use", "Uso do canal"), "%.1f%%".format(Locale.US, it)) }
                        n.airUtil?.let { KeyValue(tr("Airtime (TX)", "Tempo no ar (TX)"), "%.1f%%".format(Locale.US, it)) }
                        if (n.lat != null && n.lon != null) KeyValue(tr("Position", "Posição"), "%.5f, %.5f".format(Locale.US, n.lat, n.lon))
                        KeyValue(tr("Encryption key", "Chave de criptografia"), if (n.hasKey) tr("known (PKI DMs)", "conhecida (DM com PKI)") else tr("not known yet", "ainda não conhecida"))
                        if (n.viaMqtt) KeyValue("MQTT", tr("heard via MQTT", "ouvido via MQTT"))
                    }
                }
                if (!isMe) {
                    item { Pill(tr("Remove from the node list", "Remover da lista de nós"), Modifier.fillMaxWidth(), PillStyle.TEXT) { confirmRemove = true } }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Mesh.SURFACE_2,
            title = { Text(tr("Remove node?", "Remover nó?")) },
            text = { Text(tr("It comes back when it is heard again.", "Ele volta quando for ouvido de novo.")) },
            confirmButton = { Pill(tr("Remove", "Remover"), style = PillStyle.PRIMARY) { confirmRemove = false; MeshtasticController.removeNode(num); nav.pop() } },
            dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT) { confirmRemove = false } },
        )
    }
}

// ---- Settings ----

@Composable
fun MtOwnerCard() {
    val nodes by MeshtasticController.nodes.collectAsState()
    val my by MeshtasticController.myNum.collectAsState()
    val fw by MeshtasticController.firmware.collectAsState()
    val me = my?.let { nodes[it] }
    var long by remember(me?.longName) { mutableStateOf(me?.longName ?: "") }
    var short by remember(me?.shortName) { mutableStateOf(me?.shortName ?: "") }
    val longOk = long.isNotBlank() && long.toByteArray().size <= 24
    val shortOk = short.isNotBlank() && short.toByteArray().size <= 4
    MeshCard {
        CardTitle(tr("Meshtastic node", "Nó Meshtastic"))
        me?.let { KeyValue("ID", it.id) }
        if (fw.isNotBlank()) KeyValue("Firmware", "Meshtastic $fw (Android)")
        MeshField(long, { long = it }, tr("Long name (up to 24 bytes)", "Nome longo (até 24 bytes)"), isError = !longOk)
        MeshField(short, { short = it }, tr("Short name (up to 4)", "Nome curto (até 4)"), isError = !shortOk)
        Pill(tr("Save", "Salvar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, enabled = me != null && longOk && shortOk &&
            (long != me.longName || short != me.shortName)) {
            MeshtasticController.setOwner(long.trim(), short.trim())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MtRadioCard() {
    val lora by MeshtasticController.lora.collectAsState()
    val l = lora ?: return
    var region by remember(l) { mutableIntStateOf(l.region) }
    var preset by remember(l) { mutableIntStateOf(l.preset) }
    var hops by remember(l) { mutableIntStateOf(l.hopLimit) }
    var power by remember(l) { mutableFloatStateOf(l.txPower.toFloat()) }
    var menu by remember { mutableStateOf(false) }
    MeshCard {
        CardTitle(tr("Meshtastic radio", "Rádio Meshtastic"))
        if (l.freqMhz > 0) KeyValue(tr("Frequency", "Frequência"), "%.3f MHz".format(Locale.US, l.freqMhz))
        Muted(tr("Region", "Região"), size = 12)
        Box {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Mesh.SURFACE_2)
                    .border(1.dp, if (region == 0) Mesh.AMBER else Mesh.OUTLINE, RoundedCornerShape(14.dp)).clickable { menu = true }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    MT_REGIONS.firstOrNull { it.first == region }?.second?.let { if (region == 0) tr("Not set — choose one", "Não definida — escolha") else it } ?: "?",
                    Modifier.weight(1f), color = Mesh.TEXT, fontWeight = FontWeight.SemiBold,
                )
                Text("▾", color = Mesh.ACCENT, fontSize = 18.sp)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MT_REGIONS.filter { it.first != 0 }.forEach { (code, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; region = code })
                }
            }
        }
        Muted(
            tr("Brazil: most Meshtastic communities use ANZ (915–928 MHz). The official band is BR_902.",
                "Brasil: a maioria das comunidades Meshtastic usa ANZ (915–928 MHz). A faixa oficial é BR_902."),
            size = 12,
        )
        Muted(tr("Modem preset (the same on every node of the mesh)", "Preset do modem (o mesmo em todos os nós da malha)"), size = 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MT_PRESETS.forEach { (code, label) -> MeshChip(label, preset == code) { preset = code } }
        }
        Muted(tr("Hop limit", "Limite de saltos"), size = 12)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { (1..7).forEach { v -> MeshChip("$v", hops == v, Modifier.weight(1f)) { hops = v } } }
        Text(
            if (power.roundToInt() == 0) tr("Transmit power: region maximum", "Potência de transmissão: máximo da região")
            else tr("Transmit power: ${power.roundToInt()} dBm", "Potência de transmissão: ${power.roundToInt()} dBm"),
            color = Mesh.TEXT, fontWeight = FontWeight.Medium,
        )
        Slider(
            power, { power = it }, valueRange = 0f..22f, steps = 21,
            colors = SliderDefaults.colors(thumbColor = Mesh.ACCENT, activeTrackColor = Mesh.ACCENT, inactiveTrackColor = Mesh.SURFACE_2,
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        )
        val changed = region != l.region || preset != l.preset || hops != l.hopLimit || power.roundToInt() != l.txPower
        Pill(tr("Apply", "Aplicar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, enabled = changed && region != 0) {
            MeshtasticController.setLora(region, preset, hops, true, power.roundToInt())
        }
        Muted(tr("Some changes restart the node (a few seconds).", "Algumas mudanças reiniciam o nó (alguns segundos)."), size = 12)
    }
}

private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** Channel key as the official apps show it: base64 ("AQ==" is the default key). */
private fun pskText(hex: String): String = Base64.encodeToString(hexToBytes(hex), Base64.NO_WRAP)

private fun parsePsk(text: String): ByteArray? {
    val t = text.trim()
    if (t.isEmpty()) return byteArrayOf(1) // default key
    return try {
        Base64.decode(t, Base64.DEFAULT).takeIf { it.size in listOf(0, 1, 16, 32) }
    } catch (_: IllegalArgumentException) {
        null
    }
}

@Composable
fun MtChannelsCard() {
    val channels by MeshtasticController.channels.collectAsState()
    val lora by MeshtasticController.lora.collectAsState()
    var editing by remember { mutableStateOf<MtChannel?>(null) }
    MeshCard {
        CardTitle(tr("Meshtastic channels", "Canais Meshtastic"))
        channels.filter { it.enabled }.forEach { ch ->
            ListRow(
                title = "# " + ch.title(lora),
                subtitle = (if (ch.role == 1) tr("primary", "primário") else tr("secondary", "secundário")) + " • " +
                    when (ch.pskHex) {
                        "" -> tr("no encryption", "sem criptografia")
                        "01" -> tr("default key", "chave padrão")
                        else -> tr("own key", "chave própria")
                    },
                avatar = { MeshAvatar(ch.title(lora), Mesh.ACCENT) },
                onClick = { editing = ch },
            )
        }
        val free = channels.firstOrNull { !it.enabled && it.index > 0 }
        if (free != null) {
            Pill(tr("+ Channel", "+ Canal"), Modifier.fillMaxWidth(), PillStyle.OUTLINED) { editing = MtChannel(free.index, 2, "", "") }
        }
    }
    editing?.let { ch -> MtChannelDialog(ch) { editing = null } }
}

@Composable
private fun MtChannelDialog(ch: MtChannel, onDone: () -> Unit) {
    val isNew = ch.role == 2 && ch.name.isEmpty() && ch.pskHex.isEmpty()
    var name by remember { mutableStateOf(ch.name) }
    var key by remember { mutableStateOf(if (isNew) "" else pskText(ch.pskHex)) }
    val psk = parsePsk(key)
    val nameOk = name.toByteArray().size <= 11 && (ch.role == 1 || name.isNotBlank())
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Mesh.SURFACE_2,
        title = { Text(if (isNew) tr("New channel", "Novo canal") else tr("Channel ${ch.index}", "Canal ${ch.index}")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MeshField(name, { name = it }, tr("Name (up to 11 bytes)", "Nome (até 11 bytes)"), isError = !nameOk)
                if (ch.role == 1) Muted(tr("Empty: the preset's name (e.g. LongFast).", "Vazio: o nome do preset (ex.: LongFast)."), size = 12)
                MeshField(key, { key = it }, tr("Key (base64)", "Chave (base64)"), isError = psk == null)
                Muted(tr("Empty or AQ== = Meshtastic default key; the same key on every node of the channel.", "Vazio ou AQ== = chave padrão do Meshtastic; a mesma chave em todos os nós do canal."), size = 12)
                Pill(tr("Random key", "Chave aleatória"), Modifier.fillMaxWidth(), PillStyle.OUTLINED) {
                    key = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
                }
                if (ch.role == 2 && !isNew) {
                    Pill(tr("Delete channel", "Excluir canal"), Modifier.fillMaxWidth(), PillStyle.TEXT) {
                        MeshtasticController.setChannel(ch.index, 0, "", ByteArray(0))
                        onDone()
                    }
                }
            }
        },
        confirmButton = {
            Pill(tr("Save", "Salvar"), style = PillStyle.PRIMARY, enabled = nameOk && psk != null) {
                MeshtasticController.setChannel(ch.index, ch.role, name.trim(), psk!!)
                onDone()
            }
        },
        dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT, onClick = onDone) },
    )
}
