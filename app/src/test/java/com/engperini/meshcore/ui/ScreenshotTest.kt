package com.engperini.meshcore.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.engperini.meshcore.core.Channel
import com.engperini.meshcore.core.CliLine
import com.engperini.meshcore.core.Contact
import com.engperini.meshcore.core.DeviceInfo
import com.engperini.meshcore.core.LoginResult
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.NodeStats
import com.engperini.meshcore.core.PacketLogEntry
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.core.RepeaterStatus
import com.engperini.meshcore.core.SelfInfo
import com.engperini.meshcore.data.ConvKey
import com.engperini.meshcore.data.MsgStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the main screens with sample data to app/build/screenshots (visual check, no device needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
open class ScreenshotTest {
    /** Language of the screenshots; [ScreenshotTestEn] renders them in English. */
    open val portuguese = true

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun key(seed: Int) = ByteArray(32) { (it * 7 + seed).toByte() }
    private fun contact(name: String, type: Int, seed: Int, hops: Int, lat: Double = 0.0, lon: Double = 0.0, agoSec: Long = 300) =
        Contact(key(seed), type, 0, hops, name, System.currentTimeMillis() / 1000 - agoSec, lat, lon, 0, ByteArray(148))

    private val repeater = contact("Repetidor Jundiaí", Protocol.ADV_TYPE_REPEATER, 3, 0, -23.18, -46.88, 120)
    private val heltec = contact("Aparec | HeltecV4", Protocol.ADV_TYPE_CHAT, 1, 1, agoSec = 60)

    @Before
    fun setUp() {
        com.engperini.meshcore.core.Lang.portuguese = portuguese
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val self = SelfInfo(1, 22, 22, key(9), -23.2, -46.9, 0, 1, 0, false, 923125, 62500, 8, 8, "Arthur | AndroidFirmware")
        val device = DeviceInfo(10, 500, 20, "6 Mar 2026", "Android", "v1.14.0-android", false, 1)
        val contacts = listOf(
            heltec, repeater,
            contact("Sala MeshCore BR", Protocol.ADV_TYPE_ROOM, 5, 2, agoSec = 3600),
            contact("PY2MSB-Renato", Protocol.ADV_TYPE_CHAT, 7, 3, -23.0, -46.7, 7200),
        )
        val channels = listOf(
            Channel(0, "Public", "8b3387e9c5cdea6ac9e5edbaa115cd72".chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
            Channel(1, "#brasil", Protocol.hashtagSecret("#brasil")),
            Channel(2, "", ByteArray(16)),
        )
        val now = System.currentTimeMillis()
        val log = listOf(
            PacketLogEntry(now, -10.5f, -119, 45, "FLOOD", "GRP_TXT"), PacketLogEntry(now - 6e4.toLong(), 13.5f, -40, 8, "DIRECT", "ACK"),
            PacketLogEntry(now - 7e4.toLong(), 12.3f, -41, 8, "DIRECT", "ACK"), PacketLogEntry(now - 2e5.toLong(), -8.5f, -118, 45, "FLOOD", "GRP_TXT"),
            PacketLogEntry(now - 2.1e5.toLong(), -7.3f, -118, 45, "FLOOD", "GRP_TXT"), PacketLogEntry(now - 4e5.toLong(), 12.3f, -48, 22, "DIRECT", "TXT_MSG"),
            PacketLogEntry(now - 4.3e5.toLong(), -13.3f, -118, 47, "FLOOD", "GRP_TXT"), PacketLogEntry(now - 4.6e5.toLong(), 14.3f, -50, 8, "DIRECT", "ACK"),
            PacketLogEntry(now - 5e5.toLong(), -2.0f, -101, 120, "FLOOD", "ADVERT"),
        )
        val stats = NodeStats(uptimeSecs = 5400, noiseFloor = -120, lastRssi = -119, lastSnr = -10.5f, txAirSecs = 4, rxAirSecs = 9,
            recv = 8, sent = 4, sentFlood = 2, sentDirect = 2, recvFlood = 5, recvDirect = 3)
        MeshController.initForPreview(ctx, self, device, contacts, channels, stats, log)
        com.engperini.meshcore.core.LoraController.preview(
            com.engperini.meshcore.core.RadioMode.MESHCORE, com.engperini.meshcore.core.LoraConfig(), emptyList(),
        )

        val store = MeshController.messages
        val pub = ConvKey.channel(channels[0].secretHex)
        val t = now / 1000
        store.clear(pub)
        store.insert(pub, false, "🍀PY2MSB-Renato", "Bom dia", t - 600, MsgStatus.RECEIVED, -13.3f, 3)
        store.insert(pub, true, self.name, "Bom dia! Testando o MeshStick no celular", t - 500, MsgStatus.SENT)
        store.insert(pub, false, "Aparec | HeltecV4", "Chegou aqui com SNR bom 👍", t - 400, MsgStatus.RECEIVED, 12.0f, 1, unread = true)
        val dm = ConvKey.contact(heltec.pubKey.copyOf(6).joinToString("") { "%02x".format(it) })
        store.clear(dm)
        store.insert(dm, false, heltec.name, "test", t - 300, MsgStatus.RECEIVED, 12.3f, null)
        store.insert(dm, true, self.name, "Recebido!", t - 200, MsgStatus.DELIVERED)
        store.insert(dm, true, self.name, "Esta não teve confirmação", t - 100, MsgStatus.FAILED)
    }

    private fun shot(name: String, content: @Composable () -> Unit) {
        // Screens poll stats in an endless LaunchedEffect: drive the clock by hand.
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MeshTheme {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF060A13), Color(0xFF0A1424), Color(0xFF0B2530))),
                    ),
                ) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(1000)
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "${if (portuguese) "pt" else "en"}-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun chats() = shot("1-conversas") { Home(Tab.CHATS, {}, Nav(), {}) }
    @Test fun contacts() = shot("2-contatos") { Home(Tab.CONTACTS, {}, Nav(), {}) }
    @Test fun network() = shot("4-rede") { Home(Tab.NETWORK, {}, Nav(), {}) }
    @Test fun settings() = shot("5-config") { Home(Tab.SETTINGS, {}, Nav(), {}) }

    @Test fun chat() = shot("6-chat-canal") {
        ChatScreen(Conversation(ConvKey.channel(MeshController.channels.value[0].secretHex), "Public", true, channelIndex = 0), Nav())
    }

    @Test fun direct() = shot("7-chat-direto") {
        val p = heltec.pubKey.copyOf(6).joinToString("") { "%02x".format(it) }
        ChatScreen(Conversation(ConvKey.contact(p), heltec.name, false, prefixHex = p), Nav())
    }

    @Test fun contactDetail() = shot("8-contato") { ContactScreen(repeater.keyHex, Nav()) }

    private fun loraPreview() {
        val now = System.currentTimeMillis()
        com.engperini.meshcore.core.LoraController.preview(
            com.engperini.meshcore.core.RadioMode.LORA,
            com.engperini.meshcore.core.LoraConfig(915000, 125000, 7, 5, 0x12, 14, 8),
            listOf(
                com.engperini.meshcore.core.RawPacket(now - 9000, false, "temp=24.6;hum=61".toByteArray(), -97f, 7.25f),
                com.engperini.meshcore.core.RawPacket(now - 6000, false, byteArrayOf(0x01, 0x7F, 0x22, 0x00, 0x13), -104f, 2.5f),
                com.engperini.meshcore.core.RawPacket(now - 4000, true, "ping".toByteArray(), sent = true),
                com.engperini.meshcore.core.RawPacket(now - 2000, false, "pong from node 7".toByteArray(), -88f, 9.0f),
            ),
        )
    }

    @Test fun loraTerminal() = shot("10-lora") { loraPreview(); Home(Tab.CHATS, {}, Nav(), {}) }
    @Test fun loraSettings() = shot("11-lora-config") { loraPreview(); Home(Tab.SETTINGS, {}, Nav(), {}) }

    @Test fun admin() = shot("9-admin-repetidor") {
        val p = repeater.pubKey.copyOf(6).joinToString("") { "%02x".format(it) }
        MeshController.previewAdmin(
            p, LoginResult(repeater.pubKey.copyOf(6), true, true),
            RepeaterStatus(repeater.pubKey.copyOf(6), 4120, 0, -118, -92, 15234, 8231, 3600, 412000, 4000, 2100, 9800, 5400, -4.25f),
            listOf(CliLine(System.currentTimeMillis() - 5000, true, "ver"), CliLine(System.currentTimeMillis() - 3000, false, "v1.14.0 (6 Mar 2026)")),
        )
        AdminScreen(repeater.keyHex, Nav())
    }
}

/** Same screens in English. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTestEn : ScreenshotTest() {
    override val portuguese = false
}
