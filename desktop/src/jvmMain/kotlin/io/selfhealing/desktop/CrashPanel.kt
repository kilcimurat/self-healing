package io.selfhealing.desktop

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.selfhealing.core.model.CrashState
import io.selfhealing.core.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.awt.Desktop
import java.io.File
import java.net.URI

@Composable
fun CrashPanel(vm: AppViewModel) {
    val list = vm.sortedCrashes
    val selected = vm.selectedCrashId?.let { vm.crashes[it] }
    Column(Modifier.fillMaxSize().background(Palette.Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Crash'ler", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.OnSurface)
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.SurfaceHigh).padding(horizontal = 8.dp, vertical = 2.dp),
            ) { Text("${list.size}", fontSize = 12.sp, color = Palette.Muted) }
        }
        if (list.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Henüz crash yok — Başlat'a bastıktan sonra Sentry'den gelen crash'ler burada görünür.",
                    color = Palette.Muted, fontSize = 14.sp, lineHeight = 21.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(if (selected != null) 0.45f else 1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.report.id }) { c ->
                    CrashCard(c, c.report.id == vm.selectedCrashId, vm.detectedAt[c.report.id], vm.now) {
                        vm.selectCrash(c.report.id)
                    }
                }
            }
        }
        if (selected != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Outline))
            Box(Modifier.weight(0.55f).fillMaxWidth()) { CrashDetail(selected, vm) }
        }
    }
}

@Composable
private fun CrashCard(c: CrashState, selected: Boolean, detected: Long?, now: Long, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Palette.SurfaceHigh else Palette.Surface)
            .border(1.dp, if (selected) Palette.Accent.copy(alpha = 0.7f) else Palette.Outline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            c.report.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Palette.OnSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.report.projectSlug, fontSize = 12.sp, color = Palette.Muted, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
            PlatformBadge(c.report.platform)
            c.report.release?.let {
                Text(it, fontFamily = Mono, fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            StageChip(c)
            Box(Modifier.weight(1f))
            if (detected != null) Text(relativeTime(detected, now), fontSize = 11.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun PlatformBadge(p: Platform) {
    val (label, color) = when (p) {
        Platform.ANDROID -> "Android" to Palette.Green
        Platform.IOS -> "iOS" to Palette.Cyan
        Platform.UNKNOWN -> "?" to Palette.Muted
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
        Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StageChip(c: CrashState) {
    val color = stageColor(c.stage)
    val active = !c.stage.terminal && c.stage != io.selfhealing.core.model.CrashStage.QUEUED
    val pulse = if (active) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.55f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "alpha",
        ).value
    } else 1f
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (active) CircularProgressIndicator(Modifier.size(11.dp).alpha(pulse), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
        Text(stageLabel(c.stage), fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

private fun relativeTime(t: Long, now: Long): String {
    val s = ((now - t) / 1000).coerceAtLeast(0)
    return when {
        s < 10 -> "az önce"
        s < 60 -> "$s sn önce"
        s < 3600 -> "${s / 60} dk önce"
        s < 86400 -> "${s / 3600} sa önce"
        else -> "${s / 86400} gün önce"
    }
}

private fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {
    }
}

@Composable
private fun CrashDetail(c: CrashState, vm: AppViewModel) {
    var traceOpen by remember(c.report.id) { mutableStateOf(false) }
    var preview by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StageChip(c)
            Box(Modifier.weight(1f))
            c.report.permalink?.let { link ->
                TextButton(onClick = { openUrl(link) }) { Text("Sentry'de aç", fontSize = 12.sp) }
            }
        }
        if (c.message.isNotBlank()) Text(c.message, fontSize = 13.sp, color = Palette.OnSurface, lineHeight = 19.sp)

        c.summary?.let { s ->
            Section("Kök neden") { Text(s.rootCause, fontSize = 13.sp, color = Palette.OnSurface, lineHeight = 19.sp) }
            if (s.fix.isNotBlank()) Section("Düzeltme") { Text(s.fix, fontSize = 13.sp, color = Palette.OnSurface, lineHeight = 19.sp) }
            if (s.filesChanged.isNotEmpty()) Section("Değişen dosyalar") {
                Text(s.filesChanged.joinToString("\n"), style = CodeStyle, color = Palette.Muted)
            }
        }

        if (c.screenshots.isNotEmpty()) {
            Section("Ekran görüntüleri") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(c.screenshots) { path -> Thumbnail(path) { preview = path } }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            c.prUrl?.let { url -> Button(onClick = { openUrl(url) }) { Text("PR'ı aç") } }
            if (c.stage.terminal) OutlinedButton(onClick = { vm.retry(c.report.id) }) { Text("Tekrar dene") }
        }

        Column {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable { traceOpen = !traceOpen }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(if (traceOpen) "▾" else "▸", color = Palette.Muted, fontSize = 13.sp)
                Text("  Stack trace", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Palette.OnSurface)
            }
            if (traceOpen) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Palette.Terminal).padding(10.dp)) {
                    SelectionContainer { Text(c.report.stacktrace, style = CodeStyle, color = Palette.OnSurface) }
                }
            }
        }
    }
    preview?.let { path ->
        Dialog(onDismissRequest = { preview = null }) {
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(12.dp).clickable { preview = null },
            ) {
                val bmp by loadBitmap(path)
                val b = bmp
                if (b != null) Image(b, null, Modifier.heightIn(max = 760.dp), contentScale = ContentScale.Fit)
                else Text("Görüntü yüklenemedi", color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title.uppercase(), fontSize = 11.sp, color = Palette.Muted, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
        content()
    }
}

@Composable
private fun loadBitmap(path: String) = produceState<ImageBitmap?>(null, path) {
    value = withContext(Dispatchers.IO) {
        try {
            SkiaImage.makeFromEncoded(File(path).readBytes()).toComposeImageBitmap()
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
private fun Thumbnail(path: String, onClick: () -> Unit) {
    val bmp by loadBitmap(path)
    val b = bmp
    Box(
        Modifier.height(140.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Terminal)
            .border(BorderStroke(1.dp, Palette.Outline), RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (b != null) Image(b, null, Modifier.height(140.dp), contentScale = ContentScale.Fit)
        else Text("…", color = Palette.Muted, modifier = Modifier.padding(24.dp))
    }
}
