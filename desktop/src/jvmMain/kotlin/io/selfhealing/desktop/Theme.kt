package io.selfhealing.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import io.selfhealing.core.model.CrashStage
import io.selfhealing.core.model.LogSource

object Palette {
    val Bg = Color(0xFF0F1115)
    val Surface = Color(0xFF171A21)
    val SurfaceHigh = Color(0xFF1F232C)
    val Outline = Color(0xFF2A2F3A)
    val Accent = Color(0xFF34D399)
    val OnSurface = Color(0xFFE6E8EE)
    val Muted = Color(0xFF8B93A5)
    val Terminal = Color(0xFF0A0B0E)

    val Gray = Color(0xFF8B93A5)
    val Blue = Color(0xFF60A5FA)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
    val Amber = Color(0xFFFBBF24)
    val Purple = Color(0xFFC084FC)
    val Cyan = Color(0xFF22D3EE)
    val Orange = Color(0xFFFB923C)
}

val Mono = FontFamily.Monospace

fun stageColor(stage: CrashStage): Color = when (stage) {
    CrashStage.QUEUED -> Palette.Gray
    CrashStage.PREPARING, CrashStage.REPRODUCING, CrashStage.ANALYZING, CrashStage.BUILDING,
    CrashStage.VERIFYING, CrashStage.PUBLISHING -> Palette.Blue
    CrashStage.DONE -> Palette.Green
    CrashStage.FAILED -> Palette.Red
    CrashStage.SKIPPED -> Palette.Amber
}

fun stageLabel(stage: CrashStage): String = when (stage) {
    CrashStage.QUEUED -> "Sırada"
    CrashStage.PREPARING -> "Hazırlanıyor"
    CrashStage.REPRODUCING -> "Tekrar üretiliyor"
    CrashStage.ANALYZING -> "Analiz"
    CrashStage.BUILDING -> "Derleniyor"
    CrashStage.VERIFYING -> "Doğrulanıyor"
    CrashStage.PUBLISHING -> "PR hazırlanıyor"
    CrashStage.DONE -> "Tamamlandı"
    CrashStage.FAILED -> "Başarısız"
    CrashStage.SKIPPED -> "Atlandı"
}

fun sourceColor(s: LogSource): Color = when (s) {
    LogSource.SYSTEM -> Palette.Gray
    LogSource.SENTRY -> Palette.Purple
    LogSource.HARNESS -> Palette.Green
    LogSource.BUILD -> Palette.Cyan
    LogSource.GIT -> Palette.Orange
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Accent,
        onPrimary = Color(0xFF04201A),
        secondary = Palette.Blue,
        background = Palette.Bg,
        onBackground = Palette.OnSurface,
        surface = Palette.Surface,
        onSurface = Palette.OnSurface,
        surfaceVariant = Palette.SurfaceHigh,
        onSurfaceVariant = Palette.Muted,
        outline = Palette.Outline,
        outlineVariant = Palette.Outline,
        error = Palette.Red,
        errorContainer = Color(0xFF3A1D1F),
        onErrorContainer = Color(0xFFFFB4B4),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

val CodeStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp)
