package net.homecam.phone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The date and time written into the picture's top left corner (as seen, whatever way the frame
 * is turned), like the Reolink cameras do. Goes into the frame itself, so live view, recordings,
 * long records and photos all show it. White with a dark outline, readable on any background.
 */
class Stamp {
    private val fmt = SimpleDateFormat("dd/MM/yyyy EEE HH:mm:ss", Locale.UK)
    private var key = ""
    // The text's pixels in the frame: index into Y, into U/V (or -1), value and how much of it.
    private var yIdx = IntArray(0)
    private var uvIdx = IntArray(0)
    private var lum = IntArray(0)
    private var alpha = IntArray(0)
    private var n = 0

    /** Returns a new frame with the stamp (the caller releases it), or null if it couldn't. */
    fun apply(f: VideoFrame): VideoFrame? {
        val src = f.buffer.toI420() ?: return null
        try {
            val w = src.width
            val h = src.height
            val buf = writable(src)
            val text = fmt.format(Date())
            val k = "$text|$w|$h|${f.rotation}|${buf.strideY}|${buf.strideU}"
            if (k != key) { build(text, w, h, f.rotation, buf.strideY, buf.strideU); key = k }
            val sameUV = buf.strideU == buf.strideV
            val y = buf.dataY
            val u = buf.dataU
            val v = buf.dataV
            for (i in 0 until n) {
                val a = alpha[i]
                val yi = yIdx[i]
                val old = y.get(yi).toInt() and 0xff
                y.put(yi, ((old * (255 - a) + lum[i] * a) / 255).toByte())
                val c = uvIdx[i]
                if (c >= 0 && sameUV) {
                    val ou = u.get(c).toInt() and 0xff
                    val ov = v.get(c).toInt() and 0xff
                    u.put(c, ((ou * (255 - a) + 128 * a) / 255).toByte())
                    v.put(c, ((ov * (255 - a) + 128 * a) / 255).toByte())
                }
            }
            if (buf !== src) src.release()
            return VideoFrame(buf, f.rotation, f.timestampNs)
        } catch (e: Exception) {
            src.release()
            return null
        }
    }

    /** The camera's own buffer when it can be written to, otherwise a copy. */
    private fun writable(b: VideoFrame.I420Buffer): VideoFrame.I420Buffer {
        if (!b.dataY.isReadOnly && !b.dataU.isReadOnly && !b.dataV.isReadOnly) return b
        val c = JavaI420Buffer.allocate(b.width, b.height)
        fun copy(from: java.nio.ByteBuffer, fs: Int, to: java.nio.ByteBuffer, ts: Int, cw: Int, ch: Int) {
            val s = from.duplicate()
            val d = to.duplicate()
            for (r in 0 until ch) {
                s.limit(r * fs + cw); s.position(r * fs)
                d.position(r * ts)
                d.put(s)
                s.limit(s.capacity())
            }
        }
        copy(b.dataY, b.strideY, c.dataY, c.strideY, b.width, b.height)
        copy(b.dataU, b.strideU, c.dataU, c.strideU, (b.width + 1) / 2, (b.height + 1) / 2)
        copy(b.dataV, b.strideV, c.dataV, c.strideV, (b.width + 1) / 2, (b.height + 1) / 2)
        return c
    }

    /** Draws the text upright, then works out where each of its pixels lands in the frame. */
    private fun build(text: String, w: Int, h: Int, rot: Int, strideY: Int, strideUV: Int) {
        val side = rot % 180 != 0
        val dw = if (side) h else w
        val dh = if (side) w else h
        val size = maxOf(12f, minOf(dw, dh) / 22f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        val tw = paint.measureText(text)
        val pad = (size / 3).toInt()
        val bw = minOf(dw, (tw + 2 * pad).toInt() + 2)
        val bh = minOf(dh, (size * 1.35f).toInt() + 2 * pad / 2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val base = pad / 2 + size
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(2f, size / 8f); paint.color = Color.BLACK
        cv.drawText(text, pad.toFloat(), base, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.WHITE
        cv.drawText(text, pad.toFloat(), base, paint)
        val px = IntArray(bw * bh)
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        bmp.recycle()
        val ox = maxOf(2, dw / 80)
        val oy = maxOf(2, dh / 80)
        var cnt = 0
        for (p in px) if ((p ushr 24) != 0) cnt++
        yIdx = IntArray(cnt); uvIdx = IntArray(cnt); lum = IntArray(cnt); alpha = IntArray(cnt)
        var i = 0
        for (ty in 0 until bh) for (tx in 0 until bw) {
            val p = px[ty * bw + tx]
            val a = p ushr 24
            if (a == 0) continue
            val x = ox + tx
            val y = oy + ty
            if (x >= dw || y >= dh) continue
            // Seen picture (x, y) -> the buffer's own pixel, for a frame shown turned clockwise by rot.
            val bx: Int
            val by: Int
            when (rot) {
                90 -> { bx = y; by = h - 1 - x }
                180 -> { bx = w - 1 - x; by = h - 1 - y }
                270 -> { bx = w - 1 - y; by = x }
                else -> { bx = x; by = y }
            }
            if (bx !in 0 until w || by !in 0 until h) continue
            // The colour is grey (white or black): its brightness (getPixels isn't premultiplied).
            val g = p and 0xff
            yIdx[i] = by * strideY + bx
            uvIdx[i] = if (bx % 2 == 0 && by % 2 == 0) (by / 2) * strideUV + bx / 2 else -1
            lum[i] = 16 + g * 219 / 255
            alpha[i] = a
            i++
        }
        n = i
    }
}
