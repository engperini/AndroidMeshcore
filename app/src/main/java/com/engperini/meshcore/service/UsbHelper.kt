package com.engperini.meshcore.service

import com.engperini.meshcore.core.tr
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.NativeMesh

/** The USB radios the app can drive. Both put an SX1262 on the phone's SPI bus over USB. */
enum class RadioKind(val nativeId: Int, val label: String) {
    /** USB MeshStick / PineDio USB: WCH CH341A in SPI mode, 1a86:5512. */
    MESHSTICK(NativeMesh.RADIO_MESHSTICK, "MeshStick"),

    /** Seeed XIAO nRF52840 + Wio-SX1262 running the xiao-sx1262-usb-bridge firmware (USB CDC serial). */
    XIAO_BRIDGE(NativeMesh.RADIO_XIAO, "XIAO"),
}

/** Endpoints of a XIAO bridge after [UsbHelper.openXiao]. */
data class XiaoLink(val commInterface: Int, val dataInterface: Int, val epIn: Int, val epOut: Int)

/** Finds a supported USB radio and asks for permission. */
object UsbHelper {
    private const val CH341_VID = 0x1A86
    private const val CH341_PID = 0x5512
    private const val SEEED_VID = 0x2886
    private val XIAO_NRF52840_PIDS = setOf(0x8044, 0x8045)  // XIAO nRF52840, XIAO nRF52840 Sense

    private const val ACTION_PERMISSION = "com.engperini.meshcore.USB_PERMISSION"

    // CDC ACM class requests.
    private const val CDC_REQ_TYPE = 0x21
    private const val CDC_SET_LINE_CODING = 0x20
    private const val CDC_SET_CONTROL_LINE_STATE = 0x22
    private const val CDC_DTR_RTS = 0x03

    fun kindOf(device: UsbDevice): RadioKind? = when {
        device.vendorId == CH341_VID && device.productId == CH341_PID -> RadioKind.MESHSTICK
        device.vendorId == SEEED_VID && device.productId in XIAO_NRF52840_PIDS -> RadioKind.XIAO_BRIDGE
        else -> null
    }

    fun findRadio(usb: UsbManager): UsbDevice? = usb.deviceList.values.firstOrNull { kindOf(it) != null }

    /** Starts the radio if one is plugged in, asking for USB permission first if needed. */
    fun connect(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findRadio(usb)
        if (device == null) {
            MeshController.setNoDevice(
                tr(
                    "No USB radio found — plug in a MeshStick or a XIAO bridge with an OTG cable",
                    "Nenhum rádio USB encontrado — conecte um MeshStick ou um XIAO bridge via cabo OTG",
                ),
            )
            return
        }
        if (usb.hasPermission(device)) {
            MeshService.start(context, device)
            return
        }
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                app.unregisterReceiver(this)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    MeshService.start(app, device)
                } else {
                    MeshController.setNoDevice(tr("USB permission denied", "Permissão USB negada"))
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION_PERMISSION).setPackage(app.packageName), flags)
        usb.requestPermission(device, pi)
    }

    /**
     * Claims the CDC interfaces of a XIAO bridge and asserts DTR, which the bridge needs before it
     * answers. Returns null if the device does not look like a CDC serial port.
     */
    fun openXiao(conn: UsbDeviceConnection, device: UsbDevice): XiaoLink? {
        var comm: Int? = null
        var data: Int? = null
        var epIn: Int? = null
        var epOut: Int? = null
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            when (itf.interfaceClass) {
                UsbConstants.USB_CLASS_COMM -> if (comm == null) comm = itf.id
                UsbConstants.USB_CLASS_CDC_DATA -> if (data == null) {
                    var inEp: Int? = null
                    var outEp: Int? = null
                    for (e in 0 until itf.endpointCount) {
                        val ep = itf.getEndpoint(e)
                        if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                        if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep.address else outEp = ep.address
                    }
                    if (inEp != null && outEp != null) {
                        data = itf.id
                        epIn = inEp
                        epOut = outEp
                    }
                }
            }
        }
        if (comm == null || data == null || epIn == null || epOut == null) return null

        // force = true detaches Android's own CDC driver, if it bound one.
        for (i in 0 until device.interfaceCount) {
            val itf = device.getInterface(i)
            if (itf.id == comm || itf.id == data) conn.claimInterface(itf, true)
        }
        // 115200 8N1; the bridge ignores it, but some stacks expect a line coding first.
        val lineCoding = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
        conn.controlTransfer(CDC_REQ_TYPE, CDC_SET_LINE_CODING, 0, comm, lineCoding, lineCoding.size, 500)
        conn.controlTransfer(CDC_REQ_TYPE, CDC_SET_CONTROL_LINE_STATE, CDC_DTR_RTS, comm, null, 0, 500)
        return XiaoLink(comm, data, epIn, epOut)
    }

    /** Drops DTR, so the bridge puts the radio in a safe state. */
    fun closeXiao(conn: UsbDeviceConnection, link: XiaoLink) {
        conn.controlTransfer(CDC_REQ_TYPE, CDC_SET_CONTROL_LINE_STATE, 0, link.commInterface, null, 0, 200)
    }
}
