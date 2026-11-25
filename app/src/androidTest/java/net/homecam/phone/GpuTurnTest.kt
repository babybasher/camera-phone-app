package net.homecam.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.EglBase
import org.webrtc.JavaI420Buffer
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.TextureBufferImpl
import org.webrtc.ThreadUtils
import org.webrtc.VideoFrame
import org.webrtc.YuvConverter
import java.util.concurrent.Callable

/**
 * The graphics chip turning and stamp against the main processor's (libyuv) turning, which follows
 * WebRTC's own meaning of a frame's rotation: same picture, every way round, from both kinds of
 * camera frame; and the stamp upright in the top left of the picture as finally seen.
 */
@RunWith(AndroidJUnit4::class)
class GpuTurnTest {
    companion object {
        private lateinit var thread: HandlerThread
        private lateinit var h: Handler
        private lateinit var egl: EglBase
        private lateinit var gpu: GpuTurn

        fun <T> onGl(f: () -> T): T = ThreadUtils.invokeAtFrontUninterruptibly(h, Callable { f() })

        @BeforeClass @JvmStatic fun setup() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
            thread = HandlerThread("gl").apply { start() }
            h = Handler(thread.looper)
            onGl {
                egl = EglBase.create()
                egl.createDummyPbufferSurface()
                egl.makeCurrent()
                gpu = GpuTurn()
            }
        }

        @AfterClass @JvmStatic fun done() {
            onGl { gpu.release(); egl.release() }
            thread.quitSafely()
        }

