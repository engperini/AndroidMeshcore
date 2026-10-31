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
