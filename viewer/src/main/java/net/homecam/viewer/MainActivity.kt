package net.homecam.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.wireguard.android.backend.Tunnel
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** One screen: is the link home on, and a button to open the cameras. */
class MainActivity : Activity() {
    private lateinit var cfg: Store
    private var statusView: TextView? = null
    private var problemView: TextView? = null
    private var toggle: Button? = null
    private var message = ""
    private var reach = ""

    private val bg = Color.parseColor("#101418")
    private val card = Color.parseColor("#1B2229")
    private val edge = Color.parseColor("#2C343C")
    private val fg = Color.parseColor("#E8ECF0")
    private val muted = Color.parseColor("#9AA5B1")
    private val accent = Color.parseColor("#2C5F9E")
    private val warn = Color.parseColor("#FFB4A8")

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Store(this)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (intent?.data?.let { fromLink(it) } != true) show()
    }

    override fun onNewIntent(i: Intent?) {
        super.onNewIntent(i)
        setIntent(i)
        if (i?.data?.let { fromLink(it) } != true) show()
    }

    override fun onResume() {
        super.onResume()
        Vpn.onChange = { refresh() }
        if (cfg.ready && cfg.enabled) Vpn.check(this)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Vpn.onChange = null
    }

    /** homecamview://setup?api=https://...&code=<one-time code> from the camera page. */
    private fun fromLink(u: Uri): Boolean {
        val api = u.getQueryParameter("api")?.trimEnd('/') ?: return false
        val code = u.getQueryParameter("code") ?: return false
        if (!api.startsWith("https://")) return false
        intent?.data = null
        page {
            addView(text("Setting up...", 24f, bold = true))
            addView(text("Linking this phone to your cameras.", 15f, muted))
        }
        Thread {
            val err = try { join(api, code); null } catch (e: Exception) { e.message ?: e.toString() }
            runOnUiThread {
                if (err == null) { show(); turnOn() }
                else { message = "Setup didn't work: $err"; show() }
            }
        }.start()
        return true
    }

    /** Make this phone's key, and trade the code for the way home. Only the public half of the key is sent. */
    private fun join(api: String, code: String) {
        val keys = KeyPair()
        val body = JSONObject().put("code", code).put("pub", keys.publicKey.toBase64())
            .put("name", (Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + Build.MODEL).take(40))
        val c = URL("$api/api/vpn/join").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = c.responseCode
            val text = (if (status in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val j = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299 || !j.optBoolean("ok", false)) throw Exception(j.optString("error", "the router answered $status"))
            Vpn.down(this)
            cfg.clear()
            cfg.privateKey = keys.privateKey.toBase64()
            cfg.address = j.getString("address")
            cfg.serverPub = j.getString("server_pub")
            cfg.host = j.getString("host")
            cfg.port = j.getInt("port")
            cfg.lan = j.getString("lan")
            cfg.router = j.getString("router")
            cfg.page = j.getString("page")
            cfg.api = api
            cfg.leave = j.optString("leave")
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- on / off

    private fun turnOn() {
        val ask = try { VpnService.prepare(this) } catch (e: Exception) { message = "Android would not allow the link: ${e.message}"; show(); return }
        if (ask != null) {
            // Android asks once: "Connection request".
            try { startActivityForResult(ask, 7) } catch (e: Exception) { message = "Android would not show its permission box: ${e.message}"; show() }
            return
        }
        cfg.enabled = true
        Vpn.up(this)
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7) return
        if (resultCode == RESULT_OK) { cfg.enabled = true; Vpn.up(this) }
        else message = "Android needs you to tap OK on its \"Connection request\" box. Tap Turn on to try again."
        show()
    }

    private fun turnOff() {
        cfg.enabled = false
        Vpn.down(this)
        refresh()
    }

    // ---------------------------------------------------------------- screen

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
        minHeight = dp(52)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
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
        statusView = null; problemView = null; toggle = null; updateButton = null
        if (!cfg.ready) {
            page {
                addView(text("Camera Link", 26f, bold = true))
                addView(text("This app lets this phone see your cameras when you're away from home.", 16f))
                addView(text("To set it up: while at home on your Wi-Fi, open the camera page on this phone, tap Remote viewing, then tap \"Set up this phone\".", 15f, muted))
                if (message.isNotEmpty()) addView(text(message, 15f, warn))
                message = ""
                addView(text("Camera Link ${BuildConfig.VERSION_NAME}", 12f, muted))
            }
            return
        }
        page {
            addView(text("Camera Link", 26f, bold = true))
            statusView = text("", 17f).also { addView(it) }
            problemView = text("", 15f, warn).also { addView(it) }
            addView(button("Open the cameras", primary = true) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(cfg.page))) } catch (_: Exception) {}
            })
            toggle = button("") { if (cfg.enabled) turnOff() else turnOn() }
            addView(toggle)
            addView(text("While the link is on, the camera page works in Chrome wherever you are; you don't need to open this app. " +
                "Only camera traffic uses the link. It turns itself back on after the phone restarts.", 14f, muted))
            addView(button("Remove the link from this phone") {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remove the link? This phone will no longer reach the cameras away from home.")
                    .setPositiveButton("Remove") { _, _ -> unlink() }
                    .setNegativeButton("Cancel", null)
                    .show()
            })
            val upMsg = text("", 14f, muted)
            val up = button("") {}
            up.setOnClickListener { updateTapped(upMsg) }
            updateButton = up
            Updater.onStatus = { upMsg.setTextColor(warn); upMsg.text = it }
            addView(up)
            addView(upMsg)
            addView(text("Camera Link ${BuildConfig.VERSION_NAME}  -  ${cfg.host}", 12f, muted))
        }
        refresh()
    }

    /** Tell home to forget this phone (while the link can still carry the message), then forget home. */
    private fun unlink() {
        page { addView(text("Removing the link...", 24f, bold = true)) }
        Thread {
            val told = Updater.leave(cfg)
            runOnUiThread {
                Vpn.down(this)
                cfg.clear()
                message = if (told) "The link is removed, and home has taken this phone off its list."
                    else "The link is removed from this phone. Home couldn't be told, so also remove this phone under Remote viewing on the camera page."
                show()
            }
        }.start()
    }

    // ---------------------------------------------------------------- app update

    private var updateButton: Button? = null
    private var updateBusy = false

    /** Checks with home; when there is a newer app, the next tap installs it. */
    private fun updateTapped(msg: TextView) {
        if (updateBusy) return
        updateBusy = true
        updateButton?.isEnabled = false
        val install = Updater.available
        msg.setTextColor(muted)
        msg.text = if (install) "Downloading..." else "Checking..."
        Thread {
            var line: String
            var bad = false
            try {
                if (install) {
                    Updater.install(applicationContext, cfg) { pc -> runOnUiThread { msg.text = "Downloading... $pc%" } }
                    line = "Downloaded. Android now asks to install it (the first time it also asks to allow installs from this app). The link comes back on by itself afterwards."
                } else {
                    Updater.hello(cfg)
                    line = if (Updater.available) "Version 1.0.${Updater.latest} is available." else "This is the latest version."
                }
            } catch (e: Exception) {
                bad = true
                line = (if (install) "Update failed: " else "Could not check (the link has to be on and home answering): ") + (e.message ?: e.toString())
            }
            runOnUiThread {
                updateBusy = false
                updateButton?.isEnabled = true
                msg.setTextColor(if (bad) warn else muted)
                msg.text = line
                refresh()
            }
        }.start()
    }

    private var probing = false

    private fun refresh() {
        if (!cfg.ready) return
        val on = cfg.enabled && Vpn.state == Tunnel.State.UP
        statusView?.text = when {
            !cfg.enabled -> "The link is off."
            !on -> "Starting the link..."
            reach == "yes" -> "The link is on and home is answering."
            reach == "no" -> "The link is on, but home isn't answering right now."
            else -> "The link is on. Checking home..."
        }
        val p = if (message.isNotEmpty()) message else Vpn.problem
        problemView?.text = p
        problemView?.visibility = if (p.isEmpty()) View.GONE else View.VISIBLE
        toggle?.text = if (cfg.enabled) "Turn the link off" else "Turn the link on"
        updateButton?.text = if (Updater.available) "Install version 1.0.${Updater.latest}" else "Check for updates"
        if (on && reach.isEmpty() && !probing) probe()
        if (!on) reach = ""
    }

    /** Does the router's camera page answer through the link? */
    private fun probe() {
        probing = true
        val router = cfg.router
        Thread {
            Thread.sleep(1500)
            val ok = try { Socket().use { it.connect(InetSocketAddress(router, 443), 5000); true } } catch (_: Exception) { false }
            runOnUiThread {
                probing = false
                reach = if (ok) "yes" else "no"
                refresh()
                // Look again in a while, so the line stays true.
                window.decorView.postDelayed({ if (!probing) { reach = ""; refresh() } }, 20_000)
            }
        }.start()
    }
}
