package net.homecam.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import okhttp3.Request
import java.io.IOException

/**
 * App updates from the camera system itself: the camera page holds the newest
 * app (camera.apk) and its build number (app-version.txt). Nothing is fetched
 * from anywhere else.
 */
object Updater {
    /** Set by the screen to show what Android said about the install. */
    var onStatus: ((String) -> Unit)? = null

    private fun get(p: Prefs, file: String) = Request.Builder()
        .url(p.server.trimEnd('/') + "/" + file)
        .header("Authorization", Net.basic(p))
        .header("Cache-Control", "no-store").build()

    /** Build number of the app the camera system holds. */
    fun latest(p: Prefs): Int {
        Net.http.newCall(get(p, "app-version.txt")).execute().use { r ->
            if (r.code == 404) throw IOException("the camera system doesn't list an app version yet (it needs a newer update)")
            if (r.code == 401) throw IOException("the camera page refused the saved login")
            if (!r.isSuccessful) throw IOException("the camera page answered ${r.code}")
            val first = (r.body?.string() ?: "").trim().split(' ', '\n', '\r', '\t').firstOrNull()
            return first?.toIntOrNull() ?: throw IOException("the version file could not be read")
        }
    }

    /** Download the app and hand it to Android, which then asks the user to confirm. */
    fun install(ctx: Context, p: Prefs, progress: (Int) -> Unit) {
        Net.http.newCall(get(p, "camera.apk")).execute().use { r ->
            if (r.code == 401) throw IOException("the camera page refused the saved login")
            if (!r.isSuccessful) throw IOException("the camera page answered ${r.code}")
            val body = r.body ?: throw IOException("empty download")
            val total = body.contentLength()
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val session = installer.openSession(installer.createSession(params))
            var committed = false
            try {
                session.openWrite("camera.apk", 0, if (total > 0) total else -1).use { out ->
                    val input = body.byteStream()
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
                if (total in 1 until 200_000) throw IOException("the download was not an app")
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val back = PendingIntent.getBroadcast(ctx, 3, Intent(ctx, UpdateReceiver::class.java), flags)
                session.commit(back.intentSender)
                committed = true
            } finally {
                if (!committed) try { session.abandon() } catch (_: Exception) {}
                session.close()
            }
        }
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
