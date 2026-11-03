package io.selfhealing.core.media

import io.selfhealing.core.model.LineSink
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.process.ProcessRunner
import io.selfhealing.core.process.Tools
import io.selfhealing.core.verify.Tap
import io.selfhealing.core.verify.readTaps
import io.selfhealing.core.verify.tapsFile
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** One recording in the grid; [file] is null when that platform/phase was not recorded. */
data class VideoCell(val file: File?, val status: String, val ok: Boolean)

/** A row of the grid, e.g. "Release v1.1.0" with one cell per platform column. */
data class VideoRow(val title: String, val cells: List<VideoCell>)

/**
 * Combines the per-platform recordings into one side-by-side video: platforms as columns (Android left, iOS right),
 * release and fixed build as rows. Labels are drawn into a background image, since ffmpeg builds often lack `drawtext`.
 */
object CrashVideo {
    internal const val CELL_W = 360
    internal const val CELL_H = 780
    internal const val PAD = 16
    internal const val HEADER = 44
    internal const val BAND = 36

    /** GitHub's image proxy refuses large files; a bigger GIF is retried smaller, then dropped. */
    private const val GIF_MAX_BYTES = 9_000_000L

    data class Output(val video: File, val preview: File?)

    internal data class Layout(val width: Int, val height: Int) {
        fun cellX(col: Int) = PAD + col * (CELL_W + PAD)
        fun bandY(row: Int) = HEADER + row * (BAND + CELL_H + PAD)
        fun cellY(row: Int) = bandY(row) + BAND
    }

    internal fun layout(columns: Int, rows: Int) =
        Layout(PAD + columns * (CELL_W + PAD), HEADER + rows * (BAND + CELL_H + PAD))

