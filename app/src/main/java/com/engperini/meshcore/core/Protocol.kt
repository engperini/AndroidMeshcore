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
    const val CMD_EXPORT_CONTACT = 17
    const val CMD_IMPORT_CONTACT = 18
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22
    const val CMD_EXPORT_PRIVATE_KEY = 23
    const val CMD_IMPORT_PRIVATE_KEY = 24
    const val CMD_SEND_LOGIN = 26
    const val CMD_SEND_STATUS_REQ = 27
    const val CMD_LOGOUT = 29
    const val CMD_SEND_TRACE_PATH = 36
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SET_OTHER_PARAMS = 38
    const val CMD_SEND_PATH_DISCOVERY_REQ = 52
    const val CMD_GET_STATS = 56
    const val CMD_SET_AUTOADD_CONFIG = 58
    const val CMD_GET_AUTOADD_CONFIG = 59
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
    const val RESP_PRIVATE_KEY = 14
    const val RESP_DISABLED = 15
    const val RESP_CHANNEL_INFO = 18
    const val RESP_STATS = 24
    const val RESP_AUTOADD_CONFIG = 25

    const val PUSH_ADVERT = 0x80
    const val PUSH_PATH_UPDATED = 0x81
    const val PUSH_SEND_CONFIRMED = 0x82
    const val PUSH_MSG_WAITING = 0x83
    const val PUSH_LOGIN_SUCCESS = 0x85
    const val PUSH_LOGIN_FAIL = 0x86
    const val PUSH_STATUS_RESPONSE = 0x87
    const val PUSH_LOG_RX_DATA = 0x88
    const val PUSH_TRACE_DATA = 0x89
    const val PUSH_NEW_ADVERT = 0x8A
    const val PUSH_PATH_DISCOVERY_RESPONSE = 0x8D
    const val PUSH_CONTACT_DELETED = 0x8F
    const val PUSH_CONTACTS_FULL = 0x90

    const val PAYLOAD_TYPE_GRP_TXT = 5

    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_CLI_DATA = 1
    const val TXT_TYPE_SIGNED_PLAIN = 2

    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3
    const val ADV_TYPE_SENSOR = 4

    const val STATS_CORE = 0
    const val STATS_RADIO = 1
    const val STATS_PACKETS = 2

    // CMD_SET_AUTOADD_CONFIG bits (used when "add manually" is on).
    const val AUTOADD_OVERWRITE_OLDEST = 0x01
    const val AUTOADD_CHAT = 0x02
    const val AUTOADD_REPEATER = 0x04
    const val AUTOADD_ROOM = 0x08
    const val AUTOADD_SENSOR = 0x10

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

    fun sendText(pubKey: ByteArray, text: String, timestamp: Long, attempt: Int = 0, txtType: Int = TXT_TYPE_PLAIN): ByteArray {
        val t = text.toByteArray()
        return buf(13 + t.size)
            .put(CMD_SEND_TXT_MSG.toByte()).put(txtType.toByte()).put(attempt.toByte())
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

    /** Adds a contact known only by key, name and type (e.g. from a meshcore://contact/add QR code). */
    fun addContactByKey(pubKey: ByteArray, name: String, type: Int): ByteArray {
        val n = ByteArray(32)
        name.toByteArray().let { it.copyInto(n, 0, 0, minOf(31, it.size)) }
        return buf(1 + 32 + 3 + MAX_PATH_SIZE + 32 + 12)
            .put(CMD_ADD_UPDATE_CONTACT.toByte()).put(pubKey, 0, 32).put(type.toByte()).put(0)
            .put(OUT_PATH_UNKNOWN.toByte()).put(ByteArray(MAX_PATH_SIZE)).put(n)
            .putInt(0).putInt(0).putInt(0).bytes()
    }

    /** Re-sends a contact frame with a new outbound path (path bytes and the encoded path length). */
    fun setContactPath(contactFrame: ByteArray, pathLen: Int, path: ByteArray): ByteArray {
        val f = addContact(contactFrame)
        f[35] = pathLen.toByte()
        java.util.Arrays.fill(f, 36, 36 + MAX_PATH_SIZE, 0)
        path.copyInto(f, 36, 0, minOf(path.size, MAX_PATH_SIZE))
        return f
    }

    fun login(pubKey: ByteArray, password: String) = byteArrayOf(CMD_SEND_LOGIN.toByte()) + pubKey + password.toByteArray()

    fun logout(pubKey: ByteArray) = byteArrayOf(CMD_LOGOUT.toByte()) + pubKey

    fun statusRequest(pubKey: ByteArray) = byteArrayOf(CMD_SEND_STATUS_REQ.toByte()) + pubKey

    fun pathDiscovery(pubKey: ByteArray) = byteArrayOf(CMD_SEND_PATH_DISCOVERY_REQ.toByte(), 0) + pubKey

    /**
     * Trace ("ping"): a direct packet that follows [path] (hop hashes of 1 << [pathSz] bytes each); every
     * repeater on it adds the SNR it heard it at. The answer is PUSH_TRACE_DATA with the same [tag].
     */
    fun tracePath(tag: Int, pathSz: Int, path: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(10 + path.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(CMD_SEND_TRACE_PATH.toByte()).putInt(tag).putInt(0).put((pathSz and 0x03).toByte()).put(path)
        return b.array()
    }

    /** RESP_SENT: how long the node expects the answer to take, in ms. */
    fun sentTimeoutMs(f: ByteArray): Long? =
        if (f.size >= 10 && f[0].toInt() == RESP_SENT) le(f, 0).getInt(6).toLong() and 0xFFFFFFFFL else null

    /** RESP_SENT: the request went out by flood (the route was unknown). */
    fun sentFlood(f: ByteArray): Boolean = f.size >= 2 && f[0].toInt() == RESP_SENT && f[1].toInt() == 1

    /** PUSH_TRACE_DATA: the hops the trace went through and the SNR each one heard it at. */
    fun parseTrace(f: ByteArray): TraceResult? {
        if (f.size < 12) return null
        val pathLen = f[2].toInt() and 0xFF
        val flags = f[3].toInt() and 0xFF
        val sz = 1 shl (flags and 0x03)
        val hops = pathLen / sz
        if (f.size < 12 + pathLen + hops + 1) return null
        val tag = le(f, 0).getInt(4)
        val hashes = (0 until hops).map { h -> f.copyOfRange(12 + h * sz, 12 + (h + 1) * sz).toHex() }
        val snrs = (0 until hops).map { h -> f[12 + pathLen + h] / 4.0f }
        return TraceResult(tag, hashes, snrs, f[12 + pathLen + hops] / 4.0f)
    }

    fun getStats(type: Int) = byteArrayOf(CMD_GET_STATS.toByte(), type.toByte())

    fun exportPrivateKey() = byteArrayOf(CMD_EXPORT_PRIVATE_KEY.toByte())

    fun importPrivateKey(key: ByteArray) = byteArrayOf(CMD_IMPORT_PRIVATE_KEY.toByte()) + key

    fun getAutoAddConfig() = byteArrayOf(CMD_GET_AUTOADD_CONFIG.toByte())

    fun setAutoAddConfig(config: Int, maxHops: Int) =
        byteArrayOf(CMD_SET_AUTOADD_CONFIG.toByte(), config.toByte(), maxHops.toByte())

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
            Incoming(channelIdx = idx, senderPrefix = null, pathLen = pathLen, timestamp = ts, text = text, snr = snr, txtType = TXT_TYPE_PLAIN)
        } else {
            if (f.size < i + 12) return null
            val prefix = f.copyOfRange(i, i + 6)
            val pathLen = f[i + 6].toInt() and 0xFF
            val txtType = f[i + 7].toInt()
            val ts = le(f, 0).getInt(i + 8).toLong() and 0xFFFFFFFFL
            var t = i + 12
            // Room servers relay posts as signed messages: the 4-byte prefix is the original author.
            val author = if (txtType == TXT_TYPE_SIGNED_PLAIN && f.size >= t + 4) f.copyOfRange(t, t + 4) else null
            if (txtType == TXT_TYPE_SIGNED_PLAIN) t += 4
            if (txtType != TXT_TYPE_PLAIN && txtType != TXT_TYPE_SIGNED_PLAIN && txtType != TXT_TYPE_CLI_DATA) return null
            val text = String(f, t, maxOf(0, f.size - t), Charsets.UTF_8)
            Incoming(
                channelIdx = null, senderPrefix = prefix, pathLen = pathLen, timestamp = ts, text = text, snr = snr,
                txtType = txtType, authorPrefix = author,
            )
        }
    }

    // ---- helpers ----

    /** Key of a "#name" hashtag channel: first 16 bytes of SHA-256 of the name (with '#'). */
    fun hashtagSecret(name: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).copyOf(16)

    fun hops(pathLen: Int): Int? = if (pathLen == OUT_PATH_UNKNOWN) null else pathLen and 63

    /** Bytes per hop hash encoded in the top bits of a path length. */
    fun hashSize(pathLen: Int): Int = (pathLen shr 6) + 1

    /** Path bytes of a contact frame / path field, as hex hop hashes ("a1 > 3f"). */
    fun pathHops(pathLen: Int, path: ByteArray): List<String> {
        val n = hops(pathLen) ?: return emptyList()
        val sz = hashSize(pathLen)
        return (0 until n).mapNotNull { h ->
            if ((h + 1) * sz <= path.size) path.copyOfRange(h * sz, (h + 1) * sz).toHex() else null
        }
    }

    fun parseLoginResult(f: ByteArray): LoginResult? {
        if (f.size < 8) return null
        val ok = (f[0].toInt() and 0xFF) == PUSH_LOGIN_SUCCESS
        return LoginResult(prefix = f.copyOfRange(2, 8), success = ok, isAdmin = ok && (f[1].toInt() and 1) != 0)
    }

    /** PUSH_STATUS_RESPONSE from a repeater (RepeaterStats in simple_repeater/MyMesh.h). */
    fun parseRepeaterStatus(f: ByteArray): RepeaterStatus? {
        if (f.size < 8 + 44) return null
        val b = le(f, 0)
        val o = 8
        fun u32(at: Int) = b.getInt(o + at).toLong() and 0xFFFFFFFFL
        return RepeaterStatus(
            prefix = f.copyOfRange(2, 8),
            battMv = b.getShort(o).toInt() and 0xFFFF,
            txQueue = b.getShort(o + 2).toInt() and 0xFFFF,
            noiseFloor = b.getShort(o + 4).toInt(),
            lastRssi = b.getShort(o + 6).toInt(),
            packetsRecv = u32(8),
            packetsSent = u32(12),
            airTimeSecs = u32(16),
            uptimeSecs = u32(20),
            sentFlood = u32(24),
            sentDirect = u32(28),
            recvFlood = u32(32),
            recvDirect = u32(36),
            lastSnr = b.getShort(o + 42) / 4.0f,
        )
    }

    /** PUSH_PATH_DISCOVERY_RESPONSE: the path to the contact and the path back. */
    fun parsePathDiscovery(f: ByteArray): PathDiscovery? {
        if (f.size < 9) return null
        val outLen = f[8].toInt() and 0xFF
        val outBytes = (hops(outLen) ?: 0) * hashSize(outLen)
        if (f.size < 9 + outBytes + 1) return null
        val outPath = f.copyOfRange(9, 9 + outBytes)
        val inLen = f[9 + outBytes].toInt() and 0xFF
        val inBytes = (hops(inLen) ?: 0) * hashSize(inLen)
        val inPath = f.copyOfRange(10 + outBytes, minOf(f.size, 10 + outBytes + inBytes))
        return PathDiscovery(f.copyOfRange(2, 8), outLen, outPath, inLen, inPath)
    }

    /** A mesh packet as the radio heard it (PUSH_LOG_RX_DATA carries these bytes after snr and rssi). */
    fun parseMeshPacket(raw: ByteArray): MeshPacket? {
        if (raw.size < 2) return null
        var i = 0
        val header = raw[i++].toInt() and 0xFF
        val route = header and 0x03
        if (route == 0 || route == 3) i += 4   // transport codes
        if (i >= raw.size) return null
        val pathLen = raw[i++].toInt() and 0xFF
        val pathBytes = (pathLen and 63) * hashSize(pathLen)
        if (i + pathBytes >= raw.size) return null
        val path = raw.copyOfRange(i, i + pathBytes)
        i += pathBytes
        return MeshPacket(route, (header shr 2) and 0x0F, pathLen, path, raw.copyOfRange(i, raw.size))
    }

    /**
     * Decrypts a GRP_TXT payload (channel hash, 2-byte MAC, AES-128 blocks) with a channel secret, as
     * MeshCore's Utils::MACThenDecrypt does. Returns the sender timestamp and the "sender: text" string, or
     * null if the MAC says it is not this channel.
     */
    fun decryptGroupText(secret: ByteArray, payload: ByteArray): Pair<Long, String>? {
        if (secret.size < 16 || payload.size < 3 + 16 || (payload.size - 3) % 16 != 0) return null
        val cipher = payload.copyOfRange(3, payload.size)
        val hmacKey = secret.copyOf(32)   // the node keeps channel secrets in a 32-byte buffer
        val mac = javax.crypto.Mac.getInstance("HmacSHA256").apply {
            init(javax.crypto.spec.SecretKeySpec(hmacKey, "HmacSHA256"))
        }.doFinal(cipher)
        if (mac[0] != payload[1] || mac[1] != payload[2]) return null
        val plain = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(secret.copyOf(16), "AES"))
        }.doFinal(cipher)
        if (plain.size < 5) return null
        val ts = le(plain, 0).getInt(0).toLong() and 0xFFFFFFFFL
        var end = 5
        while (end < plain.size && plain[end].toInt() != 0) end++
        return ts to String(plain, 5, end - 5, Charsets.UTF_8)
    }

    /** RESP_STATS of the local node (core, radio or packets). */
    fun parseStats(f: ByteArray, into: NodeStats): NodeStats {
        if (f.size < 2) return into
        val b = le(f, 0)
        fun u32(at: Int) = b.getInt(at).toLong() and 0xFFFFFFFFL
        return when (f[1].toInt()) {
            STATS_CORE -> if (f.size >= 11) into.copy(uptimeSecs = u32(4), queueLen = f[10].toInt() and 0xFF) else into
            STATS_RADIO -> if (f.size >= 14) {
                into.copy(noiseFloor = b.getShort(2).toInt(), lastRssi = f[4].toInt(), lastSnr = f[5] / 4.0f,
                    txAirSecs = u32(6), rxAirSecs = u32(10))
            } else into
            STATS_PACKETS -> if (f.size >= 30) {
                into.copy(recv = u32(2), sent = u32(6), sentFlood = u32(10), sentDirect = u32(14),
                    recvFlood = u32(18), recvDirect = u32(22), recvErrors = u32(26))
            } else into
            else -> into
        }
    }

    // ---- meshcore:// links (docs/qr_codes.md of MeshCore) ----

    fun contactUri(name: String, pubKey: ByteArray, type: Int): String =
        "meshcore://contact/add?name=${java.net.URLEncoder.encode(name, "UTF-8")}&public_key=${pubKey.toHex()}&type=$type"

    fun channelUri(name: String, secret: ByteArray): String =
        "meshcore://channel/add?name=${java.net.URLEncoder.encode(name, "UTF-8")}&secret=${secret.toHex()}"

    fun parseUri(text: String): MeshLink? {
        val t = text.trim()
        if (!t.startsWith("meshcore://")) return null
        val rest = t.removePrefix("meshcore://")
        val path = rest.substringBefore('?')
        val params = rest.substringAfter('?', "").split('&').filter { '=' in it }.associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        return when (path) {
            "contact/add" -> {
                val key = params["public_key"]?.hexToBytes()?.takeIf { it.size == 32 } ?: return null
                MeshLink.AddContact(params["name"] ?: key.copyOf(4).toHex(), key, params["type"]?.toIntOrNull() ?: ADV_TYPE_CHAT)
            }
            "channel/add" -> {
                val secret = params["secret"]?.hexToBytes()?.takeIf { it.size == 16 } ?: return null
                MeshLink.AddChannel(params["name"] ?: "Channel", secret)
            }
            else -> null
        }
    }
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
            Protocol.ADV_TYPE_REPEATER -> tr("Repeater", "Repetidor")
            Protocol.ADV_TYPE_ROOM -> tr("Room", "Sala")
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
    val txtType: Int = Protocol.TXT_TYPE_PLAIN,
    val authorPrefix: ByteArray? = null,
)

