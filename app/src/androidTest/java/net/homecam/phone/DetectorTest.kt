package net.homecam.phone

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.webrtc.JavaI420Buffer

/** "What moved": the model loads and runs on Android, and the rules for what counts. */
@RunWith(AndroidJUnit4::class)
class DetectorTest {
    private fun f(label: String, score: Float, l: Float, t: Float, r: Float, b: Float) = Detector.Found(label, score, RectF(l, t, r, b))

    /** Changed spots filling a box (upright, 0..1). */
    private fun movedIn(l: Float, t: Float, r: Float, b: Float): FloatArray {
        val out = ArrayList<Float>()
        var y = t + 0.01f
        while (y < b) { var x = l + 0.01f; while (x < r) { out.add(x); out.add(y); x += 0.02f }; y += 0.02f }
        return out.toFloatArray()
    }

    @Test fun modelLoadsAndRuns() {
        val d = Detector(InstrumentationRegistry.getInstrumentation().targetContext)
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val t0 = System.currentTimeMillis()
        val found = d.look(bmp)
        val ms = System.currentTimeMillis() - t0
        assertEquals("the model loads from the app: '${d.error}'", "", d.error)
        assertTrue("a plain grey picture has nothing in it: $found", found.none { it.score >= 0.5f })
        assertTrue("one look is quick enough (took $ms ms, model load included)", ms < 15_000)
        d.close()
    }

    @Test fun whatCounts() {
        val all = setOf("person", "vehicle", "animal")
        val moved = movedIn(0.4f, 0.4f, 0.6f, 0.9f)
        val person = f("person", 0.8f, 0.42f, 0.35f, 0.58f, 0.95f)

        var v = Detector.decide(listOf(person), moved, all, ArrayList(), 0)
        assertEquals("a person where it moved", "person", v.alert?.label)
        assertEquals("Person 80%", v.notes.first())

        v = Detector.decide(listOf(person), moved, setOf("vehicle"), ArrayList(), 0)
        assertNull("people not ticked", v.alert)
        assertTrue(v.notes.toString(), v.notes.first().contains("not ticked"))

        v = Detector.decide(listOf(f("person", 0.4f, 0.42f, 0.35f, 0.58f, 0.95f)), moved, all, ArrayList(), 0)
        assertNull("not sure enough", v.alert)

        v = Detector.decide(listOf(f("person", 0.9f, 0.0f, 0.0f, 0.2f, 0.3f)), moved, all, ArrayList(), 0)
        assertNull("a person somewhere else in the picture, not where it moved", v.alert)
        assertTrue(v.notes.toString(), v.notes.first().contains("not where it moved"))

        v = Detector.decide(listOf(f("potted plant", 0.9f, 0.4f, 0.4f, 0.6f, 0.9f)), moved, all, ArrayList(), 0)
        assertNull("only people, vehicles and animals", v.alert)
        assertTrue(v.notes.isEmpty())

        v = Detector.decide(listOf(f("bird", 0.7f, 0.45f, 0.5f, 0.55f, 0.6f)), moved, all, ArrayList(), 0)
        assertEquals("a chicken is a bird", "bird", v.alert?.label)

        v = Detector.decide(listOf(f("car", 0.55f, 0.3f, 0.3f, 0.7f, 0.9f), person), moved, all, ArrayList(), 0)
        assertEquals("the surest counts first", "person", v.alert?.label)
    }

    @Test fun aParkedCarIsNotWhatMoved() {
        val all = setOf("person", "vehicle", "animal")
        val still = ArrayList<Detector.Still>()
        val moved = movedIn(0.3f, 0.5f, 0.7f, 0.9f)   // a shadow over the car
        val car = f("car", 0.9f, 0.3f, 0.5f, 0.7f, 0.9f)
        assertEquals("first seen: it just arrived", "car", Detector.decide(listOf(car), moved, all, still, 0).alert?.label)
        val later = f("car", 0.88f, 0.31f, 0.5f, 0.7f, 0.91f)
        val v = Detector.decide(listOf(later), moved, all, still, 5 * 60_000)
        assertNull("same car, same place, 5 minutes later", v.alert)
        assertTrue(v.notes.toString(), v.notes.first().contains("there a while"))
        val other = f("car", 0.9f, 0.0f, 0.4f, 0.25f, 0.7f)
        assertEquals("a car somewhere new still counts", "car", Detector.decide(listOf(other), movedIn(0f, 0.4f, 0.25f, 0.7f), all, still, 6 * 60_000).alert?.label)
        val person = f("person", 0.9f, 0.4f, 0.4f, 0.6f, 0.9f)
        Detector.decide(listOf(person), moved, all, still, 0)
        assertEquals("people never count as parked", "person", Detector.decide(listOf(person), moved, all, still, 10 * 60_000).alert?.label)
    }

    @Test fun picturesAreUpright() {
        // A sideways frame, top-left corner bright: turned 90 degrees it's at the top right.
        val b = JavaI420Buffer.allocate(64, 48)
        val y = b.dataY
        for (r in 0 until 48) for (c in 0 until 64) y.put(r * b.strideY + c, (if (r < 8 && c < 8) 235 else 16).toByte())
        for (i in 0 until b.dataU.capacity()) b.dataU.put(i, 128.toByte())
        for (i in 0 until b.dataV.capacity()) b.dataV.put(i, 128.toByte())
        val p = Detector.picture(b, 90)
        b.release()
        assertEquals(48, p.width); assertEquals(64, p.height)
        assertTrue("bright corner at the top right", Color.red(p.getPixel(45, 2)) > 200 && Color.red(p.getPixel(2, 2)) < 40)
        // And the motion spots turn the same way.
        val (u, v) = Detector.upright(0.05f, 0.05f, 90)
        assertTrue("$u,$v", u > 0.9f && v < 0.1f)
    }
}
