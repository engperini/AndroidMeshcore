package com.engperini.meshcore.service

import com.engperini.meshcore.core.tr
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.engperini.meshcore.core.MeshController

/** Finds the USB MeshStick (WCH CH341A in SPI mode, 1a86:5512) and asks for permission. */
object UsbHelper {
    const val VENDOR_ID = 0x1A86
    const val PRODUCT_ID = 0x5512
    private const val ACTION_PERMISSION = "com.engperini.meshcore.USB_PERMISSION"

    fun findMeshStick(usb: UsbManager): UsbDevice? =
        usb.deviceList.values.firstOrNull { it.vendorId == VENDOR_ID && it.productId == PRODUCT_ID }

    /** Starts the radio if a MeshStick is plugged in, asking for USB permission first if needed. */
    fun connect(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findMeshStick(usb)
        if (device == null) {
            MeshController.setNoDevice(tr("MeshStick not found — plug it in with an OTG cable", "MeshStick não encontrado — conecte-o via cabo OTG"))
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
}
