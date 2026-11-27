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
import org.json.JSONArray
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
        // What moved: looks after motion (half a second apart, so about 3 s: a door opening, then
        // who comes through), and the pause before the next round (short after "nothing", so
        // motion that carries on is looked at again almost straight away).
        const val LOOKS = 6
        const val LOOK_GAP = 500L
        const val QUIET_AFTER_FOUND = 2000L
        const val QUIET_AFTER_NOTHING = 500L

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
    // The turn the camera's own picture needs (live view frames arrive already turned).
    @Volatile private var rawRot = 0
    // Which part of the phone turns the picture and writes the stamp (shown on the settings page).
    private var turnObs: TurnObserver? = null
    @Volatile private var gpuProblem = ""
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

    // Long record: 10-minute blocks for hours (no sound), each kept in the outbox until it's on
    // the router's drive. Saved, so it carries on after the app or the phone restarts.
    private var longId = ""          // yyyyMMddHHmmss of the start: groups the blocks on the router
    private var longUntil = 0L
    private var longFps = 0
    private var longQuality = "normal"
    private var longSound = false    // record sound too (needs the camera's microphone on)
    private var longDropped = 0      // unlimited: oldest unsent blocks deleted to make room
    private var longError = ""
    private var recLong = false      // the recording running now is a long-record block

    // Motion
    @Volatile private var lastMotionCheck = 0L
    private var prevSmall: ByteArray? = null
    private var hits = 0
    private var lastMotionSent = 0L

    // What moved (Detector): after motion the phone looks at a few pictures and names what it sees.
    private val detector by lazy { Detector(this) }
    private val detectThread = HandlerThread("detect").apply { start() }
    private val dh = Handler(detectThread.looper)
    @Volatile private var detectWanted = false
    @Volatile private var detectBusy = false
    private var lastDetect = 0L
    private var episode = 0L            // when the motion being looked at started (0 = none)
    private var episodeChecks = 0
    private var episodeQuietUntil = 0L
    private val moved = ArrayList<Float>()   // where the picture changed since the last look (upright, 0..1), camera thread
    @Volatile private var movedReset = false
    private val still = ArrayList<Detector.Still>()   // detect thread
    private var seen: JSONObject? = null
    private val lastSentWhat = HashMap<String, Long>()

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
        loadLong()
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
        dh.post { detector.close() }
        detectThread.quitSafely()
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
        // The "camera is on" notice must stay while the camera runs, so it shouldn't put a "1" badge on the
        // app icon. Android keeps a channel's badge setting once made, so this is a new channel replacing "camera".
        runCatching { nm.deleteNotificationChannel("camera") }
        nm.createNotificationChannel(NotificationChannel("camera_on", "Camera running", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "camera_on")
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

    private fun needCamera() = wantStream || motionOn || rec != null || photoWanted || thumbWanted || longActive()
    @Volatile private var thumbWanted = true
    private var lastThumb = 0L

    private fun res() = settings.optInt("res", 720)
    private fun fps() = settings.optInt("fps", 15)
    private fun mic() = settings.optBoolean("mic", true)
    private fun facing() = settings.optString("facing", "environment")
    /** Which back lens (Lenses keys): "main" unless chosen in the camera settings. */
    private fun lens() = settings.optString("lens", "main")
    @Volatile private var lensError = ""

    private fun key() = "${res()}/${fps()}/${mic()}/${facing()}/${lens()}"

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
            // The back camera: the chosen lens (its own camera, or the main one's zoom).
            val lens = if (front) null else Lenses.pick(this, lens())
            // Unlisted cameras (a wide angle lens some phones keep unlisted) may be opened too.
            org.webrtc.StillCamera2Capturer.extraNames = Lenses.back(this).filter { it.unlisted }.map { it.camera }.toTypedArray()
            val dev = lens?.camera?.takeIf { it in en.deviceNames || lens.unlisted }
                ?: en.deviceNames.firstOrNull { en.isFrontFacing(it) == front } ?: en.deviceNames.firstOrNull()
                ?: throw IOException("no camera found")
            org.webrtc.StillCamera2Capturer.zoomRatio = lens?.zoom ?: 1f
            val lensKey = lens?.key ?: ""
            var gotFrame = false
            // Our copy of WebRTC's camera, without digital stabilization (it crops and softens).
            val cap = org.webrtc.StillCamera2Capturer(this, dev, object : CameraVideoCapturer.CameraEventsHandler {
                override fun onCameraError(e: String?) {
                    // A lens the phone won't open: back to the main one, and say so in its settings.
                    if (!gotFrame && lensKey.isNotEmpty() && lensKey != "main") {
                        Lenses.refuse(lensKey)
                        lensError = "This phone wouldn't open that lens, so the camera is on its main lens."
                        h.post { stopCamera(); update() }
                        return
                    }
                    problem = "Camera error: $e"; changed()
                }
                override fun onCameraDisconnected() { problem = "The camera was taken by another app."; changed() }
                override fun onCameraFreezed(e: String?) { Log.w(TAG, "camera frozen: $e") }
                override fun onCameraOpening(n: String?) {}
                override fun onFirstFrameAvailable() { gotFrame = true; if (lensKey == lens()) lensError = ""; problem = ""; changed(); h.postDelayed({ if (wantTorch()) applyTorch() }, 300) }
                override fun onCameraClosed() {}
            }) ?: throw IOException("could not open the camera")
            val helper = SurfaceTextureHelper.create("capture", egl.eglBaseContext)
            val src = factory.createVideoSource(false)
            // Recordings take the camera's frames here, at the full size asked for. (After the video
            // source, WebRTC may shrink them when the live stream struggles.)
            val obs = TurnObserver(src.capturerObserver, { settings.optInt("rotate", 0) }, { try { settings.optBoolean("stamp", false) } catch (_: Exception) { false } }, { stampRot }, { r -> rawRot = r }, { rec != null }, { try { settings.optString("turn_with", "gpu") != "cpu" } catch (_: Exception) { true } }) { f -> rec?.addFrame(f) }
            obs.onGpuProblem = { why -> Log.w(TAG, "graphics chip turning failed, using the main processor: $why"); gpuProblem = why; h.post { changed() } }
            gpuProblem = ""
            turnObs = obs
            cap.initialize(helper, applicationContext, obs)
            val r = res()
            cap.startCapture(r * 16 / 9 / 2 * 2, r, fps())
            val track = factory.createVideoTrack("video", src)
            track.addSink(sink)
            capturer = cap
            camDev = dev
            watchOrientation(dev, front)
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

    // Which way up the phone is actually held (tilt sensor), so the time stamp is drawn upright on
    // the right-way-up picture and follows the phone when it's turned. -1 until the first reading
    // (the stamp then goes by the frame's own rotation).
    @Volatile private var stampRot = -1
    private var orient: android.view.OrientationEventListener? = null
    private var held = -1

    private fun watchOrientation(dev: String, front: Boolean) {
        orient?.disable(); orient = null; held = -1; stampRot = -1
        val sensor = try {
            (getSystemService(CAMERA_SERVICE) as android.hardware.camera2.CameraManager).getCameraCharacteristics(dev)
                .get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        } catch (_: Exception) { 90 }
        val l = object : android.view.OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return  // lying flat: keep what it was
                val q = ((o + 45) / 90 * 90) % 360
                // A little stickiness so it doesn't flicker when held near 45 degrees.
                if (held >= 0 && q != held) { val d = Math.abs(((o - held + 540) % 360) - 180); if (d < 60) return }
                held = q
                stampRot = if (front) (sensor - q + 360) % 360 else (sensor + q) % 360
            }
        }
        if (l.canDetectOrientation()) { l.enable(); orient = l }
    }

    private fun stopCamera() {
        orient?.disable(); orient = null
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
            // Big enough to stay sharp on the camera list (shown up to about 800 wide).
            val tw = minOf(960, b.width) / 2 * 2
            val th = (b.height * tw / maxOf(1, b.width)) / 2 * 2
            val small = b.cropAndScale(0, 0, b.width, b.height, tw, th)
            val i = small.toI420()
            small.release()
            val rot = frame.rotation
            if (i != null) Thread { try { sendThumb(i, rot) } finally { i.release() }; h.post { update() } }.start()
        }
        if (detectWanted && !detectBusy && now - lastDetect >= LOOK_GAP && now - camStartedAt > 1500) {
            detectWanted = false
            detectBusy = true
            lastDetect = now
            val b = frame.buffer
            val tw = minOf(640, b.width) / 2 * 2
            val th = (b.height * tw / maxOf(1, b.width)) / 2 * 2
            val small = b.cropAndScale(0, 0, b.width, b.height, tw, th)
            val i = small.toI420()
            small.release()
            val rot = frame.rotation
            val pts = moved.toFloatArray()
            val want = settings.optString("detect_what", "person,vehicle,bike,animal").split(',').toSet()
            if (i == null) detectBusy = false
            else dh.post {
                var v: Detector.Verdict? = null
                try {
                    val bmp = Detector.picture(i, rot)
                    val found = detector.look(bmp)
                    var d = Detector.decide(found, pts, want, still, System.currentTimeMillis()).copy(at = now)
                    // Nothing it knows where it moved: ask the wildlife check about that patch.
                    if (d.alert == null && "animal" in want) {
                        val w = detector.wildlife(bmp, pts)
                        if (w != null) d = d.copy(alert = w, notes = listOf("${Detector.NAMES[w.label]} ${(w.score * 100).toInt()}% (wildlife check)") + d.notes)
                    }
                    bmp.recycle()
                    v = d
                } catch (e: Throwable) {
                    detector.error = (e.message ?: e.toString()).take(200)
                } finally {
                    i.release()
                    detectBusy = false
                }
                val verdict = v
                h.post { onLooked(verdict) }
            }
        }
        val watching = motionOn || alertsOn()
        if (!watching && prevSmall != null) { prevSmall = null; hits = 0 }
        if (watching && now - lastMotionCheck >= 250) {
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
        val counts = ratio > need && all.toDouble() / g.size < 0.6
        if (counts) hits++ else hits = 0
        if (movedReset) { moved.clear(); movedReset = false }
        if (counts && detectOn() && moved.size < 8000) {
            // Where it changed, for the detector: only things where the picture moved count.
            for (k in g.indices) if ((m == null || m[k]) && abs((g[k].toInt() and 255) - (prev[k].toInt() and 255)) > 22) {
                val p = Detector.upright((k % 64 + 0.5f) / 64, (k / 64 + 0.5f) / 48, rot)
                moved.add(p.first); moved.add(p.second)
            }
        }
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

    /** "Motion alerts while recording": during Record or Long record, motion sends an alert but starts no clips of its own. */
    private fun alertsOn() = settings.optBoolean("rec_alerts", false) && (rec != null || longActive())

    private fun onMotion() {
        if (!motionOn && !alertsOn()) return
        if (motionOn) startRecording(settings.optInt("after", 20))
        val now = System.currentTimeMillis()
        // Not naming what moved, or the detector can't run: plain "Motion detected" straight away.
        if (!detectOn() || detector.error.isNotEmpty()) {
            // One "unidentified" mark every 5 seconds of motion, for the recordings' timelines.
            if (now - lastPlainEvent >= 5000) { lastPlainEvent = now; logEvent(now, null, 0f) }
            alert(null)
            return
        }
        if (episode != 0L && now - episode > 10_000) episode = 0L   // the camera stopped mid-look
        if (episode == 0L && now >= episodeQuietUntil) {
            episode = now
            episodeChecks = 0
            detectWanted = true
        }
    }

    private fun detectOn() = settings.optBoolean("detect", false)

    /** One look after motion: alert about what it found, or after 3 looks with nothing, a plain alert (if wanted). */
    private fun onLooked(v: Detector.Verdict?) {
        val now = System.currentTimeMillis()
        if (v == null) {
            // The detector failed: don't lose the alert.
            if (episode != 0L) { alert(null); endEpisode(now) }
            report()
            return
        }
        if (episode == 0L) return
        episodeChecks++
        val f = v.alert
        val text = when {
            f != null -> Detector.NAMES[f.label] ?: f.label
            episodeChecks >= LOOKS -> "Nothing recognised"
            else -> null
        }
        if (text != null) {
            seen = JSONObject().put("at", now).put("text", text).put("notes", JSONArray(v.notes.take(6)))
            report()
        }
        when {
            f != null -> { if (Detector.worthLogging(lastEvents, f, v.at)) logEvent(v.at, f.label, f.score); alert(f.label); endEpisode(now) }
            episodeChecks >= LOOKS -> { logEvent(episode, null, 0f); if (settings.optBoolean("alert_other", true)) alert(null); endEpisode(now, QUIET_AFTER_NOTHING) }
            else -> detectWanted = true
        }
    }

    private fun endEpisode(now: Long, quiet: Long = QUIET_AFTER_FOUND) {
        episode = 0L
        movedReset = true
        episodeQuietUntil = now + quiet
    }

    /** Tell the router (it sends the alerts). A recognised thing isn't held back by a plain alert just before it. */
    private fun alert(what: String?) {
        val now = System.currentTimeMillis()
        if (what == null) {
            if (now - lastMotionSent <= 30_000) return
        } else {
            if (now - (lastSentWhat[what] ?: 0L) <= 30_000) return
            lastSentWhat[what] = now
        }
        lastMotionSent = now
        val m = JSONObject().put("type", "motion")
        if (what != null) m.put("what", what)
        send(m)
    }

    // ---------------------------------------------------------------- recording and photos

    private fun stamp(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(t))

    private var recManual = false
    private var recByMotion = false

    private fun startRecording(seconds: Int, manual: Boolean = false) {
        // A long record already records everything.
        if (longActive()) return
        val now = System.currentTimeMillis()
        if (rec != null) {
            recUntil = maxOf(recUntil, now + seconds * 1000L)
            if (manual) recManual = true
            return
        }
        recManual = manual
        recByMotion = !manual
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
        val long = if (recLong) longId else ""
        // Started by the motion check (not Record): tagged "Motion" in Recordings.
        val motion = recByMotion && long.isEmpty()
        recLong = false
        recByMotion = false
        r.finish { secs ->
            if (secs > 0 && f != null) uploader.add(f, "clip", t, secs, "mp4", long, motion) else f?.delete()
            h.post { update() }
        }
        report()
    }

    // ---------------------------------------------------------------- long record

    private fun longActive() = longId.isNotEmpty()
    private fun longPrefs() = getSharedPreferences("long", MODE_PRIVATE)

    private fun loadLong() {
        val p = longPrefs()
        val id = p.getString("id", "") ?: ""
        val until = p.getLong("until", 0L)
        if (id.isNotEmpty() && until > System.currentTimeMillis()) {
            longId = id; longUntil = until
            longFps = p.getInt("fps", 0); longQuality = p.getString("quality", "normal") ?: "normal"
            longSound = p.getBoolean("sound", false)
            longDropped = p.getInt("dropped", 0)
            Log.i(TAG, "long record carries on until $until")
        } else if (id.isNotEmpty()) {
            p.edit().clear().apply()
        }
    }

    /** hours <= 0: until someone stops it. */
    private fun startLong(hours: Double, fps: Int, quality: String, sound: Boolean) {
        if (longActive()) return
        if (rec != null) finishRecording()
        val now = System.currentTimeMillis()
        longId = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(now))
        longUntil = if (hours <= 0) Long.MAX_VALUE else now + (hours.coerceIn(0.25, 48.0) * 3_600_000).toLong()
        longDropped = 0
        longFps = fps.coerceIn(1, 30)
        longQuality = if (quality in listOf("low", "normal", "high")) quality else "normal"
        longSound = sound
        longError = ""
        longPrefs().edit().putString("id", longId).putLong("until", longUntil)
            .putInt("fps", longFps).putString("quality", longQuality).putBoolean("sound", longSound).apply()
        update()
        tick()
    }

    /** Stop the long record (why = a message for the page and the screen, or "" when asked to). */
    private fun stopLong(why: String) {
        if (!longActive()) return
        if (rec != null && recLong) finishRecording()
        longId = ""
        longUntil = 0L
        longError = why
        if (why.isNotEmpty()) problem = why
        longPrefs().edit().clear().apply()
        update()
    }

    /** Frames per second for the blocks: the chosen rate, never more than the camera gives. */
    private fun longRate() = (if (longFps > 0) longFps else fps()).coerceAtMost(fps()).coerceAtLeast(1)

    /** Picture size: low = 480p, normal = 720p, high = 1080p, never more than the camera's own. */
    private fun longSide() = minOf(when (longQuality) { "low" -> 480; "high" -> 1080; else -> 720 }, res())

    /** Bits per second for that picture size at 15 a second, scaled to the frame rate. */
    private fun longBitrate(): Int {
        val perFrame = (when (longSide()) { 360 -> 700_000; 480 -> 1_200_000; 1080 -> 4_500_000; else -> 2_500_000 }) / 15.0
        return (perFrame * maxOf(longRate(), 3)).toInt().coerceAtLeast(200_000)
    }

    /** Room on the phone for another block, keeping 1 GB spare. */
    private fun roomForBlock(): Boolean {
        val block = (longBitrate().toLong() + (if (longSound) 64_000 else 0)) / 8 * 600
        return uploader.dir.usableSpace > block + 1024L * 1024 * 1024
    }

    private fun startLongBlock() {
        if (videoTrack == null || rec != null) return
        val now = System.currentTimeMillis()
        val f = File(uploader.dir, "long-$now.mp4")
        try {
            // Sound when asked for and the microphone is sending it (the camera's Microphone setting).
            val withAudio = longSound && mic() && audioRate > 0 && now - lastAudioAt < 3000
            rec = Recorder(f, longRate(), longBitrate(), if (withAudio) audioRate else 0, audioChannels, longSide())
            recStarted = now
            recFile = f
            recStamp = stamp(now)
            recUntil = longUntil
            recLong = true
        } catch (e: Exception) {
            problem = "Could not record: ${e.message}"
            Log.w(TAG, "long record: $e")
        }
        report()
    }

    /** Every half second while a long record runs: end, roll over to the next block, or stop when full. */
    private fun tickLong(now: Long) {
        if (now >= longUntil) { stopLong(""); return }
        val r = rec
        if (r != null && !recLong) finishRecording()
        if (r != null && recLong && (now - recStarted >= 600_000L || videoTrack == null)) {
            finishRecording()
        }
        if (rec == null && videoTrack != null) {
            if (!roomForBlock()) {
                // Unlimited: keep the newest hours. Delete the oldest unsent blocks to make room.
                while (longUntil == Long.MAX_VALUE && !roomForBlock() && uploader.dropOldest("long-")) longDropped++
                if (!roomForBlock()) {
                    stopLong("Long record stopped: the phone ran out of space, because it couldn't send the recordings (no Wi-Fi?). They send once it's back on Wi-Fi.")
                    return
                }
                Log.i(TAG, "long record: dropped the oldest unsent blocks to make room ($longDropped so far)")
                longPrefs().edit().putInt("dropped", longDropped).apply()
            }
            startLongBlock()
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // Thumbnail for the camera list.
        val gap = if (videoTrack != null) 60_000L else 600_000L
        if (!thumbWanted && cfg.ready && now - lastThumb > gap) { thumbWanted = true; update() }
        if (longActive()) { tickLong(now); return }
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
        val rot = rawRot
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
        val bytes = jpeg(b, rotation, 85)
        val req = Request.Builder().url(cfg.api + "/api/phone/" + cfg.id + "/thumb")
            .header("Authorization", "Bearer ${cfg.token}")
            .post(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()
        // The last failure (or "" once one gets through), shown on the camera list.
        thumbError = try {
            Net.http.newCall(req).execute().use { r -> if (r.isSuccessful) "" else "the router answered ${r.code}" }
        } catch (e: Exception) { Log.w(TAG, "thumbnail: $e"); e.message ?: e.javaClass.simpleName }
    }
    @Volatile private var thumbError = ""

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

    // Motion events for the recordings' timelines: what moved (or "" = unidentified) and when.
    // Kept until the router has them (it may be out of reach for a while during a long record).
    private val events = ArrayDeque<JSONObject>()
    private var lastPlainEvent = 0L
    private val lastEvents = HashMap<String, Pair<android.graphics.RectF, Long>>()

    private fun logEvent(at: Long, what: String?, score: Float) {
        synchronized(events) {
            events.addLast(JSONObject().put("type", "event").put("at", at).put("what", what ?: "").put("score", (score * 100).toInt()))
            while (events.size > 3000) events.removeFirst()
        }
        h.post { flushEvents() }
    }

    private fun flushEvents() {
        val w = ws ?: return
        synchronized(events) {
            while (events.isNotEmpty()) {
                val ok = try { w.send(events.first().toString()) } catch (_: Exception) { false }
                if (!ok) break
                events.removeFirst()
            }
        }
    }

    private fun handle(text: String) {
        val m = try { JSONObject(text) } catch (e: Exception) { return }
        try {
            when (m.optString("cmd")) {
                "hello" -> {
                    m.optString("name").takeIf { it.isNotEmpty() }?.let { name = it; cfg.name = it }
                    applySettings(m.optJSONObject("settings") ?: JSONObject())
                    h.post { flushEvents() }
                }
                "settings" -> applySettings(m.optJSONObject("settings") ?: JSONObject())
                "name" -> { name = m.optString("name"); cfg.name = name; changed() }
                "stream" -> { wantStream = m.optBoolean("on"); update() }
                "record" -> if (m.optBoolean("stop")) { if (!recLong) finishRecording() } else startRecording(m.optInt("seconds", 30).coerceIn(5, 3600), true)
                "long" -> if (m.optBoolean("stop")) stopLong("") else startLong(m.optDouble("hours", 3.0), m.optInt("fps", 0), m.optString("quality", "normal"), m.optBoolean("sound", false))
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
        // Graphics chip chosen again: forget why it failed before (it gets another go).
        if (s.optString("turn_with", "") == "gpu" && settings.optString("turn_with", "gpu") == "cpu") gpuProblem = ""
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
        // Health alerts on the router: heat (battery temperature, Android's own heat level) and storage.
        val tempC = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val thermal = if (Build.VERSION.SDK_INT >= 29) try { getSystemService(PowerManager::class.java).currentThermalStatus } catch (_: Exception) { -1 } else -1
        val freeMb = try { uploader.dir.usableSpace / (1024 * 1024) } catch (_: Exception) { -1L }
        send(JSONObject()
            .put("type", "status")
            .put("battery", level ?: JSONObject.NULL)
            .put("charging", charging)
            .put("temp_c", tempC ?: JSONObject.NULL)
            .put("thermal", if (thermal >= 0) thermal else JSONObject.NULL)
            .put("free_mb", if (freeMb >= 0) freeMb else JSONObject.NULL)
            .put("streaming", wantStream && pubState == "CONNECTED")
            .put("recording", rec != null)
            // Started with Record (not by motion): the camera page shows Stop for it.
            .put("rec_manual", rec != null && recManual)
            .put("motion_on", motionOn)
            .put("camera", if (videoTrack != null) "on" else "off")
            .put("res", res())
            .put("torch", torchOn)
            .put("extras", 1)
            .put("app", true)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("waiting", uploader.waiting())
            .put("upload_error", if (uploader.waiting() > 0) uploader.lastError else "")
            .put("fps", fps())
            .put("long", if (longActive()) JSONObject().put("id", longId).put("until", if (longUntil == Long.MAX_VALUE) 0L else longUntil)
                .put("unlimited", longUntil == Long.MAX_VALUE).put("dropped", longDropped).put("size", longSide()).put("fps", longRate())
                .put("quality", longQuality).put("sound", longSound).put("block_started", if (recLong) recStarted else 0L) else JSONObject.NULL)
            .put("mic", mic())
            .put("stamp", settings.optBoolean("stamp", false))
            // This app can send motion alerts during a recording ("rec_alerts").
            .put("rec_alerts_ok", true)
            // Can name what moved; the last look (what it saw and why things didn't count).
            .put("detect_ok", true)
            .put("detect_error", if (detectOn()) detector.error else "")
            .put("seen", seen ?: JSONObject.NULL)
            // Live view arrives the right way up (turned on the phone by its tilt sensor).
            .put("upright", true)
            // The back lenses this phone offers, for "Which camera" in its settings.
            .put("lens_error", lensError)
            .put("camera_info", try { Lenses.describe(this@CameraService) } catch (_: Exception) { "" })
            .put("lenses", org.json.JSONArray().apply { Lenses.back(this@CameraService).forEach { put(JSONObject().put("key", it.key).put("label", it.label)) } })
            .put("turning", when {
                settings.optString("turn_with", "gpu") == "cpu" -> "processor"
                gpuProblem.isNotEmpty() -> "processor"
                turnObs?.usedGpu == true -> "graphics"
                else -> "" })
            .put("turning_error", if (settings.optString("turn_with", "gpu") == "cpu") "" else gpuProblem)
            .put("thumb_error", thumbError)
            .put("long_error", longError))
        val parts = ArrayList<String>()
        parts.add(if (linked) "Connected to the router" else "Not connected")
        if (wantStream && pubState == "CONNECTED") parts.add("someone is watching")
        if (longActive()) parts.add(if (longUntil == Long.MAX_VALUE) "long record (until stopped)" else "long record until " + SimpleDateFormat("h:mm a", Locale.US).format(Date(longUntil)))
        else if (rec != null) parts.add("recording")
        if (motionOn) parts.add("motion detection on")
        else if (alertsOn()) parts.add("motion alerts on")
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
    // Big uploads (a 10-minute block can be hundreds of MB): the router saves the whole file
    // before it answers, so wait longer than for other requests.
    private val client = Net.http.newBuilder().writeTimeout(2, java.util.concurrent.TimeUnit.MINUTES)
        .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES).build()
    /** Why the last upload failed ("" once one gets through): shown on the camera page. */
    @Volatile var lastError = ""

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

    /** Delete the oldest waiting file whose name starts with `prefix` (and its details). True if one went. */
    @Synchronized fun dropOldest(prefix: String): Boolean {
        val meta = dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }?.minByOrNull { it.name } ?: return false
        File(dir, meta.name.removeSuffix(".json")).delete()
        meta.delete()
        changed()
        return true
    }

    fun add(f: File, kind: String, t: String, secs: Int, ext: String, long: String = "", motion: Boolean = false) {
        File(dir, f.name + ".json").writeText(
            JSONObject().put("kind", kind).put("t", t).put("secs", secs).put("ext", ext).put("long", long).put("motion", motion).toString())
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
                    // A long-record block: the router keeps the blocks together as one recording.
                    q.optString("long").takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("long", it) }
                    if (q.optBoolean("motion")) url.addQueryParameter("motion", "1")
                }
                val req = Request.Builder().url(url.build())
                    .header("Authorization", "Bearer ${cfg.token}")
                    .post(data.asRequestBody("application/octet-stream".toMediaType()))
                    .build()
                try {
                    client.newCall(req).execute().use { r ->
                        if (!r.isSuccessful) {
                            // The router says why in its answer: {"ok":false,"error":"..."}
                            val why = try { JSONObject(r.body?.string() ?: "").optString("error") } catch (_: Exception) { "" }
                            throw IOException(if (why.isNotEmpty()) "the router said: $why" else "the router answered ${r.code}")
                        }
                    }
                    data.delete()
                    meta.delete()
                    lastError = ""
                    changed()
                } catch (e: Exception) {
                    Log.w("Uploader", "upload ${data.name}: $e")
                    lastError = e.message ?: e.toString()
                    changed()
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
class TurnObserver(private val inner: CapturerObserver, private val extra: () -> Int, private val stampOn: () -> Boolean, private val upright: () -> Int, private val seen: (Int) -> Unit, private val recording: () -> Boolean, private val useGpu: () -> Boolean, private val raw: (VideoFrame) -> Unit) : CapturerObserver {
    private val stamp = Stamp()
    // The graphics chip does the turning and the stamp; the main processor version below is the
    // fallback if this phone's graphics driver won't (then it's used from then on).
    private var gpu: GpuTurn? = null
    private var gpuFailed = false
    var onGpuProblem: ((String) -> Unit)? = null
    @Volatile var usedGpu = false
    private var wantedGpu = true
    override fun onCapturerStarted(ok: Boolean) = inner.onCapturerStarted(ok)
    override fun onCapturerStopped() {
        try { gpu?.release() } catch (_: Exception) {}
        gpu = null
        inner.onCapturerStopped()
    }
    override fun onFrameCaptured(f: VideoFrame) {
        // How far the camera's picture must turn to be the right way up: from the tilt sensor (how
        // the phone is actually held), or the camera's own idea until the sensor has answered.
        val t = upright()
        val rot = (((if (t >= 0) t else f.rotation) + extra()) % 360 + 360) % 360
        seen(rot)
        // The owner's choice (camera settings): graphics chip, or the main processor for a phone whose
        // graphics driver garbles it. Choosing the graphics chip again gives it another go.
        val wantGpu = useGpu()
        if (wantGpu && !wantedGpu) gpuFailed = false
        wantedGpu = wantGpu
        if (!wantGpu && gpu != null) { try { gpu?.release() } catch (_: Throwable) {}; gpu = null; usedGpu = false }
        if (wantGpu && !gpuFailed && f.buffer is VideoFrame.TextureBuffer && onGpu(f, rot)) return
        val base = if (rot == f.rotation) f else VideoFrame(f.buffer, rot, f.timestampNs)
        // The date and time in the corner (camera setting): written into the frame, so it's in
        // live view and every recording.
        val stamped = if (stampOn()) stamp.apply(base, rot) else null
        val src = stamped ?: base
        var live: VideoFrame? = null
        try {
            // Recordings: the full-size frame (before anything shrinks it for live view), turned by
            // the file's orientation note, which players follow.
            try { raw(src) } catch (_: Exception) {}
            // Live view: turned for real. The note doesn't survive the trip to the viewer's browser,
            // so a sideways picture would arrive sideways.
            live = if (rot == 0) null else Upright.turn(src, rot)
            inner.onFrameCaptured(live ?: src)
        } finally {
            live?.release()
            stamped?.release()
        }
    }

    /** The graphics chip way; false to fall back to the main processor. */
    private fun onGpu(f: VideoFrame, rot: Int): Boolean {
        val text = if (stampOn()) StampText.now() else null
        var live: VideoFrame? = null
        try {
            val g = gpu ?: GpuTurn().also { gpu = it }
            // Live view: turned the right way up, stamp on top.
            live = g.render(f, rot, text) ?: return true  // no free buffer: skip this frame
            // Recordings: the camera's own way round with the turn as the file's note; the stamp
            // drawn so it's upright once players turn it.
            if (recording()) {
                val r = if (text != null) g.render(f, 0, text, rot) else null
                val rec = if (r != null) VideoFrame(r.buffer, rot, f.timestampNs) else VideoFrame(f.buffer, rot, f.timestampNs)
                try { raw(rec) } catch (_: Exception) {} finally { r?.release() }
            }
            inner.onFrameCaptured(live)
            usedGpu = true
            return true
        } catch (e: Throwable) {
            gpuFailed = true
            try { gpu?.release() } catch (_: Throwable) {}
            gpu = null
            onGpuProblem?.invoke("${e.javaClass.simpleName}: ${e.message}")
            return false
        } finally {
            live?.release()
        }
    }
}

object Upright {
    /** The frame's picture turned clockwise by [rot] (a new frame, the caller releases it), or null. */
    fun turn(f: VideoFrame, rot: Int): VideoFrame? {
        val i = f.buffer.toI420() ?: return null
        return try {
            val side = rot % 180 != 0
            val out = org.webrtc.JavaI420Buffer.allocate(if (side) i.height else i.width, if (side) i.width else i.height)
            org.webrtc.YuvHelper.I420Rotate(i.dataY, i.strideY, i.dataU, i.strideU, i.dataV, i.strideV,
                out.dataY, out.strideY, out.dataU, out.strideU, out.dataV, out.strideV, i.width, i.height, rot)
            VideoFrame(out, 0, f.timestampNs)
        } catch (_: Throwable) {
            null
        } finally {
            i.release()
        }
    }
}
