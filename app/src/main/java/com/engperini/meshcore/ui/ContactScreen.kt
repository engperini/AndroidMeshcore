package com.engperini.meshcore.ui

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
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = Mesh.TEXT) }
            Text(c?.name ?: "Contato", color = Mesh.TEXT, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (c == null) {
            Empty("Contato não está mais na lista.")
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
                        Muted("${c.typeLabel} • visto ${ago(c.lastAdvert)}")
                    }
                }
                Text(c.keyHex, color = Mesh.MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (c.type != Protocol.ADV_TYPE_REPEATER && c.type != Protocol.ADV_TYPE_SENSOR) {
                        Pill("Mensagem", Modifier.weight(1f), PillStyle.PRIMARY) {
                            nav.openChat(Conversation(ConvKey.contact(prefix), c.name, false, prefixHex = prefix))
                        }
                    }
                    if (isAdminType) {
                        Pill(if (c.type == Protocol.ADV_TYPE_ROOM) "Login / admin" else "Administrar", Modifier.weight(1f), PillStyle.PRIMARY) {
                            nav.push(Route.Admin(c.keyHex))
                        }
                    }
                }
            }

            MeshCard {
                CardTitle("Rota")
                val hops = Protocol.pathHops(c.outPathLen, c.raw.copyOfRange(minOf(36, c.raw.size), minOf(100, c.raw.size)))
                KeyValue("Saída", Protocol.hops(c.outPathLen)?.let { if (it == 0) "direto (0 saltos)" else "$it salto(s)" } ?: "flood (desconhecida)")
                if (hops.isNotEmpty()) KeyValue("Repetidores", hops.joinToString(" › "))
                paths[prefix]?.let { pd ->
                    Spacer(Modifier.width(1.dp))
                    Text("Rota descoberta", color = Mesh.ACCENT, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    KeyValue("Ida", Protocol.pathHops(pd.outLen, pd.outPath).ifEmpty { listOf("direto") }.joinToString(" › "))
                    KeyValue("Volta", Protocol.pathHops(pd.inLen, pd.inPath).ifEmpty { listOf("direto") }.joinToString(" › "))
                    Pill("Usar esta rota", Modifier.fillMaxWidth(), PillStyle.ACTIVE) { MeshController.usePath(c, pd) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Descobrir rota", Modifier.weight(1f)) { MeshController.discoverPath(c) }
                    Pill("Redefinir", Modifier.weight(1f), PillStyle.OUTLINED) { MeshController.resetPath(c) }
                }
                Muted("Descobrir rota envia um pedido por flood e mostra os repetidores de ida e volta.", size = 12)
            }

            if (c.lat != 0.0 || c.lon != 0.0) {
                MeshCard {
                    CardTitle("Localização")
                    KeyValue("Lat, lon", "%.5f, %.5f".format(Locale.US, c.lat, c.lon))
                    Muted("Aparece na aba Mapa.", size = 12)
                }
            }

            MeshCard {
                CardTitle("Compartilhar")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("QR code", Modifier.weight(1f)) { qr = true }
                    Pill("Zero-hop", Modifier.weight(1f)) { MeshController.shareContact(c) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Copiar chave", Modifier.weight(1f), PillStyle.OUTLINED) { clipboard.setText(AnnotatedString(c.keyHex)) }
                    Pill("Remover", Modifier.weight(1f), PillStyle.OUTLINED) { confirmRemove = true }
                }
            }
            Spacer(Modifier.width(1.dp))
        }
    }

    if (qr && c != null) QrDialog(c.name, "${c.typeLabel} • escaneie no MeshCore", Protocol.contactUri(c.name, c.pubKey, c.type)) { qr = false }
    if (confirmRemove && c != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            containerColor = Mesh.SURFACE_2,
            title = { Text("Remover ${c.name}?") },
            text = { Text("O contato volta a aparecer quando enviar um novo advert.") },
            confirmButton = { Pill("Remover", style = PillStyle.PRIMARY) { confirmRemove = false; MeshController.removeContact(c); nav.pop() } },
            dismissButton = { Pill("Cancelar", style = PillStyle.TEXT) { confirmRemove = false } },
        )
    }
}
