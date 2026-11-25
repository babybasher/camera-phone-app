package net.homecam.phone

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.CapturerObserver
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.StillCamera2Capturer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The app's own copy of WebRTC's camera (no digital stabilization) opens a camera and delivers frames. */
@RunWith(AndroidJUnit4::class)
class StillCameraTest {
    @get:Rule val camera: GrantPermissionRule = GrantPermissionRule.grant(android.Manifest.permission.CAMERA)

    @Test fun deliversFrames() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val en = Camera2Enumerator(ctx)
        val dev = en.deviceNames.firstOrNull { en.isBackFacing(it) } ?: en.deviceNames.first()
        val egl = EglBase.create()
        val helper = SurfaceTextureHelper.create("camera-test", egl.eglBaseContext)
        val frames = CountDownLatch(10)
        var size = ""
        var error = ""
        val cap = StillCamera2Capturer(ctx, dev, object : CameraVideoCapturer.CameraEventsHandler {
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
            override fun onFrameCaptured(f: VideoFrame) { size = "${f.buffer.width}x${f.buffer.height}"; frames.countDown() }
        })
        cap.startCapture(640, 480, 15)
        val got = frames.await(20, TimeUnit.SECONDS)
        cap.stopCapture()
        cap.dispose()
        helper.dispose()
        egl.release()
        assertTrue("10 frames from camera $dev within 20 s (last $size, error '$error')", got && error.isEmpty())
    }
}
