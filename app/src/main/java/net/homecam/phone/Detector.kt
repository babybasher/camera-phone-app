package net.homecam.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import org.webrtc.VideoFrame

/**
 * "What moved": a small object detector on the phone (EfficientDet-Lite2, the 80 everyday things of
 * the COCO set; no internet). It looks at a picture taken just after motion and says what's there.
 * Only people, vehicles and animals count, and only where the picture changed: a car parked in the
 * same place as before, with a shadow passing over it, is not "Car detected".
 */
class Detector(private val ctx: Context) {
    data class Found(val label: String, val score: Float, val box: RectF)
    /** Something seen before in the same place (a parked car): not what moved. */
    class Still(val label: String, val box: RectF, val first: Long, var last: Long, var times: Int)
    /** What to alert about (null: nothing that counts), and what it saw, for the settings page. */
    data class Verdict(val alert: Found?, val notes: List<String>, val at: Long = 0)

    private var det: ObjectDetector? = null
    @Volatile var error = ""

    private fun get(): ObjectDetector? {
        det?.let { return it }
        return try {
            val o = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("detect.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(MIN_SCORE)
                .setMaxResults(15)
                .build()
            ObjectDetector.createFromOptions(ctx, o).also { det = it; error = "" }
        } catch (e: Throwable) {
            error = (e.message ?: e.toString()).take(200)
            null
        }
    }

    /** Everything it recognises in an upright picture; boxes are fractions of the picture (0..1). */
    fun look(bmp: Bitmap): List<Found> {
        val d = get() ?: return emptyList()
        val r = try { d.detect(BitmapImageBuilder(bmp).build()) } catch (e: Throwable) { error = (e.message ?: e.toString()).take(200); return emptyList() }
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val out = ArrayList<Found>()
        for (x in r.detections()) {
            val c = x.categories().maxByOrNull { it.score() } ?: continue
            val b = x.boundingBox()
            out.add(Found(c.categoryName(), c.score(), RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)))
        }
        return out
    }

    // The wildlife check: a picture classifier (EfficientNet-Lite0, the 1,000 ImageNet things) asked
    // about just the patch that moved, when the detector found nothing it knows there.
    private var cls: ImageClassifier? = null
    @Volatile var wildError = ""

    private fun classifier(): ImageClassifier? {
        cls?.let { return it }
        if (wildError.isNotEmpty()) return null
        return try {
            val o = ImageClassifier.ImageClassifierOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("wildlife.tflite").build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(10)
                .setScoreThreshold(0.03f)
                .build()
            ImageClassifier.createFromOptions(ctx, o).also { cls = it }
        } catch (e: Throwable) {
            wildError = (e.message ?: e.toString()).take(200)
            null
        }
    }

