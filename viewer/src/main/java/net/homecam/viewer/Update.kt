package net.homecam.viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * App updates from home, through the link: the router holds the newest app
 * and knows this phone by its link address, so no sign-in is needed.
 */
object Updater {
    /** Newest build the router holds, as last heard (0 = not heard yet). */
    @Volatile var latest = 0
    /** Set by the screen to show what Android said about the install. */
    var onStatus: ((String) -> Unit)? = null

    val available get() = latest > BuildConfig.VERSION_CODE

    private fun open(s: Store, path: String): HttpURLConnection {
        val c = URL(s.api + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 30_000
        return c
    }

    /** Tell the router which version this is (so the camera page can offer an update) and learn the newest. */
    fun hello(s: Store): Int {
        val c = open(s, "/api/vpn/hello")
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("version", BuildConfig.VERSION_CODE).toString().toByteArray()) }
            val status = c.responseCode
            if (status == 404) throw IOException("the camera system needs a newer update before it can offer app updates")
            if (status == 403) throw IOException("the router doesn't see this phone as linked; turn the link on and try again")
            if (status !in 200..299) throw IOException("the router answered $status")
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            latest = j.optInt("latest", 0)
            return latest
        } finally { c.disconnect() }
    }

    /** Download the app and hand it to Android, which then asks the user to confirm. */
    fun install(ctx: Context, s: Store, progress: (Int) -> Unit) {
        val c = open(s, "/api/vpn/app.apk")
        try {
            val status = c.responseCode
            if (status !in 200..299) throw IOException("the router answered $status")
            val total = c.contentLengthLong
            if (total in 1 until 200_000) throw IOException("the download was not an app")
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val session = installer.openSession(installer.createSession(params))
            var committed = false
            try {
                session.openWrite("camera-link.apk", 0, if (total > 0) total else -1).use { out ->
                    val input = c.inputStream
                    val buf = ByteArray(65536)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pc = (done * 100 / total).toInt()
                            if (pc != last) { last = pc; progress(pc) }
                        }
                    }
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val back = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, UpdateReceiver::class.java), flags)
                session.commit(back.intentSender)
                committed = true
            } finally {
                if (!committed) try { session.abandon() } catch (_: Exception) {}
                session.close()
            }
        } finally { c.disconnect() }
    }
}

/** Android reports here how the install went (and when it needs the user to confirm). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ask = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                try {
                    if (ask != null) ctx.startActivity(ask.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Updater.onStatus?.invoke("Android would not open the install screen: ${e.message}")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> Updater.onStatus?.invoke("Update cancelled.")
            else -> Updater.onStatus?.invoke(
                "The update did not install: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status")
            )
        }
    }
}
