package com.engperini.meshcore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.toHex
import com.engperini.meshcore.data.ConvKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A conversation the user can open: a group channel or a direct chat. */
data class Conversation(val key: String, val title: String, val isChannel: Boolean, val channelIndex: Int = -1, val prefixHex: String = "")

enum class Tab(val label: String) { CHATS("Conversas"), CONTACTS("Contatos"), CHANNELS("Canais"), SETTINGS("Config") }

@Composable
fun App(onConnect: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHATS) }
    var openConv by remember { mutableStateOf<Conversation?>(null) }

    val conv = openConv
    if (conv != null) {
        BackHandler { openConv = null }
        ChatScreen(conv, onBack = { openConv = null })
    } else {
        HomeScreen(tab, onTab = { tab = it }, onOpen = { openConv = it }, onConnect = onConnect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(tab: Tab, onTab: (Tab) -> Unit, onOpen: (Conversation) -> Unit, onConnect: () -> Unit) {
    val link by MeshController.link.collectAsState()
    val self by MeshController.self.collectAsState()
    val event by MeshController.events.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }

    LaunchedEffect(event) {
        event?.let {
            MeshController.consumeEvent()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(self?.name?.ifBlank { null } ?: "MeshCore USB", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        StatusLine(link.state, link.message)
                    }
                },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Enviar advert (flood)") },
                            enabled = link.state == LinkState.RUNNING,
                            onClick = { menu = false; MeshController.sendAdvert(true) },
                        )
                        DropdownMenuItem(
                            text = { Text("Enviar advert (zero-hop)") },
                            enabled = link.state == LinkState.RUNNING,
                            onClick = { menu = false; MeshController.sendAdvert(false) },
                        )
                        DropdownMenuItem(text = { Text("Reconectar rádio") }, onClick = { menu = false; onConnect() })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { onTab(t) },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.CHATS -> Icons.Default.Email
                                    Tab.CONTACTS -> Icons.Default.Person
                                    Tab.CHANNELS -> Icons.Default.Home
                                    Tab.SETTINGS -> Icons.Default.Settings
                                },
                                t.label,
                            )
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (link.state != LinkState.RUNNING && tab != Tab.SETTINGS) {
                ConnectPanel(link.state, link.message, onConnect)
            } else {
                when (tab) {
                    Tab.CHATS -> ConversationsList(onOpen)
                    Tab.CONTACTS -> ContactsScreen(onOpen)
                    Tab.CHANNELS -> ChannelsScreen(onOpen)
                    Tab.SETTINGS -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
fun StatusLine(state: LinkState, message: String) {
    val color = when (state) {
        LinkState.RUNNING -> Color(0xFF2EB67D)
        LinkState.STARTING -> Color(0xFFE0A000)
        LinkState.ERROR -> Color(0xFFD9534F)
        LinkState.NO_DEVICE -> Color.Gray
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(message, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun ConnectPanel(state: LinkState, message: String, onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (state == LinkState.STARTING) "Iniciando o rádio…" else "Rádio desconectado",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.padding(6.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(6.dp))
        Text(
            "1. Coloque a antena no MeshStick.\n2. Conecte-o ao celular com um cabo/adaptador USB OTG.\n" +
                "3. Aceite abrir o MeshCore USB e permitir o acesso ao dispositivo.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.padding(12.dp))
        if (state != LinkState.STARTING) Button(onClick = onConnect) { Text("Conectar") }
    }
}

@Composable
fun ConversationsList(onOpen: (Conversation) -> Unit) {
    val channels by MeshController.channels.collectAsState()
    val contacts by MeshController.contacts.collectAsState()
    val version by MeshController.messagesVersion.collectAsState()
    val summaries = remember(version) { MeshController.messages.summaries() }

    val convs = buildList {
        channels.filterNot { it.isEmpty }.forEach {
            add(Conversation(ConvKey.channel(it.secretHex), it.name, true, channelIndex = it.index))
        }
        // Direct chats with history, plus their contact names when known.
        summaries.keys.filter { it.startsWith("c:") }.forEach { key ->
            val prefix = key.removePrefix("c:")
            val name = contacts.firstOrNull { it.keyHex.startsWith(prefix) }?.name ?: prefix
            add(Conversation(key, name, false, prefixHex = prefix))
        }
    }.sortedByDescending { summaries[it.key]?.lastTime ?: 0L }

    if (convs.isEmpty()) {
        Empty("Nenhuma conversa ainda.\nAbra um canal ou um contato para começar.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(convs, key = { it.key }) { c ->
            val s = summaries[c.key]
            ConversationRow(
                title = (if (c.isChannel) "# " else "") + c.title,
                subtitle = s?.let { if (c.isChannel) "${it.lastSender}: ${it.lastText}" else it.lastText } ?: "Sem mensagens",
                time = s?.lastTime,
                unread = s?.unread ?: 0,
                onClick = { onOpen(c) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ConversationRow(title: String, subtitle: String, time: Long?, unread: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(title.removePrefix("# "))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (time != null) Text(formatTime(time), style = MaterialTheme.typography.labelSmall)
            if (unread > 0) Badge { Text("$unread") }
        }
    }
}

@Composable
fun Avatar(name: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase().ifBlank { "?" }, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Pill(text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

fun formatTime(epochSec: Long): String {
    val d = Date(epochSec * 1000)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let { it.format(Date()) == it.format(d) }
    return SimpleDateFormat(if (today) "HH:mm" else "dd/MM HH:mm", Locale.getDefault()).format(d)
}

fun ago(epochSec: Long): String {
    if (epochSec <= 0) return "nunca"
    val s = System.currentTimeMillis() / 1000 - epochSec
    return when {
        s < 60 -> "agora"
        s < 3600 -> "há ${s / 60} min"
        s < 86400 -> "há ${s / 3600} h"
        else -> "há ${s / 86400} d"
    }
}

fun ByteArray.prefixHex(): String = copyOf(6).toHex()