    /** Fox / kangaroo / rabbit / snake / lizard in the patch that moved, if sure enough (else null). */
    fun wildlife(bmp: Bitmap, moved: FloatArray): Found? {
        val box = movedBox(moved) ?: return null
        val c = classifier() ?: return null
        val w = bmp.width
        val h = bmp.height
        // Square in pixels around the patch (the classifier wants a squarish picture).
        val cx = (box.left + box.right) / 2 * w
        val cy = (box.top + box.bottom) / 2 * h
        val side = maxOf(box.width() * w, box.height() * h, minOf(w, h) * 0.2f).coerceAtMost(minOf(w, h).toFloat())
        val x0 = (cx - side / 2).coerceIn(0f, w - side).toInt()
        val y0 = (cy - side / 2).coerceIn(0f, h - side).toInt()
        val crop = Bitmap.createBitmap(bmp, x0, y0, side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1))
        val cats = try {
            c.classify(BitmapImageBuilder(crop).build()).classificationResult().classifications().firstOrNull()?.categories().orEmpty().map { Pair(it.categoryName(), it.score()) }
        } catch (e: Throwable) {
            wildError = (e.message ?: e.toString()).take(200)
            emptyList()
        } finally {
            crop.recycle()
        }
        val (key, score) = wildPick(cats) ?: return null
        return Found(key, score, RectF(x0.toFloat() / w, y0.toFloat() / h, (x0 + side) / w, (y0 + side) / h))
    }

    /** A look at a Reolink's pictures for the router: what it saw in each (picture number, thing) and where they changed. */
    class Help(val found: List<Pair<Int, Found>>, val moved: FloatArray, val pics: Int, val model: String = "EfficientDet-Lite2", val dark: Boolean = false, val glint: Float = 0f)

    /** The better detector (fetched from the camera page once); EfficientDet until it's here. */
    val yolo = Yolo(ctx)

    /**
     * The better detector's look at one picture: the whole of it, then closer at where it
     * changed (small or far-off things fill more of it); a dark one brightened first.
     */
    fun lookBetter(bmp: Bitmap, moved: FloatArray, tone: Yolo.Tone? = Yolo.darkTone(bmp)): List<Found> {
        val t = tone ?: Yolo.Tone.PLAIN
        val all = ArrayList(yolo.look(bmp, null, t))
        Yolo.closeUp(movedBox(moved))?.let { all.addAll(yolo.look(bmp, it, t)) }
        return Yolo.nms(all)
    }

    /**
     * Helping a Reolink camera (it can't run a detector): the router sends a few of its pictures,
     * about a second apart, as JPEGs. Everything it recognises in each, where they changed, and,
     * when nothing it knows is where they changed, the wildlife check on that patch. The router
     * decides what counts (ticked, sure enough, not parked there, seen in two pictures). With
     * the better detector: the first and last pictures (where it was, where it went), each
     * whole and close up, brightened at night.
     */
    fun help(jpegs: List<ByteArray>, want: Set<String>): Help {
        val ok = jpegs.filter { size(it) != null }
        val bmps = ok.mapNotNull { decode(it) }
        try {
            if (bmps.size != ok.size) return help(emptyList(), want)
            val found = ArrayList<Pair<Int, Found>>()
            // The camera's full-size picture among its small ones: looked at in detail (where
            // they changed, at its own resolution). Where they changed: from the small ones.
            val widths = ok.map { size(it)!!.first }
            val full = Yolo.fullPic(widths)
            val small = widths.indices.filter { widths[it] == widths.minOrNull() }
            val grids = if (full >= 0 && small.size >= 2) small.map { grid(bmps[it]) } else bmps.map { grid(it) }
            val moved = changes(grids)
            val better = yolo.ready() && bmps.isNotEmpty()
            var dark = false
            if (better) {
                val t0 = System.currentTimeMillis()
                if (full >= 0) {
                    val tone = Yolo.darkTone(bmps[full])
                    if (tone != null) dark = true
                    yolo.lookDetail(ok[full], movedBox(moved), tone ?: Yolo.Tone.PLAIN).forEach { found.add(Pair(full, it)) }
                }
                val plain = bmps.indices.filter { it != full }
                val picks = if (plain.size <= 2) plain else listOf(plain.first(), plain.last())
                for (k in picks) {
                    val tone = Yolo.darkTone(bmps[k])
                    if (tone != null) dark = true
                    // With a full-size picture (looked at closely already), or on a slow phone once
                    // half the router's wait is gone: no close-ups.
                    val f = if (full >= 0 || System.currentTimeMillis() - t0 > 1800) yolo.look(bmps[k], null, tone ?: Yolo.Tone.PLAIN) else lookBetter(bmps[k], moved, tone)
                    f.forEach { found.add(Pair(k, it)) }
                }
            }
            // It couldn't run after all: EfficientDet, as before.
            val usedBetter = better && !yolo.failed()
            if (!usedBetter) {
                found.clear()
                dark = false
                bmps.forEachIndexed { k, b -> look(b).forEach { found.add(Pair(k, it)) } }
            }
            val known = found.any { (_, f) -> GROUPS.containsKey(f.label) && f.score >= needScore(f.label) && movedIn(f.box, moved) >= 2 }
            if (!known && "animal" in want && bmps.isNotEmpty()) wildlife(bmps.last(), moved)?.let { found.add(Pair(bmps.size - 1, it)) }
            return Help(found, moved, bmps.size, if (usedBetter) Yolo.NAME else "EfficientDet-Lite2", dark, glintShare(grids))
        } finally {
            bmps.forEach { it.recycle() }
        }
    }

    fun close() {
        try { det?.close() } catch (_: Throwable) {}
        det = null
        try { cls?.close() } catch (_: Throwable) {}
        cls = null
        yolo.close()
    }

    companion object {
        const val MIN_SCORE = 0.35f
        /** The things that can alert, by group (the settings tick boxes). */
        val GROUPS = mapOf(
            "person" to "person",
            "car" to "vehicle", "bus" to "vehicle", "truck" to "vehicle",
            // Bicycles and motorbikes: their own tick box (easily mistaken for each other, not for cars).
            "bicycle" to "bike", "motorcycle" to "bike",
            "bird" to "animal", "cat" to "animal", "dog" to "animal", "horse" to "animal", "sheep" to "animal", "cow" to "animal",
            // From the wildlife check.
            "fox" to "animal", "kangaroo" to "animal", "rabbit" to "animal", "snake" to "animal", "lizard" to "animal",
        )
        val NAMES = mapOf(
            "person" to "Person", "bicycle" to "Bicycle", "car" to "Car", "motorcycle" to "Motorbike", "bus" to "Bus",
            "truck" to "Truck", "bird" to "Bird", "cat" to "Cat", "dog" to "Dog", "horse" to "Horse", "sheep" to "Sheep", "cow" to "Cow",
            "fox" to "Fox", "kangaroo" to "Kangaroo", "rabbit" to "Rabbit", "snake" to "Snake", "lizard" to "Lizard",
        )

        /** The classifier's names (ImageNet) for the wildlife it can name. */
        val WILD = mapOf(
            "red fox" to "fox", "kit fox" to "fox", "Arctic fox" to "fox", "grey fox" to "fox",
            "wallaby" to "kangaroo",
            "wood rabbit" to "rabbit", "hare" to "rabbit", "Angora" to "rabbit",
            "thunder snake" to "snake", "ringneck snake" to "snake", "hognose snake" to "snake", "green snake" to "snake",
            "king snake" to "snake", "garter snake" to "snake", "water snake" to "snake", "vine snake" to "snake",
            "night snake" to "snake", "boa constrictor" to "snake", "rock python" to "snake", "Indian cobra" to "snake",
            "green mamba" to "snake", "sea snake" to "snake", "horned viper" to "snake", "diamondback" to "snake", "sidewinder" to "snake",
            "banded gecko" to "lizard", "common iguana" to "lizard", "American chameleon" to "lizard", "whiptail" to "lizard",
            "agama" to "lizard", "frilled lizard" to "lizard", "alligator lizard" to "lizard", "Gila monster" to "lizard",
            "green lizard" to "lizard", "African chameleon" to "lizard", "Komodo dragon" to "lizard",
        )
        /** How sure, adding up its names for the same animal (red fox + grey fox...), before it's named. */
        const val WILD_SURE = 0.4f

        /** The wildlife a classifier's answers add up to, if sure enough. */
        fun wildPick(cats: List<Pair<String, Float>>): Pair<String, Float>? {
            val sums = HashMap<String, Float>()
            for ((name, score) in cats) WILD[name]?.let { sums[it] = (sums[it] ?: 0f) + score }
            return sums.maxByOrNull { it.value }?.takeIf { it.value >= WILD_SURE }?.toPair()
        }

        /**
         * Where the picture changed, as a box (upright, 0..1), from the changed spots: the middle 80 %
         * of them (a stray flicker elsewhere doesn't stretch it), a quarter bigger each way. Null
         * with too few spots to go on.
         */
        fun movedBox(moved: FloatArray): RectF? {
            val n = moved.size / 2
            if (n < 3) return null
            val xs = FloatArray(n) { moved[it * 2] }.sorted()
            val ys = FloatArray(n) { moved[it * 2 + 1] }.sorted()
            val lo = (n * 0.1).toInt()
            val hi = (n * 0.9).toInt().coerceAtMost(n - 1)
            val (l, r, t, b) = listOf(xs[lo], xs[hi], ys[lo], ys[hi])
            val px = (r - l) * 0.25f + 0.02f
            val py = (b - t) * 0.25f + 0.02f
            return RectF((l - px).coerceAtLeast(0f), (t - py).coerceAtLeast(0f), (r + px).coerceAtMost(1f), (b + py).coerceAtMost(1f))
        }
        // How sure it must be before an alert says so. People matter most and are what the
        // model knows best; others need a little more.
        fun needScore(label: String) = if (label == "person") 0.45f else 0.5f

        /** A JPEG as a picture about 1,000 wide at most (plenty for the detector; a 2 MP one is halved). */
        /** Of the spots that changed, the share very bright in either picture (sun glint). */
        fun glintShare(grids: List<IntArray>): Float {
            var changed = 0
            var bright = 0
            for (k in 1 until grids.size) {
                val a = grids[k - 1]
                val b = grids[k]
                for (i in a.indices) if (Math.abs(a[i] - b[i]) > 22) { changed++; if (maxOf(a[i], b[i]) > 225) bright++ }
            }
            return if (changed == 0) 0f else Math.round(bright * 100f / changed) / 100f
        }

        /** A JPEG's width and height, or null if it isn't one. */
        fun size(jpeg: ByteArray): Pair<Int, Int>? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            return if (o.outWidth > 0 && o.outHeight > 0) Pair(o.outWidth, o.outHeight) else null
        }

        fun decode(jpeg: ByteArray): Bitmap? {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= 900) s *= 2
            return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = s })
        }

        /** A 64 x 48 grey copy of a picture (0..255), for comparing pictures. */
        fun grid(bmp: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(bmp, 64, 48, true)
            val px = IntArray(64 * 48)
            small.getPixels(px, 0, 64, 0, 0, 64, 48)
            if (small !== bmp) small.recycle()
            return IntArray(px.size) { val c = px[it]; (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8 }
        }

        /**
         * Where pictures taken about a second apart changed (x, y pairs, 0..1): the spots of their
         * grey copies that changed by more than 22, the same rule as the phone's own motion check.
         * A pair that changed nearly everywhere (the light, or night vision switching) is left out.
         */
        fun changes(grids: List<IntArray>): FloatArray {
            val out = ArrayList<Float>()
            for (k in 1 until grids.size) {
                val a = grids[k - 1]
                val b = grids[k]
                if (a.size != 64 * 48 || b.size != a.size) continue
                val ch = a.indices.filter { kotlin.math.abs(a[it] - b[it]) > 22 }
                if (ch.size > a.size * 0.6) continue
                for (i in ch) { out.add((i % 64 + 0.5f) / 64); out.add((i / 64 + 0.5f) / 48) }
            }
            return out.toFloatArray()
        }

        /** Where a point of the camera's (sideways) frame is in the upright picture. */
        fun upright(fx: Float, fy: Float, rot: Int): Pair<Float, Float> = when (((rot % 360) + 360) % 360) {
            90 -> Pair(1 - fy, fx)
            180 -> Pair(1 - fx, 1 - fy)
            270 -> Pair(fy, 1 - fx)
            else -> Pair(fx, fy)
        }

        fun iou(a: RectF, b: RectF): Float {
            val iw = minOf(a.right, b.right) - maxOf(a.left, b.left)
            val ih = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            if (iw <= 0 || ih <= 0) return 0f
            val i = iw * ih
            return i / (a.width() * a.height() + b.width() * b.height() - i)
        }

        /** How many of the changed spots (upright, 0..1) fall inside the box (a little larger: boxes are snug). */
        fun movedIn(box: RectF, moved: FloatArray): Int {
            val mx = box.width() * 0.08f
            val my = box.height() * 0.08f
            var n = 0
            var k = 0
            while (k + 1 < moved.size) {
                val x = moved[k]
                val y = moved[k + 1]
                if (x >= box.left - mx && x <= box.right + mx && y >= box.top - my && y <= box.bottom + my) n++
                k += 2
            }
            return n
        }

        /**
         * Picks what to alert about: wanted groups, sure enough, where the picture changed, and not
         * something that has sat in the same place for a while. `still` is updated with what was seen.
         */
        fun decide(found: List<Found>, moved: FloatArray, want: Set<String>, still: MutableList<Still>, now: Long): Verdict {
            val notes = ArrayList<String>()
            var best: Found? = null
            for (f in found.sortedByDescending { it.score }) {
                val group = GROUPS[f.label] ?: continue
                val name = NAMES[f.label] ?: f.label
                val pct = "${(f.score * 100).toInt()}%"
                val seen = still.firstOrNull { it.label == f.label && iou(it.box, f.box) > 0.6f }
                // Only vehicles: a parked car sits in one place for hours. A person or animal in a
                // spot where one stood before is still worth an alert.
                val parked = group == "vehicle" && seen != null && now - seen.first > 60_000
                if (seen != null) { seen.last = now; seen.times++ } else still.add(Still(f.label, RectF(f.box), now, now, 1))
                when {
                    group !in want -> notes.add("$name $pct (not ticked)")
                    f.score < needScore(f.label) -> notes.add("$name $pct (not sure enough)")
                    movedIn(f.box, moved) < 2 -> notes.add("$name $pct (not where it moved)")
                    parked -> notes.add("$name $pct (has been there a while)")
                    best == null -> { best = f; notes.add(0, "$name $pct") }
                    else -> notes.add("$name $pct")
                }
            }
            // Forget things not seen for 6 hours; keep the list small.
            still.removeAll { now - it.last > 6 * 3600_000L }
            while (still.size > 40) still.removeAt(still.indices.minByOrNull { still[it].last } ?: 0)
            return Verdict(best, notes)
        }

        /**
         * Worth a new motion event? Not when the same kind of thing (person / vehicle / bike /
         * animal: a grey cat taken for a dog one moment and a cat the next is one animal) is still
         * in about the same place as the last one logged under a minute ago. `last` is updated.
         */
        fun worthLogging(last: MutableMap<String, Pair<RectF, Long>>, f: Found, t: Long): Boolean {
            val key = GROUPS[f.label] ?: f.label
            val prev = last[key]
            val same = prev != null && t - prev.second < 60_000 && iou(prev.first, f.box) > 0.3f
            // Still there: follow it as it shifts, but keep the time of the event that was logged.
            last[key] = if (same) Pair(RectF(f.box), prev!!.second) else Pair(RectF(f.box), t)
            return !same
        }

        /** A camera frame (any size) as an upright picture about `width` wide, for the detector. */
        fun picture(frame: VideoFrame.I420Buffer, rotation: Int): Bitmap {
            val w = frame.width
            val h = frame.height
            val px = IntArray(w * h)
            val y = frame.dataY; val u = frame.dataU; val v = frame.dataV
            for (row in 0 until h) {
                val yo = row * frame.strideY
                val uo = (row / 2) * frame.strideU
                val vo = (row / 2) * frame.strideV
                for (col in 0 until w) {
                    val yy = (y.get(yo + col).toInt() and 255) - 16
                    val uu = (u.get(uo + col / 2).toInt() and 255) - 128
                    val vv = (v.get(vo + col / 2).toInt() and 255) - 128
                    val c = 1192 * maxOf(0, yy)
                    val r = (c + 1634 * vv) shr 10
                    val g = (c - 833 * vv - 400 * uu) shr 10
                    val b = (c + 2066 * uu) shr 10
                    px[row * w + col] = (0xff shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
                }
            }
            val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            if (rotation % 360 == 0) return bmp
            val turned = Bitmap.createBitmap(bmp, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            bmp.recycle()
            return turned
        }
    }
}
