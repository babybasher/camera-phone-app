package net.homecam.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The better detector (YOLO11s) on Android: put in place as if fetched from the camera page
 * (the test copy comes from the test-model branch), checked, loaded, and run on real photos.
 */
@RunWith(AndroidJUnit4::class)
class YoloTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx = InstrumentationRegistry.getInstrumentation().context

    @Before fun putModel() {
        val f = File(ctx.filesDir, Yolo.FILE)
        if (!f.exists() || f.length() != Yolo.SIZE_BYTES) testCtx.assets.open(Yolo.FILE).use { i -> f.outputStream().use { i.copyTo(it) } }
    }

    private fun jpeg(name: String, dim: Float = 1f): ByteArray {
        val b = testCtx.assets.open(name).use { BitmapFactory.decodeStream(it) }
        val out = if (dim == 1f) b else Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(dim, dim, dim, 1f) }) })
        }
        val o = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 90, o)
        return o.toByteArray()
    }

    @Test fun namesPeopleAndTheBus() {
        val d = Detector(ctx)
        assertTrue("the model is checked and ready", d.yolo.ready())
        val pic = jpeg("bus.jpg")
        val t0 = System.currentTimeMillis()
        val r = d.help(listOf(pic, pic), setOf("person", "vehicle", "bike", "animal"))
        val ms = System.currentTimeMillis() - t0
        assertEquals("'${d.yolo.state}'", Yolo.NAME, r.model)
        val people = r.found.filter { it.second.label == "person" && it.second.score >= 0.7f }
        assertTrue("people named, sure: ${r.found.map { it.second }}", people.map { it.first }.distinct().size == 2 && people.size >= 6)
        assertTrue("the bus: ${r.found.map { it.second }}", r.found.any { it.second.label == "bus" && it.second.score >= 0.8f })
        assertTrue("not dark", !r.dark)
        assertTrue("2 pictures looked at in time (took $ms ms, model load included)", ms < 15_000)
        // Again, now it's loaded: what a real look costs.
        val t1 = System.currentTimeMillis()
        d.help(listOf(pic, pic), setOf("person"))
        android.util.Log.i("YoloTest", "two pictures took ${System.currentTimeMillis() - t1} ms")
        d.close()
    }

    @Test fun nightPictureIsBrightened() {
        val d = Detector(ctx)
        val pic = jpeg("zidane.jpg", 0.22f)
        val bmp = Detector.decode(pic)!!
        val tone = Yolo.darkTone(bmp)
        assertNotNull("a dim picture counts as dark", tone)
        assertNull("a daylight one doesn't", Yolo.darkTone(Detector.decode(jpeg("bus.jpg"))!!))
        val r = d.help(listOf(pic, pic), setOf("person"))
        assertTrue("dark", r.dark)
        assertTrue("both people still named: ${r.found.map { it.second }}", r.found.filter { it.first == 0 && it.second.label == "person" && it.second.score >= 0.6f }.size >= 2)
        d.close()
    }

    /** The phone's own look (plugged in): the whole picture and a close-up where it moved. */
    @Test fun ownLookWithCloseUp() {
        val d = Detector(ctx)
        val bmp = Detector.decode(jpeg("bus.jpg"))!!
        // Changed spots around the man on the left.
        val moved = ArrayList<Float>()
        var y = 0.4f
        while (y < 0.9f) { var x = 0.05f; while (x < 0.3f) { moved.add(x); moved.add(y); x += 0.02f }; y += 0.02f }
        val f = d.lookBetter(bmp, moved.toFloatArray())
        val v = Detector.decide(f, moved.toFloatArray(), setOf("person"), ArrayList(), 0)
        assertEquals("${v.notes}", "person", v.alert?.label)
        d.close()
    }

    /**
     * Something small and far off: a man about 160 pixels tall in a 2560 x 1440 camera picture,
     * 40 in its small ones. The full-size picture, looked at in detail where the small ones
     * changed, makes him out.
     */
    @Test fun farAwayInTheFullPicture() {
        val src = testCtx.assets.open("zidane.jpg").use { BitmapFactory.decodeStream(it) }
        val man = android.graphics.Rect(src.width * 5 / 100, src.height * 15 / 100, src.width * 45 / 100, src.height)
        fun scene(x: Int): Bitmap {
            val b = Bitmap.createBitmap(2560, 1440, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(android.graphics.Color.rgb(125, 115, 95))
            val h = 160
            val w = h * man.width() / man.height()
            c.drawBitmap(src, man, android.graphics.Rect(x, 900, x + w, 900 + h), Paint().apply { isFilterBitmap = true })
            return b
        }
        fun jpg(b: Bitmap, w: Int, h: Int): ByteArray {
            val s = Bitmap.createScaledBitmap(b, w, h, true)
            val o = ByteArrayOutputStream()
            s.compress(Bitmap.CompressFormat.JPEG, 85, o)
            return o.toByteArray()
        }
        val d = Detector(ctx)
        val a = scene(1500); val m = scene(1530); val b = scene(1560)
        val smallOnly = d.help(listOf(jpg(a, 640, 360), jpg(b, 640, 360)), setOf("person"))
        val t0 = System.currentTimeMillis()
        val withFull = d.help(listOf(jpg(a, 640, 360), jpg(m, 2560, 1440), jpg(b, 640, 360)), setOf("person"))
        val ms = System.currentTimeMillis() - t0
        fun best(r: Detector.Help, pic: Int) = r.found.filter { it.second.label == "person" && (pic < 0 || it.first == pic) }.maxOfOrNull { it.second.score } ?: 0f
        android.util.Log.i("YoloTest", "far away: small pictures ${best(smallOnly, -1)}, full-size in detail ${best(withFull, 1)}, $ms ms")
        assertTrue("made out in the full-size picture: ${withFull.found.map { it.second }} (small only: ${best(smallOnly, -1)})", best(withFull, 1) >= 0.6f && best(withFull, 1) >= best(smallOnly, -1) + 0.1f)
        assertTrue("where it changed, from the small ones", withFull.moved.isNotEmpty())
        d.close()
    }

    @Test fun detailSquares() {
        val one = Yolo.detailTiles(2560, 1440, RectF(0.5f, 0.5f, 0.55f, 0.6f), 640, 2)
        assertEquals(1, one.size)
        assertEquals(640f, one[0].width() * 2560, 1f)
        val all = Yolo.detailTiles(2560, 1440, null, 640, 4)
        assertTrue(all.size in 1..4)
        assertEquals(0f, all.minOf { it.left }, 0.001f); assertEquals(1f, all.maxOf { it.right }, 0.001f)
        assertEquals(1, Yolo.fullPic(listOf(640, 2560, 640))); assertEquals(-1, Yolo.fullPic(listOf(640, 640)))
        val dogNotPerson = Yolo.nms(listOf(Detector.Found("person", 0.51f, RectF(0.5f, 0.4f, 0.55f, 0.5f)), Detector.Found("dog", 0.45f, RectF(0.49f, 0.41f, 0.56f, 0.5f))))
        assertEquals(listOf("dog"), dogNotPerson.map { it.label })
    }

    @Test fun closeUpBox() {
        val c = Yolo.closeUp(RectF(0.45f, 0.45f, 0.5f, 0.5f))!!
        assertEquals(1f / 3, c.width(), 0.001f)
        assertEquals(0.475f, c.centerX(), 0.001f)
        val edge = Yolo.closeUp(RectF(0f, 0.9f, 0.1f, 1f))!!
        assertEquals(0f, edge.left, 0.001f); assertEquals(1f, edge.bottom, 0.001f)
        assertNull("most of the picture changed", Yolo.closeUp(RectF(0f, 0f, 0.9f, 0.9f)))
    }

    @Test fun badFileIsDropped() {
        val f = File(ctx.filesDir, Yolo.FILE)
        val keep = f.readBytes()
        try {
            f.writeBytes(keep.copyOf().also { it[1000] = (it[1000] + 1).toByte() })
            assertTrue("a changed file isn't used", !Yolo(ctx).have())
            assertTrue("and is deleted", !f.exists())
        } finally {
            f.writeBytes(keep)
        }
    }
}
