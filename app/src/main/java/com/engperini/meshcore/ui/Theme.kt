package com.engperini.meshcore.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0B7A5A)
private val GreenLight = Color(0xFF5FD4A8)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = GreenLight, secondary = GreenLight, primaryContainer = Color(0xFF0F3D30))
    } else {
        lightColorScheme(primary = Green, secondary = Green, primaryContainer = Color(0xFFCFF3E4))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
