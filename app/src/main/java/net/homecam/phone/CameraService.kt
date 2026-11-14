package net.homecam.phone

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Range
import android.view.Surface
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Runs the camera in the background (with a notification), so it keeps working
 * with the screen off. Speaks the same protocol as the web camera page:
 * a WebSocket to the control service for commands and status, WebRTC to the
 * video service for live view and talk, and uploads for clips and photos.
 */
class CameraService : Service() {
    companion object {
        const val TAG = "CameraService"
        @Volatile var instance: CameraService? = null
        /** The screen listens for changes here. */
        @Volatile var onChange: (() -> Unit)? = null

        fun start(ctx: Context) {
            Prefs(ctx).running = true
            ctx.startForegroundService(Intent(ctx, CameraService::class.java))
        }

        fun stop(ctx: Context) {
            Prefs(ctx).running = false
            ctx.stopService(Intent(ctx, CameraService::class.java))
        }
    }

    lateinit var cfg: Prefs
    private val worker = HandlerThread("camera").apply { start() }
    private val h = Handler(worker.looper)
    private val main = Handler(Looper.getMainLooper())

    // What the screen shows.
    @Volatile var line = "Starting..."
    @Volatile var linked = false
    @Volatile var problem = ""

    private var settings = JSONObject()
    private var name = ""
    private var wantStream = false
    private var motionOn = false
    private var photoWanted = false
    private var stopped = false

    // Photos at the camera's full size, the torch, and the motion area
    @Volatile private var photoBusy = false
    @Volatile private var fullFailed = false
    @Volatile private var lastRot = 0
    private var camDev = ""
    private var torchCmd = false
    private var torchOn = false
    private var zoneKey = ""
    private var zoneMask: BooleanArray? = null
    private var zoneCount = 64 * 48

    // WebRTC
    lateinit var egl: EglBase
    private lateinit var factory: PeerConnectionFactory
    private lateinit var adm: JavaAudioDeviceModule
    private var capturer: CameraVideoCapturer? = null
    private var texHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    var videoTrack: VideoTrack? = null
        private set
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var camKey = ""
    private var camStartedAt = 0L
    private var pubPc: PeerConnection? = null
    private var pubState = ""
    private var talkPc: PeerConnection? = null
    private var hasH264 = true

    // Recording
    @Volatile private var rec: Recorder? = null
    private var recUntil = 0L
    private var recStarted = 0L
    private var recFile: File? = null
    private var recStamp = ""
    @Volatile private var audioRate = 0
    @Volatile private var audioChannels = 1
    @Volatile private var lastAudioAt = 0L

    // Motion
    @Volatile private var lastMotionCheck = 0L
    private var prevSmall: ByteArray? = null
    private var hits = 0
    private var lastMotionSent = 0L

