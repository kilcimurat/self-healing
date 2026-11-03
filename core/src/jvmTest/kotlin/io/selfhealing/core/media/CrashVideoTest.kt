package io.selfhealing.core.media

import io.selfhealing.core.model.LineSink
import io.selfhealing.core.process.ProcessRunner
import io.selfhealing.core.process.Tools
import io.selfhealing.core.verify.Tap
import io.selfhealing.core.verify.tapsFile
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CrashVideoTest {
    @Test
    fun layoutPlacesPlatformsSideBySide() {
        val l = CrashVideo.layout(columns = 2, rows = 2)
        assertEquals(16 + 2 * (360 + 16), l.width)
        assertEquals(l.cellY(1), l.cellY(0) + CrashVideo.BAND + CrashVideo.CELL_H + CrashVideo.PAD)
        assertTrue(l.width % 2 == 0 && l.height % 2 == 0, "H.264 needs even dimensions")
    }

    @Test
    fun tapExpressionMapsDevicePixelsIntoTheCell() {
        // 1080×2424 fits the 360×780 cell at scale 1/3.1077; height-bound, so it is centred horizontally.
        val (x, y) = CrashVideo.tapExpressions(listOf(Tap(2.0, 540, 1212)), CrashVideo.Probe(1080, 2424, 10.0))
        assertEquals("if(between(t,1.90,2.70),157,-200)", x)
        assertEquals("if(between(t,1.90,2.70),367,-200)", y)
    }

    @Test
    fun iosPointScaleFollowsScreenWidth() {
        assertEquals(3.0, CrashVideo.iosPointScale(1206)) // iPhone 17 Pro
        assertEquals(2.0, CrashVideo.iosPointScale(828)) // iPhone 11
        assertEquals(2.0, CrashVideo.iosPointScale(1640)) // iPad
    }

    @Test
    fun composesFourRecordingsIntoOneVideo() = runBlocking {
        val ffmpeg = Tools.find("ffmpeg") ?: return@runBlocking // optional tool; covered where installed
        val dir = Files.createTempDirectory("crash-video").toFile()
        suspend fun clip(name: String, w: Int, h: Int, seconds: Int) = File(dir, "$name.mp4").also {
            val r = ProcessRunner.run(
                listOf(ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i", "testsrc=size=${w}x$h:rate=30:duration=$seconds", "-pix_fmt", "yuv420p", it.path),
                timeout = 2.minutes,
            )
            assertTrue(r.ok, r.output)
        }
        val androidRelease = clip("android-release", 540, 1212, 3)
        tapsFile(androidRelease).writeText("px\n1.00 270 600\n2.00 100 100\n")
        val iosRelease = clip("ios-release", 604, 1312, 2)
        tapsFile(iosRelease).writeText("pt\n0.50 100 200\n")
        val rows = listOf(
            VideoRow("Release v1.1.0", listOf(VideoCell(androidRelease, "crash reproduced", false), VideoCell(iosRelease, "crash reproduced", false))),
            VideoRow("Fixed build", listOf(VideoCell(clip("android-fixed", 540, 1212, 2), "no crash", true), VideoCell(null, "no crash", true))),
        )
        val out = assertNotNull(CrashVideo.compose(listOf("Android", "iOS"), rows, dir, "crash-video", LineSink { _, _ -> }))
        val l = CrashVideo.layout(2, 2)
        val probe = ProcessRunner.run(
            listOf(Tools.find("ffprobe")!!, "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", out.video.path),
        ).output.trim()
        assertEquals("${l.width},${l.height}", probe)
        assertTrue(out.preview!!.length() > 0)
        dir.deleteRecursively()
    }
}
