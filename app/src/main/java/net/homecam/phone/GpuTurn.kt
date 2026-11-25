package net.homecam.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import org.webrtc.GlRectDrawer
import org.webrtc.GlTextureFrameBuffer
import org.webrtc.TextureBufferImpl
import org.webrtc.VideoFrame
import org.webrtc.VideoFrameDrawer
import org.webrtc.YuvConverter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns camera frames and writes the time stamp on the graphics chip, so the picture never has to
 * be copied into ordinary memory: the frame comes out as a graphics texture the video encoder takes
 * as it is. Much cheaper than doing it on the main processor, which matters on older phones.
 *
 * Must be used on the camera's frame thread (its graphics context is current there).
 */
class GpuTurn {
    private val drawer = GlRectDrawer()
    private val frameDrawer = VideoFrameDrawer()
    private val yuv = YuvConverter()
    private val handler = Handler(Looper.myLooper()!!)

    private class Fb(val w: Int, val h: Int) {
        val fb = GlTextureFrameBuffer(GLES20.GL_RGBA).also { it.setSize(w, h) }
        val busy = AtomicBoolean(false)
    }
    private val pool = ArrayList<Fb>()

    // The stamp's picture (redrawn when the text or the picture's size changes, once a second).
    private var stampTex = 0
    private var stampKey = ""
    private var stampW = 0
    private var stampH = 0

    /**
     * Draws [src] turned clockwise by [turn] into a new texture frame (rotation 0; the caller releases
     * it). With [stamp] text, it's written in the top left corner of the picture as it will finally
     * be seen, which is this output turned clockwise by [seenTurn] (0 when the output is already the
     * right way up). Returns null if there's no free buffer.
     */
    fun render(src: VideoFrame, turn: Int, stamp: String?, seenTurn: Int = 0): VideoFrame? {
        val side = turn % 180 != 0
        val w = if (side) src.buffer.height else src.buffer.width
        val h = if (side) src.buffer.width else src.buffer.height
        val fb = obtain(w, h) ?: return null
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb.fb.frameBufferId)
        try {
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            frameDrawer.drawFrame(VideoFrame(src.buffer, (turn + 180) % 360, src.timestampNs), drawer, null, 0, 0, w, h)
            if (stamp != null) drawStamp(stamp, w, h, seenTurn)
            GLES20.glFinish()
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) { fb.busy.set(false); throw IllegalStateException("GL error $err") }
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
        val buf = TextureBufferImpl(w, h, VideoFrame.TextureBuffer.Type.RGB, fb.fb.textureId, Matrix(),
            handler, yuv) { fb.busy.set(false) }
        return VideoFrame(buf, 0, src.timestampNs)
    }

    /** A free buffer of this size (buffers the encoder still holds are skipped; old sizes go). */
    private fun obtain(w: Int, h: Int): Fb? {
        val it = pool.iterator()
        while (it.hasNext()) {
            val f = it.next()
            if ((f.w != w || f.h != h) && !f.busy.get()) { f.fb.release(); it.remove() }
        }
        for (f in pool) if (f.w == w && f.h == h && f.busy.compareAndSet(false, true)) return f
        if (pool.count { it.w == w && it.h == h } >= 6) return null
        val f = Fb(w, h)
        f.busy.set(true)
        pool.add(f)
        return f
    }

    private fun drawStamp(text: String, outW: Int, outH: Int, seen: Int) {
        val r = ((seen % 360) + 360) % 360
        val sideways = r % 180 != 0
        val dw = if (sideways) outH else outW
        val dh = if (sideways) outW else outH
        val key = "$text|${minOf(dw, dh)}"
        if (key != stampKey) {
            val bmp = StampText.bitmap(text, dw, dh)
            if (stampTex == 0) {
                val t = IntArray(1)
                GLES20.glGenTextures(1, t, 0)
                stampTex = t[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stampTex)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            } else GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, stampTex)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            stampW = bmp.width
            stampH = bmp.height
            bmp.recycle()
            stampKey = key
        }
        val p = StampText.place(stampW, stampH, outW, outH, r)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        drawer.drawRgb(stampTex, p.texMatrix, stampW, stampH, p.x, p.y, p.w, p.h)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /** Frees the graphics memory (camera thread, before its graphics context goes). */
    fun release() {
        for (f in pool) f.fb.release()
        pool.clear()
        if (stampTex != 0) { GLES20.glDeleteTextures(1, intArrayOf(stampTex), 0); stampTex = 0 }
        stampKey = ""
        drawer.release()
        frameDrawer.release()
        yuv.release()
    }
}