        // Four grey levels, one per quarter (as stored: top left, top right, bottom left, bottom right).
        val LEVELS = intArrayOf(40, 100, 160, 220)
        fun quarter(x: Int, y: Int, w: Int, h: Int) = (if (y < h / 2) 0 else 2) + (if (x < w / 2) 0 else 1)
    }

    private fun i420(w: Int, h: Int, y: (Int, Int) -> Int): VideoFrame {
        val b = JavaI420Buffer.allocate(w, h)
        for (r in 0 until h) for (c in 0 until w) b.dataY.put(r * b.strideY + c, y(c, r).toByte())
        for (r in 0 until (h + 1) / 2) for (c in 0 until (w + 1) / 2) {
            b.dataU.put(r * b.strideU + c, 128.toByte()); b.dataV.put(r * b.strideV + c, 128.toByte())
        }
        return VideoFrame(b, 0, 0)
    }

    /** An RGB texture camera frame, the kind the camera really gives (a texture with a matrix). */
    private fun texture(tw: Int, th: Int, y: (Int, Int) -> Int): VideoFrame = onGl {
        val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        for (r in 0 until th) for (c in 0 until tw) { val g = y(c, r); bmp.setPixel(c, r, Color.rgb(g, g, g)) }
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        bmp.recycle()
        // A bitmap's first row goes in at the texture's bottom: the matrix flips it the right way up.
        val m = RendererCommon.convertMatrixToAndroidGraphicsMatrix(RendererCommon.verticalFlipMatrix())
        val conv = YuvConverter()
        VideoFrame(TextureBufferImpl(tw, th, VideoFrame.TextureBuffer.Type.RGB, t[0], m, h, conv) {
            onGl { GLES20.glDeleteTextures(1, t, 0); conv.release() }
        }, 0, 0)
    }

    private fun lum(b: VideoFrame.I420Buffer, x: Int, y: Int) = b.dataY.get(y * b.strideY + x).toInt() and 255

    /** The main processor's turn of a frame (libyuv), as I420. */
    private fun cpuTurn(f: VideoFrame, rot: Int): VideoFrame.I420Buffer {
        if (rot == 0) return f.buffer.toI420()!!
        val t = Upright.turn(VideoFrame(f.buffer, rot, 0), rot)!!
        val i = t.buffer.toI420()!!
        t.release()
        return i
    }

    private fun gpuTurn(f: VideoFrame, rot: Int, stamp: String? = null, seen: Int = 0): VideoFrame.I420Buffer {
        val out = onGl { gpu.render(f, rot, stamp, seen) }!!
        assertEquals("comes out as a frame needing no further turn", 0, out.rotation)
        val i = out.buffer.toI420()!!
        out.release()
        return i
    }

    private fun samePicture(name: String, src: VideoFrame) {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val want = cpuTurn(src, rot)
            val got = gpuTurn(src, rot)
            try {
                assertEquals("$name $rot°: width", want.width, got.width)
                assertEquals("$name $rot°: height", want.height, got.height)
                for ((fx, fy) in listOf(0.25 to 0.25, 0.75 to 0.25, 0.25 to 0.75, 0.75 to 0.75, 0.5 to 0.1, 0.1 to 0.5)) {
                    val x = (fx * want.width).toInt(); val y = (fy * want.height).toInt()
                    val a = lum(want, x, y); val b = lum(got, x, y)
                    assertTrue("$name $rot° at ($fx, $fy): libyuv $a, graphics chip $b", Math.abs(a - b) <= 14)
                }
            } finally { want.release(); got.release() }
        }
    }

    @Test fun turnsLikeLibyuvFromMemoryFrames() {
        val f = i420(64, 48) { x, y -> LEVELS[quarter(x, y, 64, 48)] }
        try { samePicture("memory frame", f) } finally { f.release() }
    }

    @Test fun turnsLikeLibyuvFromTextureFrames() {
        val f = texture(64, 48) { x, y -> LEVELS[quarter(x, y, 64, 48)] }
        // The texture really holds the pattern the right way up (WebRTC's own conversion agrees).
        // (Brightness comes back on a video scale, so check the order, not exact values.)
        val i = f.buffer.toI420()!!
        val got = (0..3).map { q -> lum(i, if (q % 2 == 0) 16 else 48, if (q < 2) 12 else 36) }
        i.release()
        assertTrue("texture frame quarters darkest top left to brightest bottom right: $got", got[0] + 30 < got[1] && got[1] + 30 < got[2] && got[2] + 30 < got[3])
        try { samePicture("texture frame", f) } finally { f.release() }
    }

    /** Bright stamp pixels in the picture as seen, by region. */
    private fun stampWhere(seen: VideoFrame.I420Buffer): Triple<Int, Int, Int> {
        val w = seen.width; val h = seen.height
        var topLeft = 0; var rightEdge = 0; var lowerHalf = 0
        for (y in 0 until h) for (x in 0 until w) if (lum(seen, x, y) > 200) {
            when {
                y >= h / 2 -> lowerHalf++
                x >= w * 85 / 100 -> rightEdge++
                y < h / 5 -> topLeft++
                else -> lowerHalf++
            }
        }
        return Triple(topLeft, rightEdge, lowerHalf)
    }

    @Test fun stampUprightTopLeftInLiveViewAndRecordings() {
        val text = "25/11/2026 Wed 10:22:33"
        val f = i420(320, 240) { _, _ -> 128 }
        try {
            for (rot in intArrayOf(0, 90, 180, 270)) {
                // Live view: turned on the chip, already the right way up.
                val live = gpuTurn(f, rot, text, 0)
                val (tl, re, low) = stampWhere(live)
                assertTrue("live $rot°: stamp in the top left ($tl bright pixels), none elsewhere ($re, $low)", tl > 150 && re == 0 && low == 0)
                // Recordings: kept the camera's way round; players turn it by rot.
                val rec = gpuTurn(f, 0, text, rot)
                val seen = Upright.turn(VideoFrame(rec, 0, 0), rot)!!
                val seenI = seen.buffer.toI420()!!
                val (tl2, re2, low2) = stampWhere(seenI)
                assertTrue("recording $rot°: stamp in the top left once turned ($tl2), none elsewhere ($re2, $low2)", tl2 > 150 && re2 == 0 && low2 == 0)
                // Same size of stamp both ways.
                assertTrue("live $tl and recording $tl2 stamps about the same", Math.abs(tl - tl2) < tl / 4 + 20)
                seenI.release(); seen.release()
                live.release(); rec.release()
            }
        } finally { f.release() }
    }
}
