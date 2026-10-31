package com.engperini.meshcore.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.data.Message
import com.engperini.meshcore.data.MsgStatus

// Text budget of one MeshCore message (the group sender name prefix shares it).
private const val MAX_TEXT_BYTES = 150

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conv: Conversation, onBack: () -> Unit) {
    val version by MeshController.messagesVersion.collectAsState()
    val channels by MeshController.channels.collectAsState()
    val contacts by MeshController.contacts.collectAsState()
    val link by MeshController.link.collectAsState()
    val messages = remember(version, conv.key) { MeshController.messages.messages(conv.key) }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    val channel = channels.firstOrNull { conv.isChannel && it.index == conv.channelIndex }
    val contact = contacts.firstOrNull { !conv.isChannel && it.keyHex.startsWith(conv.prefixHex) }

    DisposableEffect(conv.key) {
        MeshController.activeConversation = conv.key
        onDispose { MeshController.activeConversation = null }
    }
    LaunchedEffect(version) {
        MeshController.markRead(conv.key)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") } },
                title = {
                    Column {
                        Text((if (conv.isChannel) "# " else "") + (channel?.name ?: contact?.name ?: conv.title))
                        val sub = when {
                            conv.isChannel -> "Canal em grupo"
                            contact == null -> "Contato desconhecido"
                            else -> Protocol.hops(contact.outPathLen)?.let { "Rota direta: $it salto(s)" } ?: "Rota: flood"
                        }
                        Text(sub, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = { IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, "Limpar") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(messages, key = { it.id }) { m ->
                    Bubble(m, showSender = conv.isChannel) {
                        if (!conv.isChannel && m.outgoing && m.status == MsgStatus.FAILED) {
                            MeshController.retryDirect(m.id, conv.prefixHex, m.text, m.timestamp)
                        }
                    }
                }
            }
            val bytes = input.toByteArray().size
            Row(Modifier.fillMaxWidth().padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.toByteArray().size <= MAX_TEXT_BYTES) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Mensagem") },
                    supportingText = { Text("$bytes/$MAX_TEXT_BYTES") },
                    maxLines = 4,
                )
                IconButton(
                    enabled = input.isNotBlank() && link.state == LinkState.RUNNING && (channel != null || !conv.isChannel),
                    onClick = {
                        val text = input.trim()
                        if (conv.isChannel) channel?.let { MeshController.sendChannel(it, text) }
                        else MeshController.sendDirect(conv.prefixHex, text)
                        input = ""
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Enviar") }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Limpar histórico?") },
            text = { Text("Apaga as mensagens desta conversa somente neste celular.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; MeshController.clearConversation(conv.key) }) { Text("Limpar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun Bubble(m: Message, showSender: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = if (m.outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp).clickable(onClick = onClick),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (showSender && !m.outgoing) {
                    Text(m.sender, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(m.text)
                Spacer(Modifier.padding(1.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val meta = buildList {
                        add(formatTime(m.timestamp))
                        if (!m.outgoing) {
                            m.hops?.let { add(if (it == 0) "direto" else "$it salto(s)") } ?: add("rota direta")
                            m.snr?.let { add("SNR %.1f".format(it)) }
                        } else {
                            add(
                                when (m.status) {
                                    MsgStatus.SENDING -> "enviando…"
                                    MsgStatus.SENT -> if (showSender) "✓ enviado" else "✓ enviado, aguardando confirmação"
                                    MsgStatus.DELIVERED -> "✓✓ entregue" + if (m.roundTripMs > 0) " (${m.roundTripMs / 1000.0}s)" else ""
                                    MsgStatus.FAILED -> if (showSender) "✗ falhou" else "✗ sem confirmação — toque para reenviar"
                                    MsgStatus.RECEIVED -> ""
                                },
                            )
                        }
                    }
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
