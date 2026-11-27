package net.homecam.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
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

    fun close() {
        try { det?.close() } catch (_: Throwable) {}
        det = null
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
        )
        val NAMES = mapOf(
            "person" to "Person", "bicycle" to "Bicycle", "car" to "Car", "motorcycle" to "Motorbike", "bus" to "Bus",
            "truck" to "Truck", "bird" to "Bird", "cat" to "Cat", "dog" to "Dog", "horse" to "Horse", "sheep" to "Sheep", "cow" to "Cow",
        )
        // How sure it must be before an alert says so. People matter most and are what the
        // model knows best; others need a little more.
        fun needScore(label: String) = if (label == "person") 0.45f else 0.5f

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
