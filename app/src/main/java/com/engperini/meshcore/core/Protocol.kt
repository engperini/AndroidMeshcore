package com.engperini.meshcore.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * MeshCore companion frame protocol (examples/companion_radio/MyMesh.cpp).
 * All integers are little endian.
 */
object Protocol {
    const val CMD_APP_START = 1
    const val CMD_SEND_TXT_MSG = 2
    const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4
    const val CMD_SEND_SELF_ADVERT = 7
    const val CMD_ADD_UPDATE_CONTACT = 9
    const val CMD_SET_ADVERT_NAME = 8
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_SET_RADIO_PARAMS = 11
    const val CMD_SET_RADIO_TX_POWER = 12
    const val CMD_RESET_PATH = 13
    const val CMD_SET_ADVERT_LATLON = 14
    const val CMD_REMOVE_CONTACT = 15
    const val CMD_SHARE_CONTACT = 16
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SET_OTHER_PARAMS = 38
    const val CMD_SET_PATH_HASH_MODE = 61

    const val RESP_OK = 0
    const val RESP_ERR = 1
    const val RESP_CONTACTS_START = 2
    const val RESP_CONTACT = 3
    const val RESP_END_OF_CONTACTS = 4
    const val RESP_SELF_INFO = 5
    const val RESP_SENT = 6
    const val RESP_CONTACT_MSG_RECV = 7
    const val RESP_CHANNEL_MSG_RECV = 8
    const val RESP_NO_MORE_MESSAGES = 10
    const val RESP_BATT_AND_STORAGE = 12
    const val RESP_DEVICE_INFO = 13
    const val RESP_CONTACT_MSG_RECV_V3 = 16
    const val RESP_CHANNEL_MSG_RECV_V3 = 17
    const val RESP_CHANNEL_INFO = 18

    const val PUSH_ADVERT = 0x80
    const val PUSH_PATH_UPDATED = 0x81
    const val PUSH_SEND_CONFIRMED = 0x82
    const val PUSH_MSG_WAITING = 0x83
    const val PUSH_LOG_RX_DATA = 0x88
    const val PUSH_NEW_ADVERT = 0x8A
    const val PUSH_CONTACT_DELETED = 0x8F
    const val PUSH_CONTACTS_FULL = 0x90

    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_SIGNED_PLAIN = 2

    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3
    const val ADV_TYPE_SENSOR = 4

    const val PUB_KEY_SIZE = 32
    const val MAX_PATH_SIZE = 64
    const val OUT_PATH_UNKNOWN = 0xFF
    const val PROTOCOL_VERSION = 3

    fun isPush(frame: ByteArray) = frame.isNotEmpty() && (frame[0].toInt() and 0xFF) >= 0x80

