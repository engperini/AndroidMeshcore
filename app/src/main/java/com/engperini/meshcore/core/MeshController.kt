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

/** One line of a remote-admin console: a command we sent or the node's reply. */
data class CliLine(val time: Long, val outgoing: Boolean, val text: String)

/**
 * App-side companion client: talks to the native node with companion frames,
 * keeps contacts/channels in memory and the message history in [MessageStore].
 */
object MeshController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var store: MessageStore
    private lateinit var prefs: SharedPreferences

    private val _link = MutableStateFlow(LinkStatus(LinkState.NO_DEVICE, tr("Connect the USB radio over OTG", "Conecte o rádio USB via OTG")))
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

    private val _stats = MutableStateFlow(NodeStats())
    val stats: StateFlow<NodeStats> = _stats.asStateFlow()

    /** Remote admin (repeaters / room servers), keyed by 6-byte pub key prefix hex. */
    private val _logins = MutableStateFlow<Map<String, LoginResult>>(emptyMap())
    val logins: StateFlow<Map<String, LoginResult>> = _logins.asStateFlow()

    private val _repeaterStatus = MutableStateFlow<Map<String, RepeaterStatus>>(emptyMap())
    val repeaterStatus: StateFlow<Map<String, RepeaterStatus>> = _repeaterStatus.asStateFlow()

    private val _cli = MutableStateFlow<Map<String, List<CliLine>>>(emptyMap())
    val cli: StateFlow<Map<String, List<CliLine>>> = _cli.asStateFlow()

    private val _paths = MutableStateFlow<Map<String, PathDiscovery>>(emptyMap())
    val paths: StateFlow<Map<String, PathDiscovery>> = _paths.asStateFlow()

    private val _autoAdd = MutableStateFlow<Pair<Int, Int>?>(null)
    val autoAdd: StateFlow<Pair<Int, Int>?> = _autoAdd.asStateFlow()

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

    @Volatile private var previewMode = false

    /** Screenshot tests: fills the state without loading the native library. */
    @androidx.annotation.VisibleForTesting
    fun initForPreview(
        context: Context, self: SelfInfo, device: DeviceInfo, contacts: List<Contact>, channels: List<Channel>,
        stats: NodeStats, log: List<PacketLogEntry>,
    ) {
        if (!::store.isInitialized) {
            store = MessageStore(context.applicationContext)
            prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        }
        previewMode = true
        _link.value = LinkStatus(LinkState.RUNNING, "Radio running")
        _self.value = self
        _device.value = device
        _contacts.value = contacts
        _channels.value = channels
        _stats.value = stats
        _packetLog.value = log
        _diag.value = """{"driver":"sx1262_ch341","status":82,"freq":923124992,"bw":62500,"sf":8,"cr":8,"tx":22,"noise_floor":-120,"dev_errors_hex":"0x0000","tcxo":1,"dio2_rf":1}"""
        bump()
    }

    @androidx.annotation.VisibleForTesting
    fun previewAdmin(prefixHex: String, login: LoginResult, status: RepeaterStatus?, cliLines: List<CliLine>) {
        _logins.update { it + (prefixHex to login) }
        if (status != null) _repeaterStatus.update { it + (prefixHex to status) }
        _cli.update { it + (prefixHex to cliLines) }
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
        if (_link.value.state != LinkState.RUNNING || previewMode) return@withLock null
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

    fun notice(text: String) {
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
            else -> _link.value = LinkStatus(LinkState.NO_DEVICE, tr("Connect the USB radio over OTG", "Conecte o rádio USB via OTG"))
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
        refreshStats()
        refreshAutoAdd()
    }

    suspend fun refreshStats() {
        var st = _stats.value
        for (type in listOf(Protocol.STATS_CORE, Protocol.STATS_RADIO, Protocol.STATS_PACKETS)) {
            val f = requestOne(Protocol.getStats(type)) ?: return
            if (f[0].toInt() == Protocol.RESP_STATS) st = Protocol.parseStats(f, st)
        }
        _stats.value = st
    }

    suspend fun refreshAutoAdd() {
        val f = requestOne(Protocol.getAutoAddConfig()) ?: return
        if (f[0].toInt() == Protocol.RESP_AUTOADD_CONFIG && f.size >= 3) {
            _autoAdd.value = (f[1].toInt() and 0xFF) to (f[2].toInt() and 0xFF)
        }
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
        if (_link.value.state == LinkState.RUNNING && !previewMode) _diag.value = NativeMesh.nativeRadioDiag()
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
                    notice(tr("Node discovered: ${c.name} (add it in Contacts)", "Nó descoberto: ${c.name} (adicione em Contatos)"))
                }
            }
            // Auto-added contacts arrive as PUSH_ADVERT (new or updated): reload the list.
            Protocol.PUSH_ADVERT, Protocol.PUSH_PATH_UPDATED, Protocol.PUSH_CONTACT_DELETED -> scheduleContactsRefresh()
            Protocol.PUSH_CONTACTS_FULL -> notice(tr("Contact list is full", "Lista de contatos cheia"))
            Protocol.PUSH_LOGIN_SUCCESS, Protocol.PUSH_LOGIN_FAIL -> Protocol.parseLoginResult(f)?.let { r ->
                val key = r.prefix.toHex()
                _logins.update { it + (key to r) }
                val name = contactByPrefix(key)?.name ?: key
                notice(if (r.success) tr("Logged in to $name", "Login em $name ok") + (if (r.isAdmin) " (admin)" else "") else tr("Login to $name refused", "Login em $name recusado"))
                if (r.success && contactByPrefix(key)?.type == Protocol.ADV_TYPE_ROOM) scope.launch { syncMessages() }
            }
            Protocol.PUSH_STATUS_RESPONSE -> Protocol.parseRepeaterStatus(f)?.let { st ->
                _repeaterStatus.update { it + (st.prefix.toHex() to st) }
            }
            Protocol.PUSH_PATH_DISCOVERY_RESPONSE -> Protocol.parsePathDiscovery(f)?.let { pd ->
                _paths.update { it + (pd.prefix.toHex() to pd) }
                val name = contactByPrefix(pd.prefix.toHex())?.name ?: pd.prefix.toHex()
                notice(tr("Route to $name: ${Protocol.hops(pd.outLen) ?: 0} hop(s)", "Rota para $name: ${Protocol.hops(pd.outLen) ?: 0} salto(s)"))
            }
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
            title = ch?.name ?: tr("Channel ${m.channelIdx}", "Canal ${m.channelIdx}")
        } else {
            val prefix = m.senderPrefix!!
            val from = contactByPrefix(prefix.toHex())
            if (m.txtType == Protocol.TXT_TYPE_CLI_DATA) {
                // Reply to a remote-admin command: goes to that node's console, not to the chat.
                addCli(prefix.toHex(), CliLine(System.currentTimeMillis(), false, m.text.trimEnd()))
                return
            }
            conv = ConvKey.contact(prefix.toHex())
            val author = m.authorPrefix?.toHex()
            // Room server posts: show the original author, not the room.
            sender = if (author != null) {
                if (_self.value?.pubKey?.toHex()?.startsWith(author) == true) _self.value!!.name
                else contactByPrefix(author)?.name ?: author
            } else {
                from?.name ?: prefix.toHex()
            }
            text = m.text
            title = if (author != null) "${from?.name ?: tr("Room", "Sala")}: $sender" else sender
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
            if (resp?.get(0)?.toInt() == Protocol.RESP_ERR) notice(tr("Contact not found on the node", "Contato não encontrado no nó"))
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
            if (flood) tr("Advert sent (flood)", "Advert enviado (flood)") else tr("Advert sent (zero-hop)", "Advert enviado (zero-hop)")
        } else tr("Failed to send advert", "Falha ao enviar advert"))
    }

    fun setName(name: String) = scope.launch {
        if (ok(requestOne(Protocol.setAdvertName(name.trim())))) refreshSelf() else notice(tr("Failed to save name", "Falha ao salvar nome"))
    }

    fun setRadio(freqKhz: Int, bwHz: Int, sf: Int, cr: Int, txDbm: Int, repeat: Boolean) = scope.launch {
        val a = ok(requestOne(Protocol.setRadioParams(freqKhz, bwHz, sf, cr, repeat)))
        val b = ok(requestOne(Protocol.setTxPower(txDbm)))
        notice(if (a && b) tr("Radio parameters applied", "Parâmetros de rádio aplicados") else tr("Invalid parameters", "Parâmetros inválidos"))
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
        notice(tr("Location saved", "Localização salva"))
    }

    /** Who may read our battery/telemetry: 0 nobody, 1 favourites, 2 everyone (needed to answer path discovery). */
    fun setTelemetryBase(mode: Int) = scope.launch {
        val s = _self.value ?: return@launch
        val modes = (s.telemetryModes and 0x03.inv()) or (mode and 0x03)
        requestOne(Protocol.setOtherParams(s.manualAddContacts, modes, s.advertLocPolicy, s.multiAcks))
        refreshSelf()
    }

    fun setManualAdd(manual: Boolean) = scope.launch {
        val s = _self.value ?: return@launch
        requestOne(Protocol.setOtherParams(manual, s.telemetryModes, s.advertLocPolicy, s.multiAcks))
        refreshSelf()
    }

    fun setChannel(index: Int, name: String, secret: ByteArray) = scope.launch {
        if (ok(requestOne(Protocol.setChannel(index, name, secret)))) {
            refreshChannels()
            notice(if (name.isBlank()) tr("Channel removed", "Canal removido") else tr("Channel \"$name\" saved", "Canal \"$name\" salvo"))
        } else {
            notice(tr("Failed to save channel", "Falha ao salvar canal"))
        }
    }

    fun addDiscovered(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.addContact(c.raw)))) {
            _discovered.update { list -> list.filterNot { it.pubKey.contentEquals(c.pubKey) } }
            refreshContacts()
            notice(tr("${c.name} added", "${c.name} adicionado"))
        } else {
            notice(tr("Failed to add (list full?)", "Falha ao adicionar (lista cheia?)"))
        }
    }

    // ---- remote admin: repeaters and room servers ----

    fun savedPassword(c: Contact): String = prefs.getString("pw_${c.keyHex}", "") ?: ""

    fun login(c: Contact, password: String, remember: Boolean) = scope.launch {
        prefs.edit().apply { if (remember) putString("pw_${c.keyHex}", password) else remove("pw_${c.keyHex}") }.apply()
        _logins.update { it - c.pubKey.copyOf(6).toHex() }
        val resp = requestOne(Protocol.login(c.pubKey, password))
        if (resp == null || resp[0].toInt() != Protocol.RESP_SENT) notice(tr("Failed to send login", "Falha ao enviar login")) else notice(tr("Login sent…", "Login enviado…"))
    }

    fun logout(c: Contact) = scope.launch {
        requestOne(Protocol.logout(c.pubKey))
        _logins.update { it - c.pubKey.copyOf(6).toHex() }
    }

    fun requestStatus(c: Contact) = scope.launch {
        val resp = requestOne(Protocol.statusRequest(c.pubKey))
        if (resp == null || resp[0].toInt() != Protocol.RESP_SENT) notice(tr("Failed to request status", "Falha ao pedir status"))
    }

    fun sendCli(c: Contact, command: String) = scope.launch {
        val key = c.pubKey.copyOf(6).toHex()
        addCli(key, CliLine(System.currentTimeMillis(), true, command))
        val resp = requestOne(Protocol.sendText(c.pubKey, command, now(), 0, Protocol.TXT_TYPE_CLI_DATA))
        if (resp == null || resp[0].toInt() != Protocol.RESP_SENT) addCli(key, CliLine(System.currentTimeMillis(), false, tr("(send failed)", "(falha ao enviar)")))
    }

    private fun addCli(key: String, line: CliLine) {
        _cli.update { m -> m + (key to ((m[key] ?: emptyList()) + line).takeLast(200)) }
    }

    fun clearCli(c: Contact) {
        _cli.update { it - c.pubKey.copyOf(6).toHex() }
    }

    // ---- paths ----

    fun discoverPath(c: Contact) = scope.launch {
        val resp = requestOne(Protocol.pathDiscovery(c.pubKey))
        notice(if (resp != null && resp[0].toInt() == Protocol.RESP_SENT) tr("Discovering route to ${c.name}…", "Descobrindo rota para ${c.name}…") else tr("Route discovery failed", "Falha ao descobrir rota"))
    }

    fun usePath(c: Contact, pd: PathDiscovery) = scope.launch {
        if (c.raw.size < 144) return@launch
        if (ok(requestOne(Protocol.setContactPath(c.raw, pd.outLen, pd.outPath)))) {
            notice(tr("Route saved for ${c.name}", "Rota salva para ${c.name}"))
            refreshContacts()
        }
    }

    // ---- sharing (meshcore:// links, QR codes) ----

    fun importLink(link: MeshLink) = scope.launch {
        when (link) {
            is MeshLink.AddContact -> {
                if (ok(requestOne(Protocol.addContactByKey(link.pubKey, link.name, link.type)))) {
                    refreshContacts()
                    notice(tr("Contact ${link.name} added", "Contato ${link.name} adicionado"))
                } else notice(tr("Failed to add contact", "Falha ao adicionar contato"))
            }
            is MeshLink.AddChannel -> {
                val existing = _channels.value.firstOrNull { it.secret.contentEquals(link.secret) }
                val free = _channels.value.firstOrNull { it.isEmpty }
                when {
                    existing != null -> notice(tr("Channel ${existing.name} already exists", "Canal ${existing.name} já existe"))
                    free == null -> notice(tr("All channel slots are in use", "Todos os slots de canal estão ocupados"))
                    else -> setChannel(free.index, link.name, link.secret)
                }
            }
        }
    }

    // ---- identity backup ----

    suspend fun exportPrivateKey(): ByteArray? {
        val f = requestOne(Protocol.exportPrivateKey()) ?: return null
        return if (f[0].toInt() == Protocol.RESP_PRIVATE_KEY && f.size >= 65) f.copyOfRange(1, 65) else null
    }

    fun importPrivateKey(key: ByteArray) = scope.launch {
        if (key.size != 64) {
            notice(tr("Invalid key (needs 64 bytes)", "Chave inválida (precisa de 64 bytes)"))
            return@launch
        }
        if (ok(requestOne(Protocol.importPrivateKey(key)))) {
            refreshSelf()
            refreshContacts()
            notice(tr("Identity restored", "Identidade restaurada"))
        } else notice(tr("Key refused by the node", "Chave recusada pelo nó"))
    }

    fun setAutoAdd(config: Int, maxHops: Int) = scope.launch {
        if (ok(requestOne(Protocol.setAutoAddConfig(config, maxHops)))) refreshAutoAdd()
    }

    fun removeContact(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.removeContact(c.pubKey)))) refreshContacts()
    }

    fun resetPath(c: Contact) = scope.launch {
        if (ok(requestOne(Protocol.resetPath(c.pubKey)))) {
            notice(tr("Route to ${c.name} reset (next message goes by flood)", "Rota de ${c.name} redefinida (próxima msg vai por flood)"))
            refreshContacts()
        }
    }

    fun shareContact(c: Contact) = scope.launch {
        notice(if (ok(requestOne(Protocol.shareContact(c.pubKey)))) tr("Contact shared (zero-hop)", "Contato compartilhado (zero-hop)") else tr("Share failed", "Falha ao compartilhar"))
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
