package com.engperini.meshcore.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.Contact
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Safe, read-only commands offered as shortcuts (MeshCore docs/cli_commands.md).
private val QUICK_COMMANDS = listOf("ver", "clock", "neighbors", "get radio", "get tx", "get name", "advert")

/** Remote administration of a repeater / room server / sensor: login, status and CLI. */
@Composable
fun AdminScreen(keyHex: String, nav: Nav) {
    val contacts by MeshController.contacts.collectAsState()
    val c = contacts.firstOrNull { it.keyHex == keyHex }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Mesh.TEXT) }
            Column {
                Text(c?.name ?: "Admin", color = Mesh.TEXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Muted("Administração remota", size = 12)
            }
        }
        if (c == null) {
            Empty("Contato não está mais na lista.")
            return
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            LoginCard(c, compact = false)
            if (c.type == Protocol.ADV_TYPE_REPEATER || c.type == Protocol.ADV_TYPE_ROOM) StatusCard(c)
            CliCard(c)
            Spacer(Modifier.heightIn(min = 8.dp))
        }
    }
}

@Composable
fun LoginCard(c: Contact, compact: Boolean) {
    val logins by MeshController.logins.collectAsState()
    val login = logins[c.pubKey.prefixHex()]
    var password by remember(c.keyHex) { mutableStateOf(MeshController.savedPassword(c)) }
    var keepPw by remember(c.keyHex) { mutableStateOf(MeshController.savedPassword(c).isNotEmpty()) }

    MeshCard {
        CardTitle("Login") {
            when {
                login == null -> StatusChip("Desconectado", Mesh.MUTED)
                login.success -> StatusChip(if (login.isAdmin) "Admin" else "Convidado", Mesh.GREEN)
                else -> StatusChip("Recusado", Mesh.RED)
            }
        }
        if (!compact || login?.success != true) {
            MeshField(password, { password = it }, if (c.type == Protocol.ADV_TYPE_ROOM) "Senha da sala" else "Senha (vazio = convidado)")
            MeshSwitchRow("Lembrar senha neste celular", keepPw) { keepPw = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Entrar", Modifier.weight(1f), PillStyle.PRIMARY) { MeshController.login(c, password, keepPw) }
                if (login?.success == true) Pill("Sair", Modifier.weight(1f), PillStyle.OUTLINED) { MeshController.logout(c) }
            }
        }
    }
}

@Composable
private fun StatusCard(c: Contact) {
    val statuses by MeshController.repeaterStatus.collectAsState()
    val st = statuses[c.pubKey.prefixHex()]
    MeshCard {
        CardTitle("Status") { Pill("Atualizar", style = PillStyle.TEXT) { MeshController.requestStatus(c) } }
        if (st == null) {
            Muted("Toque em Atualizar para pedir o status (bateria, uptime, pacotes, ruído).", size = 12)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Bateria", "%.2f V".format(Locale.US, st.battMv / 1000.0), Modifier.weight(1f))
                StatTile("Uptime", uptime(st.uptimeSecs), Modifier.weight(1f))
                StatTile("Fila TX", "${st.txQueue}", Modifier.weight(1f))
            }
            KeyValue("Pacotes recebidos / enviados", "${st.packetsRecv} / ${st.packetsSent}")
            KeyValue("Flood rx / tx", "${st.recvFlood} / ${st.sentFlood}")
            KeyValue("Direto rx / tx", "${st.recvDirect} / ${st.sentDirect}")
            KeyValue("Tempo no ar (TX)", uptime(st.airTimeSecs))
            KeyValue("Último RSSI / SNR", "${st.lastRssi} dBm / %.1f dB".format(Locale.US, st.lastSnr))
            KeyValue("Piso de ruído", "${st.noiseFloor} dBm")
        }
    }
}

@Composable
private fun CliCard(c: Contact) {
    val cli by MeshController.cli.collectAsState()
    val lines = cli[c.pubKey.prefixHex()] ?: emptyList()
    var command by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val scroll = rememberScrollState()

    MeshCard {
        CardTitle("Terminal (CLI)") { if (lines.isNotEmpty()) Pill("Limpar", style = PillStyle.TEXT) { MeshController.clearCli(c) } }
        Muted("Comandos vão criptografados para o nó. Os de configuração exigem login de admin.", size = 12)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            QUICK_COMMANDS.forEach { cmd -> MeshChip(cmd, false) { MeshController.sendCli(c, cmd) } }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).clip(RoundedCornerShape(12.dp))
                .background(Mesh.BG.copy(alpha = 0.7f)).padding(10.dp),
        ) {
            Column(Modifier.verticalScroll(scroll)) {
                if (lines.isEmpty()) Muted("Sem saída ainda.", size = 12)
                lines.forEach { l ->
                    Text(
                        "[${fmt.format(Date(l.time))}] ${if (l.outgoing) "> " else ""}${l.text}",
                        color = if (l.outgoing) Mesh.ACCENT else Mesh.TEXT, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { MeshField(command, { command = it }, "Comando") }
            Pill("Enviar", style = PillStyle.PRIMARY, enabled = command.isNotBlank()) {
                MeshController.sendCli(c, command.trim())
                command = ""
            }
        }
    }
}
