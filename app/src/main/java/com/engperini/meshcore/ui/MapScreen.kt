package com.engperini.meshcore.ui

import com.engperini.meshcore.core.tr
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.engperini.meshcore.core.Contact
import com.engperini.meshcore.core.MeshController
import com.engperini.meshcore.core.Protocol
import com.engperini.meshcore.data.ConvKey
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay

/** Nodes that share a position, on OpenStreetMap (no API key): MeshCore contacts or Meshtastic nodes. */
@Composable
fun MapScreen(nav: Nav) {
    val mode by com.engperini.meshcore.core.LoraController.mode.collectAsState()
    if (mode == com.engperini.meshcore.core.RadioMode.MESHTASTIC) MtMapScreen(nav) else MeshCoreMapScreen(nav)
}

@Composable
private fun rememberMapView(): MapView {
    val context = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = context.cacheDir.resolve("osmdroid")
            osmdroidTileCache = context.cacheDir.resolve("osmdroid/tiles")
        }
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERT_COLORS) // night map
            controller.setZoom(4.0)
            controller.setCenter(GeoPoint(-15.8, -47.9))
        }
    }
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose { mapView.onPause() }
    }
    return mapView
}

private fun fit(map: MapView, points: List<GeoPoint>) {
    map.post {
        if (points.size == 1) {
            map.controller.setZoom(12.0)
            map.controller.setCenter(points[0])
        } else {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false)
        }
    }
}

/** Meshtastic nodes with a position; the phone's own position can be shared from here. */
@Composable
private fun MtMapScreen(nav: Nav) {
    val nodes by com.engperini.meshcore.core.MeshtasticController.nodes.collectAsState()
    val my by com.engperini.meshcore.core.MeshtasticController.myNum.collectAsState()
    val link by MeshController.link.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<com.engperini.meshcore.core.MtNode?>(null) }
    var fitted by remember { mutableStateOf(false) }
    val located = nodes.values.filter { it.lat != null && it.lon != null }
    val mapView = rememberMapView()
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        val loc = if (granted.values.any { it }) lastLocation(context) else null
        if (loc != null) {
            com.engperini.meshcore.core.MeshtasticController.setPosition(loc.first, loc.second, 0)
            MeshController.notice(tr("Position shared with the mesh", "Posição compartilhada com a malha"))
        } else {
            MeshController.notice(tr("Phone location not available", "Localização do celular indisponível"))
        }
    }

    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
            update = { map ->
                map.overlays.clear()
                val points = mutableListOf<GeoPoint>()
                val density = map.context.resources.displayMetrics.density
                located.forEach { n ->
                    val p = GeoPoint(n.lat!!, n.lon!!)
                    points.add(p)
                    val me = n.num == my
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = if (me) "${n.title} (${tr("you", "você")})" else n.title
                        snippet = if (me) n.id else "${n.id} • ${ago(n.lastHeard)}"
                        icon = dot((if (me) Mesh.GREEN else if (n.role in 2..4) Mesh.AMBER else Mesh.ACCENT).toArgb(), density, big = me)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> selected = n; true }
                    })
                }
                if (!fitted && points.isNotEmpty()) {
                    fitted = true
                    fit(map, points)
                }
                map.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            StatusChip(tr("${located.size} of ${nodes.size} nodes with position", "${located.size} de ${nodes.size} nós com posição"), Mesh.ACCENT)
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            Pill(tr("Share my position", "Compartilhar posição"), style = PillStyle.PRIMARY, enabled = link.state == com.engperini.meshcore.core.LinkState.RUNNING) {
                launcher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
            }
        }
        selected?.let { n ->
            Box(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                MeshCard {
                    ListRow(
                        title = n.title,
                        subtitle = n.id + (n.hops?.let { " • " + if (it == 0) tr("direct", "direto") else hopsCount(it) } ?: "") + " • " + ago(n.lastHeard),
                        avatar = { MeshAvatar(n.short, if (n.num == my) Mesh.GREEN else Mesh.ACCENT) },
                        onClick = { nav.push(Route.MtNode(n.num)) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (n.num != my) {
                            Pill(tr("Message", "Mensagem"), Modifier.weight(1f), PillStyle.PRIMARY) {
                                nav.openChat(Conversation(ConvKey.mtDirect(n.num), n.title, false, prefixHex = "%08x".format(n.num)))
                            }
                        }
                        Pill(tr("Details", "Detalhes"), Modifier.weight(1f)) { nav.push(Route.MtNode(n.num)) }
                        Pill(tr("Close", "Fechar"), Modifier.weight(1f), PillStyle.TEXT) { selected = null }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshCoreMapScreen(nav: Nav) {
    val contacts by MeshController.contacts.collectAsState()
    val self by MeshController.self.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Contact?>(null) }
    var fitted by remember { mutableStateOf(false) }

    val located = contacts.filter { it.lat != 0.0 || it.lon != 0.0 }
    val mapView = rememberMapView()

    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)),
            update = { map ->
                map.overlays.clear()
                val points = mutableListOf<GeoPoint>()
                located.forEach { c ->
                    val p = GeoPoint(c.lat, c.lon)
                    points.add(p)
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = c.name
                        snippet = "${c.typeLabel} • ${ago(c.lastAdvert)}"
                        icon = dot(typeColor(c.type).toArgb(), map.context.resources.displayMetrics.density)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                        setOnMarkerClickListener { _, _ -> selected = c; true }
                    })
                }
                self?.takeIf { it.lat != 0.0 || it.lon != 0.0 }?.let { s ->
                    val p = GeoPoint(s.lat, s.lon)
                    points.add(p)
                    map.overlays.add(Marker(map).apply {
                        position = p
                        title = "${s.name} (${tr("you", "você")})"
                        icon = dot(Mesh.GREEN.toArgb(), map.context.resources.displayMetrics.density, big = true)
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    })
                }
                if (!fitted && points.isNotEmpty()) {
                    fitted = true
                    fit(map, points)
                }
                map.invalidate()
            },
        )
        Column(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            StatusChip(tr("${located.size} of ${contacts.size} contacts with position", "${located.size} de ${contacts.size} contatos com posição"), Mesh.ACCENT)
        }
        selected?.let { c ->
            Box(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
                MeshCard {
                    ListRow(
                        title = c.name,
                        subtitle = "${c.typeLabel} • ${hopsLabel(c.outPathLen)} • ${ago(c.lastAdvert)}",
                        avatar = { MeshAvatar(c.name, typeColor(c.type)) },
                        onClick = { nav.push(Route.ContactDetail(c.keyHex)) },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (c.type == Protocol.ADV_TYPE_CHAT || c.type == Protocol.ADV_TYPE_ROOM) {
                            Pill(tr("Message", "Mensagem"), Modifier.weight(1f), PillStyle.PRIMARY) {
                                val p = c.pubKey.prefixHex()
                                nav.openChat(Conversation(ConvKey.contact(p), c.name, false, prefixHex = p))
                            }
                        }
                        Pill(tr("Details", "Detalhes"), Modifier.weight(1f)) { nav.push(Route.ContactDetail(c.keyHex)) }
                        Pill(tr("Close", "Fechar"), Modifier.weight(1f), PillStyle.TEXT) { selected = null }
                    }
                }
            }
        }
    }
}

private fun dot(color: Int, density: Float, big: Boolean = false) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
    setStroke((2 * density).toInt(), 0xFF04121A.toInt())
    val s = ((if (big) 18 else 14) * density).toInt()
    setSize(s, s)
}
