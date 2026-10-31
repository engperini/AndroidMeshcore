package com.engperini.meshcore.ui

import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engperini.meshcore.core.Channel
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.core.hexToBytes
import com.engperini.meshcore.data.ConvKey
import java.security.SecureRandom

@Composable
fun ChannelsScreen(onOpen: (Conversation) -> Unit) {
    val channels by MeshController.channels.collectAsState()
    var adding by remember { mutableStateOf(false) }
    val used = channels.filterNot { it.isEmpty }

    Box(Modifier.fillMaxSize()) {
        if (used.isEmpty()) {
            Empty("Nenhum canal configurado.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(used, key = { it.index }) { ch ->
                    ChannelRow(ch) { onOpen(Conversation(ConvKey.channel(ch.secretHex), ch.name, true, channelIndex = ch.index)) }
                    HorizontalDivider()
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(Icons.Default.Add, null) },
            text = { Text("Canal") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (adding) {
        val free = channels.firstOrNull { it.isEmpty }?.index
        AddChannelDialog(
            freeIndex = free,
            onDismiss = { adding = false },
            onSave = { name, secret ->
                adding = false
                if (free != null) MeshController.setChannel(free, name, secret)
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChannelRow(ch: Channel, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(ch.name.removePrefix("#"))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("# " + ch.name, fontWeight = FontWeight.SemiBold)
                Text(
                    "Slot ${ch.index} · toque e segure para opções",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Abrir") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Ver chave") }, onClick = { menu = false; showKey = true })
            if (ch.index != 0) {
                DropdownMenuItem(
                    text = { Text("Remover canal") },
                    onClick = { menu = false; MeshController.setChannel(ch.index, "", ByteArray(16)) },
                )
            }
        }
    }
    if (showKey) {
        val clipboard = LocalClipboardManager.current
        val b64 = Base64.encodeToString(ch.secret, Base64.NO_WRAP)
        AlertDialog(
            onDismissRequest = { showKey = false },
            title = { Text("Chave de \"${ch.name}\"") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hex:", style = MaterialTheme.typography.labelMedium)
                    Text(ch.secretHex, fontFamily = FontFamily.Monospace)
                    Text("Base64:", style = MaterialTheme.typography.labelMedium)
                    Text(b64, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = { clipboard.setText(AnnotatedString(ch.secretHex)); showKey = false }) { Text("Copiar hex") }
            },
            dismissButton = { TextButton(onClick = { showKey = false }) { Text("Fechar") } },
        )
    }
}

private enum class ChannelKind { HASHTAG, PRIVATE_NEW, PRIVATE_KEY }

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
        title = { Text("Adicionar canal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (freeIndex == null) {
                    Text("Todos os slots de canal estão ocupados.", color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(kind == ChannelKind.HASHTAG, { kind = ChannelKind.HASHTAG }, { Text("#hashtag") })
                    FilterChip(kind == ChannelKind.PRIVATE_NEW, { kind = ChannelKind.PRIVATE_NEW }, { Text("Novo privado") })
                    FilterChip(kind == ChannelKind.PRIVATE_KEY, { kind = ChannelKind.PRIVATE_KEY }, { Text("Com chave") })
                }
                OutlinedTextField(
                    value = name, onValueChange = { if (it.toByteArray().size <= 31) name = it }, singleLine = true,
                    label = { Text(if (kind == ChannelKind.HASHTAG) "Nome (ex.: #brasil)" else "Nome do canal") },
                )
                when (kind) {
                    ChannelKind.HASHTAG -> Text(
                        "Canal público por nome: quem usar o mesmo #nome entra na mesma conversa.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChannelKind.PRIVATE_NEW -> Text(
                        "Gera uma chave aleatória. Depois use \"Ver chave\" para compartilhá-la com o grupo.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChannelKind.PRIVATE_KEY -> OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("Chave (32 hex ou base64)") },
                        isError = key.isNotBlank() && secret == null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = freeIndex != null && trimmed.isNotEmpty() && secret != null,
                onClick = { onSave(finalName, secret!!) },
            ) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
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
