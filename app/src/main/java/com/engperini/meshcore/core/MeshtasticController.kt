package com.engperini.meshcore.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.engperini.meshcore.data.ConvKey
import com.engperini.meshcore.data.MsgStatus
import com.engperini.meshcore.mt.MtEngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A Meshtastic node as our node knows it (its NodeDB). */
data class MtNode(
    val num: Long,
    val id: String,
    val longName: String,
    val shortName: String,
    val hwModel: Int = 0,
    val role: Int = 0,
    val snr: Float = 0f,
    val lastHeard: Long = 0,
    val hops: Int? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val alt: Int? = null,
    val battery: Int? = null,
    val voltage: Float? = null,
    val channelUtil: Float? = null,
    val airUtil: Float? = null,
    val viaMqtt: Boolean = false,
    val hasKey: Boolean = false,
    val favorite: Boolean = false,
) {
    val title: String get() = longName.ifBlank { id.ifBlank { "!%08x".format(num) } }
    val short: String get() = shortName.ifBlank { id.takeLast(4) }
}

/** A Meshtastic channel slot. Role 0 disabled, 1 primary, 2 secondary. */
data class MtChannel(val index: Int, val role: Int, val name: String, val pskHex: String) {
    val enabled: Boolean get() = role != 0
    val convKey: String get() = ConvKey.mtChannel(pskHex, name)

    /** Channel name as the official apps show it: the primary channel without a name is the preset's name. */
    fun title(lora: MtLora?): String = when {
        name.isNotBlank() -> name
        role == 1 -> lora?.presetName ?: "LongFast"
        else -> tr("Channel $index", "Canal $index")
    }
}

/** Radio settings of the node (Config.LoRaConfig). */
data class MtLora(
    val region: Int,
    val usePreset: Boolean,
    val preset: Int,
    val hopLimit: Int,
    val txEnabled: Boolean,
    val txPower: Int,
    val freqMhz: Double,
) {
    val presetName: String get() = MT_PRESETS.firstOrNull { it.first == preset }?.second ?: "Custom"
    val regionName: String get() = MT_REGIONS.firstOrNull { it.first == region }?.second ?: "?"
}

/** A traceroute answer: the nodes on the way there and back, with the SNR of each hop. */
data class MtTrace(
    val dest: Long,
    val route: List<Long>,
    val snrTowards: List<Float>,
    val routeBack: List<Long>,
    val snrBack: List<Float>,
    val roundTripMs: Long?,
    val at: Long,
)

/** Region codes (Config.LoRaConfig.RegionCode) the app offers, with labels. */
val MT_REGIONS = listOf(
    0 to "UNSET",
    1 to "US (902–928 MHz)",
    6 to "ANZ (915–928 MHz)",
    26 to "BR_902 (902–907.5 MHz)",
    3 to "EU_868",
    2 to "EU_433",
    4 to "CN",
    5 to "JP",
    7 to "KR",
    8 to "TW",
    9 to "RU",
    10 to "IN",
    11 to "NZ_865",
    12 to "TH",
    14 to "UA_433",
    15 to "UA_868",
    16 to "MY_433",
    17 to "MY_919",
    18 to "SG_923",
    19 to "PH_433",
    20 to "PH_868",
    21 to "PH_915",
    22 to "ANZ_433",
    23 to "KZ_433",
    24 to "KZ_863",
    25 to "NP_865",
    13 to "LORA_24 (2.4 GHz)",
)

/** Modem presets (Config.LoRaConfig.ModemPreset), in the order the official apps list them. */
val MT_PRESETS = listOf(
    0 to "LongFast",
    7 to "LongModerate",
    1 to "LongSlow",
    3 to "MediumSlow",
    4 to "MediumFast",
    5 to "ShortSlow",
    6 to "ShortFast",
    8 to "ShortTurbo",
    9 to "LongTurbo",
)

/**
 * App side of the Meshtastic mode. The node itself (the Meshtastic firmware) runs in [MtEngineService], in its
 * own process; this keeps the app's view of it (nodes, channels, settings) and the message history, which
 * shares [com.engperini.meshcore.data.MessageStore] with MeshCore under its own conversation keys.
 */
