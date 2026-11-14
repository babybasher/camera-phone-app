package net.homecam.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Takes one full-size photo straight from the phone's camera. The video
 * capture has to be paused while this runs (a camera can only be opened once),
 * so the caller stops it first and starts it again afterwards.
 */
object Still {
    private const val TAG = "Still"

    /**
     * @param maxWidth longest side wanted in pixels, or 0 for the largest the camera can do
     * @param orientation degrees clockwise the picture needs turning to be upright
     * @return the JPEG, or null if it could not be taken
     */
    @SuppressLint("MissingPermission")
    fun capture(ctx: Context, cameraId: String, maxWidth: Int, orientation: Int, torch: Boolean): ByteArray? {
        val mgr = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ch = mgr.getCameraCharacteristics(cameraId)
        val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val all = map.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        if (all.isEmpty()) return null
        val fit = all.filter { maxWidth <= 0 || maxOf(it.width, it.height) <= maxWidth }
        val size = (fit.ifEmpty { all }).maxByOrNull { it.width.toLong() * it.height } ?: return null
        val small = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
            .filter { it.width in 320..1280 }.minByOrNull { it.width.toLong() * it.height }
        val flash = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

        val thread = HandlerThread("still").apply { start() }
        val hd = Handler(thread.looper)
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val tex = SurfaceTexture(false).apply { setDefaultBufferSize(small?.width ?: 640, small?.height ?: 480) }
        val preview = Surface(tex)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        try {
            // The video capture may still be letting go of the camera: try a few times.
            for (attempt in 1..5) {
                val opened = CountDownLatch(1)
                var got: CameraDevice? = null
                try {
                    mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(d: CameraDevice) { got = d; opened.countDown() }
                        override fun onDisconnected(d: CameraDevice) { d.close(); opened.countDown() }
                        override fun onError(d: CameraDevice, e: Int) { d.close(); opened.countDown() }
                    }, hd)
                    opened.await(3, TimeUnit.SECONDS)
                } catch (e: CameraAccessException) {
                    Log.w(TAG, "open (try $attempt): $e")
                }
                device = got
                if (device != null) break
                Thread.sleep(350)
            }
            val dev = device ?: return null

            val ready = CountDownLatch(1)
            @Suppress("DEPRECATION")
            dev.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { session = s; ready.countDown() }
                override fun onConfigureFailed(s: CameraCaptureSession) { ready.countDown() }
            }, hd)
            ready.await(3, TimeUnit.SECONDS)
            val ses = session ?: return null

            fun common(b: CaptureRequest.Builder) {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                if (flash) b.set(CaptureRequest.FLASH_MODE, if (torch) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            }
            // Let exposure and focus settle on a small preview first.
            val pre = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            pre.addTarget(preview)
            common(pre)
            ses.setRepeatingRequest(pre.build(), null, hd)
            Thread.sleep(1200)

            val done = CountDownLatch(1)
            var bytes: ByteArray? = null
            reader.setOnImageAvailableListener({ r ->
                try {
                    val img = r.acquireLatestImage()
                    if (img != null) {
                        try {
                            val buf = img.planes[0].buffer
                            val out = ByteArray(buf.remaining())
                            buf.get(out)
                            bytes = out
                        } finally {
                            img.close()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "read: $e")
                }
                done.countDown()
            }, hd)
            val shot = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            shot.addTarget(reader.surface)
            common(shot)
            shot.set(CaptureRequest.JPEG_ORIENTATION, ((orientation % 360) + 360) % 360)
            shot.set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            ses.capture(shot.build(), null, hd)
            done.await(5, TimeUnit.SECONDS)
            return bytes
        } catch (e: Exception) {
            Log.w(TAG, "capture: $e")
            return null
        } finally {
            try { session?.close() } catch (_: Exception) {}
            try { device?.close() } catch (_: Exception) {}
            try { reader.close() } catch (_: Exception) {}
            preview.release()
            tex.release()
            thread.quitSafely()
            // Give the camera a moment to close before the video capture takes it back.
            try { Thread.sleep(250) } catch (_: Exception) {}
        }
    }
}
