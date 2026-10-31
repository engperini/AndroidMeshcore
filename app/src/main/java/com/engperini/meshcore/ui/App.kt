package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.MeshLink
import com.engperini.meshcore.core.toHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A conversation the user can open: a group channel or a direct chat (also rooms). */
data class Conversation(val key: String, val title: String, val isChannel: Boolean, val channelIndex: Int = -1, val prefixHex: String = "")

enum class Tab(private val en: String, private val pt: String, val icon: ImageVector) {
    CHATS("Chats", "Conversas", Icons.Default.Email),
    CONTACTS("Contacts", tr("Contacts", "Contatos"), Icons.Default.Person),
    MAP("Map", "Mapa", Icons.Default.LocationOn),
    NETWORK("Network", "Rede", Icons.Default.Info),
    SETTINGS("Settings", "Config", Icons.Default.Settings);

    val label: String get() = tr(en, pt)
}

/** Screens above the tabs. */
sealed class Route {
    data class Chat(val conv: Conversation) : Route()
    data class ContactDetail(val keyHex: String) : Route()
    data class Admin(val keyHex: String) : Route()
}

class Nav {
    val stack = mutableStateListOf<Route>()
    fun push(r: Route) = stack.add(r)
    fun pop() = stack.removeLastOrNull()
    fun openChat(c: Conversation) = push(Route.Chat(c))
}

@Composable
fun App(onConnect: () -> Unit, incomingLink: MeshLink?, onLinkHandled: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.CHATS) }
    val nav = remember { Nav() }
    val event by MeshController.events.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(event) {
        event?.let {
            MeshController.consumeEvent()
            snackbar.showSnackbar(it)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (val top = nav.stack.lastOrNull()) {
                null -> Home(tab, { tab = it }, nav, onConnect)
                is Route.Chat -> ChatScreen(top.conv, nav)
                is Route.ContactDetail -> ContactScreen(top.keyHex, nav)
                is Route.Admin -> AdminScreen(top.keyHex, nav)
            }
            if (nav.stack.isNotEmpty()) BackHandler { nav.pop() }
        }
    }

    if (incomingLink != null) LinkDialog(incomingLink, onLinkHandled)
}

@Composable
internal fun Home(tab: Tab, onTab: (Tab) -> Unit, nav: Nav, onConnect: () -> Unit) {
    val link by MeshController.link.collectAsState()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Header(onConnect)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val needsRadio = tab != Tab.SETTINGS && tab != Tab.MAP
            if (link.state != LinkState.RUNNING && needsRadio) {
                ConnectPanel(link.state, link.message, onConnect)
            } else {
                when (tab) {
                    Tab.CHATS -> ChatsScreen(nav)
                    Tab.CONTACTS -> ContactsScreen(nav)
                    Tab.MAP -> MapScreen(nav)
                    Tab.NETWORK -> NetworkScreen()
                    Tab.SETTINGS -> SettingsScreen(onConnect)
                }
            }
        }
        BottomBar(tab, onTab)
    }
}

/** MeshSDR-style header: logo, name, summary and a status chip. */
@Composable
fun Header(onConnect: () -> Unit) {
    val link by MeshController.link.collectAsState()
    val self by MeshController.self.collectAsState()
    val device by MeshController.device.collectAsState()
    var menu by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Logo(46)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("MeshCore USB", color = Mesh.TEXT, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            val summary = self?.let { s ->
                val bytes = device?.let { " • " + bytesLabel(it.pathHashMode + 1) } ?: ""
                "${s.name} • ${"%.3f".format(Locale.US, s.freqKhz / 1000.0)} MHz$bytes"
            } ?: tr("MeshCore on your phone via a USB MeshStick", "MeshCore no celular via USB MeshStick")
            Muted(summary, size = 12, maxLines = 1)
        }
        val (label, color) = when (link.state) {
            LinkState.RUNNING -> tr("On", "Ativo") to Mesh.GREEN
            LinkState.STARTING -> tr("Starting", "Iniciando") to Mesh.AMBER
            LinkState.ERROR -> tr("Error", "Erro") to Mesh.RED
            LinkState.NO_DEVICE -> tr("No radio", "Sem rádio") to Mesh.MUTED
        }
        Box(Modifier.clickable { if (link.state != LinkState.RUNNING) onConnect() }) { StatusChip(label, color) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, tr("Menu", "Menu"), tint = Mesh.MUTED) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(tr("Send advert (flood)", "Enviar advert (flood)")) }, enabled = link.state == LinkState.RUNNING,
                    onClick = { menu = false; MeshController.sendAdvert(true) },
                )
                DropdownMenuItem(
                    text = { Text(tr("Send advert (zero-hop)", "Enviar advert (zero-hop)")) }, enabled = link.state == LinkState.RUNNING,
                    onClick = { menu = false; MeshController.sendAdvert(false) },
                )
                DropdownMenuItem(text = { Text(tr("Reconnect radio", "Reconectar rádio")) }, onClick = { menu = false; onConnect() })
            }
        }
    }
}