object MeshtasticController {
    private const val TAG = "Meshtastic"
    const val BROADCAST = 0xFFFFFFFFL

    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())

    private val _myNum = MutableStateFlow<Long?>(null)
    val myNum: StateFlow<Long?> = _myNum.asStateFlow()

    private val _nodes = MutableStateFlow<Map<Long, MtNode>>(emptyMap())
    val nodes: StateFlow<Map<Long, MtNode>> = _nodes.asStateFlow()

    private val _channels = MutableStateFlow<List<MtChannel>>(emptyList())
    val channels: StateFlow<List<MtChannel>> = _channels.asStateFlow()

    private val _lora = MutableStateFlow<MtLora?>(null)
    val lora: StateFlow<MtLora?> = _lora.asStateFlow()

    private val _firmware = MutableStateFlow("")
    val firmware: StateFlow<String> = _firmware.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _traces = MutableStateFlow<Map<Long, MtTrace>>(emptyMap())
    val traces: StateFlow<Map<Long, MtTrace>> = _traces.asStateFlow()

    /** Traceroutes waiting for an answer: destination -> sent at. */
    private val _tracing = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val tracing: StateFlow<Map<Long, Long>> = _tracing.asStateFlow()

    val self: MtNode? get() = _myNum.value?.let { _nodes.value[it] }

    // ---- engine process ----

    private data class Usb(val fd: Int, val kind: Int, val epIn: Int, val epOut: Int, val iface: Int, val dir: String)

    private var usb: Usb? = null
    private var engine: Messenger? = null
    private var bound = false
    private var crashes = mutableListOf<Long>()
    private val sentAt = HashMap<Long, Long>()   // packet id -> sent time, for round trips

    private val incoming = Messenger(
        Handler(Looper.getMainLooper()) { msg ->
            if (msg.what == MtEngineService.MSG_EVENT) msg.data?.getString(MtEngineService.KEY_JSON)?.let(::onEvents)
            true
        },
    )

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engine = Messenger(service)
            sendStart()
        }

        // The engine process died while we were bound (a crash): Android starts it again and calls
        // onServiceConnected, which boots the node again. Give up if it keeps dying.
        override fun onServiceDisconnected(name: ComponentName?) {
            engine = null
            _ready.value = false
            val now = System.currentTimeMillis()
            crashes = crashes.filter { now - it < 120_000 }.toMutableList().apply { add(now) }
            if (crashes.size > 3) {
                stop()
                MeshController.setLink(LinkState.ERROR, tr("The Meshtastic node keeps stopping", "O nó Meshtastic parou várias vezes"))
            } else {
                MeshController.setLink(LinkState.STARTING, tr("Restarting Meshtastic…", "Reiniciando o Meshtastic…"))
            }
        }
    }

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
    }

    /** Boots the node on the radio of the app's open USB connection ([fd] stays owned by the caller). */
    fun start(context: Context, fd: Int, kind: Int, epIn: Int, epOut: Int, iface: Int) {
        init(context)
        val dir = appContext.filesDir.resolve("meshtastic").apply { mkdirs() }.absolutePath
        usb = Usb(fd, kind, epIn, epOut, iface, dir)
        crashes.clear()
        MeshController.setLink(LinkState.STARTING, tr("Starting Meshtastic…", "Iniciando o Meshtastic…"))
        bind()
    }

    /** Ends the node's process (radio unplugged, other mode chosen). Its state stays on disk. */
    fun stop() {
        usb = null
        unbind()
        _ready.value = false
    }

    private fun bind() {
        if (bound) return
        bound = appContext.bindService(
            Intent(appContext, MtEngineService::class.java), connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!bound) MeshController.setLink(LinkState.ERROR, tr("Could not start Meshtastic", "Não foi possível iniciar o Meshtastic"))
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        engine = null
        try {
            appContext.unbindService(connection)
        } catch (_: IllegalArgumentException) {
        }
    }

    /** A node reboot (after some settings changes) is a fresh engine process. */
    private fun reboot() {
        val u = usb ?: return
        unbind()
        _ready.value = false
        MeshController.setLink(LinkState.STARTING, tr("Meshtastic is restarting…", "O Meshtastic está reiniciando…"))
        main.postDelayed({ if (usb === u) bind() }, 1500)
    }

    private fun sendStart() {
        val u = usb ?: return
        val pfd = try {
            ParcelFileDescriptor.fromFd(u.fd)   // a duplicate for the engine process
        } catch (e: Exception) {
            MeshController.setLink(LinkState.ERROR, e.message ?: "USB")
            return
        }
        val m = Message.obtain(null, MtEngineService.MSG_START)
        m.replyTo = incoming
        m.data = Bundle().apply {
            putParcelable(MtEngineService.KEY_USB, pfd)
            putString(MtEngineService.KEY_DIR, u.dir)
            putInt(MtEngineService.KEY_KIND, u.kind)
            putInt(MtEngineService.KEY_EP_IN, u.epIn)
            putInt(MtEngineService.KEY_EP_OUT, u.epOut)
            putInt(MtEngineService.KEY_IFACE, u.iface)
        }
        try {
            engine?.send(m)
        } catch (e: RemoteException) {
            Log.w(TAG, "start", e)
        } finally {
            pfd.close()
        }
    }

    private fun call(op: String, fill: Bundle.() -> Unit = {}): Boolean {
        val e = engine ?: return false
        val m = Message.obtain(null, MtEngineService.MSG_CALL)
        m.data = Bundle().apply {
            putString(MtEngineService.KEY_OP, op)
            fill()
        }
        return try {
            e.send(m)
            true
        } catch (_: RemoteException) {
            false
        }
    }

    // ---- events from the node ----

    private fun onEvents(lines: String) {
        lines.split('\n').filter { it.isNotBlank() }.forEach { line ->
            try {
                onEvent(JSONObject(line))
            } catch (e: Exception) {
                Log.w(TAG, "bad event $line", e)
            }
        }
    }

    private fun onEvent(e: JSONObject) {
        when (e.getString("t")) {
            "status" -> when (e.getString("state")) {
                "starting" -> MeshController.setLink(LinkState.STARTING, e.optString("message"))
                "running" -> if (e.optBoolean("radio")) {
                    MeshController.setLink(LinkState.RUNNING, tr("Meshtastic running", "Meshtastic ativo"))
                    requestConfig()
                } else {
                    MeshController.setLink(LinkState.ERROR, tr("The radio did not answer", "O rádio não respondeu"))
                }
                else -> MeshController.setLink(LinkState.ERROR, e.optString("message"))
            }
            "reboot" -> reboot()
            "my" -> _myNum.value = e.getLong("num")
            "meta" -> _firmware.value = e.optString("fw")
            "node" -> onNode(e)
            "chan" -> onChannel(e)
            "lora" -> _lora.value = MtLora(
                region = e.optInt("region"), usePreset = e.optBoolean("use_preset", true), preset = e.optInt("preset"),
                hopLimit = e.optInt("hop_limit", 3), txEnabled = e.optBoolean("tx", true), txPower = e.optInt("tx_power"),
                freqMhz = e.optDouble("freq", 0.0),
            )
            "ready" -> _ready.value = true
            "text" -> onText(e)
            "ack" -> onAck(e)
            "trace" -> onTrace(e)
            "queue" -> e.optLong("id").takeIf { it != 0L }?.let { failMessage(it) }
        }
    }

    private fun onNode(e: JSONObject) {
        val num = e.getLong("num")
        val u = e.optJSONObject("user")
        val old = _nodes.value[num]
        val n = MtNode(
            num = num,
            id = u?.optString("id") ?: old?.id ?: "!%08x".format(num),
            longName = u?.optString("long") ?: old?.longName ?: "",
            shortName = u?.optString("short") ?: old?.shortName ?: "",
            hwModel = u?.optInt("hw") ?: old?.hwModel ?: 0,
            role = u?.optInt("role") ?: old?.role ?: 0,
            snr = e.optDouble("snr", 0.0).toFloat(),
            lastHeard = e.optLong("last_heard"),
            hops = if (e.has("hops")) e.getInt("hops") else null,
            lat = if (e.has("lat")) e.getDouble("lat") else null,
            lon = if (e.has("lon")) e.getDouble("lon") else null,
            alt = if (e.has("alt")) e.getInt("alt") else null,
            battery = if (e.has("batt")) e.getInt("batt") else null,
            voltage = if (e.has("volt")) e.getDouble("volt").toFloat() else null,
            channelUtil = if (e.has("ch_util")) e.getDouble("ch_util").toFloat() else null,
            airUtil = if (e.has("air_util")) e.getDouble("air_util").toFloat() else null,
            viaMqtt = e.optBoolean("mqtt"),
            hasKey = u?.optBoolean("pk") ?: old?.hasKey ?: false,
            favorite = e.optBoolean("fav"),
        )
        _nodes.update { it + (num to n) }
    }

    private fun onChannel(e: JSONObject) {
        val c = MtChannel(e.getInt("index"), e.getInt("role"), e.optString("name"), e.optString("psk"))
        _channels.update { list -> (list.filter { it.index != c.index } + c).sortedBy { it.index } }
    }

    fun nodeName(num: Long): String = _nodes.value[num]?.title ?: "!%08x".format(num)

    private fun onText(e: JSONObject) {
        val from = e.getLong("from")
        if (from == _myNum.value) return
        val to = e.getLong("to")
        val text = e.getString("text")
        val time = e.optLong("rx_time").takeIf { it > 0 } ?: (System.currentTimeMillis() / 1000)
        val sender = nodeName(from)
        val conv: String
        val title: String
        if (to == BROADCAST) {
            val ch = _channels.value.firstOrNull { it.index == e.optInt("ch") } ?: return
            conv = ch.convKey
            title = ch.title(_lora.value)
        } else {
            conv = ConvKey.mtDirect(from)
            title = sender
        }
        val store = MeshController.messages
        if (store.isDuplicate(conv, sender, text, time)) return
        val reading = MeshController.activeConversation == conv
        store.insert(
            conv, outgoing = false, sender = sender, text = text, timestamp = time, status = MsgStatus.RECEIVED,
            snr = if (e.has("snr")) e.getDouble("snr").toFloat() else null,
            hops = if (e.has("hops")) e.getInt("hops") else null, unread = !reading,
        )
        MeshController.messagesChanged()
        if (!reading) {
            MeshController.onIncoming?.invoke(IncomingNotice(conv, title, if (to == BROADCAST) "$sender: $text" else text))
        }
    }

    private fun onAck(e: JSONObject) {
        val req = e.getLong("req")
        val store = MeshController.messages
        val id = store.idByAck(req) ?: return
        val err = e.optInt("err")
        if (err != 0) {
            store.failIfNotDelivered(id)
        } else {
            val conv = store.convOf(id) ?: ""
            val from = e.optLong("from")
            if (conv.startsWith("mtd:") && from == conv.removePrefix("mtd:").toLong(16)) {
                val rtt = sentAt.remove(req)?.let { System.currentTimeMillis() - it } ?: 0L
                store.confirmAck(req, rtt)
            } else {
                // Broadcasts get an implicit ACK when a node is heard rebroadcasting them; direct messages one
                // from each relay before the destination's own.
                store.addRepeat(id, if (from != 0L && from != _myNum.value) nodeName(from) else "mesh")
            }
        }
        MeshController.messagesChanged()
    }

    private fun failMessage(packetId: Long) {
        val store = MeshController.messages
        store.idByAck(packetId)?.let { store.failIfNotDelivered(it) }
        MeshController.messagesChanged()
    }

    private fun onTrace(e: JSONObject) {
        val from = e.getLong("from")
        fun longs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getLong(it) }
        // SNRs come in quarter dB; -128 means unknown.
        fun snrs(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getInt(it).let { v -> if (v == -128) Float.NaN else v / 4f } }
        val started = _tracing.value[from]
        _tracing.update { it - from }
        _traces.update {
            it + (from to MtTrace(
                from, longs(e.optJSONArray("route")), snrs(e.optJSONArray("snr_towards")),
                longs(e.optJSONArray("route_back")), snrs(e.optJSONArray("snr_back")),
                started?.let { s -> System.currentTimeMillis() - s }, System.currentTimeMillis(),
            ))
        }
    }

    // ---- requests ----

    private fun newId(): Int {
        var id = 0
        while (id == 0) id = Random.nextInt()
        return id
    }

    fun requestConfig() {
        call(MtEngineService.OP_CONFIG) { putInt("nonce", newId()) }
    }

    /** Sends [text] to a channel ([to] = [BROADCAST]) or a node, and records it in the conversation [conv]. */
    fun sendText(conv: String, to: Long, channel: Int, text: String) {
        val id = newId()
        val packetId = id.toLong() and 0xFFFFFFFFL
        val store = MeshController.messages
        val me = self?.title ?: tr("Me", "Eu")
        val row = store.insert(conv, outgoing = true, sender = me, text = text, timestamp = System.currentTimeMillis() / 1000, status = MsgStatus.SENDING)
        val ok = call(MtEngineService.OP_TEXT) {
            putInt("id", id)
            putInt("to", to.toInt())
            putInt("ch", channel)
            putString("text", text)
        }
        store.setSent(row, packetId, if (ok) MsgStatus.SENT else MsgStatus.FAILED)
        sentAt[packetId] = System.currentTimeMillis()
        MeshController.messagesChanged()
    }

    fun sendChannel(ch: MtChannel, text: String) = sendText(ch.convKey, BROADCAST, ch.index, text)

    fun sendDirect(num: Long, text: String) = sendText(ConvKey.mtDirect(num), num, 0, text)

    fun traceroute(num: Long) {
        _tracing.update { it + (num to System.currentTimeMillis()) }
        call(MtEngineService.OP_TRACE) {
            putInt("id", newId())
            putInt("to", num.toInt())
            putInt("ch", 0)
        }
    }

    fun setOwner(longName: String, shortName: String) {
        call(MtEngineService.OP_OWNER) {
            putString("long", longName)
            putString("short", shortName)
        }
        main.postDelayed({ requestConfig() }, 800)
    }

    fun setLora(region: Int, preset: Int, hopLimit: Int, txEnabled: Boolean, txPower: Int) {
        call(MtEngineService.OP_LORA) {
            putInt("region", region)
            putInt("preset", preset)
            putInt("hops", hopLimit)
            putBoolean("tx", txEnabled)
            putInt("power", txPower)
        }
        main.postDelayed({ requestConfig() }, 800)
    }

    /** [role] 0 disables the slot, 1 primary, 2 secondary. [psk] of 0, 1 (default key n), 16 or 32 bytes. */
    fun setChannel(index: Int, role: Int, name: String, psk: ByteArray) {
        call(MtEngineService.OP_CHANNEL) {
            putInt("index", index)
            putInt("role", role)
            putString("name", name)
            putByteArray("psk", psk)
        }
        main.postDelayed({ requestConfig() }, 800)
    }

    fun removeNode(num: Long) {
        call(MtEngineService.OP_REMOVE) { putInt("num", num.toInt()) }
        _nodes.update { it - num }
    }

    /** Shares the phone's location as the node's own position. */
    fun setPosition(lat: Double, lon: Double, alt: Int) {
        call(MtEngineService.OP_POSITION) {
            putInt("lat", (lat * 1e7).toInt())
            putInt("lon", (lon * 1e7).toInt())
            putInt("alt", alt)
        }
    }

    /** Screenshot tests. */
    @androidx.annotation.VisibleForTesting
    fun preview(myNum: Long, nodes: List<MtNode>, channels: List<MtChannel>, lora: MtLora) {
        _myNum.value = myNum
        _nodes.value = nodes.associateBy { it.num }
        _channels.value = channels
        _lora.value = lora
        _ready.value = true
    }
}
