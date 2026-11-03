package com.engperini.meshcore.mt

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log

/**
 * Runs the Meshtastic node in its own process (android:process=":meshtastic"). The firmware is made of
 * process-wide singletons that boot once, like on a radio board: a node reboot, or leaving Meshtastic mode,
 * ends this process and the next start is a fresh boot. The app (MeshtasticController) talks to it with
 * [Messenger] messages; the USB connection stays with the app's MeshService and arrives here as a duplicate.
 */
class MtEngineService : Service() {
    private var client: Messenger? = null
    private var started = false

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val d = msg.data ?: Bundle()
            when (msg.what) {
                MSG_START -> start(msg.replyTo, d)
                MSG_CALL -> call(d)
            }
        }
    }
    private val messenger = Messenger(handler)

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onCreate() {
        super.onCreate()
        MtNative.listener = { json ->
            val m = Message.obtain(null, MSG_EVENT)
            m.data = Bundle().apply { putString(KEY_JSON, json) }
            try {
                client?.send(m)
            } catch (_: RemoteException) {
                // The app went away; it ends this process.
            }
        }
    }

    private fun start(replyTo: Messenger?, d: Bundle) {
        client = replyTo
        if (started) return
        @Suppress("DEPRECATION")
        val pfd: ParcelFileDescriptor = d.getParcelable(KEY_USB) ?: return
        started = true
        MtNative.start(
            d.getString(KEY_DIR) ?: filesDir.absolutePath, pfd.detachFd(), d.getInt(KEY_KIND),
            d.getInt(KEY_EP_IN, -1), d.getInt(KEY_EP_OUT, -1), d.getInt(KEY_IFACE, -1),
        )
    }

    private fun call(d: Bundle) {
        if (!started) return
        when (d.getString(KEY_OP)) {
            OP_CONFIG -> MtNative.requestConfig(d.getInt("nonce"))
            OP_TEXT -> MtNative.sendText(d.getInt("id"), d.getInt("to"), d.getInt("ch"), d.getString("text") ?: "")
            OP_TRACE -> MtNative.traceroute(d.getInt("id"), d.getInt("to"), d.getInt("ch"))
            OP_OWNER -> MtNative.setOwner(d.getString("long") ?: "", d.getString("short") ?: "")
            OP_LORA -> MtNative.setLora(d.getInt("region"), d.getInt("preset"), d.getInt("hops"), d.getBoolean("tx"), d.getInt("power"))
            OP_CHANNEL -> MtNative.setChannel(d.getInt("index"), d.getInt("role"), d.getString("name") ?: "", d.getByteArray("psk"))
            OP_REMOVE -> MtNative.removeNode(d.getInt("num"))
            OP_NODEINFO -> MtNative.sendNodeInfo()
            OP_POSITION -> MtNative.setPosition(d.getInt("lat"), d.getInt("lon"), d.getInt("alt"))
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // The app is done with this node (mode change, radio unplugged, reboot): end the process, which
        // releases the radio. The node saved its state as it went.
        Log.i(TAG, "unbound, ending the Meshtastic process")
        Process.killProcess(Process.myPid())
        return false
    }

    companion object {
        private const val TAG = "MtEngine"
        const val MSG_START = 1
        const val MSG_CALL = 2
        const val MSG_EVENT = 3

        const val KEY_USB = "usb"
        const val KEY_DIR = "dir"
        const val KEY_KIND = "kind"
        const val KEY_EP_IN = "ep_in"
        const val KEY_EP_OUT = "ep_out"
        const val KEY_IFACE = "iface"
        const val KEY_JSON = "json"
        const val KEY_OP = "op"

        const val OP_CONFIG = "config"
        const val OP_TEXT = "text"
        const val OP_TRACE = "trace"
        const val OP_OWNER = "owner"
        const val OP_LORA = "lora"
        const val OP_CHANNEL = "channel"
        const val OP_REMOVE = "remove"
        const val OP_POSITION = "position"
        const val OP_NODEINFO = "nodeinfo"
    }
}
