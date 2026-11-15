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
import java.net.Socket
import java.net.InetSocketAddress
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
    /** The router's control service, e.g. https://name.duckdns.org:1987 (phones set up before 1.0.11 didn't keep it). */
    var api: String get() = s("api").ifEmpty { "https://$host:1987" }; set(v) = put("api", v)
    /** The word that lets this phone take itself off home's device list when it unlinks. */
    var leave: String get() = s("leave"); set(v) = put("leave", v)
    /** Home hasn't allowed this phone yet (the owner does that on the Remote viewing page). */
    var pending: Boolean get() = p.getBoolean("pending", false); set(v) = p.edit().putBoolean("pending", v).apply()
    /** The camera page. */
    var page: String get() = s("page"); set(v) = put("page", v)
    /** Home's internet address when last seen (used if the name can't be looked up). */
    var lastIp: String get() = s("lastIp"); set(v) = put("lastIp", v)
    /** The user wants the link on. */
    var enabled: Boolean get() = p.getBoolean("on", false); set(v) = p.edit().putBoolean("on", v).apply()

    val ready get() = privateKey.isNotEmpty() && serverPub.isNotEmpty() && host.isNotEmpty() && lan.isNotEmpty()

    fun clear() = p.edit().clear().apply()
}

/** Kept when the link is removed: where home is, and (if home couldn't be told) the old link to replace. */
class Home(ctx: Context) {
    private val p = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)
    /** The camera page, e.g. https://name.duckdns.org/ */
    var page: String get() = p.getString("page", "") ?: ""; set(v) = p.edit().putString("page", v).apply()
    /** The private word of a link removed while home couldn't be reached: the next setup replaces it. */
    var oldLeave: String get() = p.getString("oldLeave", "") ?: ""; set(v) = p.edit().putString("oldLeave", v).apply()
}

/**
 * The link home: a WireGuard tunnel that carries only traffic for the home
 * network. Name lookups go to the home router while it is on; everything
 * else on the phone uses its normal connection.
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

    /**
     * Home's address as the phone's real network (Wi-Fi or mobile) sees it. It must not be asked
     * through the link: name lookups inside the link go to the home router, which answers with
     * its home address, and that can't be reached from outside.
     */
    private fun lookup(ctx: Context, host: String): String? {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val ip = try { n.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress } catch (_: Exception) { null }
                if (ip != null) return ip
            }
        } catch (_: Exception) {}
        // No link running yet: the ordinary lookup is the real network's.
        if (state != Tunnel.State.UP) {
            try { return InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress } catch (_: Exception) {}
        }
        return null
    }

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

    /** The link is held off because home hasn't allowed this phone yet. */
    @Volatile var waiting = false
    /** Home answered the last time it was asked directly (so checking often is cheap). */
    @Volatile var homeAnswers = false
    private var lastTrial = 0L

    private fun config(s: Store, ip: String, dns: Boolean = true): Config {
        // Only the home network goes through the link. Names are looked up by the home router (as the
        // WireGuard app does), because it knows the camera page's name means the router itself.
        // (A trial link while waiting for approval leaves name lookups alone.)
        val b = Interface.Builder().parsePrivateKey(s.privateKey).parseAddresses(s.address + "/32")
        if (dns) b.parseDnsServers(s.router)
        val me = b.build()
        val home = Peer.Builder().parsePublicKey(s.serverPub).parseEndpoint(ip + ":" + s.port).parseAllowedIPs(s.lan).build()
        return Config.Builder().setInterface(me).addPeer(home).build()
    }

    private fun bring(ctx: Context, s: Store, known: String?) {
        val ip = known ?: lookup(ctx, s.host) ?: s.lastIp.ifEmpty { null }
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

    /**
     * Has home accepted this phone's link? Only a completed WireGuard handshake proves it: reaching
     * the router proves nothing (at home it answers over Wi-Fi whether or not the link is allowed).
     */
    private fun handshake(ctx: Context, s: Store, seconds: Int): Boolean {
        val home = try { com.wireguard.crypto.Key.fromBase64(s.serverPub) } catch (_: Exception) { return false }
        repeat(seconds) {
            try {
                val stats = backend(ctx).getStatistics(tunnel).peer(home)
                if (stats != null && stats.latestHandshakeEpochMillis() > 0) return true
            } catch (_: Exception) {}
            Thread.sleep(1000)
        }
        return false
    }

    /**
     * While home hasn't allowed this phone, the link stays off: a link that can't connect would swallow
     * the home network and every name lookup, so even the camera page at home wouldn't load. Home is
     * asked directly (works at home); away from home, a trial link without name lookups is tried every
     * few minutes. Returns true while still waiting.
     */
    private fun stillWaiting(ctx: Context, s: Store): Boolean {
        if (!s.pending) { waiting = false; return false }
        // Home answers directly (at home): its word decides.
        var asked = false
        try { Updater.hello(s); asked = true; if (!s.pending) { waiting = false; return false } } catch (_: Exception) {}
        homeAnswers = asked
        val now = System.currentTimeMillis()
        // Home can't be asked (away): a trial link, judged only by its handshake.
        if (!asked && now - lastTrial > 4 * 60_000) {
            lastTrial = now
            val ip = lookup(ctx, s.host) ?: s.lastIp.ifEmpty { null }
            if (ip != null) {
                try {
                    backend(ctx).setState(tunnel, Tunnel.State.UP, config(s, ip, dns = false))
                    if (handshake(ctx, s, 10)) { s.pending = false; waiting = false; return false }
                } catch (_: Exception) {}
            }
        }
        try { backend(ctx).setState(tunnel, Tunnel.State.DOWN, null) } catch (_: Exception) {}
        builtFor = ""
        state = Tunnel.State.DOWN
        waiting = true
        changed()
        return true
    }

    /** Turn the link on (the user has already allowed it). */
    fun up(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        work.execute {
            try {
                val s = Store(app)
                if (s.ready && VpnService.prepare(app) == null && !stillWaiting(app, s)) bring(app, s, null)
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
            if (stillWaiting(app, s)) return@execute
            val running = try { backend(app).getState(tunnel) == Tunnel.State.UP } catch (_: Exception) { false }
            val ip = lookup(app, s.host)
            if (!running) bring(app, s, ip)
            else if (ip != null && ip != builtFor) bring(app, s, ip)
            // Say which version this is; hear whether there is a newer one.
            try { val before = Updater.latest; Updater.hello(s); if (Updater.latest != before) changed() } catch (_: Exception) {}
        }
    }
}

class ViewerApp : Application() {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            Vpn.check(this@ViewerApp)
            // While waiting for approval at home, ask every 15 s so the link comes on soon after it's allowed.
            main.postDelayed(this, if (Vpn.waiting && Vpn.homeAnswers) 15_000 else 180_000)
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
