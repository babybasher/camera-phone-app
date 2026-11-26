package net.homecam.phone

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.StillCamera2Capturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The back lenses are found on a real Android system, and the camera opens with a lens's zoom. */
@RunWith(AndroidJUnit4::class)
class LensesTest {
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @Test fun findsTheBackLenses() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val l = Lenses.back(ctx)
        val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val backs = cm.cameraIdList.filter { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
        assertTrue("a back camera here, so at least the main lens: $l (backs $backs)", backs.isEmpty() || l.isNotEmpty())
        if (l.isEmpty()) return
        assertEquals("the main lens is listed, as the phone's first back camera", backs.first(), l.first { it.key == "main" }.camera)
        assertTrue("keys are what the router accepts: $l", l.all { Regex("^(main|cam:[A-Za-z0-9_]{1,16}|zoom:\\d+\\.\\d\\d)$").matches(it.key) })
        assertTrue("labels say which lens: $l", l.all { it.label.startsWith("Back: ") && !it.label.contains(',') })
        assertTrue("sorted widest first: $l", l.zipWithNext().all { (a, b) -> a.times <= b.times })
        assertEquals("an unknown key falls back to the main lens", "main", Lenses.pick(ctx, "cam:nope")?.key)
        val d = Lenses.describe(ctx)
        assertTrue("camera details: $d", d.startsWith("Android ") && d.contains(" back f") && (d.contains("unlisted: ") || d.endsWith("no unlisted ones")))
        // A lens the phone refused isn't offered again, and its key then means the main lens.
        Lenses.refuse("cam:nope")
        assertEquals("main", Lenses.pick(ctx, "cam:nope")?.key)
    }

    @Test fun opensWithAZoom() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val main = Lenses.back(ctx).firstOrNull { it.key == "main" } ?: return
        // The widest zoom this camera allows (1 where it has no wide angle that way).
        val cm = ctx.getSystemService(android.content.Context.CAMERA_SERVICE) as CameraManager
        val z = if (Build.VERSION.SDK_INT >= 30) cm.getCameraCharacteristics(main.camera).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f else 1f
        StillCamera2Capturer.zoomRatio = z
        StillCamera2Capturer.extraNames = arrayOf("98")  // a name the phone doesn't have: the list grows, nothing breaks
        val egl = EglBase.create()
        val helper = SurfaceTextureHelper.create("lens-test", egl.eglBaseContext)
        val frames = CountDownLatch(10)
        var error = ""
        val cap = StillCamera2Capturer(ctx, main.camera, object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(e: String?) { error = e ?: "error" }
            override fun onCameraDisconnected() {}
            override fun onCameraFreezed(e: String?) {}
            override fun onCameraOpening(name: String?) {}
            override fun onFirstFrameAvailable() {}
            override fun onCameraClosed() {}
        })
        cap.initialize(helper, ctx, object : CapturerObserver {
            override fun onCapturerStarted(ok: Boolean) {}
            override fun onCapturerStopped() {}
            override fun onFrameCaptured(f: VideoFrame) { frames.countDown() }
        })
        cap.startCapture(640, 480, 15)
        val got = frames.await(20, TimeUnit.SECONDS)
        cap.stopCapture(); cap.dispose(); helper.dispose(); egl.release()
        StillCamera2Capturer.zoomRatio = 1f
        StillCamera2Capturer.extraNames = arrayOf()
        assertTrue("frames with zoom $z (error '$error')", got && error.isEmpty())
    }
}
