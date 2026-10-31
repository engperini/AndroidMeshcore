package com.engperini.meshcore.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.engperini.meshcore.MainActivity
import com.engperini.meshcore.R
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.NativeMesh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that owns the USB connection to the MeshStick and keeps
 * the native MeshCore node running while the screen is off.
 */
class MeshService : Service() {
    private var connection: UsbDeviceConnection? = null
    private var device: UsbDevice? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var messageNotifId = 100

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val d = intent.usbDevice() ?: return
            if (d.deviceName == device?.deviceName) {
                MeshController.setNoDevice("MeshStick desconectado")
                stopSelf()
            }
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val mv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            NativeMesh.nativeSetBattery(mv, plugged)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MeshController.init(this)
        createChannels()
        ContextCompat.registerReceiver(
            this, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_EXPORTED,
        )
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        MeshController.onIncoming = { n -> notifyMessage(n.title, n.text) }
        scope.launch {
            MeshController.link.collect {
                updateForeground(it.message, it.state)
                // Radio failed to start or the stick stopped answering: release USB and the wake lock.
                if (it.state == LinkState.ERROR) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Iniciando…")
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val target = intent?.usbDevice() ?: UsbHelper.findMeshStick(usb)
        if (target == null || !usb.hasPermission(target)) {
            MeshController.setNoDevice("Conecte o MeshStick via USB OTG")
            stopSelf()
            return START_NOT_STICKY
        }
        val state = MeshController.link.value.state
        if (target.deviceName == device?.deviceName && (state == LinkState.RUNNING || state == LinkState.STARTING)) {
            return START_STICKY
        }
        openDevice(usb, target)
        return START_STICKY
    }

    private fun openDevice(usb: UsbManager, target: UsbDevice) {
        closeDevice()
        val conn = usb.openDevice(target)
        if (conn == null) {
            MeshController.setNoDevice("Não foi possível abrir o dispositivo USB")
            stopSelf()
            return
        }
        // Claim with force=true so Android detaches its CDC/serial driver first.
        conn.claimInterface(target.getInterface(0), true)
        connection = conn
        device = target
        acquireWakeLock()
        val dataDir = File(filesDir, "meshcore").apply { mkdirs() }
        NativeMesh.nativeStart(dataDir.absolutePath, conn.fileDescriptor)
    }

    private fun closeDevice() {
        if (connection != null) {
            NativeMesh.nativeStop()
            connection?.close()
            connection = null
            device = null
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroidMeshcore:radio").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onDestroy() {
        closeDevice()
        wakeLock?.let { if (it.isHeld) it.release() }
        unregisterReceiver(detachReceiver)
        unregisterReceiver(batteryReceiver)
        MeshController.onIncoming = null
        if (MeshController.link.value.state == LinkState.RUNNING || MeshController.link.value.state == LinkState.STARTING) {
            MeshController.setNoDevice("Serviço parado")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Rádio MeshCore", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, "Mensagens", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildServiceNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("MeshCore")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openAppIntent())
            .addAction(0, "Parar", stop)
            .build()
    }

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_SERVICE, buildServiceNotification(text), type)
    }

    private fun updateForeground(text: String, state: LinkState) {
        val label = when (state) {
            LinkState.RUNNING -> "Rádio ativo — ${MeshController.self.value?.name ?: ""}"
            else -> text
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_SERVICE, buildServiceNotification(label))
    }

    private fun notifyMessage(title: String, text: String) {
        val n = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(messageNotifId++, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted.
        }
    }

    companion object {
        const val ACTION_STOP = "com.engperini.meshcore.STOP"
        private const val CH_SERVICE = "service"
        private const val CH_MESSAGES = "messages"
        private const val NOTIF_SERVICE = 1

        fun start(context: Context, device: UsbDevice?) {
            val i = Intent(context, MeshService::class.java)
            if (device != null) i.putExtra(UsbManager.EXTRA_DEVICE, device)
            ContextCompat.startForegroundService(context, i)
        }
    }
}

@Suppress("DEPRECATION")
fun Intent.usbDevice(): UsbDevice? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
    else getParcelableExtra(UsbManager.EXTRA_DEVICE)
