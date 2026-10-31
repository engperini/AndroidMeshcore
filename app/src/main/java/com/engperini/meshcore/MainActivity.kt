package com.engperini.meshcore

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.engperini.meshcore.core.LinkState
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.MeshLink
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.service.MeshService
import com.engperini.meshcore.service.UsbHelper
import com.engperini.meshcore.service.usbDevice
import com.engperini.meshcore.ui.App
import com.engperini.meshcore.ui.MeshTheme

class MainActivity : ComponentActivity() {
    /** meshcore:// link opened from a QR code or another app, waiting for confirmation. */
    private var pendingLink by mutableStateOf<MeshLink?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MeshController.init(this)
        requestNotificationPermission()
        handleIntent(intent)
        setContent {
            MeshTheme {
                App(
                    onConnect = { UsbHelper.connect(this) },
                    incomingLink = pendingLink,
                    onLinkHandled = { pendingLink = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data?.scheme == "meshcore") {
            pendingLink = Protocol.parseUri(intent.dataString ?: "")
            if (MeshController.link.value.state !in listOf(LinkState.RUNNING, LinkState.STARTING)) UsbHelper.connect(this)
        } else if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            // Opened by plugging in the stick: Android already granted access to it.
            MeshService.start(this, intent.usbDevice())
        } else if (MeshController.link.value.state !in listOf(LinkState.RUNNING, LinkState.STARTING)) {
            UsbHelper.connect(this)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
