package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.data.Message
import com.engperini.meshcore.data.MsgStatus

// Text budget of one MeshCore message (the group sender name prefix shares it).
private const val MAX_TEXT_BYTES = 150

@Composable
fun ChatScreen(conv: Conversation, nav: Nav) {
    val version by MeshController.messagesVersion.collectAsState()
    val channels by MeshController.channels.collectAsState()
    val contacts by MeshController.contacts.collectAsState()
    val logins by MeshController.logins.collectAsState()
    val link by MeshController.link.collectAsState()
    val messages = remember(version, conv.key) { MeshController.messages.messages(conv.key) }
    var input by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    var heardBy by remember { mutableStateOf<Message?>(null) }
    val listState = rememberLazyListState()

    val channel = channels.firstOrNull { conv.isChannel && it.index == conv.channelIndex }
    val contact = contacts.firstOrNull { !conv.isChannel && it.keyHex.startsWith(conv.prefixHex) }
    val isRoom = contact?.type == Protocol.ADV_TYPE_ROOM
    val loggedIn = logins[conv.prefixHex]?.success == true

    DisposableEffect(conv.key) {
        MeshController.activeConversation = conv.key
        onDispose { MeshController.activeConversation = null }
    }
    LaunchedEffect(version) {
        MeshController.markRead(conv.key)
        if (messages.isNotEmpty()) listState.animateScrollToItem(0)   // newest, at the bottom
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        // Top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = Mesh.TEXT) }
            MeshAvatar(channel?.name ?: contact?.name ?: conv.title, if (conv.isChannel) Mesh.ACCENT else typeColor(contact?.type ?: 1), 38)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    (if (conv.isChannel) "# " else "") + (channel?.name?.removePrefix("#") ?: contact?.name ?: conv.title),
                    color = Mesh.TEXT, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                )
                val sub = when {
                    conv.isChannel -> tr("Group channel", "Canal em grupo")
                    contact == null -> tr("Unknown contact", "Contato desconhecido")
                    isRoom -> if (loggedIn) tr("Room • logged in", "Sala • conectado") else tr("Room • log in to see the messages", "Sala • faça login para ver as mensagens")
                    else -> Protocol.hops(contact.outPathLen)?.let { tr("Direct route: ", "Rota direta: ") + hopsCount(it) } ?: tr("Route: flood", "Rota: flood")
                }
                Muted(sub, size = 12, maxLines = 1)
            }
            if (contact != null) {
                IconButton(onClick = { nav.push(Route.ContactDetail(contact.keyHex)) }) { Icon(Icons.Default.Info, tr("Details", "Detalhes"), tint = Mesh.MUTED) }
            }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.Delete, tr("Clear", "Limpar"), tint = Mesh.MUTED) }
        }

        if (isRoom && !loggedIn && contact != null) {
            Box(Modifier.padding(horizontal = 12.dp)) { LoginCard(contact, compact = true) }
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
                Bubble(m, showSender = conv.isChannel || isRoom) {
                    if (!conv.isChannel && m.outgoing && m.status == MsgStatus.FAILED) {
                        MeshController.retryDirect(m.id, conv.prefixHex, m.text, m.timestamp)
                    } else if (m.outgoing && m.repeaters.isNotEmpty()) {
                        heardBy = m
                    }
                }
            }
        }

        val bytes = input.toByteArray().size
        val canSend = input.isNotBlank() && link.state == LinkState.RUNNING && (channel != null || !conv.isChannel)
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                MeshField(
                    input, { if (it.toByteArray().size <= MAX_TEXT_BYTES) input = it }, tr("Message", "Mensagem"),
                    singleLine = false, supporting = "$bytes/$MAX_TEXT_BYTES",
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = canSend,
                colors = IconButtonDefaults.iconButtonColors(containerColor = Mesh.ACCENT, contentColor = Mesh.ON_ACCENT,
                    disabledContainerColor = Mesh.SURFACE_2, disabledContentColor = Mesh.MUTED),
                onClick = {
                    val text = input.trim()
                    if (conv.isChannel) channel?.let { MeshController.sendChannel(it, text) }
                    else MeshController.sendDirect(conv.prefixHex, text)
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
                        tr("Repeaters your radio heard passing this message on:", "Repetidoras que o seu rádio ouviu repassando esta mensagem:"),
                        size = 12,
                    )
                    m.repeaters.forEach { h ->
                        val name = MeshController.hopName(h)
                        Text(if (name == h) h else "$name ($h)", color = Mesh.TEXT, fontSize = 14.sp)
                    }
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

@Composable
fun Bubble(m: Message, showSender: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp, 18.dp, if (m.outgoing) 4.dp else 18.dp, if (m.outgoing) 18.dp else 4.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(if (m.outgoing) Mesh.ACCENT.copy(alpha = 0.18f) else Mesh.SURFACE)
                .border(1.dp, if (m.outgoing) Mesh.ACCENT.copy(alpha = 0.5f) else Mesh.OUTLINE, shape)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (showSender && !m.outgoing) {
                Text(m.sender, color = Mesh.ACCENT, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(m.text, color = Mesh.TEXT, fontSize = 15.sp)
            val meta = buildList {
                add(formatTime(m.timestamp))
                if (!m.outgoing) {
                    m.hops?.let { add(if (it == 0) tr("direct", "direto") else hopsCount(it)) } ?: add(tr("direct route", "rota direta"))
                    m.snr?.let { add("SNR %.1f".format(it)) }
                } else {
                    add(
                        when (m.status) {
                            MsgStatus.SENDING -> tr("sending…", "enviando…")
                            MsgStatus.SENT -> if (showSender) tr("✓ sent", "✓ enviado") else tr("✓ sent, waiting for confirmation", "✓ enviado, aguardando confirmação")
                            MsgStatus.DELIVERED -> tr("✓✓ delivered", "✓✓ entregue") + if (m.roundTripMs > 0) " (${m.roundTripMs / 1000.0}s)" else ""
                            MsgStatus.FAILED -> if (showSender) tr("✗ failed", "✗ falhou") else tr("✗ not confirmed — tap to resend", "✗ sem confirmação — toque para reenviar")
                            MsgStatus.RECEIVED -> ""
                        },
                    )
                    if (m.repeaters.isNotEmpty()) add(repeatersLabel(m.repeaters.size))
                }
            }
            val color = when {
                m.outgoing && m.status == MsgStatus.DELIVERED -> Mesh.GREEN
                m.outgoing && m.repeaters.isNotEmpty() -> Mesh.GREEN
                m.outgoing && m.status == MsgStatus.FAILED -> Mesh.RED
                else -> Mesh.MUTED
            }
            Text(meta.joinToString(" · "), color = color, fontSize = 11.sp)
        }
    }
}

/** "heard by 3 repeaters" */
fun repeatersLabel(n: Int): String =
    tr(if (n == 1) "heard by 1 repeater" else "heard by $n repeaters", if (n == 1) "ouvida por 1 repetidora" else "ouvida por $n repetidoras")
