package com.engperini.meshcore.core

/** JNI bridge to the native MeshCore companion node (libmeshcore.so). */
object NativeMesh {
    const val STATUS_STOPPED = 0
    const val STATUS_STARTING = 1
    const val STATUS_RUNNING = 2
    const val STATUS_ERROR = 3

    @Volatile var frameListener: ((ByteArray) -> Unit)? = null
    @Volatile var statusListener: ((Int, String) -> Unit)? = null

    init {
        System.loadLibrary("meshcore")
    }

    @JvmStatic external fun nativeStart(dataDir: String, usbFd: Int)
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeSendFrame(frame: ByteArray)
    @JvmStatic external fun nativeSetBattery(millivolts: Int, charging: Boolean)
    @JvmStatic external fun nativeSetAutoAdvert(minutes: Int, flood: Boolean)
    @JvmStatic external fun nativeRadioDiag(): String

    // Called from the native mesh thread.
    @JvmStatic fun onFrame(frame: ByteArray) {
        frameListener?.invoke(frame)
    }

    @JvmStatic fun onStatus(status: Int, message: String) {
        statusListener?.invoke(status, message)
    }
}
