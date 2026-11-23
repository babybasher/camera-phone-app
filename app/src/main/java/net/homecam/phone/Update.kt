package net.homecam.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageInstaller
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import okhttp3.Request
import java.io.File
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

    fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /** Download the app (kept, so Android's own installer can be used if the direct way is refused)
     *  and hand it to Android, which then asks the user to confirm. */
    fun install(ctx: Context, p: Prefs, progress: (Int) -> Unit) {
        val apk = apkFile(ctx)
        val part = File(ctx.cacheDir, "update.apk.part")
        Net.http.newCall(get(p, "camera.apk")).execute().use { r ->
            if (r.code == 401) throw IOException("the camera page refused the saved login")
            if (!r.isSuccessful) throw IOException("the camera page answered ${r.code}")
            val body = r.body ?: throw IOException("empty download")
            val total = body.contentLength()
            part.outputStream().use { out ->
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
            }
            if (total > 0 && part.length() != total) throw IOException("the download was cut short")
        }
        if (part.length() < 200_000) { part.delete(); throw IOException("the download was not an app") }
        if (!part.renameTo(apk)) throw IOException("could not keep the download")
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            val session = installer.openSession(installer.createSession(params))
            var committed = false
            try {
                session.openWrite("camera.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out, 65536) }
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
        } catch (e: Exception) {
            // Some phones refuse the direct way outright: use Android's own installer screen.
            if (!systemInstaller(ctx)) throw IOException("Android refused the install: ${e.message}")
        }
    }

    /** Opens Android's own install screen for the downloaded app (the one the browser uses).
     *  Settings are kept: it's the same app, signed the same way. */
    fun systemInstaller(ctx: Context): Boolean {
        if (!apkFile(ctx).isFile) return false
        return try {
            @Suppress("DEPRECATION")
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(Uri.parse("content://${ctx.packageName}.apk/camera.apk"), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            true
        } catch (_: Exception) { false }
    }
}

/** Hands the downloaded update to Android's installer (read only, just that one file). */
class ApkProvider : ContentProvider() {
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/camera.apk" || mode != "r") throw SecurityException("not shared")
        val f = Updater.apkFile(context!!)
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? {
        if (uri.path != "/camera.apk") return null
        val f = Updater.apkFile(context!!)
        return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE))
            .apply { addRow(arrayOf<Any>("camera.apk", f.length())) }
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
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
            else -> {
                val why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "error $status"
                // Refused the direct way (some phones block it): try Android's own install screen.
                if (status != PackageInstaller.STATUS_FAILURE_CONFLICT && status != PackageInstaller.STATUS_FAILURE_STORAGE &&
                    Updater.systemInstaller(ctx)) Updater.onStatus?.invoke("Android's own install screen is opening instead (the direct way was refused: $why).")
                else Updater.onStatus?.invoke("The update did not install: $why")
            }
        }
    }
}
