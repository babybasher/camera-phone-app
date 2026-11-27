package net.homecam.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.ConnectivityManager
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * The better detector (YOLO11s, the 80 COCO things, the same one the PCs use first): much surer
 * of people, bikes and dogs than EfficientDet, but bigger and slower. Not in the app: fetched
 * once from the camera page (on Wi-Fi), checked against its known checksum, kept in the app's
 * own storage. Until then (or if it can't run) the phone uses EfficientDet as before.
 */
class Yolo(private val ctx: Context) {
    private val file = File(ctx.filesDir, FILE)
    private var interp: Interpreter? = null
    private val input: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { Array(84) { FloatArray(BOXES) } }
    private val px = IntArray(SIZE * SIZE)
    /** What's up with it, for the settings page: "" (ready or not fetched yet), "fetching", or what went wrong. */
    @Volatile var state = ""
    @Volatile private var fetching = false
    @Volatile private var lastTry = 0L

    /** On the phone and checked (checked once per run of the app). */
    @Volatile private var checked = false
    fun have(): Boolean {
        if (checked) return true
        if (!file.exists() || file.length() != SIZE_BYTES) return false
        checked = sha(file) == SHA
        if (!checked) file.delete()
        return checked
    }

    /**
     * Fetch it from the camera page if it isn't here yet: in the background, only on an
     * unmetered connection (it's 38 MB), at most once an hour.
     */
    fun fetchSoon(p: Prefs) {
        if (fetching || p.server.isEmpty() || have()) return
        val now = System.currentTimeMillis()
        if (now - lastTry < 3600_000L) return
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        if (cm == null || cm.isActiveNetworkMetered) return
        lastTry = now
        fetching = true
        Thread {
            try {
                state = "fetching"
                val part = File(ctx.filesDir, "$FILE.part")
                val req = Request.Builder().url(p.server.trimEnd('/') + "/" + FILE).header("Authorization", Net.basic(p)).header("Cache-Control", "no-store").build()
                Net.http.newCall(req).execute().use { r ->
                    if (r.code == 404) throw IOException("the camera system doesn't have it yet (it needs update 147)")
                    if (!r.isSuccessful) throw IOException("the camera page answered ${r.code}")
                    part.outputStream().use { o -> r.body!!.byteStream().copyTo(o, 65536) }
                }
                if (sha(part) != SHA) { part.delete(); throw IOException("the download didn't match its checksum") }
                if (!part.renameTo(file)) throw IOException("couldn't keep it")
                state = ""
            } catch (e: Throwable) {
                state = "couldn't fetch the better detector: " + (e.message ?: e.toString()).take(120)
            } finally {
                fetching = false
            }
        }.apply { isDaemon = true; name = "yolo-fetch" }.start()
    }

    private fun get(): Interpreter? {
        interp?.let { return it }
        if (!have()) return null
        return try {
            Interpreter(file, Interpreter.Options().setNumThreads(4)).also { i -> interp = i; state = "" }
        } catch (e: Throwable) {
            state = CANT + ": " + (e.message ?: e.toString()).take(120)
            // (ready() is false from now on, until the app restarts)
            null
        }
    }

    fun failed() = state.startsWith(CANT)
    fun ready() = interp != null || (have() && !failed())

    /**
     * Everything it recognises (that the router knows) in an upright picture, or the part of it
     * at crop (0..1), with boxes as fractions of the whole picture. tone brightens a dark one.
     * Empty if it can't run.
     */
    fun look(bmp: Bitmap, crop: RectF? = null, tone: Tone = Tone.PLAIN): List<Detector.Found> {
        val i = get() ?: return emptyList()
        val bw = bmp.width
        val bh = bmp.height
        val cx0 = ((crop?.left ?: 0f) * bw).toInt().coerceIn(0, bw - 1)
        val cy0 = ((crop?.top ?: 0f) * bh).toInt().coerceIn(0, bh - 1)
        val cw = (((crop?.right ?: 1f) * bw).toInt() - cx0).coerceIn(1, bw - cx0)
        val ch = (((crop?.bottom ?: 1f) * bh).toInt() - cy0).coerceIn(1, bh - cy0)
        if (crop != null && (cw < 32 || ch < 32)) return emptyList()
        val r = minOf(SIZE.toFloat() / cw, SIZE.toFloat() / ch)
        val nw = Math.round(cw * r).coerceIn(1, SIZE)
        val nh = Math.round(ch * r).coerceIn(1, SIZE)
        val left = (SIZE - nw) / 2
        val top = (SIZE - nh) / 2
        val part = if (crop != null) Bitmap.createBitmap(bmp, cx0, cy0, cw, ch) else bmp
        val scaled = Bitmap.createScaledBitmap(part, nw, nh, true)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== part && scaled !== bmp) scaled.recycle()
        if (part !== bmp) part.recycle()
        input.rewind()
        val grey = 114f / 255
        val lo = tone.lo
        val span = tone.hi - tone.lo
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            if (y < top || y >= top + nh || x < left || x >= left + nw) {
                input.putFloat(grey); input.putFloat(grey); input.putFloat(grey)
                continue
            }
            val c = px[(y - top) * nw + (x - left)]
            input.putFloat((((c shr 16 and 255) - lo) / span).coerceIn(0f, 1f))
            input.putFloat((((c shr 8 and 255) - lo) / span).coerceIn(0f, 1f))
            input.putFloat((((c and 255) - lo) / span).coerceIn(0f, 1f))
        }
        input.rewind()
        try {
            i.run(input, output)
        } catch (e: Throwable) {
            state = CANT + ": " + (e.message ?: e.toString()).take(120)
            return emptyList()
        }
        val out = output[0]
        val found = ArrayList<Detector.Found>()
        for (k in 0 until BOXES) {
            var best = 0f
            var bc = -1
            for ((c, _) in KNOWN) { val v = out[4 + c][k]; if (v > best) { best = v; bc = c } }
            if (bc < 0 || best < MIN_SCORE) continue
            val x = out[0][k]; val y = out[1][k]; val w = out[2][k]; val h = out[3][k]
            // Model square -> the part -> the whole picture (0..1).
            fun fx(v: Float) = ((cx0 + (v - left) / r) / bw).coerceIn(0f, 1f)
            fun fy(v: Float) = ((cy0 + (v - top) / r) / bh).coerceIn(0f, 1f)
            val box = RectF(fx(x - w / 2), fy(y - h / 2), fx(x + w / 2), fy(y + h / 2))
            if (box.right <= box.left || box.bottom <= box.top) continue
            found.add(Detector.Found(KNOWN[bc]!!, best, box))
        }
        return nms(found)
    }

    fun close() {
        try { interp?.close() } catch (_: Throwable) {}
        interp = null
    }

    /** How a picture's brightness is stretched for the detector (lo..hi becomes 0..255). */
    data class Tone(val lo: Float, val hi: Float) {
        companion object { val PLAIN = Tone(0f, 255f) }
    }

    companion object {
        const val FILE = "phone-helper-yolo11s.tflite"
        const val SHA = "521d9c34f9ef50e172e5740de3baaa5460f1170c42629a4b5f1296d53ad4acbd"
        const val SIZE_BYTES = 38_034_416L
        const val NAME = "YOLO11s"
        const val CANT = "the better detector can't run"
        const val SIZE = 640
        const val BOXES = 8400
        /** Low, for the router's "seen in two pictures" (it decides what counts). */
        const val MIN_SCORE = 0.2f
        /** The COCO numbers of the things the router knows. */
        val KNOWN = mapOf(0 to "person", 1 to "bicycle", 2 to "car", 3 to "motorcycle", 5 to "bus", 7 to "truck",
            14 to "bird", 15 to "cat", 16 to "dog", 17 to "horse", 18 to "sheep", 19 to "cow")

        fun sha(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        /** The surest of overlapping boxes of the same thing; at most 30. */
        fun nms(f: List<Detector.Found>): List<Detector.Found> {
            val keep = ArrayList<Detector.Found>()
            for (x in f.sortedByDescending { it.score }) {
                if (keep.none { it.label == x.label && Detector.iou(it.box, x.box) > 0.5f }) keep.add(x)
                if (keep.size == 30) break
            }
            return keep
        }

        /**
         * A dark picture (night, infrared) and the stretch that brightens it: its darkest 1 % to
         * black, its brightest 1 % to white. Null when it isn't dark.
         */
        fun darkTone(bmp: Bitmap): Tone? {
            val w = bmp.width
            val h = bmp.height
            val row = IntArray(w)
            val hist = IntArray(256)
            var n = 0
            var sum = 0.0
            var y = 0
            while (y < h) {
                bmp.getPixels(row, 0, w, 0, y, w, 1)
                var x = 0
                while (x < w) {
                    val c = row[x]
                    val v = (0.299 * (c shr 16 and 255) + 0.587 * (c shr 8 and 255) + 0.114 * (c and 255)).toInt().coerceIn(0, 255)
                    hist[v]++; sum += v; n++
                    x += 6
                }
                y += 6
            }
            if (n == 0 || sum / n >= 90) return null
            fun at(frac: Double): Float {
                val want = (frac * n).toInt()
                var c = 0
                for (v in 0 until 256) { c += hist[v]; if (c > want) return v.toFloat() }
                return 255f
            }
            val lo = at(0.01)
            val hi = maxOf(at(0.99), lo + 60)
            return Tone(lo, hi)
        }

        /**
         * The part of the picture to look at again, closer: where it changed, made at least a
         * third of the picture each way, within it. Null when the change covers most of it.
         */
        fun closeUp(box: RectF?): RectF? {
            if (box == null || box.width() * box.height() > 0.45f) return null
            val c = RectF(box)
            fun fit(a: Float, b: Float): Pair<Float, Float> {
                if (b - a >= 1f / 3) return Pair(a, b)
                val mid = (a + b) / 2
                return when {
                    mid - 1f / 6 < 0 -> Pair(0f, 1f / 3)
                    mid + 1f / 6 > 1 -> Pair(2f / 3, 1f)
                    else -> Pair(mid - 1f / 6, mid + 1f / 6)
                }
            }
            fit(c.left, c.right).let { c.left = it.first; c.right = it.second }
            fit(c.top, c.bottom).let { c.top = it.first; c.bottom = it.second }
            return c
        }
    }
}