    // Link to the control service
    private var ws: WebSocket? = null
    private var wsGen = 0
    private var backoff = 1000L

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private lateinit var uploader: Uploader

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Prefs(this)
        name = cfg.name
        instance = this
        startInForeground()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "homecam:camera").apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "homecam:wifi").apply { setReferenceCounted(false); acquire() }
        uploader = Uploader(this, cfg) { changed() }
        watchNetwork()
        h.post {
            setupWebrtc()
            connect()
        }
        h.postDelayed(object : Runnable {
            override fun run() {
                tick()
                h.postDelayed(this, 500)
            }
        }, 500)
        h.postDelayed(object : Runnable {
            override fun run() {
                report()
                h.postDelayed(this, 5000)
            }
        }, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stopped = true
        netCallback?.let { try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {} }
        instance = null
        val done = CountDownLatch(1)
        h.post {
            try {
                ws?.close(1000, "bye")
                finishRecording()
                closeTalk()
                stopCamera()
            } catch (e: Exception) {
                Log.w(TAG, "stopping: $e")
            }
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        uploader.stop()
        wake?.release()
        wifi?.release()
        h.postDelayed({ worker.quitSafely() }, 3000)
        changed()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- network changes

    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * When Wi-Fi comes back (or the phone switches network), drop old
     * connections and get going straight away: reconnect and upload what's waiting.
     */
    private fun watchNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) = fresh()
            override fun onLost(n: Network) { Net.http.connectionPool.evictAll() }
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {}
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (e: Exception) { Log.w(TAG, "network watch: $e") }
    }

    private fun fresh() {
        Net.http.connectionPool.evictAll()
        uploader.kick()
        h.postDelayed({
            if (!linked && !stopped) { backoff = 1000; connect() }
            if (pubState == "FAILED" || pubState == "DISCONNECTED") { closePublish(); if (needCamera()) ensurePublish() }
        }, 1500)
    }

    // ---------------------------------------------------------------- notification

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("camera", "Camera running", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "camera")
            .setSmallIcon(R.drawable.ic_cam)
            .setContentTitle("${cfg.name.ifEmpty { "Phone" }} camera is on")
            .setContentText("Tap to see it or turn it off")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun changed() {
        main.post { onChange?.invoke() }
    }

    private fun say(s: String) {
        line = s
        changed()
    }

    // ---------------------------------------------------------------- WebRTC setup

    private fun setupWebrtc() {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions())
        egl = EglBase.create()
        adm = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { s ->
                audioRate = s.sampleRate
                audioChannels = s.channelCount
                lastAudioAt = System.currentTimeMillis()
                rec?.addAudio(s.data)
            }
            .createAudioDeviceModule()
        val enc = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        hasH264 = enc.supportedCodecs.any { it.name.equals("H264", true) }
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(enc)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        if (!hasH264) problem = "This phone has no H.264 video encoder that the live view can use."
    }

    // ---------------------------------------------------------------- the camera

    private fun needCamera() = wantStream || motionOn || rec != null || photoWanted || thumbWanted
    @Volatile private var thumbWanted = true
    private var lastThumb = 0L

    private fun res() = settings.optInt("res", 720)
    private fun fps() = settings.optInt("fps", 15)
    private fun mic() = settings.optBoolean("mic", true)
    private fun facing() = settings.optString("facing", "environment")

    private fun key() = "${res()}/${fps()}/${mic()}/${facing()}"

    /** Turn the camera on or off (and the stream to the video service) to match what is needed. */
    private fun update() {
        if (stopped) return
        if (needCamera()) {
            if (videoTrack != null && camKey != key()) {
                if (rec != null) finishRecording()
                stopCamera()
            }
            if (videoTrack == null) startCamera()
            if (videoTrack != null) ensurePublish()
        } else {
            stopCamera()
        }
        report()
    }

    private fun startCamera() {
        try {
            val en = Camera2Enumerator(this)
            val front = facing() == "user"
            val dev = en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull()
                ?: throw IOException("no camera found")
            val cap = en.createCapturer(dev, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(e: String?) { problem = "Camera error: $e"; changed() }
                override fun onCameraDisconnected() { problem = "The camera was taken by another app."; changed() }
                override fun onCameraFreezed(e: String?) { Log.w(TAG, "camera frozen: $e") }
                override fun onCameraOpening(n: String?) {}
                override fun onFirstFrameAvailable() { problem = ""; changed(); h.postDelayed({ if (wantTorch()) applyTorch() }, 300) }
                override fun onCameraClosed() {}
            }) ?: throw IOException("could not open the camera")
            val helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
            val src = factory.createVideoSource(false)
            cap.initialize(helper, applicationContext, TurnObserver(src.capturerObserver) { settings.optInt("rotate", 0) })
            val r = res()
            cap.startCapture(r * 16 / 9 / 2 * 2, r, fps())
            val track = factory.createVideoTrack("video", src)
            track.addSink(sink)
            capturer = cap
            camDev = dev
            torchOn = false
            texHelper = helper
            videoSource = src
            videoTrack = track
            if (mic()) {
                audioSource = factory.createAudioSource(MediaConstraints())
                audioTrack = factory.createAudioTrack("audio", audioSource)
            }
            camKey = key()
            camStartedAt = System.currentTimeMillis()
            prevSmall = null
            hits = 0
            changed()
        } catch (e: Exception) {
            problem = "Camera could not start: ${e.message}"
            Log.w(TAG, "camera: $e")
            changed()
        }
    }

    private fun stopCamera() {
        closePublish()
        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose(); capturer = null
        torchOn = false
        videoTrack?.removeSink(sink)
        videoTrack?.dispose(); videoTrack = null
        videoSource?.dispose(); videoSource = null
        texHelper?.dispose(); texHelper = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        camKey = ""
        changed()
    }

    /** The screen's preview. */
    fun addPreview(s: VideoSink) = h.post { videoTrack?.addSink(s) }
    fun removePreview(s: VideoSink) = h.post { videoTrack?.removeSink(s) }

    // Every camera frame passes through here (camera thread).
    private val sink = VideoSink { frame ->
        rec?.addFrame(frame)
        val now = System.currentTimeMillis()
        lastRot = frame.rotation
        if (photoWanted && !photoBusy && now - camStartedAt > 1500 && wantFullPhoto() && !fullFailed) {
            // Bigger than the video: pause the video for a moment and take it straight from the camera.
            photoBusy = true
            h.post { fullPhoto() }
        } else if (photoWanted && !photoBusy && now - camStartedAt > 1500) {
            photoWanted = false
            fullFailed = false
            val i420 = frame.buffer.toI420()
            val rot = frame.rotation
            if (i420 != null) {
                Thread {
                    try { savePhoto(i420, rot) } finally { i420.release() }
                    h.post { update() }
                }.start()
            }
        }
        if (thumbWanted && now - camStartedAt > 1500) {
            thumbWanted = false
            lastThumb = now
            val b = frame.buffer
            val tw = 480
            val th = (b.height * tw / maxOf(1, b.width)) / 2 * 2
            val small = b.cropAndScale(0, 0, b.width, b.height, tw, th)
            val i = small.toI420()
            small.release()
            val rot = frame.rotation
            if (i != null) Thread { try { sendThumb(i, rot) } finally { i.release() }; h.post { update() } }.start()
        }
        if (motionOn && now - lastMotionCheck >= 250) {
            lastMotionCheck = now
            val b = frame.buffer
            val small = b.cropAndScale(0, 0, b.width, b.height, 64, 48)
            val i = small.toI420()
            small.release()
            if (i != null) {
                val g = ByteArray(64 * 48)
                val y = i.dataY
                for (row in 0 until 48) {
                    y.position(row * i.strideY)
                    y.get(g, row * 64, 64)
                }
                i.release()
                checkMotion(g, frame.rotation)
            }
        }
    }

    // ---------------------------------------------------------------- motion

    /** Same rule as the web camera page: count pixels that changed, ignore whole-picture jumps (light). */
    private fun checkMotion(g: ByteArray, rot: Int) {
        val prev = prevSmall
        prevSmall = g
        if (prev == null) return
        val m = mask(rot)
        var n = 0
        var all = 0
        for (k in g.indices) if (abs((g[k].toInt() and 255) - (prev[k].toInt() and 255)) > 22) {
            all++
            if (m == null || m[k]) n++
        }
        val ratio = n.toDouble() / (if (m == null) g.size else maxOf(1, zoneCount))
        val need = 0.002 + (100 - settings.optInt("sens", 50)) / 100.0 * 0.06
        // A big jump across the whole picture is the camera adjusting to light, not motion.
        if (ratio > need && all.toDouble() / g.size < 0.6) hits++ else hits = 0
        if (hits >= 2) h.post { onMotion() }
    }

    /**
     * The motion area: "zones" is 6 rows of 8 cells over the upright picture, '1' = watch.
     * Returns which of the 64x48 comparison pixels count (null = all of them).
     */
    private fun mask(rot: Int): BooleanArray? {
        val z = settings.optString("zones", "")
        if (z.length != 48 || !z.contains('0') || !z.contains('1')) return null
        val key = "$z/$rot"
        if (key == zoneKey) return zoneMask
        val m = BooleanArray(64 * 48)
        var n = 0
        for (sy in 0 until 48) for (sx in 0 until 64) {
            val fx = (sx + 0.5f) / 64
            val fy = (sy + 0.5f) / 48
            // The frame is as the sensor sees it; turn the point to where it is in the upright picture.
            val u: Float
            val v: Float
            when (((rot % 360) + 360) % 360) {
                90 -> { u = 1 - fy; v = fx }
                180 -> { u = 1 - fx; v = 1 - fy }
                270 -> { u = fy; v = 1 - fx }
                else -> { u = fx; v = fy }
            }
            val on = z[minOf(5, (v * 6).toInt()) * 8 + minOf(7, (u * 8).toInt())] == '1'
            m[sy * 64 + sx] = on
            if (on) n++
        }
        zoneKey = key
        zoneMask = m
        zoneCount = n
        return m
    }

    private fun onMotion() {
        if (!motionOn) return
        val after = settings.optInt("after", 20)
        startRecording(after)
        val now = System.currentTimeMillis()
        if (now - lastMotionSent > 30_000) {
            lastMotionSent = now
            send(JSONObject().put("type", "motion"))
        }
    }

    // ---------------------------------------------------------------- recording and photos

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    private var recManual = false

    private fun startRecording(seconds: Int, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (rec != null) {
            recUntil = maxOf(recUntil, now + seconds * 1000L)
            if (manual) recManual = true
            return
        }
        recManual = manual
        recUntil = now + seconds * 1000L
        update()
        if (videoTrack == null) return
        val withAudio = mic() && now - lastAudioAt < 3000 && audioRate > 0
        val f = File(uploader.dir, "clip-$now.mp4")
        try {
            rec = Recorder(f, fps(), bitrate(), if (withAudio) audioRate else 0, audioChannels)
            recStarted = now
            recFile = f
            recStamp = stamp(now)
        } catch (e: Exception) {
            problem = "Could not record: ${e.message}"
            Log.w(TAG, "record: $e")
        }
        report()
    }

    private fun bitrate() = when (res()) { 360 -> 700_000; 480 -> 1_200_000; 1080 -> 4_500_000; else -> 2_500_000 }

    private fun finishRecording() {
        val r = rec ?: return
        rec = null
        val f = recFile
        val t = recStamp
        r.finish { secs ->
            if (secs > 0 && f != null) uploader.add(f, "clip", t, secs, "mp4")
            h.post { update() }
        }
        report()
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // Thumbnail for the camera list.
        val gap = if (videoTrack != null) 60_000L else 600_000L
        if (!thumbWanted && cfg.ready && now - lastThumb > gap) { thumbWanted = true; update() }
        val r = rec ?: return
        if (now > recUntil || videoTrack == null) { finishRecording(); return }
        if (recManual) {
            // Long recordings are saved in 10-minute parts, so each upload stays a sensible size.
            if (now - recStarted > 600_000L) {
                val left = ((recUntil - now) / 1000).toInt()
                finishRecording()
                if (left > 2) startRecording(left, true)
            }
        } else if (now - recStarted > settings.optInt("max_clip", 120) * 1000L) {
            finishRecording()
        }
        if (r !== rec) report()
    }

    /** True when the photo setting asks for more than the video's size. */
    private fun wantFullPhoto(): Boolean {
        val want = when (settings.optString("photo", "max")) { "1080" -> 1920; "720" -> 1280; else -> 0 }
        return want == 0 || want > res() * 16 / 9
    }

    /** Pause the video, take one photo at the camera's own size, and carry on. (Service thread.) */
    private fun fullPhoto() {
        val cap = capturer
        if (cap == null || camDev.isEmpty()) { photoBusy = false; return }
        val dev = camDev
        val rot = lastRot
        val torch = wantTorch()
        val maxW = when (settings.optString("photo", "max")) { "1080" -> 1920; "720" -> 1280; else -> 0 }
        try { cap.stopCapture() } catch (e: Exception) { Log.w(TAG, "pause for photo: $e") }
        torchOn = false
        Thread {
            val bytes = try { Still.capture(applicationContext, dev, maxW, rot, torch) } catch (e: Exception) { Log.w(TAG, "photo: $e"); null }
            h.post {
                if (capturer === cap) {
                    try {
                        val r = res()
                        cap.startCapture(r * 16 / 9 / 2 * 2, r, fps())
                        camStartedAt = System.currentTimeMillis()
                    } catch (e: Exception) {
                        problem = "Camera could not restart after the photo: ${e.message}"
                        Log.w(TAG, "resume after photo: $e")
                    }
                }
                if (bytes != null) {
                    val now = System.currentTimeMillis()
                    val f = File(uploader.dir, "photo-$now.jpg")
                    try {
                        f.writeBytes(bytes)
                        uploader.add(f, "photo", stamp(now), 0, "jpg")
                    } catch (e: Exception) {
                        Log.w(TAG, "save photo: $e")
                    }
                    photoWanted = false
                } else {
                    // Couldn't: the next video frame is used instead.
                    fullFailed = true
                }
                photoBusy = false
                update()
            }
        }.start()
    }

    // ---------------------------------------------------------------- torch

    private fun wantTorch() = settings.optBoolean("torch", false) || torchCmd

    private fun field(c: Class<*>, o: Any, name: String): Any? {
        var k: Class<*>? = c
        while (k != null) {
            try {
                val f = k.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (_: NoSuchFieldException) {
                k = k.superclass
            }
        }
        return null
    }

    /**
     * Switch the phone's light on or off while the video capture keeps running.
     * The video library has no torch control, so this reaches into its camera
     * session and re-sends the capture request with the light set. (Service thread.)
     */
    private fun applyTorch() {
        val cap = capturer ?: return
        val on = wantTorch()
        try {
            val session = field(cap.javaClass, cap, "currentSession") ?: return
            val sc = session.javaClass
            val cs = field(sc, session, "captureSession") as? CameraCaptureSession ?: return
            val dev = field(sc, session, "cameraDevice") as? CameraDevice ?: return
            val surface = field(sc, session, "surface") as? Surface ?: return
            val handler = field(sc, session, "cameraThreadHandler") as? Handler
            val fmt = field(sc, session, "captureFormat") as? CameraEnumerationAndroid.CaptureFormat
            val unit = maxOf(1, (field(sc, session, "fpsUnitFactor") as? Int) ?: 1)
            val b = dev.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.set(CaptureRequest.FLASH_MODE, if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            if (fmt != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fmt.framerate.min / unit, fmt.framerate.max / unit))
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.CONTROL_AE_LOCK, false)
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            b.addTarget(surface)
            cs.setRepeatingRequest(b.build(), null, handler)
            torchOn = on
        } catch (e: Exception) {
            Log.w(TAG, "torch: $e")
            torchOn = false
        }
        report()
    }

    private fun savePhoto(b: VideoFrame.I420Buffer, rotation: Int) {
        val bytes = jpeg(b, rotation, 92)
        val now = System.currentTimeMillis()
        val f = File(uploader.dir, "photo-$now.jpg")
        f.writeBytes(bytes)
        uploader.add(f, "photo", stamp(now), 0, "jpg")
    }

    private fun jpeg(b: VideoFrame.I420Buffer, rotation: Int, quality: Int): ByteArray {
        val w = b.width
        val hgt = b.height
        val nv21 = ByteArray(w * hgt * 3 / 2)
        val y = b.dataY
        for (row in 0 until hgt) {
            y.position(row * b.strideY)
            y.get(nv21, row * w, w)
        }
        val u = b.dataU
        val v = b.dataV
        var o = w * hgt
        for (row in 0 until hgt / 2) for (col in 0 until w / 2) {
            nv21[o++] = v.get(row * b.strideV + col)
            nv21[o++] = u.get(row * b.strideU + col)
        }
        val out = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, hgt, null).compressToJpeg(Rect(0, 0, w, hgt), quality, out)
        var bytes = out.toByteArray()
        if (rotation % 360 != 0) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            val o2 = ByteArrayOutputStream()
            rot.compress(Bitmap.CompressFormat.JPEG, quality, o2)
            bytes = o2.toByteArray()
            bmp.recycle(); rot.recycle()
        }
        return bytes
    }

    /** A small picture for the camera list: when the camera starts, every minute while on, and every 10 minutes otherwise. */
    private fun sendThumb(b: VideoFrame.I420Buffer, rotation: Int) {
        val bytes = jpeg(b, rotation, 80)
        val req = Request.Builder().url(cfg.api + "/api/phone/" + cfg.id + "/thumb")
            .header("Authorization", "Bearer ${cfg.token}")
            .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        try { Net.http.newCall(req).execute().close() } catch (e: Exception) { Log.w(TAG, "thumbnail: $e") }
    }

    // ---------------------------------------------------------------- live view and talk

    private fun videoUrl(q: String): String = cfg.server.trimEnd('/') + "/api/webrtc?" + q

    private fun ensurePublish() {
        val pc = pubPc
        if (pc != null && pubState != "FAILED" && pubState != "CLOSED") return
        closePublish()
        val vt = videoTrack ?: return
        val conf = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        lateinit var newPc: PeerConnection
        newPc = factory.createPeerConnection(conf, Obs { st ->
            h.post {
                if (pubPc !== newPc) return@post
                pubState = st.name
                report()
                if (st == PeerConnection.PeerConnectionState.FAILED || st == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    h.postDelayed({
                        if (pubPc === newPc && (pubState == "FAILED" || pubState == "DISCONNECTED")) {
                            closePublish()
                            if (needCamera()) ensurePublish()
                        }
                    }, 3000)
                }
            }
        }) ?: return
        pubPc = newPc
        pubState = "NEW"
        newPc.addTransceiver(vt, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cam")))
        audioTrack?.let { newPc.addTransceiver(it, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cam"))) }
        Thread {
            try {
                negotiate(newPc, videoUrl("dst=" + java.net.URLEncoder.encode(cfg.id, "UTF-8")))
            } catch (e: Exception) {
                Log.w(TAG, "publish: $e")
                h.post {
                    if (pubPc === newPc) {
                        problem = "Live view could not start: ${e.message}"
                        pubState = "FAILED"
                        h.postDelayed({ if (pubPc === newPc && needCamera()) { closePublish(); ensurePublish() } }, 5000)
                        changed()
                    }
                }
            }
        }.start()
    }

    private fun closePublish() {
        pubPc?.let { try { it.dispose() } catch (_: Exception) {} }
        pubPc = null
        pubState = ""
    }

    private fun setTalk(on: Boolean) {
        closeTalk()
        if (!on) { report(); return }
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = true
        val conf = PeerConnection.RTCConfiguration(emptyList()).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val pc = factory.createPeerConnection(conf, Obs { h.post { report() } }) ?: return
        talkPc = pc
        pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY))
        Thread {
            try {
                negotiate(pc, videoUrl("src=" + java.net.URLEncoder.encode(cfg.id + "_talk", "UTF-8")))
            } catch (e: Exception) {
                Log.w(TAG, "talk: $e")
            }
        }.start()
        report()
    }

    private fun closeTalk() {
        talkPc?.let { try { it.dispose() } catch (_: Exception) {} }
        if (talkPc != null) {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        }
        talkPc = null
    }

    /** Offer, wait for network candidates, post to the video service, apply its answer. */
    private fun negotiate(pc: PeerConnection, url: String) {
        val offer = sdp { pc.createOffer(it, MediaConstraints()) }!!
        val munged = SessionDescription(SessionDescription.Type.OFFER, preferH264(offer.description))
        sdp { pc.setLocalDescription(it, munged) }
        val until = System.currentTimeMillis() + 3000
        while (pc.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE && System.currentTimeMillis() < until) Thread.sleep(100)
        val body = pc.localDescription.description
        val req = Request.Builder().url(url)
            .header("Authorization", Net.basic(cfg))
            .post(body.toRequestBody("text/plain".toMediaType()))
            .build()
        val answer = Net.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("video service answered ${r.code}")
            r.body!!.string()
        }
        sdp { pc.setRemoteDescription(it, SessionDescription(SessionDescription.Type.ANSWER, answer)) }
    }

    private fun sdp(op: (SdpObserver) -> Unit): SessionDescription? {
        val latch = CountDownLatch(1)
        var result: SessionDescription? = null
        var err: String? = null
        op(object : SdpObserver {
            override fun onCreateSuccess(s: SessionDescription?) { result = s; latch.countDown() }
            override fun onSetSuccess() { latch.countDown() }
            override fun onCreateFailure(e: String?) { err = e ?: "failed"; latch.countDown() }
            override fun onSetFailure(e: String?) { err = e ?: "failed"; latch.countDown() }
        })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IOException("timed out")
        err?.let { throw IOException(it) }
        return result
    }

    /**
     * The video service needs H.264: list it first in the offer. Also leave out
     * the "video orientation" tag: the video service ignores it, which made the
     * picture sideways. Without it the phone turns the frames itself.
     */
    private fun preferH264(sdp: String): String {
        val lines = sdp.split("\r\n").filterNot { it.contains("urn:3gpp:video-orientation") }.toMutableList()
        val mi = lines.indexOfFirst { it.startsWith("m=video") }
        if (mi < 0) return sdp
        val h264 = HashSet<String>()
        val rtx = HashMap<String, String>()
        for (l in lines) {
            Regex("^a=rtpmap:(\\d+) H264/").find(l)?.let { h264.add(it.groupValues[1]) }
            Regex("^a=fmtp:(\\d+) apt=(\\d+)").find(l)?.let { rtx[it.groupValues[1]] = it.groupValues[2] }
        }
        if (h264.isEmpty()) return sdp
        val parts = lines[mi].split(" ")
        val head = parts.take(3)
        val pts = parts.drop(3)
        val first = pts.filter { it in h264 || rtx[it] in h264 }
        lines[mi] = (head + first + pts.filter { it !in first }).joinToString(" ")
        return lines.joinToString("\r\n")
    }

    private class Obs(val onState: (PeerConnection.PeerConnectionState) -> Unit) : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) { if (s != null) onState(s) }
    }

    // ---------------------------------------------------------------- link to the control service

    private fun connect() {
        if (stopped || !cfg.ready) return
        val gen = ++wsGen
        val url = cfg.api.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/api/phone/" + java.net.URLEncoder.encode(cfg.id, "UTF-8") + "/ws"
        say("Connecting...")
        ws = Net.ws.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(w: WebSocket, r: Response) {
                w.send(JSONObject().put("token", cfg.token).toString())
                h.post { if (gen == wsGen) { backoff = 1000; linked = true; say("Connected"); report() } }
            }
            override fun onMessage(w: WebSocket, text: String) {
                h.post { if (gen == wsGen) handle(text) }
            }
            override fun onClosed(w: WebSocket, code: Int, reason: String) = lost(gen)
            override fun onFailure(w: WebSocket, t: Throwable, r: Response?) = lost(gen)
        })
    }

    private fun lost(gen: Int) {
        h.post {
            if (gen != wsGen || stopped) return@post
            linked = false
            say("Not connected to the router; trying again...")
            h.postDelayed({ if (gen == wsGen) connect() }, backoff)
            backoff = minOf(backoff * 2, 15_000)
        }
    }

    private fun send(j: JSONObject) {
        try { ws?.send(j.toString()) } catch (_: Exception) {}
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        try {
            when (m.optString("cmd")) {
                "hello" -> {
                    m.optString("name").takeIf { it.isNotEmpty() }?.let { name = it; cfg.name = it }
                    applySettings(m.optJSONObject("settings") ?: JSONObject())
                }
                "settings" -> applySettings(m.optJSONObject("settings") ?: JSONObject())
                "name" -> { name = m.optString("name"); cfg.name = name; changed() }
                "stream" -> { wantStream = m.optBoolean("on"); update() }
                "record" -> if (m.optBoolean("stop")) finishRecording() else startRecording(m.optInt("seconds", 30).coerceIn(5, 3600), true)
                "photo" -> { photoWanted = true; update() }
                "talk" -> setTalk(m.optBoolean("on"))
                "torch" -> { torchCmd = m.optBoolean("on"); if (videoTrack != null) applyTorch() }
                "removed" -> {
                    stopped = true
                    cfg.unlink()
                    problem = "This camera was removed from the camera list."
                    main.post { stop(this) }
                }
                "replaced" -> {
                    stopped = true
                    problem = "Camera mode was started on another device or page for this camera, so it stopped here."
                    main.post { stop(this) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "command ${m.optString("cmd")}: $e")
            problem = "${m.optString("cmd")} failed: ${e.message}"
            changed()
        }
    }

    private fun applySettings(s: JSONObject) {
        for (k in s.keys()) settings.put(k, s.get(k))
        motionOn = settings.optBoolean("motion", false)
        if (!motionOn) { prevSmall = null; hits = 0 }
        update()
        if (videoTrack != null && wantTorch() != torchOn) applyTorch()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun report() {
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.let { 100 * it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) / maxOf(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)) }
        val charging = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        send(JSONObject()
            .put("type", "status")
            .put("battery", level ?: JSONObject.NULL)
            .put("charging", charging)
            .put("streaming", wantStream && pubState == "CONNECTED")
            .put("recording", rec != null)
            .put("motion_on", motionOn)
            .put("camera", if (videoTrack != null) "on" else "off")
            .put("res", res())
            .put("torch", torchOn)
            .put("extras", 1)
            .put("app", true)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("waiting", uploader.waiting()))
        val parts = ArrayList<String>()
        parts.add(if (linked) "Connected to the router" else "Not connected")
        if (wantStream && pubState == "CONNECTED") parts.add("someone is watching")
        if (rec != null) parts.add("recording")
        if (motionOn) parts.add("motion detection on")
        if (talkPc != null) parts.add("talk")
        if (torchOn) parts.add("torch on")
        if (videoTrack != null) parts.add("camera on")
        val w = uploader.waiting()
        if (w > 0) parts.add("$w waiting to upload")
        line = parts.joinToString(" · ")
        changed()
    }
}

