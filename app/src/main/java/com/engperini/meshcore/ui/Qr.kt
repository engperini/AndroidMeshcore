package com.engperini.meshcore.ui

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.core.MeshLink
import com.engperini.meshcore.core.Protocol
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** QR code of a meshcore:// link, compatible with the official MeshCore app. */
@Composable
fun QrDialog(title: String, subtitle: String, uri: String, onDismiss: () -> Unit) {
    val bmp = remember(uri) { qrBitmap(uri).asImageBitmap() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mesh.SURFACE_2,
        title = { Text(title) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(bmp, null, Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(6.dp))
                Muted(subtitle, size = 12)
                Text(uri, color = Mesh.MUTED, fontSize = 10.sp, maxLines = 3)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Copiar", Modifier.weight(1f)) { clipboard.setText(AnnotatedString(uri)) }
                    Pill("Enviar", Modifier.weight(1f)) {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, uri)
                        context.startActivity(Intent.createChooser(send, title))
                    }
                }
            }
        },
        confirmButton = { Pill("Fechar", style = PillStyle.TEXT, onClick = onDismiss) },
    )
}

/** Opens the camera to read a meshcore:// QR code. */
@Composable
fun rememberQrScanner(onLink: (MeshLink?) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { onLink(Protocol.parseUri(it)) }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Aponte para o QR code do contato ou canal")
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}
