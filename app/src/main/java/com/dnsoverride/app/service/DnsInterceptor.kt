package com.dnsoverride.app.service

import android.net.VpnService
import android.util.Log
import com.dnsoverride.app.R
import com.dnsoverride.app.cache.DnsCache
import com.dnsoverride.app.doh.DohClient
import com.dnsoverride.app.model.DnsRule
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.store.RuleStore
import com.dnsoverride.app.store.SettingsStore
import com.dnsoverride.app.store.StatsStore
import com.dnsoverride.app.util.DnsProtocol
import com.dnsoverride.app.util.IpPacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * DNS 拦截核心，工作在「DNS 报文」层（不含 IP/UDP/TCP 头），UDP 与 TCP 承载共用：
 * 1. 解析 Question 域名，匹配规则（覆盖 / 屏蔽 / 直连白名单）
 * 2. 未命中 → 查缓存 → 转发上游（DoH 优先，失败回退 UDP，多服务器逐个尝试）
 * 3. 上游全部失败 → 返回 SERVFAIL 快速失败，客户端不会干等超时
 * 4. 通过 [listener] 回报每条查询日志，供 UI 显示
 *
 * 线程模型：调用方（DnsVpnService）在多线程执行器中并发调用，本类自身线程安全。
 *
 * @param vpnService 用于 protect() 转发 socket，避免流量回环
 * @param listener 查询日志回调（在 worker 线程触发，UI 侧需自行切主线程）
 */