@Composable
private fun BottomBar(tab: Tab, onTab: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .clip(RoundedCornerShape(22.dp))
            .background(Mesh.SURFACE)
            .border(1.dp, Mesh.OUTLINE, RoundedCornerShape(22.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Tab.entries.forEach { t ->
            val sel = t == tab
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (sel) Mesh.ACCENT.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onTab(t) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, tint = if (sel) Mesh.ACCENT else Mesh.MUTED, modifier = Modifier.size(22.dp))
                Text(t.label, color = if (sel) Mesh.ACCENT else Mesh.MUTED, fontSize = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
fun ConnectPanel(state: LinkState, message: String, onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
        MeshCard {
            CardTitle(if (state == LinkState.STARTING) tr("Starting the radio…", "Iniciando o rádio…") else tr("Radio disconnected", "Rádio desconectado"))
            Muted(message)
            Spacer(Modifier.height(4.dp))
            Muted(
                tr(
                    "1. Attach the antenna to the MeshStick.\n2. Plug it into the phone with a USB OTG cable or adapter.\n" +
                        "3. Allow MeshCore USB to open and to access the device.",
                    "1. Coloque a antena no MeshStick.\n2. Conecte-o ao celular com um cabo ou adaptador USB OTG.\n" +
                        "3. Aceite abrir o MeshCore USB e permitir o acesso ao dispositivo.",
                ),
            )
            if (state != LinkState.STARTING) Pill(tr("Connect", "Conectar"), Modifier.fillMaxWidth(), PillStyle.PRIMARY, onClick = onConnect)
        }
    }
}

/** Confirms a meshcore:// link (QR code, camera, another app) before adding it. */
@Composable
fun LinkDialog(link: MeshLink, onDone: () -> Unit) {
    val (title, body) = when (link) {
        is MeshLink.AddContact -> tr("Add contact?", "Adicionar contato?") to "${link.name}\n${tr("Key", "Chave")} ${link.pubKey.copyOf(8).toHex()}…"
        is MeshLink.AddChannel -> tr("Add channel?", "Adicionar canal?") to "${link.name}\n${tr("Key", "Chave")} ${link.secret.toHex()}"
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = Mesh.SURFACE_2,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Pill(tr("Add", "Adicionar"), style = PillStyle.PRIMARY) { MeshController.importLink(link); onDone() } },
        dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT, onClick = onDone) },
    )
}

/** Standard list row: avatar, title, subtitle, trailing column. */
@Composable
fun ListRow(
    title: String,
    subtitle: String,
    avatar: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickableCompat(onClick, onLongClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        avatar()
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Mesh.TEXT, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(subtitle, size = 13, maxLines = 1)
        }
        trailing()
    }
}

fun formatTime(epochSec: Long): String {
    val d = Date(epochSec * 1000)
    val today = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let { it.format(Date()) == it.format(d) }
    return SimpleDateFormat(if (today) "HH:mm" else "dd/MM HH:mm", Locale.getDefault()).format(d)
}

fun ago(epochSec: Long): String {
    if (epochSec <= 0) return tr("never", "nunca")
    val s = System.currentTimeMillis() / 1000 - epochSec
    return when {
        s < 60 -> tr("now", "agora")
        s < 3600 -> tr("${s / 60} min ago", "há ${s / 60} min")
        s < 86400 -> tr("${s / 3600} h ago", "há ${s / 3600} h")
        else -> tr("${s / 86400} d ago", "há ${s / 86400} d")
    }
}

fun uptime(secs: Long): String = when {
    secs < 3600 -> "${secs / 60} min"
    secs < 86400 -> "${secs / 3600} h ${(secs % 3600) / 60} min"
    else -> "${secs / 86400} d ${(secs % 86400) / 3600} h"
}

fun ByteArray.prefixHex(): String = copyOf(6).toHex()

fun hopsLabel(pathLen: Int): String = when (val h = com.engperini.meshcore.core.Protocol.hops(pathLen)) {
    null -> "flood"
    0 -> tr("direct", "direto")
    else -> hopsCount(h)
}

fun hopsCount(h: Int): String = tr(if (h == 1) "1 hop" else "$h hops", "$h salto(s)")

fun bytesLabel(b: Int): String = tr(if (b == 1) "1 byte" else "$b bytes", if (b == 1) "1 byte" else "$b bytes")
