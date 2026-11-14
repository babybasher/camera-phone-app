package net.homecam.phone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/** Setup the first time; afterwards shows the camera and lets you turn it on or off. */
class MainActivity : Activity() {
    private lateinit var cfg: Prefs
    private var preview: SurfaceViewRenderer? = null
    private var previewOn = false
    private var statusView: TextView? = null
    private var problemView: TextView? = null
    private var toggle: Button? = null

    private val bg = Color.parseColor("#101418")
    private val card = Color.parseColor("#1B2229")
    private val edge = Color.parseColor("#2C343C")
    private val fg = Color.parseColor("#E8ECF0")
    private val muted = Color.parseColor("#9AA5B1")
    private val accent = Color.parseColor("#2F7D63")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Prefs(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (intent?.data?.let { fromLink(it) } != true) show()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        if (i?.data?.let { fromLink(it) } != true) show()
    }

    /**
     * homecam://setup?server=https://... from the camera page, plus
     * &api=...&pair=<code> from a QR code: then the app sets itself up.
     * Returns true when it is setting itself up.
     */
    private fun fromLink(u: Uri): Boolean {
        val s = u.getQueryParameter("server") ?: return false
        if (s.startsWith("https://") || s.startsWith("http://")) cfg.server = s.trimEnd('/')
        val code = u.getQueryParameter("pair") ?: return false
        val api = u.getQueryParameter("api")?.trimEnd('/') ?: return false
        if (!api.startsWith("https://")) return false
        pairWith(api, code)
        return true
    }

    private var setupMessage = ""

