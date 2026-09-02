package com.dnsoverride.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dnsoverride.app.LogBuffer
import com.dnsoverride.app.MainActivity
import com.dnsoverride.app.R
import com.dnsoverride.app.doh.DohClient
import com.dnsoverride.app.doh.DohProviders
import com.dnsoverride.app.store.SettingsStore
import com.dnsoverride.app.store.StatsStore
import com.dnsoverride.app.store.SubscriptionUpdater
import com.dnsoverride.app.util.IpPacket
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN 服务：建立 TUN 虚拟网卡，**仅接管 DNS 服务器 IP 的流量**。
 *
 * 关键策略（区别于全流量接管）：
 * - `addDnsServer("8.8.8.8")` 告诉系统用 8.8.8.8 做 DNS
 * - `addRoute(ip, 32)` 仅让发往公共 DNS IP 的包进入 TUN
 * - 其他流量（HTTP/HTTPS/IM 等）不进入 TUN，直接走物理网络，零损耗
 *
 * 进入 TUN 的包按协议分发：
 * - UDP/TCP 53：DNS 拦截（UDP 并发处理；TCP 由 [TcpFlowHandler] 的最小状态机应答）
 * - UDP/TCP 其他端口（DoT 853 / DoH 443 / QUIC 等）：经 protected socket 透明中继，
 *   不再黑洞——这是旧版「开 VPN 后部分网站/App 打不开」的主要根因之一
 * - ICMP：回 Echo Reply，保证 ping 这些 IP 可用于排查
 */
class DnsVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var workerThread: Thread? = null
    @Volatile private var tunOutput: FileOutputStream? = null

    private val interceptor: DnsInterceptor by lazy {
        DnsInterceptor(this) { log -> onQuery(log) }
    }

    private var dnsExecutor: ExecutorService? = null
    private var tcpHandler: TcpFlowHandler? = null
    private var udpRelay: UdpRelay? = null

    /** 本次隧道实际生效的 per-app 排除列表，用于检测设置变化后重建隧道。 */
    private var appliedExclusions: Set<String> = emptySet()

    private val hitCount = AtomicLong(0)
    private val missCount = AtomicLong(0)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                // 只在隧道已运行时重载。
                // 不能「未运行就顺手启动」：设置页任意一项变更（缓存大小、屏蔽方式…）
                // 都会发 RELOAD，若这里补启动，用户没点开关也会莫名其妙连上 VPN。
                // 服务若已被系统回收（进程内 vpnInterface 为 null 但 STATE 仍为 RUNNING），
                // 由 UI 在检测到状态不一致时显式重新 START，而不是在这里兜底。
                if (vpnInterface == null) {
                    Log.i(TAG, "ignoring RELOAD: tunnel is not running")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // 排除应用列表变化必须重建隧道（Builder 参数在 establish 时固定）
                val currentExclusions = SettingsStore.get(this).excludedApps
                if (currentExclusions != appliedExclusions) {
                    Log.i(TAG, "excluded apps changed, rebuilding tunnel")
                    teardownTunnel()
                    startVpn()
                    return START_STICKY
                }
                val s = SettingsStore.get(this)
                interceptor.reloadRules()
                interceptor.applyCacheSettings(s.cacheEnabled, s.cacheMaxEntries)
                applyDohSettings()
                return START_STICKY
            }
            ACTION_START, null -> startVpn()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }

    override fun onRevoke() {
        // 用户在系统设置中撤销了 VPN，等同停止
        stopVpn()
    }

    private fun startVpn() {
        if (vpnInterface != null) {
            Log.i(TAG, "VPN already running")
            return
        }
        STATE = State.STARTING
        broadcastState()

        startForeground(NOTIF_ID, buildNotification(getString(R.string.state_starting)))

        val settings = SettingsStore.get(this)
        // 规则可能在上次停止期间被改动，重建时刷新快照；缓存一并清空，避免返回过期答案
        interceptor.reloadRules()
        interceptor.clearCache()
        interceptor.applyCacheSettings(settings.cacheEnabled, settings.cacheMaxEntries)
        applyDohSettings()

        // 核心策略：只路由 DNS 服务器 IP，不接管其他流量
        val builder = Builder()
            .addAddress(VPN_ADDRESS, 24)
            .addDnsServer(PRIMARY_DNS)
            .setSession(SESSION_NAME)
            .setMtu(MTU)

        // 为所有可能的上游 DNS 服务器添加路由
        // 系统发往这些 IP 的 DNS 查询会进入 TUN，由我们拦截
        ROUTED_DNS_SERVERS.forEach { ip ->
            builder.addRoute(ip, 32)
        }

        // 按应用排除：银行/游戏类 App 检测到 active VPN 可能直接拒绝工作
        appliedExclusions = settings.excludedApps
        appliedExclusions.forEach { pkg ->
            runCatching { builder.addDisallowedApplication(pkg) }
                .onFailure { Log.w(TAG, "exclude app $pkg failed: ${it.message}") }
        }

        val pfd = builder.establish()

        if (pfd == null) {
            Log.e(TAG, "establish() returned null — VPN permission revoked?")
            stopSelf()
            return
        }
        vpnInterface = pfd
        tunOutput = FileOutputStream(pfd.fileDescriptor)
        activeInstance = this

        val executor = Executors.newFixedThreadPool(DNS_WORKER_THREADS)
        dnsExecutor = executor
        tcpHandler = TcpFlowHandler(
            vpnService = this,
            executor = executor,
            dnsResolver = { query -> interceptor.resolveMessage(query) },
            sendToTun = { packet -> writeToTun(packet) }
        )
        udpRelay = UdpRelay(this) { packet -> writeToTun(packet) }

        STATE = State.RUNNING
        broadcastState()

        workerThread = Thread({ processPackets() }, "vpn-packet-loop").apply {
            isDaemon = true
            start()
        }
        updateNotification(getString(R.string.notif_running))

        // 订阅规则自动更新（>24h 的订阅组后台拉取）
        if (settings.subscriptionAutoUpdate) {
            Thread({
                runCatching { SubscriptionUpdater.refreshOutdated(this) }
                    .onFailure { Log.w(TAG, "subscription refresh failed: ${it.message}") }
            }, "sub-refresh").apply { isDaemon = true }.start()
        }
    }

    /** 根据设置初始化/停用 DoH 上游。 */
    private fun applyDohSettings() {
        val settings = SettingsStore.get(this)
        if (settings.upstreamMode == SettingsStore.UpstreamMode.DOH) {
            val provider = DohProviders.byUrl(settings.dohProviderUrl)
                ?: DohProviders.AliDNS
            interceptor.enableDoh(DohClient.forProvider(provider))
        } else {
            interceptor.disableDoh()
        }
    }

    private fun stopVpn() {
        STATE = State.STOPPING
        broadcastState()
        teardownTunnel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        STATE = State.STOPPED
        broadcastState()
        stopSelf()
    }

    /** 停止隧道本身（线程/执行器/流表），不 stopSelf，供 RELOAD 重建隧道复用。 */
    private fun teardownTunnel() {
        // 统计最后一个刷新周期内的数据落盘，避免停 VPN 后丢失
        runCatching { StatsStore.get(this).flush() }
        interceptor.clearCache()
        workerThread?.interrupt()
        workerThread = null
        runCatching { tcpHandler?.closeAll() }
        tcpHandler = null
        runCatching { udpRelay?.closeAll() }
        udpRelay = null
        dnsExecutor?.shutdownNow()
        dnsExecutor = null
        runCatching { tunOutput?.flush() }
        tunOutput = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        // 主动断开静态引用，避免 DnsDiagnostics 长期持有已销毁的 Service 实例导致泄漏
        if (activeInstance === this) activeInstance = null
    }

    /**
     * 包处理循环：只做协议识别与分发，绝不在本线程做阻塞 I/O，
     * 否则一条慢查询（DoH 超时 + 多个 UDP 上游逐个超时）会阻塞所有后续 DNS 查询。
     */
    private fun processPackets() {
        val pfd = vpnInterface ?: return
        val input = FileInputStream(pfd.fileDescriptor)
        val buffer = ByteArray(BUFFER_SIZE)
        var count = 0

        try {
            while (!Thread.interrupted()) {
                val n = input.read(buffer)
                if (n <= 0) continue
                val packet = buffer.copyOfRange(0, n)

                try {
                    dispatchPacket(packet)
                } catch (t: Throwable) {
                    Log.w(TAG, "packet handling failed: ${t.message}")
                }

                if (++count % SWEEP_INTERVAL_PACKETS == 0) {
                    runCatching { tcpHandler?.sweepIdle() }
                    runCatching { udpRelay?.sweepIdle() }
                }
            }
        } catch (e: InterruptedException) {
            // 正常退出
        } catch (e: Exception) {
            Log.e(TAG, "packet loop crashed: ${e.message}", e)
        }
    }

    private fun dispatchPacket(packet: ByteArray) {
        if (packet.size < 20 || (packet[0].toInt() and 0xf0) != 0x40) return
        when (IpPacket.protocol(packet)) {
            IpPacket.PROTO_UDP -> {
                if (packet.size < IpPacket.headerLength(packet) + 8) return
                if (IpPacket.dstPort(packet) == 53 || IpPacket.srcPort(packet) == 53) {
                    dnsExecutor?.execute {
                        val response = runCatching { interceptor.handleUdp(packet) }.getOrNull()
                        if (response != null) writeToTun(response)
                    }
                } else {
                    udpRelay?.handlePacket(packet)
                }
            }
            IpPacket.PROTO_TCP -> tcpHandler?.handlePacket(packet)
            IpPacket.PROTO_ICMP -> {
                IpPacket.buildIcmpEchoReply(packet)?.let { writeToTun(it) }
            }
        }
    }

    /** 把响应包写回 TUN。多线程并发调用，必须串行化。 */
    @Synchronized
    private fun writeToTun(packet: ByteArray) {
        try {
            tunOutput?.write(packet)
            tunOutput?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "writeToTun failed: ${e.message}")
        }
    }

    /** 每条 DNS 查询的回调：更新计数 + 推送通知 + 写入进程级日志缓冲。 */
    private fun onQuery(log: DnsInterceptor.QueryLog) {
        if (log.hit) hitCount.incrementAndGet() else missCount.incrementAndGet()
        updateNotification(notificationText())
        // 写入进程级 LogBuffer，UI（StatsFragment）通过订阅获取，避免广播在 Fragment 切换时丢失
        LogBuffer.add(log)
    }

    private fun notificationText(): String {
        val hit = hitCount.get()
        val miss = missCount.get()
        return getString(R.string.notif_stats, hit, miss)
    }

    // ------------------------- 通知 -------------------------

    private fun buildNotification(text: String): Notification {
        ensureChannel()
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DnsVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(R.drawable.ic_stop, getString(R.string.notif_action_stop), stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "DNS Override",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun broadcastState() {
        val intent = Intent(ACTION_STATE).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATE, STATE.name)
        }
        sendBroadcast(intent)
        // 通知 Quick Settings Tile 刷新状态
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            android.service.quicksettings.TileService.requestListeningState(
                this,
                android.content.ComponentName(this, com.dnsoverride.app.tile.DnsTileService::class.java)
            )
        }
    }

    // ------------------------- 公开状态 -------------------------

    enum class State { STOPPED, STARTING, RUNNING, STOPPING }

    companion object {
        private const val TAG = "DnsVpnService"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "vpn_status"
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val SESSION_NAME = "DNS Override"
        private const val MTU = 1500
        private const val BUFFER_SIZE = 32767

        /** DNS 解析并发线程数：netd 会并发发多条查询，串行会被慢查询拖死。 */
        private const val DNS_WORKER_THREADS = 4

        /** 每处理 N 个包做一次空闲流回收。 */
        private const val SWEEP_INTERVAL_PACKETS = 512

        /** addDnsServer 指定的主 DNS，系统 DNS 查询会发往此 IP。 */
        private const val PRIMARY_DNS = "8.8.8.8"

        /**
         * 需要 addRoute 的 DNS 服务器 IP 列表。
         * 系统发往这些 IP 的 53 端口包会进入 TUN，由 DnsInterceptor 处理；
         * 这些 IP 的其他端口流量由 TcpFlowHandler/UdpRelay 中继，不会被黑洞。
         */
        private val ROUTED_DNS_SERVERS = listOf(
            "8.8.8.8",      // Google
            "8.8.4.4",      // Google
            "1.1.1.1",      // Cloudflare
            "1.0.0.1",      // Cloudflare
            "223.5.5.5",    // 阿里
            "223.6.6.6",    // 阿里
            "114.114.114.114" // 114DNS
        )

        const val ACTION_START = "com.dnsoverride.app.START"
        const val ACTION_STOP = "com.dnsoverride.app.STOP"
        const val ACTION_RELOAD = "com.dnsoverride.app.RELOAD"
        const val ACTION_STATE = "com.dnsoverride.app.STATE"
        const val EXTRA_STATE = "state"

        @Volatile var STATE: State = State.STOPPED
            private set

        /** 诊断工具用：protect socket 绕过 VPN，避免自测流量走 TUN。隧道关闭时置空。 */
        @Volatile var activeInstance: DnsVpnService? = null
            private set
    }
}
