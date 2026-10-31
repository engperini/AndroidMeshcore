package com.engperini.meshcore.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Dark "night sky" palette shared with MeshSDR-Android. */
object Mesh {
    val BG = Color(0xFF070B14)
    val SURFACE = Color(0xE6111A2B)
    val SURFACE_2 = Color(0xFF16233A)
    val OUTLINE = Color(0xFF24344F)
    val ACCENT = Color(0xFF22D3EE)
    val ACCENT_DARK = Color(0xFF0E7490)
    val ON_ACCENT = Color(0xFF04121A)
    val TEXT = Color(0xFFE6EDF7)
    val MUTED = Color(0xFF8DA2BE)
    val GREEN = Color(0xFF34D399)
    val AMBER = Color(0xFFFBBF24)
    val RED = Color(0xFFF87171)
}

private val scheme = darkColorScheme(
    primary = Mesh.ACCENT,
    onPrimary = Mesh.ON_ACCENT,
    primaryContainer = Color(0xFF0E3A47),
    onPrimaryContainer = Mesh.TEXT,
    secondary = Mesh.ACCENT,
    onSecondary = Mesh.ON_ACCENT,
    secondaryContainer = Mesh.SURFACE_2,
    onSecondaryContainer = Mesh.TEXT,
    tertiary = Mesh.GREEN,
    background = Color.Transparent,
    onBackground = Mesh.TEXT,
    surface = Mesh.BG,
    onSurface = Mesh.TEXT,
    surfaceVariant = Mesh.SURFACE_2,
    onSurfaceVariant = Mesh.MUTED,
    surfaceContainer = Mesh.SURFACE_2,
    surfaceContainerHigh = Color(0xFF1A2A44),
    surfaceContainerHighest = Color(0xFF1E3050),
    surfaceContainerLow = Mesh.SURFACE_2,
    outline = Mesh.OUTLINE,
    outlineVariant = Mesh.OUTLINE,
    error = Mesh.RED,
)

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