    private fun buf(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun ByteBuffer.bytes(): ByteArray = array().copyOf(position())

    fun appStart(appName: String): ByteArray {
        val name = appName.toByteArray()
        return buf(8 + name.size).put(CMD_APP_START.toByte()).put(ByteArray(7)).put(name).bytes()
    }

    fun deviceQuery() = byteArrayOf(CMD_DEVICE_QUERY.toByte(), PROTOCOL_VERSION.toByte())

    fun getContacts() = byteArrayOf(CMD_GET_CONTACTS.toByte())

    fun syncNextMessage() = byteArrayOf(CMD_SYNC_NEXT_MESSAGE.toByte())

    fun getChannel(idx: Int) = byteArrayOf(CMD_GET_CHANNEL.toByte(), idx.toByte())

    fun getBattery() = byteArrayOf(CMD_GET_BATT_AND_STORAGE.toByte())

    fun sendSelfAdvert(flood: Boolean) = byteArrayOf(CMD_SEND_SELF_ADVERT.toByte(), if (flood) 1 else 0)

    fun setAdvertName(name: String): ByteArray {
        val n = name.toByteArray().let { if (it.size > 31) it.copyOf(31) else it }
        return byteArrayOf(CMD_SET_ADVERT_NAME.toByte()) + n
    }

    fun sendText(pubKey: ByteArray, text: String, timestamp: Long, attempt: Int = 0): ByteArray {
        val t = text.toByteArray()
        return buf(13 + t.size)
            .put(CMD_SEND_TXT_MSG.toByte()).put(TXT_TYPE_PLAIN.toByte()).put(attempt.toByte())
            .putInt(timestamp.toInt()).put(pubKey, 0, 6).put(t).bytes()
    }

    fun sendChannelText(channelIdx: Int, text: String, timestamp: Long): ByteArray {
        val t = text.toByteArray()
        return buf(7 + t.size)
            .put(CMD_SEND_CHANNEL_TXT_MSG.toByte()).put(TXT_TYPE_PLAIN.toByte()).put(channelIdx.toByte())
            .putInt(timestamp.toInt()).put(t).bytes()
    }

    fun setChannel(idx: Int, name: String, secret: ByteArray): ByteArray {
        val n = ByteArray(32)
        name.toByteArray().copyInto(n, 0, 0, minOf(31, name.toByteArray().size))
        return buf(2 + 32 + 16).put(CMD_SET_CHANNEL.toByte()).put(idx.toByte()).put(n).put(secret, 0, 16).bytes()
    }

    fun setRadioParams(freqKhz: Int, bwHz: Int, sf: Int, cr: Int, repeat: Boolean): ByteArray =
        buf(12).put(CMD_SET_RADIO_PARAMS.toByte()).putInt(freqKhz).putInt(bwHz).put(sf.toByte()).put(cr.toByte())
            .put(if (repeat) 1 else 0).bytes()

    fun setTxPower(dbm: Int) = byteArrayOf(CMD_SET_RADIO_TX_POWER.toByte(), dbm.toByte())

    fun setLatLon(lat: Double, lon: Double): ByteArray =
        buf(9).put(CMD_SET_ADVERT_LATLON.toByte()).putInt((lat * 1e6).toInt()).putInt((lon * 1e6).toInt()).bytes()

    fun setOtherParams(manualAdd: Boolean, telemetry: Int, advertLocPolicy: Int, multiAcks: Int) = byteArrayOf(
        CMD_SET_OTHER_PARAMS.toByte(), if (manualAdd) 1 else 0, telemetry.toByte(), advertLocPolicy.toByte(),
        multiAcks.toByte(),
    )

    fun setPathHashMode(mode: Int) = byteArrayOf(CMD_SET_PATH_HASH_MODE.toByte(), 0, mode.toByte())

    /** Adds a contact the node only reported (PUSH_NEW_ADVERT, manual-add mode); same layout as the contact frame. */
    fun addContact(contactFrame: ByteArray): ByteArray =
        byteArrayOf(CMD_ADD_UPDATE_CONTACT.toByte()) + contactFrame.copyOfRange(1, minOf(contactFrame.size, 144))

    fun removeContact(pubKey: ByteArray) = byteArrayOf(CMD_REMOVE_CONTACT.toByte()) + pubKey

    fun resetPath(pubKey: ByteArray) = byteArrayOf(CMD_RESET_PATH.toByte()) + pubKey

    fun shareContact(pubKey: ByteArray) = byteArrayOf(CMD_SHARE_CONTACT.toByte()) + pubKey

    // ---- parsing ----

    private fun le(frame: ByteArray, offset: Int): ByteBuffer =
        ByteBuffer.wrap(frame, offset, frame.size - offset).order(ByteOrder.LITTLE_ENDIAN)

    private fun cString(frame: ByteArray, offset: Int, maxLen: Int): String {
        val end = (offset until minOf(frame.size, offset + maxLen)).firstOrNull { frame[it].toInt() == 0 }
            ?: minOf(frame.size, offset + maxLen)
        return String(frame, offset, maxOf(0, end - offset), Charsets.UTF_8)
    }

    fun parseSelfInfo(f: ByteArray): SelfInfo? {
        if (f.size < 58) return null
        val b = le(f, 0)
        return SelfInfo(
            advType = f[1].toInt(),
            txPower = f[2].toInt(),
            maxTxPower = f[3].toInt(),
            pubKey = f.copyOfRange(4, 36),
            lat = b.getInt(36) / 1e6,
            lon = b.getInt(40) / 1e6,
            multiAcks = f[44].toInt(),
            advertLocPolicy = f[45].toInt(),
            telemetryModes = f[46].toInt() and 0xFF,
            manualAddContacts = f[47].toInt() != 0,
            freqKhz = b.getInt(48),
            bwHz = b.getInt(52),
            sf = f[56].toInt(),
            cr = f[57].toInt(),
            name = String(f, 58, f.size - 58, Charsets.UTF_8),
        )
    }

    fun parseDeviceInfo(f: ByteArray): DeviceInfo? {
        if (f.size < 80) return null
        return DeviceInfo(
            firmwareVer = f[1].toInt(),
            maxContacts = (f[2].toInt() and 0xFF) * 2,
            maxChannels = f[3].toInt() and 0xFF,
            buildDate = cString(f, 8, 12),
            manufacturer = cString(f, 20, 40),
            version = cString(f, 60, 20),
            clientRepeat = f.size > 80 && f[80].toInt() != 0,
            pathHashMode = if (f.size > 81) f[81].toInt() else 0,
        )
    }

    fun parseContact(f: ByteArray): Contact? {
        if (f.size < 148) return null
        val b = le(f, 0)
        return Contact(
            pubKey = f.copyOfRange(1, 33),
            type = f[33].toInt(),
            flags = f[34].toInt(),
            outPathLen = f[35].toInt() and 0xFF,
            name = cString(f, 100, 32),
            lastAdvert = b.getInt(132).toLong() and 0xFFFFFFFFL,
            lat = b.getInt(136) / 1e6,
            lon = b.getInt(140) / 1e6,
            lastMod = b.getInt(144).toLong() and 0xFFFFFFFFL,
            raw = f,
        )
    }

    fun parseChannel(f: ByteArray): Channel? {
        if (f.size < 50) return null
        return Channel(index = f[1].toInt() and 0xFF, name = cString(f, 2, 32), secret = f.copyOfRange(34, 50))
    }

    /** RESP_CONTACT_MSG_RECV(_V3) / RESP_CHANNEL_MSG_RECV(_V3). */
    fun parseIncoming(f: ByteArray): Incoming? {
        val code = f[0].toInt()
        val v3 = code == RESP_CONTACT_MSG_RECV_V3 || code == RESP_CHANNEL_MSG_RECV_V3
        var i = 1
        val snr = if (v3) f[1] / 4.0f else null
        if (v3) i += 3
        val isChannel = code == RESP_CHANNEL_MSG_RECV || code == RESP_CHANNEL_MSG_RECV_V3
        return if (isChannel) {
            if (f.size < i + 7) return null
            val idx = f[i].toInt() and 0xFF
            val pathLen = f[i + 1].toInt() and 0xFF
            val ts = le(f, 0).getInt(i + 3).toLong() and 0xFFFFFFFFL
            val text = String(f, i + 7, f.size - i - 7, Charsets.UTF_8)
            Incoming(channelIdx = idx, senderPrefix = null, pathLen = pathLen, timestamp = ts, text = text, snr = snr)
        } else {
            if (f.size < i + 12) return null
            val prefix = f.copyOfRange(i, i + 6)
            val pathLen = f[i + 6].toInt() and 0xFF
            val txtType = f[i + 7].toInt()
            val ts = le(f, 0).getInt(i + 8).toLong() and 0xFFFFFFFFL
            var t = i + 12
            if (txtType == TXT_TYPE_SIGNED_PLAIN) t += 4
            if (txtType != TXT_TYPE_PLAIN && txtType != TXT_TYPE_SIGNED_PLAIN) return null
            val text = String(f, t, maxOf(0, f.size - t), Charsets.UTF_8)
            Incoming(channelIdx = null, senderPrefix = prefix, pathLen = pathLen, timestamp = ts, text = text, snr = snr)
        }
    }

    // ---- helpers ----

    /** Key of a "#name" hashtag channel: first 16 bytes of SHA-256 of the name (with '#'). */
    fun hashtagSecret(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).copyOf(16)

    fun hops(pathLen: Int): Int? = if (pathLen == OUT_PATH_UNKNOWN) null else pathLen and 63
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray? {
    val s = trim().replace(" ", "")
    if (s.length % 2 != 0 || !s.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

data class SelfInfo(
    val advType: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val pubKey: ByteArray,
    val lat: Double,
    val lon: Double,
    val multiAcks: Int,
    val advertLocPolicy: Int,
    val telemetryModes: Int,
    val manualAddContacts: Boolean,
    val freqKhz: Int,
    val bwHz: Int,
    val sf: Int,
    val cr: Int,
    val name: String,
)

data class DeviceInfo(
    val firmwareVer: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val buildDate: String,
    val manufacturer: String,
    val version: String,
    val clientRepeat: Boolean,
    val pathHashMode: Int,
)

data class Contact(
    val pubKey: ByteArray,
    val type: Int,
    val flags: Int,
    val outPathLen: Int,
    val name: String,
    val lastAdvert: Long,
    val lat: Double,
    val lon: Double,
    val lastMod: Long,
    val raw: ByteArray = ByteArray(0),
) {
    val keyHex: String get() = pubKey.toHex()
    val typeLabel: String
        get() = when (type) {
            Protocol.ADV_TYPE_CHAT -> "Chat"
            Protocol.ADV_TYPE_REPEATER -> "Repetidor"
            Protocol.ADV_TYPE_ROOM -> "Sala"
            Protocol.ADV_TYPE_SENSOR -> "Sensor"
            else -> "?"
        }
}

data class Channel(val index: Int, val name: String, val secret: ByteArray) {
    val isEmpty: Boolean get() = name.isBlank() && secret.all { it.toInt() == 0 }
    val secretHex: String get() = secret.toHex()
}

data class Incoming(
    val channelIdx: Int?,
    val senderPrefix: ByteArray?,
    val pathLen: Int,
    val timestamp: Long,
    val text: String,
    val snr: Float?,
)
