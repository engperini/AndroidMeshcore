package com.engperini.meshcore.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Runs two copies of the real MeshCore companion firmware (tools/hosttest/build/host_node,
 * built from the same sources as libmeshcore.so) linked by a UDP "radio", and drives them
 * with the app's [Protocol] encoder/decoder: adverts, direct messages with ACK, channels.
 *
 * Build the node first: tools/hosttest/build.sh (skipped otherwise).
 */
class TwoNodeMeshTest {
    private class Node(binary: String, index: Int, basePort: Int) {
        val dir: File = Files.createTempDirectory("node$index").toFile()
        val proc: Process = ProcessBuilder(binary, dir.absolutePath, index.toString(), "2", basePort.toString())
            .redirectErrorStream(false).start()
        val frames = LinkedBlockingQueue<ByteArray>()
        private val out = proc.outputStream.bufferedWriter()

        init {
            Thread {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    if (line.startsWith("< ")) frames.add(line.substring(2).hexToBytes()!!)
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(frame: ByteArray) {
            out.write(frame.toHex() + "\n")
            out.flush()
        }

        /** Next frame matching [pred], skipping others (pushes may interleave). */
        fun await(timeoutMs: Long = 5000, pred: (ByteArray) -> Boolean): ByteArray {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                val f = frames.poll(maxOf(1, left), TimeUnit.MILLISECONDS) ?: throw AssertionError("timeout waiting for frame")
                if (pred(f)) return f
            }
        }

        fun call(frame: ByteArray, code: Int): ByteArray {
            send(frame)
            return await { (it[0].toInt() and 0xFF) == code || it[0].toInt() == Protocol.RESP_ERR }
                .also { assertEquals("response to cmd ${frame[0]}", code, it[0].toInt() and 0xFF) }
        }

        fun syncOne(): Incoming {
            send(Protocol.syncNextMessage())
            val f = await {
                it[0].toInt() in listOf(Protocol.RESP_CONTACT_MSG_RECV_V3, Protocol.RESP_CHANNEL_MSG_RECV_V3, Protocol.RESP_NO_MORE_MESSAGES)
            }
            return Protocol.parseIncoming(f) ?: throw AssertionError("no message queued")
        }

        fun stop() {
            proc.destroy()
            dir.deleteRecursively()
        }
    }

    private lateinit var a: Node
    private lateinit var b: Node

    @Before
    fun setUp() {
        val bin = System.getProperty("hostNode") ?: ""
        assumeTrue("host_node not built (run tools/hosttest/build.sh)", File(bin).canExecute())
        val port = 47000 + (System.nanoTime() % 1000).toInt() * 2
        a = Node(bin, 0, port)
        b = Node(bin, 1, port)
    }

    @After
    fun tearDown() {
        if (::a.isInitialized) a.stop()
        if (::b.isInitialized) b.stop()
    }

    private fun handshake(n: Node, name: String): SelfInfo {
        val info = Protocol.parseDeviceInfo(n.call(Protocol.deviceQuery(), Protocol.RESP_DEVICE_INFO))
        assertNotNull(info)
        assertEquals("v1.14.0-android", info!!.version)
        assertEquals(20, info.maxChannels)
        n.call(Protocol.setAdvertName(name), Protocol.RESP_OK)
        val self = Protocol.parseSelfInfo(n.call(Protocol.appStart("test"), Protocol.RESP_SELF_INFO))!!
        assertEquals(name, self.name)
        assertEquals(923125, self.freqKhz)
        assertEquals(62500, self.bwHz)
        assertEquals(8, self.sf)
        assertEquals(8, self.cr)
        return self
    }

    @Test
    fun advertsDirectMessagesAndChannels() {
        val selfA = handshake(a, "Alice")
        val selfB = handshake(b, "Bob")

        // Adverts: auto-added contacts are announced with PUSH_ADVERT (pub key only).
        a.call(Protocol.sendSelfAdvert(true), Protocol.RESP_OK)
        val bobHeard = b.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_ADVERT }
        assertArrayEquals(selfA.pubKey, bobHeard.copyOfRange(1, 33))
        b.call(Protocol.sendSelfAdvert(true), Protocol.RESP_OK)
        a.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_ADVERT }