/** The stamp's text and picture, shared by the graphics chip and main processor versions. */
object StampText {
    private val fmt = java.text.SimpleDateFormat("dd/MM/yyyy EEE HH:mm:ss", java.util.Locale.UK)
    fun now(): String = synchronized(fmt) { fmt.format(java.util.Date()) }

    /** Text size for a picture seen [dw] x [dh]. */
    fun size(dw: Int, dh: Int) = maxOf(12f, minOf(dw, dh) / 27f)

    /** Corner gap for a picture seen [dw] x [dh]. */
    fun gapX(dw: Int) = maxOf(2, dw / 80)
    fun gapY(dh: Int) = maxOf(2, dh / 80)

    /** White text with a dark outline on a clear background, upright. */
    fun bitmap(text: String, dw: Int, dh: Int): Bitmap {
        val size = size(dw, dh)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val pad = (size / 3).toInt()
        val bw = maxOf(1, minOf(dw, (paint.measureText(text) + 2 * pad).toInt() + 2))
        val bh = maxOf(1, minOf(dh, (size * 1.35f).toInt() + 2 * pad / 2))
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val base = pad / 2 + size
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(2f, size / 8f); paint.color = Color.BLACK
        cv.drawText(text, pad.toFloat(), base, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        cv.drawText(text, pad.toFloat(), base, paint)
        return bmp
    }

    class Place(val x: Int, val y: Int, val w: Int, val h: Int, val texMatrix: FloatArray)

    /**
     * Where to draw a [bw] x [bh] stamp in an [outW] x [outH] output (graphics viewport, origin
     * bottom left) so that it sits upright in the top left corner once the output is turned
     * clockwise by [r], and the texture matrix that turns the stamp's picture to match.
     */
    fun place(bw: Int, bh: Int, outW: Int, outH: Int, r: Int): Place {
        val sideways = r % 180 != 0
        val dw = if (sideways) outH else outW
        val dh = if (sideways) outW else outH
        val ox = gapX(dw).toFloat()
        val oy = gapY(dh).toFloat()
        val W = outW.toFloat()
        val H = outH.toFloat()
        // Seen point (top left origin) -> output point (top left origin).
        fun toOut(x: Float, y: Float): Pair<Float, Float> = when (r) {
            90 -> Pair(H - y, x)
            180 -> Pair(W - x, H - y)
            270 -> Pair(W - y, x)
            else -> Pair(x, y)
        }
        // And back.
        fun toSeen(bx: Float, by: Float): Pair<Float, Float> = when (r) {
            90 -> Pair(H - by, bx)
            180 -> Pair(W - bx, H - by)
            270 -> Pair(by, W - bx)
            else -> Pair(bx, by)
        }
        val cs = listOf(toOut(ox, oy), toOut(ox + bw, oy), toOut(ox, oy + bh), toOut(ox + bw, oy + bh))
        val rx = cs.minOf { it.first }
        val ry = cs.minOf { it.second }
        val rw = cs.maxOf { it.first } - rx
        val rh = cs.maxOf { it.second } - ry
        // Viewport (u, v) in 0..1, v up -> stamp picture (s, t), t = 0 at its top row.
        fun st(u: Float, v: Float): Pair<Float, Float> {
            val (x, y) = toSeen(rx + u * rw, ry + (1 - v) * rh)
            return Pair((x - ox) / bw, (y - oy) / bh)
        }
        val o = st(0f, 0f)
        val ux = st(1f, 0f)
        val vy = st(0f, 1f)
        val m = FloatArray(16)
        m[0] = ux.first - o.first; m[1] = ux.second - o.second
        m[4] = vy.first - o.first; m[5] = vy.second - o.second
        m[10] = 1f
        m[12] = o.first; m[13] = o.second
        m[15] = 1f
        return Place(Math.round(rx), Math.round(H - (ry + rh)), Math.round(rw), Math.round(rh), m)
    }
}
