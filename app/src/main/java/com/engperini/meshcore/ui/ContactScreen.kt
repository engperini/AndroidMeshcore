package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.data.ConvKey
import java.util.Locale

@Composable
fun ContactScreen(keyHex: String, nav: Nav) {
    val contacts by MeshController.contacts.collectAsState()
    val paths by MeshController.paths.collectAsState()
    val c = contacts.firstOrNull { it.keyHex == keyHex }
    val clipboard = LocalClipboardManager.current
    var qr by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Voltar"), tint = Mesh.TEXT) }
            Text(c?.name ?: tr("Contact", "Contato"), color = Mesh.TEXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (c == null) {
            Empty(tr("This contact is no longer in the list.", "Contato não está mais na lista."))
            return
        }
        val prefix = c.pubKey.prefixHex()
        val isAdminType = c.type == Protocol.ADV_TYPE_REPEATER || c.type == Protocol.ADV_TYPE_ROOM || c.type == Protocol.ADV_TYPE_SENSOR
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MeshCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeshAvatar(c.name, typeColor(c.type), 52)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Mesh.TEXT, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Muted("${c.typeLabel} • ${tr("seen", "visto")} ${ago(c.lastAdvert)}")
                    }
                }
                Text(c.keyHex, color = Mesh.MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.type != Protocol.ADV_TYPE_REPEATER && c.type != Protocol.ADV_TYPE_SENSOR) {
                        Pill(tr("Message", "Mensagem"), Modifier.weight(1f), PillStyle.PRIMARY) {
                            nav.openChat(Conversation(ConvKey.contact(prefix), c.name, false, prefixHex = prefix))
                        }
                    }
                    if (isAdminType) {
                        Pill(if (c.type == Protocol.ADV_TYPE_ROOM) "Login / admin" else tr("Manage", "Administrar"), Modifier.weight(1f), PillStyle.PRIMARY) {
                            nav.push(Route.Admin(c.keyHex))
                        }
                    }
                }
            }

            MeshCard {
                CardTitle(tr("Route", "Rota"))
                val hops = Protocol.pathHops(c.outPathLen, c.raw.copyOfRange(minOf(36, c.raw.size), minOf(100, c.raw.size)))
                KeyValue(tr("Outbound", "Saída"), Protocol.hops(c.outPathLen)?.let { if (it == 0) tr("direct (0 hops)", "direto (0 saltos)") else hopsCount(it) } ?: tr("flood (unknown)", "flood (desconhecida)"))
                if (hops.isNotEmpty()) KeyValue(tr("Repeaters", "Repetidores"), hops.joinToString(" › "))
                paths[prefix]?.let { pd ->
                    Spacer(Modifier.width(1.dp))
                    Text(tr("Discovered route", "Rota descoberta"), color = Mesh.ACCENT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    KeyValue(tr("Out", "Ida"), Protocol.pathHops(pd.outLen, pd.outPath).ifEmpty { listOf(tr("direct", "direto")) }.joinToString(" › "))
                    KeyValue(tr("Return", "Volta"), Protocol.pathHops(pd.inLen, pd.inPath).ifEmpty { listOf(tr("direct", "direto")) }.joinToString(" › "))
                    Pill(tr("Use this route", "Usar esta rota"), Modifier.fillMaxWidth(), PillStyle.ACTIVE) { MeshController.usePath(c, pd) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("Discover route", "Descobrir rota"), Modifier.weight(1f)) { MeshController.discoverPath(c) }
                    Pill(tr("Reset", "Redefinir"), Modifier.weight(1f), PillStyle.OUTLINED) { MeshController.resetPath(c) }
                }
                Muted(tr("Route discovery sends a flood request and shows the repeaters out and back.", "Descobrir rota envia um pedido por flood e mostra os repetidores de ida e volta."), size = 12)
            }

            if (c.lat != 0.0 || c.lon != 0.0) {
                MeshCard {
                    CardTitle(tr("Location", "Localização"))
                    KeyValue(tr("Lat, lon", "Lat, lon"), "%.5f, %.5f".format(Locale.US, c.lat, c.lon))
                    Muted(tr("Shown in the Map tab.", "Aparece na aba Mapa."), size = 12)
                }
            }

            MeshCard {
                CardTitle(tr("Share", "Compartilhar"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("QR code", "QR code"), Modifier.weight(1f)) { qr = true }
                    Pill(tr("Zero-hop", "Zero-hop"), Modifier.weight(1f)) { MeshController.shareContact(c) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(tr("Copy key", "Copiar chave"), Modifier.weight(1f), PillStyle.OUTLINED) { clipboard.setText(AnnotatedString(c.keyHex)) }
                    Pill(tr("Remove", "Remover"), Modifier.weight(1f), PillStyle.OUTLINED) { confirmRemove = true }
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }

    if (qr && c != null) QrDialog(c.name, "${c.typeLabel} • ${tr("scan in MeshCore", "escaneie no MeshCore")}", Protocol.contactUri(c.name, c.pubKey, c.type)) { qr = false }
    if (confirmRemove && c != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Mesh.SURFACE_2,
            title = { Text(tr("Remove ${c.name}?", "Remover ${c.name}?")) },
            text = { Text(tr("The contact shows up again when it sends a new advert.", "O contato volta a aparecer quando enviar um novo advert.")) },
            confirmButton = { Pill(tr("Remove", "Remover"), style = PillStyle.PRIMARY) { confirmRemove = false; MeshController.removeContact(c); nav.pop() } },
            dismissButton = { Pill(tr("Cancel", "Cancelar"), style = PillStyle.TEXT) { confirmRemove = false } },
        )
    }
}