/** Clips and photos wait in the app's storage until the router has them, then they are deleted. */
class Uploader(ctx: Context, private val cfg: Prefs, private val changed: () -> Unit) {
    val dir = File(ctx.filesDir, "outbox").apply { mkdirs() }
    private val thread = HandlerThread("upload").apply { start() }
    private val h = Handler(thread.looper)
    private var busy = false
    private var retry = 5_000L
    private val again = Runnable { flush() }

    init {
        h.post { flush() }
    }

    /** Try now (e.g. Wi-Fi just came back). */
    fun kick() {
        retry = 5_000L
        h.removeCallbacks(again)
        h.post(again)
    }

    fun waiting() = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0

    fun add(f: File, kind: String, t: String, secs: Int, ext: String) {
        File(dir, f.name + ".json").writeText(
            JSONObject().put("kind", kind).put("t", t).put("secs", secs).put("ext", ext).toString())
        changed()
        kick()
    }

    fun stop() = thread.quitSafely()

    private fun flush() {
        if (busy) return
        busy = true
        try {
            val items = dir.listFiles { f -> f.name.endsWith(".json") }?.sortedBy { it.name } ?: return
            for (meta in items) {
                val data = File(dir, meta.name.removeSuffix(".json"))
                if (!data.exists()) { meta.delete(); continue }
                val q = JSONObject(meta.readText())
                val url = (cfg.api + "/api/phone/" + cfg.id + "/upload").toHttpUrl().newBuilder()
                    .addQueryParameter("kind", q.optString("kind"))
                    .addQueryParameter("t", q.optString("t"))
                if (q.optString("kind") == "clip") {
                    url.addQueryParameter("secs", q.optInt("secs").toString())
                    url.addQueryParameter("ext", q.optString("ext"))
                }
                val req = Request.Builder().url(url.build())
                    .header("Authorization", "Bearer ${cfg.token}")
                    .post(data.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                try {
                    Net.http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    }
                    data.delete()
                    meta.delete()
                    changed()
                } catch (e: Exception) {
                    Log.w("Uploader", "upload ${data.name}: $e")
                    Net.http.connectionPool.evictAll()
                    // Try again soon, then less often: 5 s, 10 s, 20 s ... up to a minute.
                    h.removeCallbacks(again)
                    h.postDelayed(again, retry)
                    retry = minOf(retry * 2, 60_000L)
                    return
                }
            }
            retry = 5_000L
        } finally {
            busy = false
        }
    }
}

/** Adds the "Turn the picture" setting to every camera frame. */
class TurnObserver(private val inner: CapturerObserver, private val extra: () -> Int) : CapturerObserver {
    override fun onCapturerStarted(ok: Boolean) = inner.onCapturerStarted(ok)
    override fun onCapturerStopped() = inner.onCapturerStopped()
    override fun onFrameCaptured(f: VideoFrame) {
        val e = extra()
        if (e == 0) inner.onFrameCaptured(f)
        else inner.onFrameCaptured(VideoFrame(f.buffer, (f.rotation + e) % 360, f.timestampNs))
    }
}
