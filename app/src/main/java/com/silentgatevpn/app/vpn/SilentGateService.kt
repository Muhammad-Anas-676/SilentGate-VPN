package com.silentgatevpn.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import com.silentgatevpn.app.MainActivity
import com.silentgatevpn.app.R
import io.nekohasekai.libbox.AutoRedirectHandler
import io.nekohasekai.libbox.AutoRedirectSession
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import io.nekohasekai.libbox.NetworkInterface as BoxInterface
import io.nekohasekai.libbox.Notification as BoxNotification
import java.net.InetAddress
import java.net.NetworkInterface as JavaInterface

/** Simple list-backed StringIterator for the Go side. */
class StringList(private val items: List<String>) : StringIterator {
    private var i = 0
    override fun len(): Int = items.size - i
    override fun hasNext(): Boolean = i < items.size
    override fun next(): String = items[i++]
}

class InterfaceList(private val items: List<BoxInterface>) : NetworkInterfaceIterator {
    private var i = 0
    override fun hasNext(): Boolean = i < items.size
    override fun next(): BoxInterface = items[i++]
}

class SilentGateService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "com.silentgatevpn.app.START"
        const val ACTION_STOP = "com.silentgatevpn.app.STOP"
        private const val CHANNEL_ID = "silentgate_vpn"
        private const val NOTIFICATION_ID = 17
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectJob: Job? = null
    private var monitorJob: Job? = null
    private var server: CommandServer? = null
    private var currentLayer: ConfigBuilder.Layer? = null
    private var currentNodeId: String? = null
    private val failedNodes = HashSet<String>()
    @Volatile private var lastNetChange = 0L
    private var lastIfaceName = ""
    private var lastIfaceIndex = -1
    private var lastNetwork: Network? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // ---------------------------------------------------------------- lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                connectJob?.cancel()
                disconnect()
                stopSelf()
            }
            return START_NOT_STICKY
        }
        startForegroundNow("Connecting…")
        if (connectJob?.isActive != true && VpnState.status.value != Status.CONNECTED) {
            connectJob = scope.launch { connectFlow(avoidNodeId = null, skipLayer = null) }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        scope.launch { connectJob?.cancel(); disconnect(); stopSelf() }
    }

    override fun onDestroy() {
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        scope.coroutineContext[Job]?.cancel()
        if (VpnState.status.value != Status.ERROR) VpnState.status.value = Status.DISCONNECTED
        super.onDestroy()
    }

    // ---------------------------------------------------------------- connect flow

    private enum class Stage(val layer: ConfigBuilder.Layer) {
        MY_SERVER(ConfigBuilder.Layer.MY_SERVER),
        NODES(ConfigBuilder.Layer.NODE),
        WARP(ConfigBuilder.Layer.WARP),
        WARP_NODE(ConfigBuilder.Layer.WARP_NODE),
        DPI_ONLY(ConfigBuilder.Layer.DPI_ONLY)
    }

    /** Everything one connect run needs; built once so every attempt shares the same network facts. */
    private class Run(
        val s: AppSettings,
        val home: HomeInfo,
        val boot: List<DnsProvider>,
        val mtu: Int,
        val reasons: MutableList<String>
    )

    /**
     * Smart Auto never ends in an unmasked connection: own server, then community nodes, then WARP.
     * "DPI only" exists, but only when the user picks it explicitly.
     */
    private fun stagesFor(s: AppSettings): List<Stage> = when (s.mode) {
        ConnectMode.AUTO -> buildList {
            if (ProfileStore.active() != null) add(Stage.MY_SERVER)
            if (s.communityNodes) add(Stage.NODES)
            add(Stage.WARP)
        }
        ConnectMode.NODES -> listOf(Stage.NODES)
        ConnectMode.WARP -> listOf(Stage.WARP)
        ConnectMode.WARP_NODE -> listOf(Stage.WARP_NODE)
        ConnectMode.MY_SERVER -> listOf(Stage.MY_SERVER)
        ConnectMode.DPI_ONLY -> listOf(Stage.DPI_ONLY)
    }

    private fun overrides() = OverrideOptions().apply {
        setAutoRedirect(false)
        setIncludePackage(StringList(emptyList()))
        setExcludePackage(StringList(emptyList()))
    }

    private fun underlyingMtu(): Int {
        val cm = getSystemService(ConnectivityManager::class.java)
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
        for (n in nets) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            if (Build.VERSION.SDK_INT >= 29 && lp.mtu > 0) return lp.mtu
            val name = lp.interfaceName ?: continue
            val mtu = runCatching { JavaInterface.getByName(name)?.mtu ?: 0 }.getOrDefault(0)
            if (mtu > 0) return mtu
        }
        return 0
    }

    private suspend fun connectFlow(avoidNodeId: String?, skipLayer: ConfigBuilder.Layer?) {
        monitorJob?.cancel()
        VpnState.status.value = Status.CONNECTING
        VpnState.lastFailure.value = emptyList()
        VpnState.resetSession()
        val s = SettingsStore.value
        try {
            if (server == null) {
                server = Libbox.newCommandServer(this, this).also { it.start() }
            }
            VpnState.message.value = "Checking your network…"
            updateNotification("Checking your network…")
            // Failing over while connected: the tunnel is up, so keep the home IP measured before it started.
            val home = if (avoidNodeId == null) Net.homeInfo() else Net.home
            val boot = Net.bootstrapOrder(s.dns)
            if (avoidNodeId == null) failedNodes.clear() else failedNodes.add(avoidNodeId)
            val run = Run(s, home, boot, underlyingMtu(), mutableListOf())
            VpnState.log("Home: ${home.cc} ${home.ip}, carrier MTU ${run.mtu}")

            var stages = stagesFor(s)
            if (skipLayer != null) stages = stages.filter { it.layer != skipLayer } + stages.filter { it.layer == skipLayer }
            for (stage in stages) {
                val ok = when (stage) {
                    Stage.MY_SERVER -> tryOwnServer(run)
                    Stage.NODES -> tryNodes(run, ConfigBuilder.Layer.NODE)
                    Stage.WARP_NODE -> tryNodes(run, ConfigBuilder.Layer.WARP_NODE)
                    Stage.WARP -> attempt(run, ConfigBuilder.Layer.WARP, null, null, "Cloudflare WARP", enforce = false, rotateDns = true)
                    Stage.DPI_ONLY -> attempt(run, ConfigBuilder.Layer.DPI_ONLY, null, null, "DPI bypass", enforce = false, rotateDns = true)
                }
                if (ok) return
            }
            VpnState.lastFailure.value = run.reasons
            fail("Could not make a safe connection. See details below.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            fail(e.message ?: "Unknown error")
        }
    }

    private suspend fun tryOwnServer(run: Run): Boolean {
        val profile = ProfileStore.active()
        if (profile == null) { run.reasons += "My server: no profile added"; return false }
        val proxy = runCatching { ProxyParser.parseAny(profile.data) }.getOrElse {
            run.reasons += "My server: ${it.message}"; return false
        }
        return attempt(run, ConfigBuilder.Layer.MY_SERVER, proxy, null, profile.name, enforce = run.s.mode == ConnectMode.AUTO, rotateDns = true)
    }

    private suspend fun tryNodes(run: Run, layer: ConfigBuilder.Layer): Boolean {
        val s = run.s
        var cands = NodePool.candidates(s, run.home, failedNodes)
        if (cands.size < 3 || NodePool.nodes.value.isEmpty()) {
            VpnState.message.value = "Fetching nodes…"
            updateNotification("Fetching nodes…")
            withTimeoutOrNull(14_000) { NodePool.refresh() }
            cands = NodePool.candidates(s, run.home, failedNodes)
        }
        if (cands.isEmpty()) {
            run.reasons += "Nodes: none available" + if (s.preferredCountry != "AUTO") " for ${Geo.name(s.preferredCountry)}" else " (check subscriptions in Profiles)"
            return false
        }
        val t0 = System.currentTimeMillis()
        val budget = 42_000L
        val now = System.currentTimeMillis()

        // Fast path: nodes that worked recently are tried first, without any pre-flight, so reconnects are quick.
        val fast = cands.filter { it.lastOkAt > 0 && now - it.lastOkAt < NodePool.STALE_MS }.take(2)
        for (n in fast) {
            if (attemptNode(run, layer, n)) return true
            if (System.currentTimeMillis() - t0 > budget) return false
        }

        // Others: pre-flight in parallel (6 at a time, gentle on a low-RAM phone) and connect in order of arrival.
        val rest = cands.filter { c -> fast.none { it.id == c.id } }.take(14)
        val ch = Channel<Pair<Node, Long>>(Channel.UNLIMITED)
        val sem = Semaphore(6)
        val jobs = rest.map { n -> Net.launchIo { sem.withPermit { ch.send(n to Prober.preflight(n)) } } }
        var pending = jobs.size
        var tried = 0
        var ok = false
        try {
            while (pending > 0 && tried < 6 && !ok && System.currentTimeMillis() - t0 < budget) {
                val got = withTimeoutOrNull(6_000) { ch.receive() } ?: break
                pending--
                val (n, ms) = got
                if (ms < 0) { NodePool.markFail(n.id); failedNodes.add(n.id); continue }
                tried++
                ok = attemptNode(run, layer, n)
            }
        } finally {
            jobs.forEach { it.cancel() }
            ch.close()
        }
        if (!ok && tried == 0) run.reasons += "Nodes: none of the ${rest.size} tested nodes is reachable on this network"
        return ok
    }

    private suspend fun attemptNode(run: Run, layer: ConfigBuilder.Layer, n: Node): Boolean {
        val proxy = runCatching { ProxyParser.parseAny(n.raw) }.getOrNull()
        if (proxy == null) { NodePool.markFail(n.id); failedNodes.add(n.id); return false }
        val label = (if (n.cc.isNotEmpty()) Geo.flag(n.cc) + " " else "") + n.name.take(28)
        val ok = attempt(run, layer, proxy, n, label, enforce = true, rotateDns = false)
        if (!ok) { NodePool.markFail(n.id); failedNodes.add(n.id) }
        return ok
    }

    /** Starts one config, waits for traffic, then verifies the real exit. Returns true only for a verified tunnel. */
    private suspend fun attempt(
        run: Run, layer: ConfigBuilder.Layer, proxy: ParsedProxy?, node: Node?,
        label: String, enforce: Boolean, rotateDns: Boolean
    ): Boolean {
        currentLayer = layer
        VpnState.layer.value = layer
        VpnState.message.value = "Trying $label…"
        updateNotification("Trying $label…")
        val ip = proxy?.takeIf { !it.isEndpoint && !it.isIpServer }?.let { Net.resolveHost(it.server) }
        val ctx = ConfigBuilder.Ctx(run.boot, run.mtu, ip)
        var started = false
        var okVariant = 0
        var lastError = ""
        for (variant in 0 until ConfigBuilder.variants(layer)) {
            val cfg = try {
                ConfigBuilder.build(layer, run.s, proxy, variant, ctx)
            } catch (e: Exception) {
                lastError = e.message ?: "config error"
                break
            }
            try {
                server!!.startOrReloadService(cfg, overrides())
                started = true
                okVariant = variant
                if (variant > 0) VpnState.log("$label: running with reduced options (variant $variant)")
                break
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: e.toString()
                VpnState.log("$label start error (variant $variant): $lastError")
            }
        }
        if (!started) { run.reasons += "$label: ${lastError.take(120)}"; return false }

        var result = Net.waitForTunnel(layer.timeoutMs)
        if (result == Net.TunnelResult.DNS_BROKEN && rotateDns) {
            // Tunnel carries traffic but names do not resolve (resolver blocked): rotate DNS provider.
            for (d in DnsProvider.entries.filter { it != run.s.dns }) {
                VpnState.message.value = "DNS blocked, trying ${d.label}…"
                val ok = runCatching {
                    server!!.startOrReloadService(ConfigBuilder.build(layer, run.s.copy(dns = d), proxy, okVariant, ctx), overrides())
                }.isSuccess
                if (!ok) continue
                if (Net.waitForTunnel(12_000) == Net.TunnelResult.OK) {
                    SettingsStore.update { it.copy(dns = d) }
                    result = Net.TunnelResult.OK
                    break
                }
            }
        }
        if (result != Net.TunnelResult.OK) {
            run.reasons += "$label: " + if (result == Net.TunnelResult.DNS_BROKEN) "tunnel works but every DNS provider is blocked" else "no traffic (blocked or too slow)"
            return false
        }

        VpnState.message.value = "Verifying exit…"
        val want = if (layer == ConfigBuilder.Layer.NODE || layer == ConfigBuilder.Layer.WARP_NODE) run.s.preferredCountry else "AUTO"
        val v = Net.verifyExit(run.home, run.s, layer.masksIp, enforce, want)
        if (node != null && v.cc.isNotEmpty()) NodePool.markExit(node.id, v.cc, v.rc)
        if (!v.ok) {
            VpnState.log("$label rejected: ${v.reason}")
            run.reasons += "$label: ${v.reason}"
            return false
        }
        if (node != null) NodePool.markOk(node.id, v.cc, v.rc, v.rttMs)
        onConnected(layer, node, v)
        return true
    }

    private fun onConnected(layer: ConfigBuilder.Layer, node: Node?, v: Verify) {
        val protection = when {
            !layer.masksIp -> Protection.DPI_ONLY
            !v.ipChanged -> Protection.NONE
            v.geoLocal || Geo.isRestricted(v.cc, v.rc) -> Protection.GEO_LOCAL
            else -> Protection.FULL
        }
        VpnState.protection.value = protection
        VpnState.node.value = node?.name ?: ""
        VpnState.nodeCc.value = v.cc
        currentNodeId = node?.id
        VpnState.status.value = Status.CONNECTED
        VpnState.message.value = when (protection) {
            Protection.FULL -> "Protected via ${layer.title}"
            Protection.GEO_LOCAL -> "IP hidden, but the exit looks like ${Geo.name(v.cc)}. Try Smart Auto or WARP + Node."
            Protection.DPI_ONLY -> "DPI bypass only. Your IP and country are NOT hidden."
            Protection.NONE -> "Not protected: your IP did not change"
        }
        updateNotification(
            when (protection) {
                Protection.DPI_ONLY -> "DPI bypass only · IP visible"
                else -> "Connected · ${layer.title}" + if (v.cc.isNotEmpty()) " · ${Geo.flag(v.cc)} ${v.cc}" else ""
            }
        )
        refreshAlwaysOn()
        scope.launch { runCatching { VpnState.exit.value = Net.exitInfo() } }
        if (SettingsStore.value.communityNodes && NodePool.isStale()) scope.launch { runCatching { NodePool.refresh() } }
        startMonitor(layer)
    }

    /** Tunnel byte counters: the TUN interface when Android lets us read it, otherwise this app's UID totals. */
    private class TunCounters(private val uid: Int) {
        private val tunName: String? = runCatching {
            JavaInterface.getNetworkInterfaces()?.toList()?.firstOrNull { it.isUp && it.name.startsWith("tun") }?.name
        }.getOrNull()

        private fun read(name: String, stat: String): Long =
            runCatching { File("/sys/class/net/$name/statistics/$stat").readText().trim().toLong() }.getOrDefault(-1L)

        private val useTun = tunName != null && read(tunName, "rx_bytes") >= 0

        // Bytes written INTO the tun by the core are what apps receive (download), and the reverse for upload.
        fun rx(): Long = if (useTun) read(tunName!!, "rx_bytes") else TrafficStats.getUidRxBytes(uid).let { if (it < 0) TrafficStats.getTotalRxBytes() else it }
        fun tx(): Long = if (useTun) read(tunName!!, "tx_bytes") else TrafficStats.getUidTxBytes(uid).let { if (it < 0) TrafficStats.getTotalTxBytes() else it }
    }

    private fun startMonitor(layer: ConfigBuilder.Layer) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val counters = TunCounters(Process.myUid())
            val rx0 = counters.rx(); val tx0 = counters.tx()
            var lastRx = rx0; var lastTx = tx0
            var tick = 0
            var failures = 0
            val canFailover = SettingsStore.value.mode.let {
                it == ConnectMode.AUTO || it == ConnectMode.NODES || it == ConnectMode.WARP_NODE
            }
            while (true) {
                delay(1000)
                val r = counters.rx(); val t = counters.tx()
                VpnState.rxSpeed.value = (r - lastRx).coerceAtLeast(0)
                VpnState.txSpeed.value = (t - lastTx).coerceAtLeast(0)
                VpnState.sessionRx.value = (r - rx0).coerceAtLeast(0)
                VpnState.sessionTx.value = (t - tx0).coerceAtLeast(0)
                lastRx = r; lastTx = t
                tick++
                // Right after a Wi-Fi <-> mobile switch, check every 3 s instead of every 10 s.
                val recentChange = System.currentTimeMillis() - lastNetChange < 25_000
                if (tick % (if (recentChange) 3 else 10) == 0) {
                    val ok = Net.probe(5000)
                    failures = if (ok) 0 else failures + 1
                    if (failures >= (if (recentChange) 2 else 3) && canFailover) {
                        val bad = currentNodeId
                        VpnState.log("Tunnel stalled on ${layer.title}; failing over")
                        if (bad != null) NodePool.markFail(bad)
                        connectJob = scope.launch { connectFlow(avoidNodeId = bad, skipLayer = if (bad == null) layer else null) }
                        return@launch
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        VpnState.message.value = message
        VpnState.status.value = Status.ERROR
        VpnState.protection.value = Protection.NONE
        VpnState.log("Error: $message")
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        stopForegroundCompat()
        stopSelf()
    }

    private suspend fun disconnect() {
        VpnState.status.value = Status.DISCONNECTING
        monitorJob?.cancel()
        runCatching { server?.closeService() }
        runCatching { server?.close() }
        server = null
        VpnState.resetSession()
        VpnState.layer.value = null
        VpnState.message.value = ""
        VpnState.status.value = Status.DISCONNECTED
        stopForegroundCompat()
    }

    private fun refreshAlwaysOn() {
        if (Build.VERSION.SDK_INT >= 29) {
            VpnState.alwaysOn.value = isAlwaysOn
            VpnState.lockdown.value = isLockdownEnabled
        }
    }

    // ---------------------------------------------------------------- notification

    private fun buildNotification(text: String) : android.app.Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SilentGateService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("SilentGate VPN")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect", stop)
            .build()
    }

    private fun startForegroundNow(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ---------------------------------------------------------------- CommandServerHandler

    override fun serviceStop() {
        scope.launch { connectJob?.cancel(); disconnect(); stopSelf() }
    }

    override fun serviceReload() {
        VpnState.log("Core requested reload")
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(enabled: Boolean) {}
    override fun triggerNativeCrash() {}
    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) VpnState.log(message)
    }
    override fun connectSSHAgent(): Int = throw UnsupportedOperationException("SSH agent not supported")

    // ---------------------------------------------------------------- PlatformInterface

    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (VpnService.prepare(this) != null) throw IllegalStateException("VPN permission not granted")
        val b = Builder().setSession("SilentGate VPN").setMtu(options.getMTU())
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)

        var has4 = false
        var has6 = false
        options.getInet4Address().let { while (it.hasNext()) { val p = it.next(); b.addAddress(p.address(), p.prefix()); has4 = true } }
        options.getInet6Address().let { while (it.hasNext()) { val p = it.next(); b.addAddress(p.address(), p.prefix()); has6 = true } }

        if (options.getAutoRoute()) {
            options.getDNSServerAddress().let { if (it.hasNext()) b.addDnsServer(it.next()) }

            var any4 = false
            options.getInet4RouteAddress().let { while (it.hasNext()) { val p = it.next(); b.addRoute(p.address(), p.prefix()); any4 = true } }
            if (!any4 && has4) b.addRoute("0.0.0.0", 0)
            var any6 = false
            options.getInet6RouteAddress().let { while (it.hasNext()) { val p = it.next(); b.addRoute(p.address(), p.prefix()); any6 = true } }
            if (!any6 && has6) b.addRoute("::", 0)

            if (Build.VERSION.SDK_INT >= 33) {
                options.getInet4RouteExcludeAddress().let {
                    while (it.hasNext()) { val p = it.next(); b.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix())) }
                }
                options.getInet6RouteExcludeAddress().let {
                    while (it.hasNext()) { val p = it.next(); b.excludeRoute(IpPrefix(InetAddress.getByName(p.address()), p.prefix())) }
                }
            }

            val include = options.getIncludePackage()
            val exclude = options.getExcludePackage()
            if (include.hasNext()) {
                while (include.hasNext()) {
                    runCatching { b.addAllowedApplication(include.next()) }
                }
            } else {
                while (exclude.hasNext()) {
                    runCatching { b.addDisallowedApplication(exclude.next()) }
                }
            }
        }

        val pfd = b.establish() ?: throw IllegalStateException("Could not establish the VPN interface")
        return pfd.detachFd()
    }

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String?, sourcePort: Int, destinationAddress: String?, destinationPort: Int
    ): ConnectionOwner = throw UnsupportedOperationException("not supported")

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cm = getSystemService(ConnectivityManager::class.java)
        // NOT_VPN is explicit on purpose: without it tun0 can be reported back to the core as the "physical"
        // interface during Wi-Fi <-> mobile switches, which makes the tunnel carry its own traffic and hang.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        lastIfaceName = ""
        lastIfaceIndex = -1
        lastNetwork = null
        val cb = object : ConnectivityManager.NetworkCallback() {
            private fun push(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val name = cm.getLinkProperties(network)?.interfaceName ?: return
                if (name.startsWith("tun")) return
                val index = runCatching { JavaInterface.getByName(name)?.index ?: 0 }.getOrDefault(0)
                if (name == lastIfaceName && index == lastIfaceIndex) return
                lastIfaceName = name
                lastIfaceIndex = index
                lastNetwork = network
                lastNetChange = System.currentTimeMillis()
                runCatching { setUnderlyingNetworks(arrayOf(network)) }
                VpnState.log("Network changed: $name")
                listener.updateDefaultInterface(name, index, false, false)
            }
            override fun onAvailable(network: Network) = push(network)
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = push(network)
            override fun onLost(network: Network) {
                // Only react when the network we were using disappeared; an old network dying after the new one
                // was already pushed must not wipe the good interface.
                if (network != lastNetwork) return
                lastIfaceName = ""
                lastIfaceIndex = -1
                lastNetwork = null
                listener.updateDefaultInterface("", -1, false, false)
            }
        }
        networkCallback = cb
        if (Build.VERSION.SDK_INT >= 31) {
            cm.registerBestMatchingNetworkCallback(request, cb, android.os.Handler(mainLooper))
        } else {
            cm.requestNetwork(request, cb)
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        val cb = networkCallback ?: return
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) }
        networkCallback = null
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val result = ArrayList<BoxInterface>()
        val all = runCatching { JavaInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (ni in all) {
            if (!ni.isUp) continue
            val box = BoxInterface()
            box.setIndex(ni.index)
            box.setMTU(ni.mtu)
            box.setName(ni.name)
            box.setAddresses(StringList(ni.interfaceAddresses.map {
                it.address.hostAddress!!.substringBefore('%') + "/" + it.networkPrefixLength
            }))
            var flags = 0x1 or 0x40 // IFF_UP | IFF_RUNNING
            if (ni.isLoopback) flags = flags or 0x8
            if (ni.isPointToPoint) flags = flags or 0x10
            if (ni.supportsMulticast()) flags = flags or 0x1000
            box.setFlags(flags)
            val type = when {
                ni.name.startsWith("wlan") -> 0
                ni.name.startsWith("rmnet") || ni.name.startsWith("ccmni") || ni.name.startsWith("v4-rmnet") -> 1
                ni.name.startsWith("eth") -> 2
                else -> 3
            }
            box.setType(type)
            box.setDNSServer(StringList(emptyList()))
            box.setDNSSearchDomain(StringList(emptyList()))
            box.setGateway(StringList(emptyList()))
            box.setMetered(false)
            result += box
        }
        return InterfaceList(result)
    }

    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun readWIFIState(): WIFIState? = null
    override fun systemCertificates(): StringIterator = StringList(emptyList())
    override fun clearDNSCache() {}
    override fun sendNotification(notification: BoxNotification?) {}
    override fun cancelNotification(identifier: String?, typeID: Int) {}
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}
    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() {}
    override fun openShellSession(
        user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int
    ): ShellSession = throw UnsupportedOperationException("shell not supported")
    override fun lookupUser(username: String?): PlatformUser = throw UnsupportedOperationException("not supported")
    override fun lookupSFTPServer(): String = throw UnsupportedOperationException("not supported")
    override fun readSystemSSHHostKey(): String = throw UnsupportedOperationException("not supported")
    override fun tailscaleHostname(): String = ""

    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = throw UnsupportedOperationException("not supported")
    override fun usePlatformAutoRedirect(): Boolean = false
    override fun createAutoRedirect(options: ByteArray?, handler: AutoRedirectHandler?): AutoRedirectSession =
        throw UnsupportedOperationException("not supported")
}