data class LoginResult(val prefix: ByteArray, val success: Boolean, val isAdmin: Boolean)

data class RepeaterStatus(
    val prefix: ByteArray,
    val battMv: Int,
    val txQueue: Int,
    val noiseFloor: Int,
    val lastRssi: Int,
    val packetsRecv: Long,
    val packetsSent: Long,
    val airTimeSecs: Long,
    val uptimeSecs: Long,
    val sentFlood: Long,
    val sentDirect: Long,
    val recvFlood: Long,
    val recvDirect: Long,
    val lastSnr: Float,
)

/** A raw mesh packet: route type, payload type (5 = GRP_TXT), encoded path length, path hashes and payload. */
data class MeshPacket(val routeType: Int, val payloadType: Int, val pathLen: Int, val path: ByteArray, val payload: ByteArray)

/** A trace ("ping"): [hops] are the hop hashes in order, [snrs] the SNR each heard it at, [finalSnr] ours. */
data class TraceResult(val tag: Int, val hops: List<String>, val snrs: List<Float>, val finalSnr: Float)

data class PathDiscovery(val prefix: ByteArray, val outLen: Int, val outPath: ByteArray, val inLen: Int, val inPath: ByteArray)

data class NodeStats(
    val uptimeSecs: Long = 0,
    val queueLen: Int = 0,
    val noiseFloor: Int = 0,
    val lastRssi: Int = 0,
    val lastSnr: Float = 0f,
    val txAirSecs: Long = 0,
    val rxAirSecs: Long = 0,
    val recv: Long = 0,
    val sent: Long = 0,
    val sentFlood: Long = 0,
    val sentDirect: Long = 0,
    val recvFlood: Long = 0,
    val recvDirect: Long = 0,
    val recvErrors: Long = 0,
)

sealed class MeshLink {
    data class AddContact(val name: String, val pubKey: ByteArray, val type: Int) : MeshLink()
    data class AddChannel(val name: String, val secret: ByteArray) : MeshLink()
}
