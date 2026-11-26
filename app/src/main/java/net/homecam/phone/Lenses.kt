package net.homecam.phone

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The back lenses this phone lets other apps use: the main one, and a wide angle or zoom lens
 * when there is one. Phones offer extra lenses either as separate cameras, or through the main
 * camera's zoom (below 1x is the wide angle lens, as in the phone's own camera app). Only what
 * the phone actually offers is listed.
 */
object Lenses {
    /** [key] is what the settings store; [camera] the camera id to open; [zoom] the zoom to ask for. */
    data class Lens(val key: String, val label: String, val camera: String, val zoom: Float, val times: Float, val unlisted: Boolean = false)

    /** Lenses this phone refused to open (this run of the app): not offered again. */
    private val refused = HashSet<String>()

    @Synchronized
    fun refuse(key: String) { refused.add(key); cached = cached?.filter { it.key != key } }

    /** Back cameras the phone doesn't list but answers for (some phones keep the wide angle there). */
    fun unlistedBacks(cm: CameraManager): List<String> {
        val listed = cm.cameraIdList.toSet()
        return (0..99).map { it.toString() }.filter { it !in listed }.filter {
            try { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } catch (_: Exception) { false }
        }
    }

    private var cached: List<Lens>? = null

    @Synchronized
    fun back(ctx: Context): List<Lens> {
        cached?.let { return it }
        val found = try { find(ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager) } catch (_: Exception) { emptyList() }.filter { it.key !in refused }
        cached = found
        return found
    }

    /** How much of the scene a camera sees, across: bigger is wider. */
    private fun view(c: CameraCharacteristics): Float? {
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
        if (f <= 0f) return null
        return maxOf(size.width, size.height) / f
    }

    private fun label(times: Float): String {
        val t = if (times >= 0.95f && times <= 1.05f) "1" else if (times < 1f) String.format(java.util.Locale.US, "%.1f", times).trimEnd('0').trimEnd('.') else times.roundToInt().toString()
        return when {
            times < 0.85f -> "Back: wide angle (${t}x)"
            times > 1.4f -> "Back: zoom (${t}x)"
            else -> "Back: main"
        }
    }

    private fun find(cm: CameraManager): List<Lens> {
        val backs = cm.cameraIdList.filter {
            try { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK } catch (_: Exception) { false }
        }
        if (backs.isEmpty()) return emptyList()
        val main = backs.first()
        val mc = cm.getCameraCharacteristics(main)
        val mainView = view(mc)
        val out = ArrayList<Lens>()
        out.add(Lens("main", "Back: main", main, 1f, 1f))
        fun have(times: Float) = out.any { abs(it.times - times) / maxOf(it.times, times) < 0.08f }
        // Separate back cameras: each one that sees noticeably more or less than the main one.
        if (mainView != null) for (id in backs.drop(1)) {
            val v = try { view(cm.getCameraCharacteristics(id)) } catch (_: Exception) { null } ?: continue
            val times = mainView / v
            if (abs(times - 1f) < 0.15f || have(times)) continue
            out.add(Lens("cam:$id", label(times), id, 1f, times))
        }
        // Back cameras the phone doesn't list (it may still open them).
        if (mainView != null) for (id in try { unlistedBacks(cm) } catch (_: Exception) { emptyList() }) {
            val v = try { view(cm.getCameraCharacteristics(id)) } catch (_: Exception) { null } ?: continue
            val times = mainView / v
            if (abs(times - 1f) < 0.15f || have(times)) continue
            out.add(Lens("cam:$id", label(times), id, 1f, times, unlisted = true))
        }
        // The main camera's own zoom: below 1x switches to its wide angle lens.
        if (Build.VERSION.SDK_INT >= 30) {
            val range = mc.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (range != null && range.lower < 0.9f && !out.any { it.times < 0.85f }) {
                val times = range.lower
                out.add(Lens(String.format(java.util.Locale.US, "zoom:%.2f", times), label(times), main, times, times))
            }
            // A zoom lens inside the main camera (its physical lenses), reached by zooming in.
            if (Build.VERSION.SDK_INT >= 28 && mainView != null && range != null && !out.any { it.times > 1.4f }) {
                val tele = mc.physicalCameraIds.mapNotNull { p ->
                    try { view(cm.getCameraCharacteristics(p))?.let { mainView / it } } catch (_: Exception) { null }
                }.filter { it > 1.4f && it <= range.upper }.minOrNull()
                if (tele != null) out.add(Lens(String.format(java.util.Locale.US, "zoom:%.2f", tele), label(tele), main, tele, tele))
            }
        }
        return out.sortedBy { it.times }
    }

    private var info: String? = null

    /**
     * What the phone says about its cameras, for working out how to reach more lenses: each
     * listed camera (facing, focal lengths, sensor size, zoom range, lenses inside it), and any
     * unlisted camera numbers 0-9 that answer. Only asks about them; opens nothing.
     */
    @Synchronized
    fun describe(ctx: Context): String {
        info?.let { return it }
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        fun one(id: String): String? = try {
            val c = cm.getCameraCharacteristics(id)
            val facing = when (c.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_BACK -> "back"; CameraCharacteristics.LENS_FACING_FRONT -> "front"; else -> "other" }
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { String.format(java.util.Locale.US, "%.2f", it) } ?: "?"
            val sz = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { String.format(java.util.Locale.US, "%.1fx%.1f", it.width, it.height) } ?: "?"
            val z = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.let { String.format(java.util.Locale.US, " zoom %.2f-%.1f", it.lower, it.upper) } ?: "" else ""
            val phys = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds.takeIf { it.isNotEmpty() }?.joinToString(",", " inside[", "]") { p ->
                p + (try { cm.getCameraCharacteristics(p).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.let { String.format(java.util.Locale.US, " f%.2f", it) } ?: "" } catch (_: Exception) { "" })
            } ?: "" else ""
            "$id $facing f$f $sz$z$phys"
        } catch (_: Exception) { null }
        val listed = cm.cameraIdList.toList()
        val parts = listed.mapNotNull { one(it) }
        val hidden = (0..99).map { it.toString() }.filter { it !in listed }.mapNotNull { one(it) }
        val out = "Android ${Build.VERSION.RELEASE}; " + parts.joinToString("; ") + (if (hidden.isNotEmpty()) "; unlisted: " + hidden.joinToString("; ") else "; no unlisted ones")
        info = out
        return out
    }

    /** The lens for a stored key; the main one if the phone doesn't have it (any more). */
    fun pick(ctx: Context, key: String): Lens? {
        val l = back(ctx)
        return l.firstOrNull { it.key == key } ?: l.firstOrNull { it.key == "main" } ?: l.firstOrNull()
    }
}
