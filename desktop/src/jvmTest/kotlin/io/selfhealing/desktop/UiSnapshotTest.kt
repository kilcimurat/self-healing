package io.selfhealing.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import io.selfhealing.core.config.AppConfig
import io.selfhealing.core.config.GitHubConfig
import io.selfhealing.core.config.SentryConfig
import io.selfhealing.core.model.CrashReport
import io.selfhealing.core.model.CrashStage
import io.selfhealing.core.model.CrashState
import io.selfhealing.core.model.EngineEvent
import io.selfhealing.core.model.FixSummary
import io.selfhealing.core.model.LogSource
import io.selfhealing.core.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.selfhealing.core.devices.DeviceInfo
import io.selfhealing.core.devices.DeviceState
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Renders the main window with sample data to build/ui-snapshot.png (no display needed). */
class UiSnapshotTest {
    @Test
    fun rendersMainWindow() = runBlocking {
        val vm = withContext(Dispatchers.Main) { sampleViewModel() }
        val scene = withContext(Dispatchers.Main) {
            ImageComposeScene(width = 1600, height = 1000, density = Density(1f)) { AppRoot(vm) }
        }
        val bytes = withContext(Dispatchers.Main) {
            scene.render(0)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }
        val out = File("build/ui-snapshot.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
        withContext(Dispatchers.Main) { scene.close() }
        assertTrue(out.length() > 10_000)
    }

    private fun sampleViewModel(): AppViewModel {
        val vm = AppViewModel("/Users/me/self-healing/config.yml")
        vm.config = AppConfig(
            sentry = SentryConfig(authToken = "x", organization = "acme", projects = emptyList()),
            github = GitHubConfig(token = "x"),
            repos = emptyList(),
        )
        vm.status = EngineStatus.LISTENING
        val ios = CrashReport(
            id = "5821", projectSlug = "shop-ios", title = "EXC_BREAKPOINT: swift::AsyncTask::~AsyncTask()",
            culprit = "PrefetchIdentityAccessTokenService.schedulePrefetch", platform = Platform.IOS, release = "11.2641.3",
            permalink = "https://acme.sentry.io/issues/5821/",
            stacktrace = "EXC_BREAKPOINT\n  at swift::AsyncTask::~AsyncTask() +432\n  at destroyTask +20\n  at closure #1 in schedulePrefetch(…) [in-app]",
        )
        val android = CrashReport(
            id = "5790", projectSlug = "shop-android", title = "NullPointerException: CartViewModel.total",
            platform = Platform.ANDROID, release = "8.14.0", stacktrace = "java.lang.NullPointerException",
        )
        val t = System.currentTimeMillis()
        vm.onCrashes(
            mapOf(
                android.id to CrashState(
                    android, CrashStage.DONE, "PR opened: https://github.com/acme/shop-android/pull/412",
                    FixSummary(true, "Cart is cleared on logout while the total flow is still collecting.", "Guard with null-safe snapshot"),
                    prUrl = "https://github.com/acme/shop-android/pull/412",
                ),
                ios.id to CrashState(
                    ios, CrashStage.ANALYZING, "Running claude_code with claude-opus-5-5",
                ),
            ),
        )
        vm.detectedAt[android.id] = t - 3_600_000
        vm.detectedAt[ios.id] = t - 90_000
        listOf(
            EngineEvent.EngineStatus(true, "Listening for Sentry crashes"),
            EngineEvent.CrashDetected(ios),
            EngineEvent.StageChanged(ios.id, CrashStage.PREPARING, "Preparing shop-ios (main)"),
            EngineEvent.Log(ios.id, LogSource.GIT, "$ git fetch origin --prune"),
            EngineEvent.Log(ios.id, LogSource.GIT, "$ git worktree add -B self-healing/5821-exc-breakpoint workspace/runs/5821 origin/main"),
            EngineEvent.StageChanged(ios.id, CrashStage.ANALYZING, "Running claude_code with claude-opus-5-5"),
            EngineEvent.Log(ios.id, LogSource.HARNESS, "model: claude-opus-5-5"),
            EngineEvent.Log(ios.id, LogSource.HARNESS, "▸ Grep prefetchTask"),
            EngineEvent.Log(ios.id, LogSource.HARNESS, "▸ Read Sources/Identity/PrefetchIdentityAccessTokenService.swift"),
            EngineEvent.Log(ios.id, LogSource.HARNESS, "Two writers touch `prefetchTask` without synchronization: the OAuthTokenManager actor assigns a new Task while the finishing task sets it to nil."),
            EngineEvent.Log(ios.id, LogSource.HARNESS, "▸ Edit Sources/Identity/PrefetchIdentityAccessTokenService.swift"),
        ).forEach(vm::onEvent)
        vm.flush()
        vm.selectCrash(ios.id)
        vm.liveDevices = listOf(
            DeviceInfo(Platform.ANDROID, "emulator-5554", "Pixel_10", DeviceState.READY),
            DeviceInfo(Platform.IOS, null, "iPhone 16", DeviceState.STARTING, "Booting iPhone 16"),
        )
        val frame = sampleFrame()
        vm.setFrameForTest(Platform.ANDROID, DeviceFrame(frame.toComposeImageBitmap(), frame.width, frame.height))
        return vm
    }

    /** Draws a simple fake app screen and round-trips it through PNG like a real screencap. */
    private fun sampleFrame(): SkiaImage {
        val surface = Surface.makeRasterN32Premul(540, 1170)
        val c: Canvas = surface.canvas
        fun rect(color: Int, l: Float, t: Float, r: Float, b: Float) =
            c.drawRect(Rect.makeLTRB(l, t, r, b), Paint().apply { this.color = color })
        rect(0xFFF4F4F8.toInt(), 0f, 0f, 540f, 1170f)
        rect(0xFF3D5AFE.toInt(), 0f, 0f, 540f, 140f)
        for (i in 0 until 5) rect(0xFFFFFFFF.toInt(), 30f, 180f + i * 170f, 510f, 320f + i * 170f)
        rect(0xFF3D5AFE.toInt(), 150f, 1060f, 390f, 1120f)
        val png = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        return SkiaImage.makeFromEncoded(png)
    }
}
