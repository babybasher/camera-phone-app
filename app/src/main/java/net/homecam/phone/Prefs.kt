package net.homecam.phone

import android.content.Context
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * What this phone needs to reach the camera system. Everything is kept in the
 * app's private storage. All network access goes through [Net], so a built-in
 * tunnel can be added later in one place.
 */
class Prefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("camera", Context.MODE_PRIVATE)

    /** Address of the camera page, e.g. https://name.duckdns.org */
    var server: String
        get() = p.getString("server", "") ?: ""
        set(v) = p.edit().putString("server", v).apply()
    var user: String
        get() = p.getString("user", "") ?: ""
        set(v) = p.edit().putString("user", v).apply()
    var pass: String
        get() = p.getString("pass", "") ?: ""
        set(v) = p.edit().putString("pass", v).apply()
    /** Control service: its address (with port) and token, from control.json. */
    var api: String
        get() = p.getString("api", "") ?: ""
        set(v) = p.edit().putString("api", v).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    /** This phone's camera id and name in the camera list. */
    var id: String
        get() = p.getString("id", "") ?: ""
        set(v) = p.edit().putString("id", v).apply()
    var name: String
        get() = p.getString("name", "") ?: ""
        set(v) = p.edit().putString("name", v).apply()
    /** The user wants the camera running (used to offer a restart after a reboot). */
    var running: Boolean
        get() = p.getBoolean("running", false)
        set(v) = p.edit().putBoolean("running", v).apply()
    /** Reserved for the built-in tunnel (WireGuard) in a later version. */
    var tunnelConfig: String
        get() = p.getString("tunnel", "") ?: ""
        set(v) = p.edit().putString("tunnel", v).apply()

    val ready get() = id.isNotEmpty() && token.isNotEmpty() && api.isNotEmpty()

    fun unlink() {
        val keep = server
        val keepUser = user
        p.edit().clear().apply()
        server = keep
        user = keepUser
    }
}

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        // A connection left over from before a Wi-Fi drop can hang; check it's alive before reusing it.
        .retryOnConnectionFailure(true)
        .build()
    val ws: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    private val JSON = "application/json".toMediaType()

    fun basic(p: Prefs) = Credentials.basic(p.user, p.pass)

    /** Read control.json from the camera page (needs the page login). */
    fun control(server: String, user: String, pass: String): JSONObject {
        val req = Request.Builder().url(server.trimEnd('/') + "/control.json")
            .header("Authorization", Credentials.basic(user, pass))
            .header("Cache-Control", "no-store").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("wrong username or password")
            if (!r.isSuccessful) throw IOException("the camera page answered ${r.code}")
            return JSONObject(r.body!!.string())
        }
    }

    /** Address of the control service, worked out like the web pages do. */
    fun apiBase(server: String, c: JSONObject): String {
        val u = android.net.Uri.parse(server.trimEnd('/'))
        val https = u.scheme == "https"
        val port = if (https && c.optInt("tls_port", 0) > 0) c.optInt("tls_port") else c.optInt("port")
        return "${u.scheme}://${u.host}:$port"
    }

    fun call(p: Prefs, method: String, path: String, body: JSONObject? = null): JSONObject =
        call(p.api, p.token, method, path, body)

    fun call(api: String, token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
        val b = Request.Builder().url(api + path).header("Authorization", "Bearer $token")
        if (method == "GET") b.get() else b.method(method, (body ?: JSONObject()).toString().toRequestBody(JSON))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string() ?: ""
            val j = try { JSONObject(text) } catch (e: Exception) { JSONObject().put("ok", false).put("error", "HTTP ${r.code}") }
            if (!r.isSuccessful || j.optBoolean("ok", true) == false) throw IOException(j.optString("error", "HTTP ${r.code}"))
            return j
        }
    }
}
