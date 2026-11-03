package com.engperini.meshcore.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The protocol the radio runs. One at a time; each keeps its own data. */
enum class RadioMode(val nativeId: Int) {
    MESHCORE(NativeMesh.MODE_MESHCORE),
    LORA(NativeMesh.MODE_LORA);

    val label: String
        get() = when (this) {
            MESHCORE -> "MeshCore"
            LORA -> tr("Generic LoRa", "LoRa genérico")
        }
}

/** Generic LoRa radio parameters. [syncWord] is the one-byte LoRa sync word (0x12 private, 0x34 public). */
data class LoraConfig(
    val freqKhz: Int = 915000,
    val bwHz: Int = 125000,
    val sf: Int = 7,
    val cr: Int = 5,
    val syncWord: Int = 0x12,
    val txDbm: Int = 14,
    val preamble: Int = 8,
) {
    /** SX126x sync word register value: 0x12 -> 0x1424, 0x34 -> 0x3444, 0x2B -> 0x24B4. */
    val syncRegister: Int get() = ((syncWord and 0xF0) shl 8) or ((syncWord and 0x0F) shl 4) or 0x0404

    val summary: String
        get() = "%.3f MHz • %s kHz • SF%d • CR 4/%d • 0x%02X".format(
            java.util.Locale.US, freqKhz / 1000.0, trimKhz(bwHz), sf, cr, syncWord,
        )
}

private fun trimKhz(hz: Int): String {
    val k = hz / 1000.0
    return if (k == k.toLong().toDouble()) k.toLong().toString() else "%.2f".format(java.util.Locale.US, k).trimEnd('0').trimEnd('.')
}

/** A preset for the generic LoRa mode, e.g. to listen to another network's channel. */
data class LoraPreset(val label: String, val config: LoraConfig)

val LORA_PRESETS = listOf(
    LoraPreset("MeshCore Brasil", LoraConfig(923125, 62500, 8, 8, 0x12, 14, 16)),
    LoraPreset("Meshtastic BR LongFast", LoraConfig(919875, 250000, 11, 5, 0x2B, 14, 16)),
    LoraPreset("Meshtastic US LongFast", LoraConfig(906875, 250000, 11, 5, 0x2B, 14, 16)),
    LoraPreset("Meshtastic EU LongFast", LoraConfig(869525, 250000, 11, 5, 0x2B, 14, 16)),
    LoraPreset("LoRa 915 MHz, SF7 / 125 kHz", LoraConfig(915000, 125000, 7, 5, 0x12, 14, 8)),
    LoraPreset("LoRa 868 MHz, SF7 / 125 kHz", LoraConfig(868100, 125000, 7, 5, 0x12, 14, 8)),
)

/** A packet heard or sent in the generic LoRa mode. */
data class RawPacket(
    val time: Long,
    val outgoing: Boolean,
    val data: ByteArray,
    val rssi: Float? = null,
    val snr: Float? = null,
    val sent: Boolean? = null,   // outgoing: null while queued, then true/false
)

/** Which protocol runs on the radio, and the generic LoRa mode's state. */
object LoraController {
    private const val MAX_LOG = 500
    private lateinit var prefs: SharedPreferences

    private val _mode = MutableStateFlow(RadioMode.MESHCORE)
    val mode: StateFlow<RadioMode> = _mode.asStateFlow()

    private val _config = MutableStateFlow(LoraConfig())
    val config: StateFlow<LoraConfig> = _config.asStateFlow()

    private val _packets = MutableStateFlow<List<RawPacket>>(emptyList())
    val packets: StateFlow<List<RawPacket>> = _packets.asStateFlow()

    private val _received = MutableStateFlow(0)
    val received: StateFlow<Int> = _received.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        _mode.value = RadioMode.entries.firstOrNull { it.name == prefs.getString("radio_mode", null) } ?: RadioMode.MESHCORE
        _config.value = LoraConfig(
            freqKhz = prefs.getInt("lora_freq_khz", 915000),
            bwHz = prefs.getInt("lora_bw_hz", 125000),
            sf = prefs.getInt("lora_sf", 7),
            cr = prefs.getInt("lora_cr", 5),
            syncWord = prefs.getInt("lora_sync", 0x12),
            txDbm = prefs.getInt("lora_tx_dbm", 14),
            preamble = prefs.getInt("lora_preamble", 8),
        )
        NativeMesh.rawPacketListener = { data, rssi, snr ->
            add(RawPacket(System.currentTimeMillis(), false, data, rssi, snr))
            _received.update { it + 1 }
        }
        NativeMesh.rawSentListener = { ok ->
            _packets.update { list ->
                val i = list.indexOfLast { it.outgoing && it.sent == null }
                if (i < 0) list else list.toMutableList().also { it[i] = it[i].copy(sent = ok) }
            }
        }
    }

    /** Hands the mode and parameters to the native side; call before the radio starts. */
    fun pushToNative() {
        val c = _config.value
        NativeMesh.nativeSetLoraConfig(c.freqKhz * 1000, c.bwHz, c.sf, c.cr, c.syncRegister, c.txDbm, c.preamble)
        NativeMesh.nativeSetMode(_mode.value.nativeId)
    }

    fun setMode(mode: RadioMode) {
        if (mode == _mode.value) return
        _mode.value = mode
        if (::prefs.isInitialized) prefs.edit().putString("radio_mode", mode.name).apply()
        pushToNative()   // a running radio switches at once; MeshCore keeps its state
    }

    fun setConfig(c: LoraConfig) {
        _config.value = c
        if (::prefs.isInitialized) {
            prefs.edit()
                .putInt("lora_freq_khz", c.freqKhz).putInt("lora_bw_hz", c.bwHz).putInt("lora_sf", c.sf)
                .putInt("lora_cr", c.cr).putInt("lora_sync", c.syncWord).putInt("lora_tx_dbm", c.txDbm)
                .putInt("lora_preamble", c.preamble).apply()
        }
        pushToNative()
    }

    fun send(data: ByteArray) {
        if (data.isEmpty() || data.size > 255) return
        add(RawPacket(System.currentTimeMillis(), true, data))
        NativeMesh.nativeSendRaw(data)
    }

    fun clear() {
        _packets.value = emptyList()
        _received.value = 0
    }

    private fun add(p: RawPacket) {
        _packets.update { (it + p).takeLast(MAX_LOG) }
    }

    /** Screenshot tests. */
    @androidx.annotation.VisibleForTesting
    fun preview(mode: RadioMode, config: LoraConfig, packets: List<RawPacket>) {
        _mode.value = mode
        _config.value = config
        _packets.value = packets
        _received.value = packets.count { !it.outgoing }
    }
}
