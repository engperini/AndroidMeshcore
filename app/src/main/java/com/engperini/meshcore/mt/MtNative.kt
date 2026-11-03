package com.engperini.meshcore.mt

/**
 * JNI of the Meshtastic engine (libmeshtastic.so, the Meshtastic firmware built for Android). Used only in
 * the ":meshtastic" process by [MtEngineService]: the firmware boots once per process.
 */
object MtNative {
    const val RADIO_MESHSTICK = 0
    const val RADIO_XIAO = 1

    @Volatile var listener: ((String) -> Unit)? = null

    init {
        System.loadLibrary("meshtastic")
    }

    /** Boots the node. [fd] is a duplicate of the app's USB connection; the engine owns it from now on. */
    @JvmStatic external fun start(dataDir: String, fd: Int, kind: Int, epIn: Int, epOut: Int, iface: Int)
    @JvmStatic external fun requestConfig(nonce: Int)
    @JvmStatic external fun sendText(id: Int, to: Int, channel: Int, text: String)
    @JvmStatic external fun traceroute(id: Int, dest: Int, channel: Int)
    @JvmStatic external fun setOwner(longName: String, shortName: String)
    @JvmStatic external fun setLora(region: Int, preset: Int, hopLimit: Int, txEnabled: Boolean, txPower: Int)
    @JvmStatic external fun setChannel(index: Int, role: Int, name: String, psk: ByteArray?)
    @JvmStatic external fun removeNode(num: Int)
    @JvmStatic external fun sendNodeInfo()
    @JvmStatic external fun setPosition(latI: Int, lonI: Int, alt: Int)

    /** Called on the radio thread with one or more JSON events, one per line. */
    @JvmStatic fun onEvent(json: String) {
        listener?.invoke(json)
    }
}
