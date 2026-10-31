package com.engperini.meshcore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.engperini.meshcore.R

// Compose versions of the MeshSDR view builders (card, pill, chip, status chip, stat tile).

private val CardShape = RoundedCornerShape(20.dp)
private val PillShape = RoundedCornerShape(22.dp)

/** Translucent rounded panel. */
@Composable
fun MeshCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(Mesh.SURFACE)
            .border(1.dp, Mesh.OUTLINE, CardShape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun CardTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = Mesh.TEXT, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        trailing?.invoke()
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = Mesh.MUTED, fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

enum class PillStyle { PRIMARY, OUTLINED, TONAL, TEXT, ACTIVE }

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.TONAL,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val pad = PaddingValues(horizontal = 14.dp)
    val mod = modifier.heightIn(min = 44.dp)
    when (style) {
        PillStyle.PRIMARY -> Button(
            onClick, mod, enabled, PillShape,
            ButtonDefaults.buttonColors(containerColor = Mesh.ACCENT, contentColor = Mesh.ON_ACCENT,
                disabledContainerColor = Mesh.SURFACE_2, disabledContentColor = Mesh.MUTED),
            contentPadding = pad,
        ) { Text(text, fontWeight = FontWeight.Medium) }
        PillStyle.OUTLINED -> OutlinedButton(
            onClick, mod, enabled, PillShape,
            ButtonDefaults.outlinedButtonColors(contentColor = Mesh.TEXT), border = BorderStroke(1.dp, Mesh.OUTLINE),
            contentPadding = pad,
        ) { Text(text, fontWeight = FontWeight.Medium) }
        PillStyle.TONAL -> Button(
            onClick, mod, enabled, PillShape,
            ButtonDefaults.buttonColors(containerColor = Mesh.SURFACE_2, contentColor = Mesh.TEXT),
            contentPadding = pad,
        ) { Text(text, fontWeight = FontWeight.Medium) }
        PillStyle.TEXT -> TextButton(onClick, mod, enabled, contentPadding = pad) {
            Text(text, color = if (enabled) Mesh.ACCENT else Mesh.MUTED, fontWeight = FontWeight.Medium)
        }
        PillStyle.ACTIVE -> OutlinedButton(
            onClick, mod, enabled, PillShape,
            ButtonDefaults.outlinedButtonColors(containerColor = Mesh.GREEN.copy(alpha = 0.16f), contentColor = Mesh.GREEN),
            border = BorderStroke(1.dp, Mesh.GREEN), contentPadding = pad,
        ) { Text(text, fontWeight = FontWeight.Medium) }
    }
}

/** Small selectable chip (regions, bytes, filters). */
@Composable
fun MeshChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 38.dp)
            .clip(PillShape)
            .background(if (selected) Mesh.ACCENT.copy(alpha = 0.18f) else Mesh.SURFACE_2)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Mesh.ACCENT else Mesh.OUTLINE, PillShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (selected) Mesh.ACCENT else Mesh.TEXT, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Coloured dot + text, e.g. "● Rádio ativo". */
@Composable
fun StatusChip(text: String, color: Color) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Stat tile: small caption over a big value. */
@Composable
fun StatTile(caption: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Mesh.TEXT, sub: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Mesh.SURFACE)
            .border(1.dp, Mesh.OUTLINE, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(caption, color = Mesh.MUTED, fontSize = 11.sp, maxLines = 1)
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        if (sub != null) Text(sub, color = Mesh.MUTED, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun Logo(size: Int = 48) {
    // painterResource only loads vectors/bitmaps (not layer-lists), so stack the two layers here.
    val shape = RoundedCornerShape((size / 4).dp)
    Box(Modifier.size(size.dp).clip(shape).border(1.dp, Mesh.OUTLINE, shape)) {
        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
    }
}

@Composable
fun MeshSwitchRow(label: String, checked: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Mesh.TEXT, fontSize = 14.sp)
            if (sub != null) Muted(sub, size = 12)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Mesh.ON_ACCENT, checkedTrackColor = Mesh.ACCENT,
                uncheckedThumbColor = Mesh.MUTED, uncheckedTrackColor = Mesh.SURFACE_2, uncheckedBorderColor = Mesh.OUTLINE,
            ),
        )
    }
}

@Composable
fun MeshField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supporting: String? = null,
) {
    OutlinedTextField(
        value, onValueChange, modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Mesh.ACCENT, unfocusedBorderColor = Mesh.OUTLINE,
            focusedLabelColor = Mesh.ACCENT, unfocusedLabelColor = Mesh.MUTED, cursorColor = Mesh.ACCENT,
            focusedContainerColor = Mesh.SURFACE_2.copy(alpha = 0.5f), unfocusedContainerColor = Mesh.SURFACE_2.copy(alpha = 0.5f),
        ),
    )
}

/** Label / value row for detail cards. */
@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Mesh.TEXT) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(key, Modifier.weight(1f), color = Mesh.MUTED, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MeshAvatar(name: String, color: Color = Mesh.ACCENT, size: Int = 42) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)).border(1.dp, color.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.trim().removePrefix("#").take(1).uppercase().ifBlank { "?" }, color = color, fontWeight = FontWeight.Bold)
    }
}

/** Colour per node type, used by avatars and map markers. */
fun typeColor(type: Int): Color = when (type) {
    2 -> Mesh.AMBER   // repeater
    3 -> Color(0xFFA78BFA) // room server
    4 -> Mesh.GREEN   // sensor
    else -> Mesh.ACCENT
}

@Composable
fun SectionHeader(text: String) {
    Text(text.uppercase(), color = Mesh.MUTED, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Mesh.MUTED, fontSize = 14.sp)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier =
    this.then(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick))