class DnsInterceptor(
    private val vpnService: VpnService,
    private val listener: (QueryLog) -> Unit
) {
    data class QueryLog(
        val domain: String,
        val resultIp: String,
        val hit: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
        val source: String = ""
    )

    private val settings = SettingsStore.get(vpnService)
    private val stats = StatsStore.get(vpnService)
    private val ruleStore = RuleStore.get(vpnService)
    private val cache = DnsCache(settings.cacheMaxEntries)
    // 上游竞速线程池：并发查询多时按需扩容，空闲线程自动回收
    private val upstreamExecutor = Executors.newCachedThreadPool()

    @Volatile
    private var dohClient: DohClient? = null

    /**
     * 规则快照。**不**在每条查询里实时读 RuleStore：
     * `getAllEnabledRules()` 会把所有规则组从 SharedPreferences 里反序列化一遍，
     * 订阅组动辄上万条时，把它放在 DNS 查询热路径上会明显拖慢解析。
     *
     * 代价：UI 修改规则后必须显式调用 [reloadRules] 才生效
     * （RulesFragment 每次增删改后都会发 ACTION_RELOAD，已覆盖该路径）。
     * 用一次 RELOAD 换取热路径零反序列化，是明确划算的取舍。
     */
    @Volatile
    private var rulesSnapshot: List<DnsRule> = ruleStore.getAllEnabledRules()

    private fun activeRules(): List<DnsRule> = rulesSnapshot

    /** 重新从 RuleStore 载入规则快照。规则增删改后必须调用。 */
    fun reloadRules() {
        val next = ruleStore.getAllEnabledRules()
        rulesSnapshot = next
        Log.i(TAG, "rules reloaded: ${next.size}")
    }

    fun enableDoh(client: DohClient) {
        dohClient = client
        Log.i(TAG, "DoH enabled: $client")
    }

    fun disableDoh() {
        dohClient = null
        Log.i(TAG, "DoH disabled")
    }

    fun clearCache() = cache.clear()

    /** 把缓存容量同步为当前设置值（设置页改动缓存大小后调用）。 */
    fun applyCacheSettings(enabled: Boolean, maxEntries: Int) {
        cache.resize(maxEntries)
        if (!enabled) cache.clear()
    }

    /**
     * 处理一个 UDP 53 的 IP 包：解析 → 报文级解析 → 包装回 IP+UDP 包。
     * 超过 TUN MTU 的响应会被截断并置 TC 位，客户端自动改用 TCP 重试。
     */
    fun handleUdp(packet: ByteArray): ByteArray? {
        val ihl = IpPacket.headerLength(packet)
        if (packet.size < ihl + 8 + 12) return null
        val query = packet.copyOfRange(ihl + 8, packet.size)

        val response = resolveMessage(query) ?: return null
        var wrapped = DnsProtocol.wrapUdpMessage(packet, response)
        if (wrapped.size > MAX_UDP_PACKET_BYTES) {
            val truncated = DnsProtocol.truncateDnsMessage(response, MAX_UDP_PACKET_BYTES - 28)
            wrapped = DnsProtocol.wrapUdpMessage(packet, truncated)
        }
        return wrapped
    }

    /**
     * 报文级解析入口（UDP/TCP 共用）。返回响应 DNS 报文；
     * null 表示查询报文无法解析，调用方只能丢弃。
     */
    fun resolveMessage(query: ByteArray): ByteArray? {
        val question = DnsProtocol.parseQuestionMessage(query) ?: return null
        val rule = activeRules().firstOrNull { it.matches(question.domain) }

        // 全局「屏蔽 AAAA」：IPv6 查询一律 NODATA（除非覆盖规则的 IP 本身是 v6）
        if (settings.blockAaaa && question.qtype == DnsProtocol.QTYPE_AAAA &&
            !(rule != null && rule.effectiveAction() == RuleAction.OVERRIDE && rule.ip.contains(':'))
        ) {
            listener(QueryLog(question.domain, vpnService.getString(R.string.log_blocked_aaaa), hit = true, source = "block-aaaa"))
            return DnsProtocol.buildEmptyAnswerMessage(query, question, DnsProtocol.RCODE_NOERROR)
        }

        if (rule != null) {
            when (rule.effectiveAction()) {
                RuleAction.BLOCK -> {
                    val msg = if (settings.blockModeNxdomain) {
                        DnsProtocol.buildEmptyAnswerMessage(query, question, DnsProtocol.RCODE_NXDOMAIN)
                    } else {
                        DnsProtocol.buildOverrideMessage(query, question, BLOCK_IP)
                    }
                    stats.addBlocked(question.domain)
                    listener(QueryLog(question.domain, vpnService.getString(R.string.log_blocked), hit = true, source = "block"))
                    return msg
                }
                RuleAction.OVERRIDE -> {
                    val msg = runCatching {
                        DnsProtocol.buildOverrideMessage(query, question, rule.ip)
                    }.getOrElse {
                        // 规则 IP 非法（例如脏数据）→ 当作未命中，走上游
                        Log.w(TAG, "invalid rule ip '${rule.ip}': ${it.message}")
                        null
                    }
                    if (msg != null) {
                        stats.addBlocked(question.domain)
                        listener(QueryLog(question.domain, rule.ip, hit = true, source = "rule"))
                        return msg
                    }
                }
                RuleAction.DIRECT -> {
                    // 直连白名单：跳过缓存直接走上游，避免缓存里旧答案干扰
                }
            }
        }

        val direct = rule?.effectiveAction() == RuleAction.DIRECT
        if (settings.cacheEnabled && !direct) {
            cache.get(question.domain, question.qtype)?.let { cached ->
                stats.addForwarded(question.domain, fromCache = true, viaDoh = false)
                listener(QueryLog(question.domain, vpnService.getString(R.string.log_cache_hit), hit = false, source = "cache"))
                return cached
            }
        }

        val upstream = forwardUpstream(query, question.domain)
        if (upstream != null) {
            DnsProtocol.rewriteTtl(upstream, settings.forwardTtl.coerceIn(0, 300))
            if (settings.cacheEnabled && !direct && isCacheable(upstream)) {
                cache.put(question.domain, question.qtype, upstream.copyOf(), settings.defaultTtl)
            }
            listener(
                QueryLog(
                    question.domain,
                    DnsProtocol.firstARecordIp(upstream) ?: vpnService.getString(R.string.log_tag_forward),
                    hit = false,
                    source = "upstream"
                )
            )
            return upstream
        }

        // 上游全部失败：快速返回 SERVFAIL，客户端立即失败/走备用路径而不是干等数秒
        listener(QueryLog(question.domain, vpnService.getString(R.string.log_upstream_failed), hit = false, source = "servfail"))
        return DnsProtocol.buildEmptyAnswerMessage(query, question, DnsProtocol.RCODE_SERVFAIL)
    }

    // ----------------------------- 上游转发 -----------------------------

    /**
     * 上游转发策略（修复「单台上游抖动导致整站不可达」）：
     * 1. DoH（若启用）与全部 UDP 上游**并发竞速**，首个有效响应胜出（避免逐台串行超时）；
     * 2. 首轮全部失败且当前仅用了一种协议时，做一次跨模式重试（DoH↔UDP）；
     * 3. 确无可用响应才返回 null，由调用方快速返回 SERVFAIL。
     *
     * 竞速期间产生的多余 socket 在分出胜负后立即关闭，避免资源堆积。
     */
    private fun forwardUpstream(query: ByteArray, domain: String): ByteArray? {
        val udpServers = settings.upstreamServerList()
        val useDoh = settings.upstreamMode == SettingsStore.UpstreamMode.DOH && dohClient != null

        // 本次查询专属的 socket 集合。
        // 不能用共享集合：多条查询并发进行时，A 查询"竞速结束"会把 B 查询
        // 仍在等待响应的 socket 一起关掉，导致 B 无谓失败。
        val sockets = Collections.newSetFromMap(ConcurrentHashMap<DatagramSocket, Boolean>())
        try {
            val tasks = buildList<() -> ByteArray?> {
                if (useDoh) {
                    val client = dohClient
                    add { client?.query(query)?.takeIf { isValidResponse(query, it) } }
                }
                udpServers.forEach { server -> add { forwardUdpOnce(query, server, sockets) } }
            }

            race(tasks)?.let { winner ->
                stats.addForwarded(domain, fromCache = false, viaDoh = useDoh)
                return winner
            }

            // 阶段二：跨模式重试（仅当首轮只用了一半能力时才有意义）
            val retry = if (useDoh) {
                udpServers.firstNotNullOfOrNull { forwardUdpOnce(query, it, sockets) }
            } else {
                dohClient?.query(query)?.takeIf { isValidResponse(query, it) }
            }
            if (retry != null) {
                // 阶段二的成败归属与首轮相反：首轮走 UDP 失败、DoH 成功时应记为 viaDoh
                stats.addForwarded(domain, fromCache = false, viaDoh = !useDoh)
                return retry
            }
        } finally {
            // 无论胜负，本次查询创建的所有 socket 一律回收
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
        Log.w(TAG, "all upstream DNS failed")
        return null
    }

    /**
     * 真正的并发竞速：任意任务先返回有效结果就立刻胜出，其余任务取消。
     *
     * 用 [ExecutorCompletionService] 而不是逐个 `Future.get(timeout)`——后者是
     * 串行等待，总耗时会被累加成 `N × 超时`，慢的上游会把整条查询拖死。
     * 整体还有一道 [UPSTREAM_TIMEOUT_MS] 总预算兜底。
     */
    private fun race(tasks: List<() -> ByteArray?>): ByteArray? {
        if (tasks.isEmpty()) return null
        if (tasks.size == 1) return tasks[0]()

        val completion = ExecutorCompletionService<ByteArray?>(upstreamExecutor)
        val futures = tasks.map { completion.submit(Callable { it() }) }
        return try {
            val deadlineNs = System.nanoTime() + UPSTREAM_TIMEOUT_MS * 1_000_000L
            var pending = futures.size
            while (pending > 0) {
                val remainingMs = (deadlineNs - System.nanoTime()) / 1_000_000L
                if (remainingMs <= 0) break
                val future = runCatching {
                    completion.poll(remainingMs, TimeUnit.MILLISECONDS)
                }.getOrNull() ?: break
                pending--
                val value = runCatching { future.get() }.getOrNull()
                if (value != null) return value
            }
            null
        } finally {
            futures.forEach { runCatching { it.cancel(true) } }
        }
    }

    private fun forwardUdpOnce(
        query: ByteArray,
        server: String,
        sockets: MutableSet<DatagramSocket>
    ): ByteArray? {
        val socket = DatagramSocket()
        sockets.add(socket)
        try {
            if (!vpnService.protect(socket)) {
                Log.w(TAG, "protect failed for upstream $server")
                return null
            }
            socket.soTimeout = UPSTREAM_TIMEOUT_MS
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
            val buf = ByteArray(4096)
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            val data = resp.data.copyOf(resp.length)
            return if (isValidResponse(query, data)) data else null
        } catch (e: IOException) {
            Log.w(TAG, "upstream $server failed: ${e.message}")
            return null
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    /** 校验上游响应：事务 ID 一致且 QR=1，避免错包/串包被当作答案返回。 */
    private fun isValidResponse(query: ByteArray, resp: ByteArray): Boolean =
        resp.size >= 12 && query.size >= 2 &&
            resp[0] == query[0] && resp[1] == query[1] &&
            (resp[2].toInt() and 0x80) != 0

    /** 只缓存有正向答案的响应；NXDOMAIN/空答案不缓存，避免负缓存干扰排障。 */
    private fun isCacheable(dns: ByteArray): Boolean =
        dns.size >= 12 && DnsProtocol.firstARecordIp(dns) != null

    companion object {
        private const val TAG = "DnsInterceptor"
        private const val UPSTREAM_TIMEOUT_MS = 1_200

        /** TUN MTU（与 DnsVpnService 一致），超过的 UDP 响应必须截断。 */
        const val MAX_UDP_PACKET_BYTES = 1500

        /** 屏蔽动作返回的空地址（AAAA 查询会因类型不匹配自动变 NODATA）。 */
        private const val BLOCK_IP = "0.0.0.0"
    }
}