    /** Writes `<name>.mp4` (and a `<name>.gif` preview) into [dir]; null when ffmpeg is missing or nothing was recorded. */
    suspend fun compose(columns: List<String>, rows: List<VideoRow>, dir: File, name: String, log: LineSink): Output? {
        val inputs = rows.flatMapIndexed { r, row -> row.cells.mapIndexedNotNull { c, cell -> cell.file?.takeIf { it.length() > 0 }?.let { Triple(r, c, it) } } }
        if (inputs.isEmpty()) return null
        val ffmpeg = Tools.find("ffmpeg") ?: return null.also { log.line(LogSource.SYSTEM, "ffmpeg not found; recordings are not combined (brew install ffmpeg)") }
        val layout = layout(columns.size, rows.size)
        val background = File(dir, "$name-background.png")
        ImageIO.write(drawBackground(columns, rows, layout), "png", background)

        val tapImage = File(dir, "$name-tap.png")
        ImageIO.write(drawTap(), "png", tapImage)
        val clips = inputs.map { (r, c, f) ->
            val probe = probe(f)
            val taps = readTaps(tapsFile(f))?.let { (unit, list) ->
                val scale = if (unit == "pt" && probe != null) iosPointScale(probe.width) else 1.0
                list.map { Tap(it.seconds, (it.x * scale).toInt(), (it.y * scale).toInt()) }
            }.orEmpty()
            Clip(layout.cellX(c), layout.cellY(r), probe, taps)
        }
        val total = clips.maxOf { it.probe?.seconds ?: 60.0 } + 1.5 // shorter clips freeze on their last frame (the crash) until the longest ends
        val filter = buildFilter(clips)
        val video = File(dir, "$name.mp4")
        val args = listOf(ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-loop", "1", "-framerate", "30", "-i", background.path) +
            inputs.flatMap { listOf("-i", it.third.path) } +
            clips.filter { it.taps.isNotEmpty() }.flatMap { listOf("-loop", "1", "-framerate", "30", "-i", tapImage.path) } +
            listOf(
                "-filter_complex", filter, "-map", "[out]", "-t", "%.2f".format(java.util.Locale.ROOT, total),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", video.path,
            )
        val r = ProcessRunner.run(args, timeout = 10.minutes)
        background.delete()
        tapImage.delete()
        if (!r.ok || video.length() == 0L) {
            log.line(LogSource.SYSTEM, "Combining recordings failed: ${r.output.lines().takeLast(5).joinToString("\n")}")
            return null
        }
        log.line(LogSource.SYSTEM, "Combined ${inputs.size} recordings into ${video.name} (${"%.0f".format(total)}s)")
        return Output(video, gif(ffmpeg, video, File(dir, "$name.gif"), log))
    }

    internal data class Probe(val width: Int, val height: Int, val seconds: Double)

    /** A recording placed at ([x], [y]) on the canvas; [taps] are in the recording's pixels. */
    internal data class Clip(val x: Int, val y: Int, val probe: Probe?, val taps: List<Tap>)

    /** Simulator recordings are in pixels, Maestro's iOS taps in points: @3x on current iPhones, @2x on SE/11-class phones and iPads. */
    internal fun iosPointScale(pixelWidth: Int) = if (pixelWidth in 1000..1400) 3.0 else 2.0

    /**
     * Each recording fit into its cell and padded, a ring drawn on every tap (inputs after the recordings),
     * then held on its last frame and overlaid onto the background (input 0).
     */
    internal fun buildFilter(clips: List<Clip>): String = buildString {
        var tapInput = clips.size + 1
        clips.forEachIndexed { i, clip ->
            append("[${i + 1}:v]fps=30,scale=$CELL_W:$CELL_H:force_original_aspect_ratio=decrease,")
            append("pad=$CELL_W:$CELL_H:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1")
            if (clip.taps.isNotEmpty() && clip.probe != null) {
                val (x, y) = tapExpressions(clip.taps, clip.probe)
                append("[p$i];[p$i][${tapInput++}:v]overlay=x='$x':y='$y':eval=frame:shortest=1")
            }
            append(",tpad=stop_mode=clone:stop_duration=3600[v$i];")
        }
        var base = "0:v"
        clips.forEachIndexed { i, clip ->
            val out = if (i == clips.lastIndex) "out" else "b$i"
            append("[$base][v$i]overlay=x=${clip.x}:y=${clip.y}:eof_action=repeat[$out]")
            if (i != clips.lastIndex) append(';')
            base = out
        }
    }

    /** Overlay position expressions: the ring sits on each tap for a moment, off-canvas otherwise. */
    internal fun tapExpressions(taps: List<Tap>, probe: Probe): Pair<String, String> {
        val s = minOf(CELL_W.toDouble() / probe.width, CELL_H.toDouble() / probe.height)
        val offX = (CELL_W - probe.width * s) / 2
        val offY = (CELL_H - probe.height * s) / 2
        fun expr(pos: (Tap) -> Double) = taps.foldRight("-200") { tap, rest ->
            "if(between(t,%.2f,%.2f),%d,%s)".format(java.util.Locale.ROOT, tap.seconds - 0.1, tap.seconds + TAP_SECONDS, pos(tap).toInt() - TAP_SIZE / 2, rest)
        }
        return expr { offX + it.x * s } to expr { offY + it.y * s }
    }

    private const val TAP_SIZE = 46
    private const val TAP_SECONDS = 0.7

    private suspend fun probe(file: File): Probe? {
        val ffprobe = Tools.find("ffprobe") ?: return null
        val r = ProcessRunner.run(
            listOf(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "default=nw=1", file.path),
            timeout = 30.seconds,
        )
        val values = r.output.lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
        val w = values["width"]?.toIntOrNull() ?: return null
        val h = values["height"]?.toIntOrNull() ?: return null
        return Probe(w, h, values["duration"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: 60.0)
    }

    /** A white ring with a translucent fill, like Android's "show taps" dot. */
    private fun drawTap(): BufferedImage {
        val img = BufferedImage(TAP_SIZE, TAP_SIZE, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(255, 255, 255, 110)
        g.fillOval(2, 2, TAP_SIZE - 4, TAP_SIZE - 4)
        g.stroke = java.awt.BasicStroke(3f)
        g.color = Color(255, 255, 255, 235)
        g.drawOval(2, 2, TAP_SIZE - 4, TAP_SIZE - 4)
        g.color = Color(0, 0, 0, 120)
        g.stroke = java.awt.BasicStroke(1f)
        g.drawOval(0, 0, TAP_SIZE - 1, TAP_SIZE - 1)
        g.dispose()
        return img
    }

    /** Inline preview for the PR comment (GitHub renders GIFs, not MP4s, from a repository URL). */
    private suspend fun gif(ffmpeg: String, video: File, out: File, log: LineSink): File? {
        for ((fps, width) in listOf(8 to 520, 6 to 440, 4 to 360)) {
            val r = ProcessRunner.run(
                listOf(
                    ffmpeg, "-y", "-hide_banner", "-loglevel", "error", "-i", video.path, "-filter_complex",
                    "fps=$fps,scale=$width:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=96:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4",
                    out.path,
                ),
                timeout = 10.minutes,
            )
            if (r.ok && out.length() in 1..GIF_MAX_BYTES) return out
        }
        out.delete()
        log.line(LogSource.SYSTEM, "GIF preview too large; the PR comment links the MP4 only")
        return null
    }

    internal fun drawBackground(columns: List<String>, rows: List<VideoRow>, layout: Layout): BufferedImage {
        val img = BufferedImage(layout.width, layout.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x0D1117)
        g.fillRect(0, 0, layout.width, layout.height)

        fun centered(text: String, x: Int, width: Int, baseline: Int) {
            val w = g.fontMetrics.stringWidth(text)
            g.drawString(text, x + (width - w) / 2, baseline)
        }

        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.color = Color.WHITE
        columns.forEachIndexed { c, title -> centered(title, layout.cellX(c), CELL_W, HEADER - 12) }

        rows.forEachIndexed { r, row ->
            row.cells.forEachIndexed { c, cell ->
                val x = layout.cellX(c)
                val accent = if (cell.ok) Color(0x2EA043) else Color(0xDA3633)
                g.color = Color(accent.red, accent.green, accent.blue, 60)
                g.fillRoundRect(x, layout.bandY(r) + 3, CELL_W, BAND - 8, 10, 10)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 15)
                g.color = Color.WHITE
                centered("${row.title} · ${cell.status}", x, CELL_W, layout.bandY(r) + BAND - 14)
                g.color = Color(0x161B22)
                g.fillRect(x, layout.cellY(r), CELL_W, CELL_H)
                if (cell.file == null) {
                    g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
                    g.color = Color(0x8B949E)
                    centered("Not recorded", x, CELL_W, layout.cellY(r) + CELL_H / 2)
                }
            }
        }
        g.dispose()
        return img
    }
}
