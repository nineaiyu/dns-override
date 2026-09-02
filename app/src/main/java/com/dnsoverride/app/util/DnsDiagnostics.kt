package com.dnsoverride.app.util

import android.content.Context
import android.net.VpnService
import com.dnsoverride.app.doh.DohClient
import com.dnsoverride.app.doh.DohProviders
import com.dnsoverride.app.model.RuleAction
import com.dnsoverride.app.service.DnsVpnService
import com.dnsoverride.app.store.RuleStore
import com.dnsoverride.app.store.SettingsStore
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * DNS 诊断工具：规则命中情况 + 各上游连通性/耗时测量。
 * 帮助用户自助排查「开了 VPN 某些网站打不开」到底是规则、缓存还是上游的问题。
 *
 * 所有方法都是阻塞的网络操作，调用方需在 IO 线程执行。
 */
object DnsDiagnostics {

    /** 对 [domain] 做一次全路径检查，返回人类可读的诊断行。 */
    fun resolveReport(context: Context, domain: String): List<String> {
        val settings = SettingsStore.get(context)
        val lines = mutableListOf<String>()

        lines += "VPN 状态: ${DnsVpnService.STATE.name}"

        val rule = RuleStore.get(context).getAllEnabledRules().firstOrNull { it.matches(domain) }
        lines += when (rule?.effectiveAction()) {
            null -> "规则: 未命中（走上游）"
            RuleAction.OVERRIDE -> "规则: 覆盖 → ${rule.ip}"
            RuleAction.BLOCK -> "规则: 屏蔽（${if (settings.blockModeNxdomain) "NXDOMAIN" else "0.0.0.0"}）"
            RuleAction.DIRECT -> "规则: 直连白名单（强制走上游）"
        }
        if (settings.blockAaaa) lines += "全局: 屏蔽 AAAA 已开启（IPv6 查询返回 NODATA）"

        val query = DnsProtocol.buildQueryMessage(domain)
        lines += ""

        settings.upstreamServerList().forEach { server ->
            val (ok, ms, ip) = probeUdp(query, server)
            lines += if (ok) "UDP $server: ${ms}ms → $ip"
            else "UDP $server: 失败（超时或不可达）"
        }

        if (settings.upstreamMode == SettingsStore.UpstreamMode.DOH) {
            val provider = DohProviders.byUrl(settings.dohProviderUrl) ?: DohProviders.AliDNS
            val start = System.currentTimeMillis()
            val resp = runCatching { DohClient.forProvider(provider).query(query) }.getOrNull()
            val ms = System.currentTimeMillis() - start
            lines += if (resp != null && DnsProtocol.firstARecordIp(resp) != null) {
                "DoH ${provider.name}: ${ms}ms → ${DnsProtocol.firstARecordIp(resp)}"
            } else {
                "DoH ${provider.name}: 失败（${ms}ms）"
            }
        }
        return lines
    }

    /** 只测各上游连通性（用固定域名），返回诊断行。 */
    fun upstreamReport(context: Context): List<String> {
        return resolveReport(context, "example.com")
            .filterNot { it.startsWith("规则:") || it.startsWith("全局:") }
    }

    private data class ProbeResult(val ok: Boolean, val ms: Long, val ip: String?)

    private fun probeUdp(query: ByteArray, server: String): ProbeResult {
        val socket = DatagramSocket()
        return try {
            // VPN 运行时绕过 TUN 直测真实网络；未运行时本来就不经过 TUN
            val vpn: VpnService? = DnsVpnService.activeInstance
            if (vpn == null || vpn.protect(socket)) {
                socket.soTimeout = 2000
                val start = System.currentTimeMillis()
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName(server), 53))
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                val ms = System.currentTimeMillis() - start
                val dns = resp.data.copyOf(resp.length)
                val ip = DnsProtocol.firstARecordIp(dns)
                if (dns.size >= 12 && ip != null) ProbeResult(true, ms, ip) else ProbeResult(false, ms, null)
            } else {
                ProbeResult(false, 0, null)
            }
        } catch (e: Exception) {
            ProbeResult(false, 0, null)
        } finally {
            runCatching { socket.close() }
        }
    }
}
