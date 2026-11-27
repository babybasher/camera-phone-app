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
import java.nio.ByteBuffer

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

        // Bicycles and motorbikes have their own tick, apart from cars.
        val bike = f("motorcycle", 0.8f, 0.42f, 0.4f, 0.58f, 0.9f)
        assertNull("bikes not ticked", Detector.decide(listOf(bike), moved, setOf("person", "vehicle", "animal"), ArrayList(), 0).alert)
        assertEquals("motorcycle", Detector.decide(listOf(bike), moved, setOf("bike"), ArrayList(), 0).alert?.label)
        assertEquals("bike", Detector.GROUPS["bicycle"])

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

    @Test fun wildlifeCheck() {
        // Its names for the same animal add up; it must be sure enough.
        assertEquals(Pair("fox", 0.5f), Detector.wildPick(listOf(Pair("red fox", 0.3f), Pair("grey fox", 0.2f), Pair("tabby", 0.25f))))
        assertEquals("kangaroo", Detector.wildPick(listOf(Pair("wallaby", 0.62f)))?.first)
        assertEquals("snake", Detector.wildPick(listOf(Pair("king snake", 0.2f), Pair("garter snake", 0.15f), Pair("rock python", 0.1f)))?.first)
        assertNull("not sure enough", Detector.wildPick(listOf(Pair("hare", 0.3f), Pair("valley", 0.5f))))
        assertNull("fox squirrel isn't a fox", Detector.wildPick(listOf(Pair("fox squirrel", 0.9f))))
        assertEquals("animal", Detector.GROUPS["kangaroo"])
        // The patch that moved: the middle of the spots, a bit bigger; a stray flicker doesn't stretch it.
        val pts = ArrayList<Float>()
        for (i in 0 until 40) { pts.add(0.5f + (i % 5) * 0.01f); pts.add(0.6f + (i / 5) * 0.01f) }
        pts.add(0.02f); pts.add(0.02f)
        val box = Detector.movedBox(pts.toFloatArray())!!
        assertTrue("$box", box.left > 0.4f && box.right < 0.6f && box.top > 0.5f && box.bottom < 0.75f)
        assertNull("too few spots", Detector.movedBox(floatArrayOf(0.5f, 0.5f)))
        // The model loads and runs on Android; a plain grey patch is no animal.
        val d = Detector(InstrumentationRegistry.getInstrumentation().targetContext)
        val bmp = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        assertNull(d.wildlife(bmp, pts.toFloatArray()))
        assertEquals("the classifier loads from the app: '${d.wildError}'", "", d.wildError)
        d.close()
    }

    @Test fun aCatSittingThereIsOneEventAMinute() {
        val last = HashMap<String, Pair<RectF, Long>>()
        val cat = f("cat", 0.8f, 0.4f, 0.5f, 0.6f, 0.8f)
        assertTrue("first sighting", Detector.worthLogging(last, cat, 0))
        assertTrue("still there 10 s later: no", !Detector.worthLogging(last, cat, 10_000))
        assertTrue("called a dog in the same spot: same animal", !Detector.worthLogging(last, f("dog", 0.6f, 0.41f, 0.5f, 0.61f, 0.8f), 20_000))
        assertTrue("a minute on, still there: one more", Detector.worthLogging(last, cat, 61_000))
        assertTrue("moved across the picture: new", Detector.worthLogging(last, f("cat", 0.8f, 0.0f, 0.1f, 0.2f, 0.4f), 65_000))
        assertTrue("a person meanwhile: new", Detector.worthLogging(last, f("person", 0.8f, 0.4f, 0.5f, 0.6f, 0.8f), 66_000))
    }

    @Test fun picturesAreUpright() {
        // A sideways frame, top-left corner bright: turned 90 degrees it's at the top right.
        val y = ByteBuffer.allocateDirect(64 * 48)
        val uu = ByteBuffer.allocateDirect(32 * 24)
        val v0 = ByteBuffer.allocateDirect(32 * 24)
        for (r in 0 until 48) for (c in 0 until 64) y.put(r * 64 + c, (if (r < 8 && c < 8) 235 else 16).toByte())
        for (i in 0 until 32 * 24) { uu.put(i, 128.toByte()); v0.put(i, 128.toByte()) }
        val b = JavaI420Buffer.wrap(64, 48, y, 64, uu, 32, v0, 32, null)
        val p = Detector.picture(b, 90)
        b.release()
        assertEquals(48, p.width); assertEquals(64, p.height)
        assertTrue("bright corner at the top right", Color.red(p.getPixel(45, 2)) > 200 && Color.red(p.getPixel(2, 2)) < 40)
        // And the motion spots turn the same way.
        val (u, v) = Detector.upright(0.05f, 0.05f, 90)
        assertTrue("$u,$v", u > 0.9f && v < 0.1f)
    }
}
