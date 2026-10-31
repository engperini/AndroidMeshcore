package com.engperini.meshcore.ui

import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.Channel
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.MeshLink
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.core.hexToBytes
import com.engperini.meshcore.data.ConvKey
import java.security.SecureRandom

@Composable
fun ChatsScreen(nav: Nav) {
    val channels by MeshController.channels.collectAsState()
    val contacts by MeshController.contacts.collectAsState()
    val version by MeshController.messagesVersion.collectAsState()
    val summaries = remember(version) { MeshController.messages.summaries() }
    var adding by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<MeshLink?>(null) }
    val scan = rememberQrScanner { link ->
        if (link == null) MeshController.notice("QR code não é do MeshCore") else scanned = link
    }

    val used = channels.filterNot { it.isEmpty }
    val directs = summaries.keys.filter { it.startsWith("c:") }
        .map { key ->
            val prefix = key.removePrefix("c:")
            val c = contacts.firstOrNull { it.keyHex.startsWith(prefix) }
            Triple(Conversation(key, c?.name ?: prefix, false, prefixHex = prefix), c?.type ?: 1, summaries[key])
        }
        .sortedByDescending { it.third?.lastTime ?: 0L }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("+ Canal", Modifier.weight(1f), PillStyle.PRIMARY) { adding = true }
                Pill("Ler QR code", Modifier.weight(1f), PillStyle.OUTLINED, onClick = scan)
            }
        }
        item { SectionHeader("Canais") }
        item {
            MeshCard {
                if (used.isEmpty()) Muted("Nenhum canal configurado.")
                used.forEach { ch ->
                    val conv = Conversation(ConvKey.channel(ch.secretHex), ch.name, true, channelIndex = ch.index)
                    ChannelRow(ch, summaries[conv.key]) { nav.openChat(conv) }
                }
            }
        }
        item { SectionHeader("Mensagens diretas e salas") }
        item {
            MeshCard {
                if (directs.isEmpty()) Muted("Abra um contato para conversar. Salas e repetidores ficam em Contatos.")
                directs.forEach { (conv, type, s) ->
                    ListRow(
                        title = conv.title,
                        subtitle = s?.let { if (type == Protocol.ADV_TYPE_ROOM) "${it.lastSender}: ${it.lastText}" else it.lastText } ?: "",
                        avatar = { MeshAvatar(conv.title, typeColor(type)) },
                        onClick = { nav.openChat(conv) },
                        trailing = { TimeAndBadge(s?.lastTime, s?.unread ?: 0) },
                    )
                }
            }
        }
    }

    if (adding) {
        val free = channels.firstOrNull { it.isEmpty }?.index
        AddChannelDialog(free, onDismiss = { adding = false }) { name, secret ->
            adding = false
            if (free != null) MeshController.setChannel(free, name, secret)
        }
    }
    scanned?.let { LinkDialog(it) { scanned = null } }
}

@Composable
fun TimeAndBadge(time: Long?, unread: Int) {
    Column(horizontalAlignment = Alignment.End) {
        if (time != null) Muted(formatTime(time), size = 11)
        if (unread > 0) Badge(containerColor = Mesh.ACCENT, contentColor = Mesh.ON_ACCENT) { Text("$unread") }
    }
}

@Composable
private fun ChannelRow(ch: Channel, s: com.engperini.meshcore.data.ConversationSummary?, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf(false) }
    Box {
        ListRow(
            title = "# " + ch.name.removePrefix("#"),
            subtitle = s?.let { "${it.lastSender}: ${it.lastText}" } ?: "Sem mensagens",
            avatar = { MeshAvatar(ch.name, Mesh.ACCENT) },
            onClick = onOpen,
            onLongClick = { menu = true },
            trailing = { TimeAndBadge(s?.lastTime, s?.unread ?: 0) },
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Abrir") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Compartilhar (QR code)") }, onClick = { menu = false; qr = true })
            if (ch.index != 0) {
                DropdownMenuItem(
                    text = { Text("Remover canal", color = Mesh.RED) },
                    onClick = { menu = false; MeshController.setChannel(ch.index, "", ByteArray(16)) },
                )
            }
        }
    }
    if (qr) {
        QrDialog("Canal ${ch.name}", "Chave ${ch.secretHex}", Protocol.channelUri(ch.name, ch.secret)) { qr = false }
    }
}

private enum class ChannelKind(val label: String) { HASHTAG("#hashtag"), PRIVATE_NEW("Privado novo"), PRIVATE_KEY("Com chave") }

@Composable
fun AddChannelDialog(freeIndex: Int?, onDismiss: () -> Unit, onSave: (String, ByteArray) -> Unit) {
    var kind by remember { mutableStateOf(ChannelKind.HASHTAG) }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    val randomKey = remember { ByteArray(16).also { SecureRandom().nextBytes(it) } }

    val trimmed = name.trim()
    val secret: ByteArray? = when (kind) {
        ChannelKind.HASHTAG -> if (trimmed.length > 1) Protocol.hashtagSecret(if (trimmed.startsWith("#")) trimmed else "#$trimmed") else null
        ChannelKind.PRIVATE_NEW -> randomKey
        ChannelKind.PRIVATE_KEY -> parseKey(key)
    }
    val finalName = if (kind == ChannelKind.HASHTAG && trimmed.isNotEmpty() && !trimmed.startsWith("#")) "#$trimmed" else trimmed

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mesh.SURFACE_2,
        title = { Text("Adicionar canal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (freeIndex == null) Text("Todos os slots de canal estão ocupados.", color = Mesh.RED, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChannelKind.entries.forEach { k -> MeshChip(k.label, kind == k) { kind = k } }
                }
                MeshField(name, { if (it.toByteArray().size <= 31) name = it }, if (kind == ChannelKind.HASHTAG) "Nome (ex.: #brasil)" else "Nome do canal")
                when (kind) {
                    ChannelKind.HASHTAG -> Muted("Canal público por nome: quem usar o mesmo #nome entra na mesma conversa.", size = 12)
                    ChannelKind.PRIVATE_NEW -> Muted("Gera uma chave aleatória. Compartilhe pelo QR code (toque e segure o canal).", size = 12)
                    ChannelKind.PRIVATE_KEY -> MeshField(key, { key = it }, "Chave (32 hex ou base64)", isError = key.isNotBlank() && secret == null)
                }
            }
        },
        confirmButton = {
            Pill("Salvar", style = PillStyle.PRIMARY, enabled = freeIndex != null && trimmed.isNotEmpty() && secret != null) {
                onSave(finalName, secret!!)
            }
        },
        dismissButton = { Pill("Cancelar", style = PillStyle.TEXT, onClick = onDismiss) },
    )
}

private fun parseKey(text: String): ByteArray? {
    val t = text.trim()
    if (t.isEmpty()) return null
    t.hexToBytes()?.let { if (it.size == 16) return it }
    return try {
        Base64.decode(t, Base64.DEFAULT).takeIf { it.size == 16 }
    } catch (_: IllegalArgumentException) {
        null
    }
}
