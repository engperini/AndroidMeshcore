package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.MeshLink
import com.engperini.meshcore.core.Protocol

private enum class Sort(private val en: String, private val pt: String) {
    RECENT("Recent", "Recentes"), NAME("Name", "Nome"), HOPS("Hops", "Saltos");

    val label: String get() = tr(en, pt)
}

@Composable
fun ContactsScreen(nav: Nav) {
    val contacts by MeshController.contacts.collectAsState()
    val discovered by MeshController.discovered.collectAsState()
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<Int?>(null) }
    var sort by remember { mutableStateOf(Sort.RECENT) }
    var scanned by remember { mutableStateOf<MeshLink?>(null) }
    val scan = rememberQrScanner { link ->
        if (link == null) MeshController.notice(tr("Not a MeshCore QR code", "QR code não é do MeshCore")) else scanned = link
    }

    val filtered = contacts
        .filter { (type == null || it.type == type) && (query.isBlank() || it.name.contains(query, ignoreCase = true)) }
        .let { list ->
            when (sort) {
                Sort.RECENT -> list.sortedByDescending { it.lastAdvert }
                Sort.NAME -> list.sortedBy { it.name.lowercase() }
                Sort.HOPS -> list.sortedBy { Protocol.hops(it.outPathLen) ?: 99 }
            }
        }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { MeshField(query, { query = it }, tr("Search contacts", "Buscar contato")) }
                Pill("QR", style = PillStyle.OUTLINED, onClick = scan)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MeshChip(tr("All ${contacts.size}", "Todos ${contacts.size}"), type == null) { type = null }
                MeshChip("Chat", type == Protocol.ADV_TYPE_CHAT) { type = Protocol.ADV_TYPE_CHAT }
                MeshChip(tr("Repeaters", "Repetidores"), type == Protocol.ADV_TYPE_REPEATER) { type = Protocol.ADV_TYPE_REPEATER }
                MeshChip(tr("Rooms", "Salas"), type == Protocol.ADV_TYPE_ROOM) { type = Protocol.ADV_TYPE_ROOM }
                MeshChip(tr("Sensors", "Sensores"), type == Protocol.ADV_TYPE_SENSOR) { type = Protocol.ADV_TYPE_SENSOR }
                MeshChip("↕ ${sort.label}", false) { sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size] }
            }
        }
        if (discovered.isNotEmpty()) {
            item { SectionHeader(tr("Discovered (not saved)", "Descobertos (não salvos)")) }
            item {
                MeshCard {
                    discovered.forEach { c ->
                        ListRow(
                            title = c.name, subtitle = "${c.typeLabel} • ${ago(c.lastAdvert)}",
                            avatar = { MeshAvatar(c.name, typeColor(c.type)) },
                            onClick = { MeshController.addDiscovered(c) },
                            trailing = { Pill(tr("Add", "Adicionar"), style = PillStyle.TEXT) { MeshController.addDiscovered(c) } },
                        )
                    }
                }
            }
        }
        item { SectionHeader(tr("Contacts", "Contatos")) }
        if (filtered.isEmpty()) {
            item { Empty(tr("No contacts.\nNodes show up when they send an advert.\nSend yours from the ⋮ menu to be discovered.", "Nenhum contato.\nOs nós aparecem quando enviam um advert.\nEnvie o seu pelo menu ⋮ para ser descoberto.")) }
        }
        items(filtered, key = { it.keyHex }) { c ->
            ListRow(
                title = c.name.ifBlank { c.keyHex.take(12) },
                subtitle = "${c.typeLabel} • ${hopsLabel(c.outPathLen)} • ${ago(c.lastAdvert)}",
                avatar = { MeshAvatar(c.name, typeColor(c.type)) },
                onClick = {
                    if (c.type == Protocol.ADV_TYPE_REPEATER || c.type == Protocol.ADV_TYPE_SENSOR) nav.push(Route.ContactDetail(c.keyHex))
                    else nav.openChat(Conversation(com.engperini.meshcore.data.ConvKey.contact(c.pubKey.prefixHex()), c.name, false, prefixHex = c.pubKey.prefixHex()))
                },
                onLongClick = { nav.push(Route.ContactDetail(c.keyHex)) },
                trailing = { Muted(c.keyHex.take(4), size = 11) },
            )
        }
        item { Muted(tr("Long-press a contact for details, route, QR code and admin.", "Toque e segure um contato para ver detalhes, rota, QR code e administração."), Modifier.padding(8.dp), size = 12) }
    }
    scanned?.let { LinkDialog(it) { scanned = null } }
}
