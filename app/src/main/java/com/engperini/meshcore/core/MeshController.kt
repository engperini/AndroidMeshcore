package com.engperini.meshcore.core

import android.content.Context
import android.content.SharedPreferences
import com.engperini.meshcore.data.ConvKey
import com.engperini.meshcore.data.MessageStore
import com.engperini.meshcore.data.MsgStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class LinkState { NO_DEVICE, STARTING, RUNNING, ERROR }

data class LinkStatus(val state: LinkState, val message: String)

data class PacketLogEntry(val time: Long, val snr: Float, val rssi: Int, val len: Int, val route: String, val type: String)

data class IncomingNotice(val conv: String, val title: String, val text: String)

/**
 * App-side companion client: talks to the native node with companion frames,
 * keeps contacts/channels in memory and the message history in [MessageStore].
 */
object MeshController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: MessageStore
    private lateinit var prefs: SharedPreferences

    private val _link = MutableStateFlow(LinkStatus(LinkState.NO_DEVICE, "Conecte o MeshStick via USB OTG"))
    val link: StateFlow<LinkStatus> = _link.asStateFlow()

    private val _self = MutableStateFlow<SelfInfo?>(null)
    val self: StateFlow<SelfInfo?> = _self.asStateFlow()

    private val _device = MutableStateFlow<DeviceInfo?>(null)
    val device: StateFlow<DeviceInfo?> = _device.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /** Nodes heard while "add contacts manually" is on: reported by the node but not saved yet. */
    private val _discovered = MutableStateFlow<List<Contact>>(emptyList())
    val discovered: StateFlow<List<Contact>> = _discovered.asStateFlow()

    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    /** Bumped whenever stored messages change, so screens reload. */
    private val _messagesVersion = MutableStateFlow(0L)
    val messagesVersion: StateFlow<Long> = _messagesVersion.asStateFlow()

    private val _packetLog = MutableStateFlow<List<PacketLogEntry>>(emptyList())
    val packetLog: StateFlow<List<PacketLogEntry>> = _packetLog.asStateFlow()

    private val _diag = MutableStateFlow("{}")
    val diag: StateFlow<String> = _diag.asStateFlow()

    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events.asStateFlow()

    @Volatile var activeConversation: String? = null
    var onIncoming: ((IncomingNotice) -> Unit)? = null

    val messages: MessageStore get() = store

    // ---- request/response plumbing ----

    private val cmdMutex = Mutex()
    private val pendingLock = Any()
    private var pending: CompletableDeferred<List<ByteArray>>? = null
    private var pendingFrames = mutableListOf<ByteArray>()
    private var pendingIsLast: (ByteArray) -> Boolean = { true }
    private val pushes = KChannel<ByteArray>(KChannel.UNLIMITED)
    private val syncMutex = Mutex()
    private var contactsRefreshJob: Job? = null

    fun init(context: Context) {
        if (::store.isInitialized) return
        store = MessageStore(context.applicationContext)
        prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        NativeMesh.frameListener = ::onFrame
        NativeMesh.statusListener = ::onStatus
        scope.launch { for (f in pushes) handlePush(f) }
    }

    private fun onFrame(frame: ByteArray) {
        if (frame.isEmpty()) return
        if (Protocol.isPush(frame)) {
            pushes.trySend(frame)
            return
        }
        synchronized(pendingLock) {
            val d = pending ?: return
            pendingFrames.add(frame)
            if (pendingIsLast(frame)) {
                pending = null
                d.complete(pendingFrames)
            }
        }
    }

    private suspend fun request(
        frame: ByteArray, timeoutMs: Long = 5000, isLast: (ByteArray) -> Boolean = { true },
    ): List<ByteArray>? = cmdMutex.withLock {
        if (_link.value.state != LinkState.RUNNING) return@withLock null
        val d = CompletableDeferred<List<ByteArray>>()
        synchronized(pendingLock) {
            pendingFrames = mutableListOf()
            pendingIsLast = isLast
            pending = d
        }
        NativeMesh.nativeSendFrame(frame)
        val result = withTimeoutOrNull(timeoutMs) { d.await() }
        synchronized(pendingLock) { if (pending === d) pending = null }
        result
    }

    private suspend fun requestOne(frame: ByteArray, timeoutMs: Long = 5000): ByteArray? =
        request(frame, timeoutMs)?.firstOrNull()

    private fun ok(resp: ByteArray?) = resp != null && resp[0].toInt() == Protocol.RESP_OK

    private fun notice(text: String) {
        _events.value = text
    }

    fun consumeEvent() {
        _events.value = null
    }

    // ---- lifecycle ----

    private fun onStatus(status: Int, message: String) {
        when (status) {
            NativeMesh.STATUS_STARTING -> _link.value = LinkStatus(LinkState.STARTING, message)
            NativeMesh.STATUS_RUNNING -> {
                _link.value = LinkStatus(LinkState.RUNNING, message)
                scope.launch { handshake() }
            }
            NativeMesh.STATUS_ERROR -> _link.value = LinkStatus(LinkState.ERROR, message)
            else -> _link.value = LinkStatus(LinkState.NO_DEVICE, "Conecte o MeshStick via USB OTG")
        }
    }

    fun setNoDevice(message: String) {
        _link.value = LinkStatus(LinkState.NO_DEVICE, message)
    }

    private suspend fun handshake() {
        requestOne(Protocol.deviceQuery())?.let { Protocol.parseDeviceInfo(it) }?.let { _device.value = it }
        refreshSelf()
        refreshContacts()
        refreshChannels()
        applyAutoAdvert()
        syncMessages()
        refreshDiag()
    }

    suspend fun refreshSelf() {
        requestOne(Protocol.appStart("AndroidMeshcore"))?.let { Protocol.parseSelfInfo(it) }?.let { _self.value = it }
    }

    suspend fun refreshContacts() {
        val frames = request(Protocol.getContacts(), 15000) {
            val c = it[0].toInt()
            c == Protocol.RESP_END_OF_CONTACTS || c == Protocol.RESP_ERR
        } ?: return
        _contacts.value = frames.filter { it[0].toInt() == Protocol.RESP_CONTACT }
            .mapNotNull { Protocol.parseContact(it) }
            .sortedByDescending { it.lastAdvert }
    }

    private fun scheduleContactsRefresh() {
        contactsRefreshJob?.cancel()
        contactsRefreshJob = scope.launch {
            delay(1500)
            refreshContacts()
        }
    }

    suspend fun refreshChannels() {
        val max = _device.value?.maxChannels?.takeIf { it > 0 } ?: 20
        val list = mutableListOf<Channel>()
        for (i in 0 until max) {
            val resp = requestOne(Protocol.getChannel(i)) ?: break
            if (resp[0].toInt() != Protocol.RESP_CHANNEL_INFO) break
            Protocol.parseChannel(resp)?.let { list.add(it) }
        }
        _channels.value = list
    }

    suspend fun refreshDiag() {
        if (_link.value.state == LinkState.RUNNING) _diag.value = NativeMesh.nativeRadioDiag()
    }

    // ---- incoming ----

    private suspend fun handlePush(f: ByteArray) {
        when (f[0].toInt() and 0xFF) {
            Protocol.PUSH_MSG_WAITING -> scope.launch { syncMessages() }
            Protocol.PUSH_SEND_CONFIRMED -> {
                if (f.size >= 9) {
                    val b = ByteBuffer.wrap(f).order(ByteOrder.LITTLE_ENDIAN)
                    val ack = b.getInt(1).toLong() and 0xFFFFFFFFL
                    val rtt = b.getInt(5).toLong() and 0xFFFFFFFFL
                    if (store.confirmAck(ack, rtt)) bump()
                }
            }
            Protocol.PUSH_NEW_ADVERT -> {
                Protocol.parseContact(f)?.let { c ->
                    _discovered.update { list -> listOf(c) + list.filterNot { it.pubKey.contentEquals(c.pubKey) } }
                    notice("Nó descoberto: ${c.name} (adicione em Contatos)")
                }
            }
            // Auto-added contacts arrive as PUSH_ADVERT (new or updated): reload the list.
            Protocol.PUSH_ADVERT, Protocol.PUSH_PATH_UPDATED, Protocol.PUSH_CONTACT_DELETED -> scheduleContactsRefresh()
            Protocol.PUSH_CONTACTS_FULL -> notice("Lista de contatos cheia")
            Protocol.PUSH_LOG_RX_DATA -> logRx(f)
        }
    }

    private fun logRx(f: ByteArray) {
        if (f.size < 4) return
        val header = f[3].toInt() and 0xFF
        val route = when (header and 0x03) {
            0 -> "T-FLOOD"; 1 -> "FLOOD"; 2 -> "DIRECT"; else -> "T-DIRECT"
        }
        val type = when ((header shr 2) and 0x0F) {
            0 -> "REQ"; 1 -> "RESPONSE"; 2 -> "TXT_MSG"; 3 -> "ACK"; 4 -> "ADVERT"; 5 -> "GRP_TXT"
            6 -> "GRP_DATA"; 7 -> "ANON_REQ"; 8 -> "PATH"; 9 -> "TRACE"; 10 -> "MULTIPART"; 11 -> "CONTROL"
            15 -> "RAW"; else -> "?"
        }
        val entry = PacketLogEntry(System.currentTimeMillis(), f[1] / 4.0f, f[2].toInt(), f.size - 3, route, type)
        _packetLog.update { (listOf(entry) + it).take(200) }
    }

    suspend fun syncMessages() = syncMutex.withLock {
        while (true) {
            val f = requestOne(Protocol.syncNextMessage()) ?: break
            val code = f[0].toInt()
            if (code == Protocol.RESP_NO_MORE_MESSAGES) break
            if (code in listOf(
                    Protocol.RESP_CONTACT_MSG_RECV, Protocol.RESP_CONTACT_MSG_RECV_V3,
                    Protocol.RESP_CHANNEL_MSG_RECV, Protocol.RESP_CHANNEL_MSG_RECV_V3,
                )
            ) {
                Protocol.parseIncoming(f)?.let { storeIncoming(it) }
            }
        }
    }

    private fun storeIncoming(m: Incoming) {
        val conv: String
        val sender: String
        val text: String
        val title: String
        if (m.channelIdx != null) {
            val ch = _channels.value.firstOrNull { it.index == m.channelIdx }
            conv = ConvKey.channel(ch?.secretHex ?: "idx${m.channelIdx}")
            // Group texts carry "sender: message".
            val sep = m.text.indexOf(": ")
            sender = if (sep > 0) m.text.substring(0, sep) else "?"
            text = if (sep > 0) m.text.substring(sep + 2) else m.text
            title = ch?.name ?: "Canal ${m.channelIdx}"
        } else {
            val prefix = m.senderPrefix!!
            conv = ConvKey.contact(prefix.toHex())
            sender = contactByPrefix(prefix.toHex())?.name ?: prefix.toHex()
            text = m.text
            title = sender
        }
        if (store.isDuplicate(conv, sender, text, m.timestamp)) return
        val reading = activeConversation == conv
        store.insert(
            conv, outgoing = false, sender = sender, text = text, timestamp = m.timestamp,
            status = MsgStatus.RECEIVED, snr = m.snr, hops = Protocol.hops(m.pathLen), unread = !reading,
        )
        bump()
        if (!reading) {
            onIncoming?.invoke(IncomingNotice(conv, title, if (m.channelIdx != null) "$sender: $text" else text))
        }
    }

    private fun bump() {
        _messagesVersion.update { it + 1 }
    }

    fun contactByPrefix(prefixHex: String): Contact? = _contacts.value.firstOrNull { it.keyHex.startsWith(prefixHex) }

    fun channelBySecret(secretHex: String): Channel? = _channels.value.firstOrNull { it.secretHex == secretHex }

    fun markRead(conv: String) {
        store.markRead(conv)
        bump()
    }

    fun clearConversation(conv: String) {
        store.clear(conv)
        bump()
    }

    // ---- outgoing ----

    private fun now() = System.currentTimeMillis() / 1000

    fun sendDirect(prefixHex: String, text: String) {
        val id = store.insert(ConvKey.contact(prefixHex), true, _self.value?.name ?: "eu", text, now(), MsgStatus.SENDING)
        bump()
        scope.launch { transmitDirect(id, prefixHex, text, now(), attempt = 0) }
    }

    fun retryDirect(id: Long, prefixHex: String, text: String, timestamp: Long) {
        store.setStatus(id, MsgStatus.SENDING)
        bump()
        scope.launch { transmitDirect(id, prefixHex, text, timestamp, attempt = 1) }
    }

    private suspend fun transmitDirect(id: Long, prefixHex: String, text: String, timestamp: Long, attempt: Int) {
        val key = prefixHex.hexToBytes() ?: return
        val resp = requestOne(Protocol.sendText(key, text, timestamp, attempt))
        if (resp == null || resp[0].toInt() != Protocol.RESP_SENT || resp.size < 10) {
            store.setStatus(id, MsgStatus.FAILED)
            bump()
            if (resp?.get(0)?.toInt() == Protocol.RESP_ERR) notice("Contato não encontrado no nó")
            return
        }
        val b = ByteBuffer.wrap(resp).order(ByteOrder.LITTLE_ENDIAN)
        val ack = b.getInt(2).toLong() and 0xFFFFFFFFL
        val timeout = b.getInt(6).toLong() and 0xFFFFFFFFL
        store.setSent(id, ack, MsgStatus.SENT)
        bump()
        delay(maxOf(timeout, 5000L) + 3000L)
        store.failIfNotDelivered(id)
        bump()
    }

    fun sendChannel(channel: Channel, text: String) {
        val id = store.insert(ConvKey.channel(channel.secretHex), true, _self.value?.name ?: "eu", text, now(), MsgStatus.SENDING)
        bump()
        scope.launch {
            val ok = ok(requestOne(Protocol.sendChannelText(channel.index, text, now())))
            store.setStatus(id, if (ok) MsgStatus.SENT else MsgStatus.FAILED)
            bump()
        }
    }

    fun sendAdvert(flood: Boolean) = scope.launch {
        notice(if (ok(requestOne(Protocol.sendSelfAdvert(flood)))) {
            if (flood) "Advert enviado (flood)" else "Advert enviado (zero-hop)"
        } else "Falha ao enviar advert")
    }

    fun setName(name: String) = scope.launch {
        if (ok(requestOne(Protocol.setAdvertName(name.trim())))) refreshSelf() else notice("Falha ao salvar nome")
    }

    fun setRadio(freqKhz: Int, bwHz: Int, sf: Int, cr: Int, txDbm: Int, repeat: Boolean) = scope.launch {
        val a = ok(requestOne(Protocol.setRadioParams(freqKhz, bwHz, sf, cr, repeat)))
        val b = ok(requestOne(Protocol.setTxPower(txDbm)))
        notice(if (a && b) "Parâmetros de rádio aplicados" else "Parâmetros inválidos")
        refreshSelf()
        requestOne(Protocol.deviceQuery())?.let { Protocol.parseDeviceInfo(it) }?.let { _device.value = it }
        delay(500)
        refreshDiag()
    }

    fun setPathHashMode(mode: Int) = scope.launch {
        if (ok(requestOne(Protocol.setPathHashMode(mode)))) {
            requestOne(Protocol.deviceQuery())?.let { Protocol.parseDeviceInfo(it) }?.let { _device.value = it }
        }
    }

    fun setLocation(lat: Double, lon: Double, share: Boolean) = scope.launch {
        val s = _self.value ?: return@launch
        requestOne(Protocol.setLatLon(lat, lon))
        requestOne(
            Protocol.setOtherParams(s.manualAddContacts, s.telemetryModes, if (share) 1 else 0, s.multiAcks),
        )
        refreshSelf()
        notice("Localização salva")
    }

    fun setManualAdd(manual: Boolean) = scope.launch {
        val s = _self.value ?: return@launch
        requestOne(Protocol.setOtherParams(manual, s.telemetryModes, s.advertLocPolicy, s.multiAcks))
        refreshSelf()
    }

    fun setChannel(index: Int, name: String, secret: ByteArray) = scope.launch {
        if (ok(requestOne(Protocol.setChannel(index, name, secret)))) {
            refreshChannels()
            notice(if (name.isBlank()) "Canal removido" else "Canal \"$name\" salvo")
        } else {
            notice("Falha ao salvar canal")
        }
    }

    fun addDiscovered(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.addContact(c.raw)))) {
            _discovered.update { list -> list.filterNot { it.pubKey.contentEquals(c.pubKey) } }
            refreshContacts()
            notice("${c.name} adicionado")
        } else {
            notice("Falha ao adicionar (lista cheia?)")
        }
    }

    fun removeContact(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.removeContact(c.pubKey)))) refreshContacts()
    }

    fun resetPath(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.resetPath(c.pubKey)))) {
            notice("Rota de ${c.name} redefinida (próxima msg vai por flood)")
            refreshContacts()
        }
    }

    fun shareContact(c: Contact) = scope.launch {
        notice(if (ok(requestOne(Protocol.shareContact(c.pubKey)))) "Contato compartilhado (zero-hop)" else "Falha ao compartilhar")
    }

    // ---- app settings (kept on the phone) ----

    val autoAdvertMinutes: Int get() = prefs.getInt("auto_advert_min", 0)
    val autoAdvertFlood: Boolean get() = prefs.getBoolean("auto_advert_flood", true)

    fun setAutoAdvert(minutes: Int, flood: Boolean) {
        prefs.edit().putInt("auto_advert_min", minutes).putBoolean("auto_advert_flood", flood).apply()
        applyAutoAdvert()
    }

    private fun applyAutoAdvert() {
        if (_link.value.state == LinkState.RUNNING) NativeMesh.nativeSetAutoAdvert(autoAdvertMinutes, autoAdvertFlood)
    }
}
