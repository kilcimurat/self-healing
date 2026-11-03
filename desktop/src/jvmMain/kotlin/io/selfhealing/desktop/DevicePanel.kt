package io.selfhealing.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.selfhealing.core.devices.DeviceInfo
import io.selfhealing.core.devices.DeviceState
import io.selfhealing.core.model.Platform
import kotlin.math.roundToInt

@Composable
fun DevicePanel(vm: AppViewModel) {
    val tiles = vm.deviceTiles
    Row(
        Modifier.fillMaxSize().background(Palette.Bg).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (info in tiles) {
            DeviceTile(vm, info, Modifier.weight(1f).fillMaxSize())
        }
    }
}

private fun stateColor(s: DeviceState) = when (s) {
    DeviceState.OFF -> Palette.Gray
    DeviceState.STARTING -> Palette.Blue
    DeviceState.READY -> Palette.Green
    DeviceState.ERROR -> Palette.Red
}

private fun stateLabel(s: DeviceState) = when (s) {
    DeviceState.OFF -> "Kapalı"
    DeviceState.STARTING -> "Açılıyor"
    DeviceState.READY -> "Hazır"
    DeviceState.ERROR -> "Hata"
}

@Composable
private fun DeviceTile(vm: AppViewModel, info: DeviceInfo, modifier: Modifier) {
    val isAndroid = info.platform == Platform.ANDROID
    val frame = vm.frames[info.platform]?.takeIf { info.state == DeviceState.READY }
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (isAndroid) "Android" else "iOS", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
            Text(
                info.name, fontSize = 12.sp, color = Palette.OnSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val c = stateColor(info.state)
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                if (info.state == DeviceState.STARTING) CircularProgressIndicator(Modifier.size(11.dp), color = c, strokeWidth = 1.5.dp)
                Text(stateLabel(info.state), fontSize = 11.sp, color = c, fontWeight = FontWeight.Medium)
            }
        }
        if (info.state == DeviceState.ERROR && info.message.isNotBlank()) {
            Text(info.message, fontSize = 11.sp, color = Palette.Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val aspect = if (frame != null && frame.height > 0) frame.width.toFloat() / frame.height else 9f / 19.5f
            val pad = 6.dp
            val availW = maxWidth - pad * 2
            val availH = maxHeight - pad * 2
            val w = if (availW / availH > aspect) availH * aspect else availW
            val h = w / aspect
            Box(
                Modifier.size(w + pad * 2, h + pad * 2).clip(RoundedCornerShape(22.dp))
                    .background(Color0).border(2.dp, Palette.Outline, RoundedCornerShape(22.dp)).padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                if (frame != null) {
                    val currentFrame by rememberUpdatedState(frame)
                    var dragStart by remember { mutableStateOf(Offset.Zero) }
                    var dragEnd by remember { mutableStateOf(Offset.Zero) }
                    var dragT0 by remember { mutableStateOf(0L) }
                    val density = LocalDensity.current
                    val sizePx = with(density) { Offset(w.toPx(), h.toPx()) }
                    val sizeState by rememberUpdatedState(sizePx)
                    // The image is drawn to exactly fill this box (aspect-fit already applied above),
                    // so box-relative position maps linearly to device pixels.
                    fun toDevice(o: Offset): Pair<Int, Int> {
                        val f = currentFrame
                        val x = (o.x / sizeState.x * f.width).roundToInt().coerceIn(0, f.width - 1)
                        val y = (o.y / sizeState.y * f.height).roundToInt().coerceIn(0, f.height - 1)
                        return x to y
                    }
                    var m: Modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    if (isAndroid || !vm.iosInteractiveHint) {
                        m = m.pointerInput(Unit) {
                            detectTapGestures { o -> val (x, y) = toDevice(o); vm.tap(info.platform, x, y) }
                        }.pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { o -> dragStart = o; dragEnd = o; dragT0 = System.nanoTime() },
                                onDrag = { change, _ -> dragEnd = change.position },
                                onDragEnd = {
                                    val ms = ((System.nanoTime() - dragT0) / 1_000_000L).toInt().coerceIn(100, 800)
                                    val (x1, y1) = toDevice(dragStart)
                                    val (x2, y2) = toDevice(dragEnd)
                                    vm.swipe(info.platform, x1, y1, x2, y2, ms)
                                },
                            )
                        }
                    }
                    Image(
                        bitmap = frame.bitmap, contentDescription = null,
                        contentScale = ContentScale.FillBounds, modifier = m,
                    )
                } else {
                    val text = when (info.state) {
                        DeviceState.ERROR -> info.message.ifBlank { "Cihaz hatası" }
                        DeviceState.STARTING -> info.message.ifBlank { "Açılıyor…" }
                        DeviceState.READY -> "Görüntü bekleniyor…"
                        DeviceState.OFF -> "Başlat'a basınca emülatör burada açılır"
                    }
                    Text(
                        text, color = Palette.Muted, fontSize = 12.sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        if (isAndroid) {
            val ready = info.state == DeviceState.READY
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                NavButton("◁ Geri", ready) { vm.androidKey("KEYCODE_BACK") }
                NavButton("○ Ana ekran", ready) { vm.home(info.platform) }
                NavButton("□ Son", ready) { vm.androidKey("KEYCODE_APP_SWITCH") }
            }
        } else if (!vm.iosInteractiveHint) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                NavButton("○ Ana ekran", info.state == DeviceState.READY) { vm.home(info.platform) }
            }
        } else {
            Text(
                "Yalnızca görüntü — dokunmak için AXe kurun: brew install cameroncooke/axe/axe", fontSize = 11.sp, color = Palette.Muted,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color(0xFF05060A)

@Composable
private fun NavButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
    ) { Text(text, fontSize = 12.sp) }
}