    private fun pairWith(api: String, code: String) {
        page {
            addView(text("Setting up the camera...", 24f, bold = true))
            addView(text("Using the setup code from the QR code.", 15f, muted))
        }
        Thread {
            val err = try {
                val r = Net.call(api, "", "POST", "/api/phone/pair", JSONObject().put("code", code))
                if (CameraService.instance != null) runOnUiThread { CameraService.stop(this) }
                cfg.server = r.getString("server"); cfg.user = r.optString("user"); cfg.pass = r.optString("pass")
                cfg.api = api; cfg.token = r.getString("token"); cfg.id = r.getString("id"); cfg.name = r.optString("name")
                null
            } catch (e: Exception) { e.message ?: e.toString() }
            runOnUiThread {
                intent?.data = null
                if (err == null) { show(); askPermissions() }
                else { setupMessage = "The QR code didn't work: $err"; show() }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        CameraService.onChange = { refresh() }
        // Opened from the restart notification, or after an app update: the camera should be on.
        val asked = intent?.getBooleanExtra("start", false) == true
        intent?.removeExtra("start")
        if (cfg.ready && CameraService.instance == null && (asked || cfg.running)) askPermissions()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        CameraService.onChange = null
        stopPreview()
    }

    override fun onDestroy() {
        stopPreview()
        preview?.release()
        preview = null
        super.onDestroy()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float = 16f, color: Int = fg, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextColor(color)
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun button(s: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = s
        isAllCaps = false
        textSize = 16f
        setTextColor(fg)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (primary) accent else card)
            setStroke(dp(1), if (primary) accent else edge)
        }
        minHeight = dp(48)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun field(hint: String, value: String, type: Int) = EditText(this).apply {
        this.hint = hint
        setText(value)
        inputType = type
        setTextColor(fg)
        setHintTextColor(muted)
        isSingleLine = true
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(bg); setStroke(dp(1), edge) }
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
    }

    private fun page(build: LinearLayout.() -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            build()
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true; addView(col) })
    }

    private fun show() {
        stopPreview()
        preview?.release()
        preview = null
        if (cfg.ready) showCamera() else showSetup()
    }

    // ---------------------------------------------------------------- setup

    private fun showSetup() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        statusView = null; problemView = null; toggle = null
        page {
            addView(text("Use this phone as a camera", 24f, bold = true))
            addView(text("Sign in to your camera page once. The camera then keeps working with the screen off; keep the phone plugged in.", 15f, muted))
            val server = field("Camera page address, e.g. https://name.duckdns.org", cfg.server, InputType.TYPE_TEXT_VARIATION_URI)
            val user = field("Username (camera page login)", cfg.user, InputType.TYPE_CLASS_TEXT)
            val pass = field("Password", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val name = field("Camera name, e.g. Kitchen", cfg.name, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
            addView(server)
            if (cfg.server.isEmpty()) addView(text("Tip: on the camera page, Add camera → Open the app fills in the address for you.", 13f, muted))
            addView(user); addView(pass); addView(name)
            addView(text("Using the name of a phone camera that is already in the list takes it over (keeps its recordings and settings).", 13f, muted))
            val msg = text(setupMessage, 15f, Color.parseColor("#FFB4A8"))
            setupMessage = ""
            val go = button("Connect", primary = true) {}
            go.setOnClickListener {
                var s = server.text.toString().trim().trimEnd('/')
                if (s.isNotEmpty() && !s.contains("://")) s = "https://$s"
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                val n = name.text.toString().trim()
                if (s.isEmpty() || u.isEmpty() || p.isEmpty() || n.isEmpty()) { msg.text = "Fill in all four boxes."; return@setOnClickListener }
                if (n.length > 24) { msg.text = "Names can be up to 24 characters."; return@setOnClickListener }
                go.isEnabled = false
                msg.setTextColor(muted); msg.text = "Connecting..."
                Thread {
                    val err = try { link(s, u, p, n); null } catch (e: Exception) { e.message ?: e.toString() }
                    runOnUiThread {
                        go.isEnabled = true
                        if (err != null) { msg.setTextColor(Color.parseColor("#FFB4A8")); msg.text = "Could not connect: $err" }
                        else { show(); askPermissions() }
                    }
                }.start()
            }
            addView(go)
            addView(msg)
        }
    }

    /** Sign in, then add this phone to the camera list (or take over the camera with that name). */
    private fun link(server: String, user: String, pass: String, name: String) {
        val c = Net.control(server, user, pass)
        val api = Net.apiBase(server, c)
        val token = c.getString("token")
        val list = Net.call(api, token, "GET", "/api/cameras").optJSONArray("cameras")
        var id = ""
        var finalName = name
        if (list != null) for (i in 0 until list.length()) {
            val cam = list.getJSONObject(i)
            if (cam.optString("brand") == "phone" && cam.optString("name").equals(name, true)) {
                id = cam.getString("id"); finalName = cam.optString("name"); break
            }
        }
        if (id.isEmpty()) {
            val r = Net.call(api, token, "POST", "/api/phone/register", JSONObject().put("name", name))
            id = r.getString("id")
            finalName = r.optString("name", name)
        }
        cfg.server = server; cfg.user = user; cfg.pass = pass
        cfg.api = api; cfg.token = token; cfg.id = id; cfg.name = finalName
    }

    // ---------------------------------------------------------------- permissions

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) want.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) afterPermissions() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        afterPermissions()
    }

    private fun afterPermissions() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            problemView?.text = "The camera permission is needed. Allow it in Settings > Apps > Phone Camera > Permissions."
            return
        }
        CameraService.start(this)
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            AlertDialog.Builder(this)
                .setTitle("Keep the camera running")
                .setMessage("Android may stop apps to save battery. Allow this app to keep running so the camera doesn't drop out.")
                .setPositiveButton("Allow") { _, _ ->
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Not now", null)
                .show()
        }
        refresh()
    }

    // ---------------------------------------------------------------- running

    private fun showCamera() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        page {
            addView(text(cfg.name.ifEmpty { "Camera" }, 26f, bold = true))
            statusView = text("", 15f, muted).also { addView(it) }
            problemView = text("", 15f, Color.parseColor("#FFB4A8")).also { addView(it) }
            val frame = FrameLayout(this@MainActivity).apply {
                background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.BLACK) }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)).apply { topMargin = dp(8) }
            }
            val pv = SurfaceViewRenderer(this@MainActivity)
            frame.addView(pv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            val hint = text("The picture shows here while the camera is on (someone watching, recording, or motion detection).", 14f, muted).apply {
                gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
            }
            frame.addView(hint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            pv.tag = hint
            preview = pv
            addView(frame)
            toggle = button("", primary = true) {
                if (CameraService.instance != null) CameraService.stop(this@MainActivity) else askPermissions()
                window.decorView.postDelayed({ refresh() }, 300)
            }
            addView(toggle)
            addView(text("You can lock the phone or leave this screen; the camera keeps running. The notification shows it is on.", 13f, muted))
            addView(button("Allow running in the background") {
                try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            })
            addView(button("Unlink this phone") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Stop using this phone as a camera? It stays in the camera list (offline) until you remove it in its Settings on the camera page.")
                    .setPositiveButton("Unlink") { _, _ ->
                        CameraService.stop(this@MainActivity)
                        cfg.unlink()
                        show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            val upMsg = text("", 14f, muted)
            val up = button(if (updateTo > 0) "Install version 1.0.$updateTo" else "Check for updates") {}
            up.setOnClickListener { updateTapped(up, upMsg) }
            Updater.onStatus = { upMsg.setTextColor(warn); upMsg.text = it }
            addView(up)
            addView(upMsg)
            addView(text("Phone Camera ${BuildConfig.VERSION_NAME}", 12f, muted))
        }
        refresh()
    }

    // ---------------------------------------------------------------- app update

    private val warn = Color.parseColor("#FFB4A8")
    private var updateTo = 0
    private var updateBusy = false

    /** First tap asks the camera system which app version it holds; if newer, the next tap installs it. */
    private fun updateTapped(b: Button, msg: TextView) {
        if (updateBusy) return
        updateBusy = true
        b.isEnabled = false
        val want = updateTo
        msg.setTextColor(muted)
        msg.text = if (want > 0) "Downloading..." else "Checking..."
        Thread {
            var line: String
            var bad = false
            try {
                if (want > 0) {
                    Updater.install(applicationContext, cfg) { pc -> runOnUiThread { msg.text = "Downloading... $pc%" } }
                    line = "Downloaded. Android now asks to install it (the first time it also asks to allow installs from this app). " +
                        "The camera goes off during the update: afterwards tap the notification, or open the app, to turn it back on."
                } else {
                    val v = Updater.latest(cfg)
                    if (v > BuildConfig.VERSION_CODE) { updateTo = v; line = "Version 1.0.$v is available." }
                    else line = "This is the latest version."
                }
            } catch (e: Exception) {
                bad = true
                line = (if (want > 0) "Update failed: " else "Could not check: ") + (e.message ?: e.toString())
            }
            runOnUiThread {
                updateBusy = false
                b.isEnabled = true
                b.text = if (updateTo > 0) "Install version 1.0.$updateTo" else "Check for updates"
                msg.setTextColor(if (bad) warn else muted)
                msg.text = line
            }
        }.start()
    }

    private fun refresh() {
        if (!cfg.ready) {
            if (statusView != null) show()
            return
        }
        val s = CameraService.instance
        statusView?.text = if (s == null) "Camera is off. Turn it on to show up in the camera list." else s.line
        problemView?.text = s?.problem ?: ""
        problemView?.visibility = if ((s?.problem ?: "").isEmpty()) View.GONE else View.VISIBLE
        toggle?.text = if (s == null) "Turn the camera on" else "Turn the camera off"
        val running = s != null && s.videoTrack != null
        if (running && !previewOn) startPreview() else if (!running && previewOn) stopPreview()
    }

    private fun startPreview() {
        val s = CameraService.instance ?: return
        val pv = preview ?: return
        try {
            pv.init(s.egl.eglBaseContext, null)
            pv.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            s.addPreview(pv)
            previewOn = true
            (pv.tag as? View)?.visibility = View.GONE
        } catch (_: Exception) {}
    }

    private fun stopPreview() {
        val pv = preview ?: return
        if (!previewOn) return
        CameraService.instance?.removePreview(pv)
        try { pv.release() } catch (_: Exception) {}
        previewOn = false
        (pv.tag as? View)?.visibility = View.VISIBLE
    }
}
