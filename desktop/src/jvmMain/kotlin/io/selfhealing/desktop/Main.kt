package io.selfhealing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Taskbar
import javax.imageio.ImageIO
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import io.selfhealing.core.config.HarnessType
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

private fun defaultConfigPath(args: Array<String>): String {
    args.firstOrNull { !it.startsWith("-") }?.let { return File(it).absolutePath }
    System.getenv("SELF_HEALING_CONFIG")?.takeIf { it.isNotBlank() }?.let { return File(it).absolutePath }
    return File("config.yml").absolutePath
}

/** App icon from the classpath; also set as the Dock/taskbar icon, which `gradlew run` would otherwise show as Java's. */
private fun loadAppIcon(): BitmapPainter? {
    val image = runCatching { AppViewModel::class.java.getResourceAsStream("/icon.png")?.use { ImageIO.read(it) } }.getOrNull() ?: return null
    runCatching { if (Taskbar.isTaskbarSupported()) Taskbar.getTaskbar().iconImage = image }
    return BitmapPainter(image.toComposeImageBitmap())
}

fun main(args: Array<String>) {
    val vm = AppViewModel(defaultConfigPath(args))
    val icon = loadAppIcon()
    application {
        val scope = rememberCoroutineScope()
        val state = rememberWindowState(size = DpSize(1600.dp, 1000.dp))
        Window(
            onCloseRequest = {
                scope.launch {
                    vm.shutdown()
                    exitApplication()
                    exitProcess(0)
                }
            },
            title = "Self-Healing",
            icon = icon,
            state = state,
        ) {
            AppTheme { AppRoot(vm) }
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    Surface(Modifier.fillMaxSize(), color = Palette.Bg) {
        Column(Modifier.fillMaxSize()) {
            TopBar(vm)
            vm.error?.let { ErrorBanner(it) { vm.error = null } }
            Body(vm)
        }
    }
}

@Composable
private fun TopBar(vm: AppViewModel) {
    val running = vm.status == EngineStatus.LISTENING || vm.status == EngineStatus.STARTING
    Row(
        Modifier.fillMaxWidth().background(Palette.Surface).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Self-Healing", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)

        Row(
            Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Bg)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = vm.configPath,
                onValueChange = { vm.configPath = it },
                enabled = !running,
                singleLine = true,
                textStyle = CodeStyle.copy(color = Palette.OnSurface),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.weight(1f),
            )
            TextButton(enabled = !running, onClick = {
                chooseConfig(vm.configPath)?.let { vm.configPath = it }
            }) { Text("Seç…") }
        }

        val (dot, label) = when (vm.status) {
            EngineStatus.STOPPED -> Palette.Gray to "Durduruldu"
            EngineStatus.STARTING -> Palette.Amber to "Başlatılıyor"
            EngineStatus.LISTENING -> Palette.Green to "Dinleniyor"
            EngineStatus.ERROR -> Palette.Red to "Hata"
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Text(label, fontSize = 13.sp, color = Palette.OnSurface)
        }

        if (running) {
            Button(
                onClick = { vm.stop() },
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Red, contentColor = Color(0xFF2A0A0A)),
            ) { Text("Durdur") }
        } else {
            Button(onClick = { vm.start() }) { Text("Başlat") }
        }
    }
}

private fun chooseConfig(current: String): String? {
    val dialog = FileDialog(null as Frame?, "Yapılandırma dosyası seç", FileDialog.LOAD)
    File(current).parentFile?.takeIf { it.isDirectory }?.let { dialog.directory = it.absolutePath }
    dialog.setFilenameFilter { _, name -> name.endsWith(".yml", true) || name.endsWith(".yaml", true) }
    dialog.isVisible = true
    val file = dialog.file ?: return null
    return File(dialog.directory, file).absolutePath
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF3A1D1F)).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = Color(0xFFFFB4B4), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 4, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onDismiss) { Text("Kapat", color = Color(0xFFFFB4B4)) }
    }
}

@Composable
private fun Body(vm: AppViewModel) {
    var fraction by remember { mutableStateOf(0.5f) }
    var vFraction by remember { mutableStateOf(1f / 3f) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxWidth
        val totalPx = with(LocalDensity.current) { total.toPx() }
        Row(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxHeight().width(total * fraction)) {
                val totalH = maxHeight
                val totalHPx = with(LocalDensity.current) { totalH.toPx() }
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(totalH * vFraction)) { CrashPanel(vm) }
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).background(Palette.Outline)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .pointerInput(totalHPx) {
                                detectVerticalDragGestures { _, dy ->
                                    vFraction = (vFraction + dy / totalHPx).coerceIn(0.2f, 0.7f)
                                }
                            },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) { DevicePanel(vm) }
                }
            }
            Box(
                Modifier.fillMaxHeight().width(6.dp).background(Palette.Outline)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .pointerInput(totalPx) {
                        detectHorizontalDragGestures { _, dx ->
                            fraction = (fraction + dx / totalPx).coerceIn(0.25f, 0.65f)
                        }
                    },
            )
            Box(Modifier.fillMaxHeight().weight(1f)) { TerminalPanel(vm) }
        }
    }
}

internal fun harnessLabel(vm: AppViewModel): String? {
    val h = vm.config?.harness ?: return null
    val type = when (h.type) {
        HarnessType.CLAUDE_CODE -> "Claude Code"
        else -> h.type.name
    }
    return "$type · ${h.model}"
}