        // Contact list.
        a.send(Protocol.getContacts())
        a.await { it[0].toInt() == Protocol.RESP_CONTACTS_START }
        val contact = Protocol.parseContact(a.await { it[0].toInt() == Protocol.RESP_CONTACT })!!
        assertEquals("Bob", contact.name)
        assertArrayEquals(selfB.pubKey, contact.pubKey)
        assertEquals(Protocol.ADV_TYPE_CHAT, contact.type)
        a.await { it[0].toInt() == Protocol.RESP_END_OF_CONTACTS }

        // Direct message with delivery ACK.
        val sent = a.call(Protocol.sendText(selfB.pubKey, "Olá Bob!", System.currentTimeMillis() / 1000), Protocol.RESP_SENT)
        val ack = ByteBuffer.wrap(sent).order(ByteOrder.LITTLE_ENDIAN).getInt(2)
        b.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_MSG_WAITING }
        val dm = b.syncOne()
        assertEquals("Olá Bob!", dm.text)
        assertArrayEquals(selfA.pubKey.copyOf(6), dm.senderPrefix)
        assertEquals(null, dm.channelIdx)
        val confirmed = a.await(8000) { (it[0].toInt() and 0xFF) == Protocol.PUSH_SEND_CONFIRMED }
        assertEquals(ack, ByteBuffer.wrap(confirmed).order(ByteOrder.LITTLE_ENDIAN).getInt(1))

        // Public channel (slot 0 preconfigured by the firmware).
        val pub = Protocol.parseChannel(a.call(Protocol.getChannel(0), Protocol.RESP_CHANNEL_INFO))!!
        assertEquals("Public", pub.name)
        a.call(Protocol.sendChannelText(0, "oi pessoal", System.currentTimeMillis() / 1000), Protocol.RESP_OK)
        b.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_MSG_WAITING }
        val grp = b.syncOne()
        assertEquals(0, grp.channelIdx)
        assertEquals("Alice: oi pessoal", grp.text)

        // Hashtag channel added on both sides by name only.
        val secret = Protocol.hashtagSecret("#teste")
        a.call(Protocol.setChannel(1, "#teste", secret), Protocol.RESP_OK)
        b.call(Protocol.setChannel(1, "#teste", secret), Protocol.RESP_OK)
        val ch = Protocol.parseChannel(b.call(Protocol.getChannel(1), Protocol.RESP_CHANNEL_INFO))!!
        assertEquals("#teste", ch.name)
        assertArrayEquals(secret, ch.secret)
        b.call(Protocol.sendChannelText(1, "canal hashtag ok", System.currentTimeMillis() / 1000), Protocol.RESP_OK)
        a.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_MSG_WAITING }
        val tag = a.syncOne()
        assertEquals(1, tag.channelIdx)
        assertEquals("Bob: canal hashtag ok", tag.text)

        // Settings commands used by the app.
        a.call(Protocol.setRadioParams(869618, 62500, 8, 8, false), Protocol.RESP_OK)
        a.call(Protocol.setTxPower(14), Protocol.RESP_OK)
        a.call(Protocol.setPathHashMode(1), Protocol.RESP_OK)
        val self = Protocol.parseSelfInfo(a.call(Protocol.appStart("test"), Protocol.RESP_SELF_INFO))!!
        assertEquals(869618, self.freqKhz)
        assertEquals(14, self.txPower)
        assertEquals(1, Protocol.parseDeviceInfo(a.call(Protocol.deviceQuery(), Protocol.RESP_DEVICE_INFO))!!.pathHashMode)
        a.call(Protocol.setLatLon(-23.5, -46.6), Protocol.RESP_OK)
        a.call(Protocol.resetPath(selfB.pubKey), Protocol.RESP_OK)
        a.call(Protocol.removeContact(selfB.pubKey), Protocol.RESP_OK)
        assertTrue(Protocol.hops(0xFF) == null)
    }

    @Test
    fun sharingStatsBackupAndPaths() {
        val selfA = handshake(a, "Alice")
        val selfB = handshake(b, "Bob")

        // Hashtag key vector from MeshCore docs/companion_protocol.md.
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", Protocol.hashtagSecret("#test").toHex())

        // meshcore:// contact link: Alice adds Bob without any advert.
        val uri = Protocol.contactUri("Bob Ç", selfB.pubKey, Protocol.ADV_TYPE_CHAT)
        val link = Protocol.parseUri(uri) as MeshLink.AddContact
        assertEquals("Bob Ç", link.name)
        a.call(Protocol.addContactByKey(link.pubKey, link.name, link.type), Protocol.RESP_OK)
        a.send(Protocol.getContacts())
        val added = Protocol.parseContact(a.await { it[0].toInt() == Protocol.RESP_CONTACT })!!
        assertArrayEquals(selfB.pubKey, added.pubKey)
        assertEquals("Bob Ç", added.name)
        assertEquals(Protocol.OUT_PATH_UNKNOWN, added.outPathLen)

        // ... and can message him right away (Bob learns Alice from the message? no: he needs her key).
        b.call(Protocol.addContactByKey(selfA.pubKey, "Alice", Protocol.ADV_TYPE_CHAT), Protocol.RESP_OK)
        a.call(Protocol.sendText(selfB.pubKey, "via QR", System.currentTimeMillis() / 1000), Protocol.RESP_SENT)
        b.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_MSG_WAITING }
        assertEquals("via QR", b.syncOne().text)

        // Saving a path on a contact.
        val withPath = Protocol.setContactPath(added.raw, 0x42, byteArrayOf(0x11, 0x22, 0x33, 0x44))
        a.call(withPath, Protocol.RESP_OK)
        a.send(Protocol.getContacts())
        val routed = Protocol.parseContact(a.await { it[0].toInt() == Protocol.RESP_CONTACT })!!
        assertEquals(0x42, routed.outPathLen)
        assertEquals(listOf("1122", "3344"), Protocol.pathHops(routed.outPathLen, routed.raw.copyOfRange(36, 100)))
        a.call(Protocol.resetPath(selfB.pubKey), Protocol.RESP_OK)

        // Channel link round trip.
        val ch = Protocol.parseUri(Protocol.channelUri("#teste", Protocol.hashtagSecret("#teste"))) as MeshLink.AddChannel
        assertEquals("#teste", ch.name)
        assertArrayEquals(Protocol.hashtagSecret("#teste"), ch.secret)
        assertEquals(null, Protocol.parseUri("https://example.com"))

        // Local stats.
        var st = NodeStats()
        for (t in listOf(Protocol.STATS_CORE, Protocol.STATS_RADIO, Protocol.STATS_PACKETS)) {
            st = Protocol.parseStats(a.call(Protocol.getStats(t), Protocol.RESP_STATS), st)
        }
        assertTrue(st.sent >= 1)
        assertTrue(st.uptimeSecs >= 0)

        // Auto-add config.
        a.call(Protocol.setAutoAddConfig(Protocol.AUTOADD_CHAT or Protocol.AUTOADD_REPEATER, 3), Protocol.RESP_OK)
        val cfg = a.call(Protocol.getAutoAddConfig(), Protocol.RESP_AUTOADD_CONFIG)
        assertEquals(Protocol.AUTOADD_CHAT or Protocol.AUTOADD_REPEATER, cfg[1].toInt())
        assertEquals(3, cfg[2].toInt())

        // Identity backup: export, then import it into Bob's node.
        val key = a.call(Protocol.exportPrivateKey(), Protocol.RESP_PRIVATE_KEY).copyOfRange(1, 65)
        assertEquals(64, key.size)
        b.call(Protocol.importPrivateKey(key), Protocol.RESP_OK)
        val bAsAlice = Protocol.parseSelfInfo(b.call(Protocol.appStart("test"), Protocol.RESP_SELF_INFO))!!
        assertArrayEquals(selfA.pubKey, bAsAlice.pubKey)
    }

    @Test
    fun pathDiscoveryBetweenCompanions() {
        val selfA = handshake(a, "Alice")
        val selfB = handshake(b, "Bob")
        a.call(Protocol.addContactByKey(selfB.pubKey, "Bob", Protocol.ADV_TYPE_CHAT), Protocol.RESP_OK)
        b.call(Protocol.addContactByKey(selfA.pubKey, "Alice", Protocol.ADV_TYPE_CHAT), Protocol.RESP_OK)
        // Bob answers path discovery only if he shares base telemetry (battery) with everyone.
        b.call(Protocol.setOtherParams(false, 2, 0, 0), Protocol.RESP_OK)
        val sent = a.call(Protocol.pathDiscovery(selfB.pubKey), Protocol.RESP_SENT)
        // The app waits for the answer using the node's own estimate.
        assertTrue("estimated timeout", (Protocol.sentTimeoutMs(sent) ?: 0L) > 0L)
        assertTrue("unknown route goes by flood", Protocol.sentFlood(sent))
        val pd = Protocol.parsePathDiscovery(a.await(8000) { (it[0].toInt() and 0xFF) == Protocol.PUSH_PATH_DISCOVERY_RESPONSE })!!
        assertArrayEquals(selfB.pubKey.copyOf(6), pd.prefix)
        assertEquals(0, Protocol.hops(pd.outLen))
    }

    @Test
    fun traceRequestIsAccepted() {
        handshake(a, "Alice")
        val selfB = handshake(b, "Bob")
        // Ping as the app builds it, to a neighbour: the path is just the target's 1-byte hash.
        val sent = a.call(Protocol.tracePath(0x12345678, 0, selfB.pubKey.copyOf(1)), Protocol.RESP_SENT)
        assertTrue("estimated timeout", (Protocol.sentTimeoutMs(sent) ?: 0L) > 0L)
        assertEquals(0x12345678, ByteBuffer.wrap(sent, 2, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        // 2-byte hashes (path size code 1) are accepted too.
        a.call(Protocol.tracePath(1, 1, selfB.pubKey.copyOf(2)), Protocol.RESP_SENT)
    }

    @Test
    fun parsesTraceData() {
        // PUSH_TRACE_DATA: code, reserved, path_len, flags, tag, auth, 2 hashes, 2 SNRs, final SNR.
        val f = byteArrayOf(
            0x89.toByte(), 0, 2, 0, 0x78, 0x56, 0x34, 0x12, 0, 0, 0, 0,
            0xA1.toByte(), 0x3F, 26, (-8).toByte(), 30,
        )
        val t = Protocol.parseTrace(f)!!
        assertEquals(0x12345678, t.tag)
        assertEquals(listOf("a1", "3f"), t.hops)
        assertEquals(listOf(6.5f, -2.0f), t.snrs)
        assertEquals(7.5f, t.finalSnr)
    }

    @Test
    fun manualAddDiscoveredContact() {
        handshake(a, "Alice")
        handshake(b, "Bob")
        // Bob only adds contacts manually: Alice's advert arrives as PUSH_NEW_ADVERT (full contact frame).
        b.call(Protocol.setOtherParams(true, 0, 0, 0), Protocol.RESP_OK)
        a.call(Protocol.sendSelfAdvert(true), Protocol.RESP_OK)
        val frame = b.await { (it[0].toInt() and 0xFF) == Protocol.PUSH_NEW_ADVERT }
        val discovered = Protocol.parseContact(frame)!!
        assertEquals("Alice", discovered.name)
        b.call(Protocol.addContact(discovered.raw), Protocol.RESP_OK)
        b.send(Protocol.getContacts())
        val saved = Protocol.parseContact(b.await { it[0].toInt() == Protocol.RESP_CONTACT })!!
        assertEquals("Alice", saved.name)
        assertArrayEquals(discovered.pubKey, saved.pubKey)
    }
}
