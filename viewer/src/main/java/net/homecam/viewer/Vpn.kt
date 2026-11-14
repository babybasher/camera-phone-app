package net.homecam.viewer

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Executors

/** What this phone needs to reach home. Kept in the app's private storage. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("link", Context.MODE_PRIVATE)
    private fun s(k: String) = p.getString(k, "") ?: ""
    private fun put(k: String, v: String) = p.edit().putString(k, v).apply()

    /** This phone's own key; it never leaves the phone. */
    var privateKey: String get() = s("key"); set(v) = put("key", v)
    /** This phone's address inside the tunnel, e.g. 10.6.0.50 */
    var address: String get() = s("address"); set(v) = put("address", v)
    var serverPub: String get() = s("server"); set(v) = put("server", v)
    /** Home's internet name and WireGuard port. */
    var host: String get() = s("host"); set(v) = put("host", v)
    var port: Int get() = p.getInt("port", 51820); set(v) = p.edit().putInt("port", v).apply()
    /** The home network, e.g. 192.168.50.0/24, and the router's address on it. */
    var lan: String get() = s("lan"); set(v) = put("lan", v)
    var router: String get() = s("router"); set(v) = put("router", v)
    /** The camera page. */
    var page: String get() = s("page"); set(v) = put("page", v)
    /** Home's internet address when last seen (used if the name can't be looked up). */
    var lastIp: String get() = s("lastIp"); set(v) = put("lastIp", v)
    /** The user wants the link on. */
    var enabled: Boolean get() = p.getBoolean("on", false); set(v) = p.edit().putBoolean("on", v).apply()

    val ready get() = privateKey.isNotEmpty() && serverPub.isNotEmpty() && host.isNotEmpty() && lan.isNotEmpty()

    fun clear() = p.edit().clear().apply()
}

/**
 * The link home: a WireGuard tunnel that carries only traffic for the home
 * network (and for home's own internet address, so the camera page's normal
 * address works through it). Everything else on the phone is left alone.
 */
object Vpn {
    @Volatile var state: Tunnel.State = Tunnel.State.DOWN
    @Volatile var problem = ""
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var backend: GoBackend? = null
    /** Home's address the running tunnel was built for. */
    @Volatile private var builtFor = ""

    private val tunnel = object : Tunnel {
        override fun getName() = "cameras"
        override fun onStateChange(newState: Tunnel.State) {
            state = newState
            changed()
        }
    }

    private fun changed() { main.post { onChange?.invoke() } }

    private fun backend(ctx: Context): GoBackend = synchronized(this) {
        backend ?: GoBackend(ctx.applicationContext).also { backend = it }
    }

    private fun lookup(host: String): String? = try {
        InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    private fun toInt(ip: String): Long? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var n = 0L
        for (part in parts) { val v = part.toIntOrNull() ?: return null; if (v !in 0..255) return null; n = n * 256 + v }
        return n
    }

    /** Is [ip] inside [net] (written like 192.168.50.0/24)? */
    fun inside(ip: String, net: String): Boolean {
        val base = toInt(net.substringBefore('/')) ?: return false
        val bits = net.substringAfter('/', "32").toIntOrNull() ?: return false
        val addr = toInt(ip) ?: return false
        if (bits !in 0..32) return false
        val mask = if (bits == 0) 0L else (0xFFFFFFFFL shl (32 - bits)) and 0xFFFFFFFFL
        return (addr and mask) == (base and mask)
    }

    private fun config(s: Store, ip: String): Config {
        // Home's own internet address goes through the link too, so the camera page's usual name works.
        val allowed = if (inside(ip, s.lan)) s.lan else s.lan + ", " + ip + "/32"
        val me = Interface.Builder().parsePrivateKey(s.privateKey).parseAddresses(s.address + "/32").build()
        val home = Peer.Builder().parsePublicKey(s.serverPub).parseEndpoint(ip + ":" + s.port).parseAllowedIPs(allowed).build()
        return Config.Builder().setInterface(me).addPeer(home).build()
    }

    private fun bring(ctx: Context, s: Store, known: String?) {
        val ip = known ?: lookup(s.host) ?: s.lastIp.ifEmpty { null }
        if (ip == null) {
            problem = "Can't look up ${s.host} right now (no internet?). It keeps trying."
            changed()
            return
        }
        try {
            backend(ctx).setState(tunnel, Tunnel.State.UP, config(s, ip))
            builtFor = ip
            s.lastIp = ip
            problem = ""
        } catch (e: Exception) {
            problem = "Could not start the link: " + (if (e is BackendException) e.reason.name else (e.message ?: e.toString()))
        }
        changed()
    }

    /** Turn the link on (the user has already allowed it). */
    fun up(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        work.execute {
            try {
                val s = Store(app)
                if (s.ready && VpnService.prepare(app) == null) bring(app, s, null)
            } finally { done?.invoke() }
        }
    }

    fun down(ctx: Context) {
        val app = ctx.applicationContext
        work.execute {
            try { backend(app).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
            builtFor = ""
            problem = ""
            state = Tunnel.State.DOWN
            changed()
        }
    }

    /** Now and then, and when the phone changes network: follow home's address if it moved, restart if stopped. */
    fun check(ctx: Context) {
        val app = ctx.applicationContext
        work.execute {
            val s = Store(app)
            if (!s.ready || !s.enabled) return@execute
            if (VpnService.prepare(app) != null) return@execute
            val running = try { backend(app).getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }
            val ip = lookup(s.host)
            if (!running) bring(app, s, ip)
            else if (ip != null && ip != builtFor) bring(app, s, ip)
        }
    }
}

class ViewerApp : Application() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Vpn.check(this@ViewerApp)
            main.postDelayed(this, 180_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // If Android's own "Always-on VPN" setting starts us, bring the link up.
        GoBackend.setAlwaysOnCallback { Vpn.up(this) }
        main.postDelayed(tick, 5_000)
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val real = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(real, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.postDelayed({ Vpn.check(this@ViewerApp) }, 2_500) }
                override fun onLost(network: Network) { main.postDelayed({ Vpn.check(this@ViewerApp) }, 2_500) }
            })
        } catch (_: Exception) {}
    }
}

/** After a restart or an app update: turn the link back on if it was on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val s = Store(ctx)
        if (!s.ready || !s.enabled) return
        val pending = goAsync()
        Vpn.up(ctx) { pending.finish() }
    }
}
