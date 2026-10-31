package com.engperini.meshcore.ui

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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.engperini.meshcore.core.Contact
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.data.ConvKey

@Composable
fun ContactsScreen(onOpen: (Conversation) -> Unit) {
    val contacts by MeshController.contacts.collectAsState()
    val discovered by MeshController.discovered.collectAsState()
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<Int?>(null) }

    val filtered = contacts.filter {
        (type == null || it.type == type) && (query.isBlank() || it.name.contains(query, ignoreCase = true))
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Buscar contato") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        )
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Todos (${contacts.size})") })
            FilterChip(selected = type == Protocol.ADV_TYPE_CHAT, onClick = { type = Protocol.ADV_TYPE_CHAT }, label = { Text("Chat") })
            FilterChip(selected = type == Protocol.ADV_TYPE_REPEATER, onClick = { type = Protocol.ADV_TYPE_REPEATER }, label = { Text("Repetidores") })
            FilterChip(selected = type == Protocol.ADV_TYPE_ROOM, onClick = { type = Protocol.ADV_TYPE_ROOM }, label = { Text("Salas") })
        }
        discovered.forEach { c ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Descoberto: ${c.name}", fontWeight = FontWeight.SemiBold)
                    Text(c.typeLabel, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { MeshController.addDiscovered(c) }) { Text("Adicionar") }
            }
        }
        if (filtered.isEmpty()) {
            Empty("Nenhum contato.\nOs nós aparecem aqui quando enviam um advert.\nEnvie o seu pelo menu ⋮ para ser descoberto.")
            return
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(filtered, key = { it.keyHex }) { c ->
                ContactRow(c) {
                    val prefix = c.pubKey.prefixHex()
                    onOpen(Conversation(ConvKey.contact(prefix), c.name, false, prefixHex = prefix))
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactRow(c: Contact, onOpen: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(c.name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name.ifBlank { c.keyHex.take(12) }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val route = Protocol.hops(c.outPathLen)?.let { "$it salto(s)" } ?: "flood"
                Text(
                    "${c.typeLabel} · $route · visto ${ago(c.lastAdvert)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Pill(c.keyHex.take(4))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Mensagem direta") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Redefinir rota") }, onClick = { menu = false; MeshController.resetPath(c) })
            DropdownMenuItem(text = { Text("Compartilhar (zero-hop)") }, onClick = { menu = false; MeshController.shareContact(c) })
            DropdownMenuItem(text = { Text("Remover contato") }, onClick = { menu = false; MeshController.removeContact(c) })
        }
    }
}
