package com.dnsoverride.app.doh

/**
 * 内置 DoH 服务商列表。
 */
data class DohProvider(val name: String, val url: String, val bootstrapIp: String)

object DohProviders {
    val AliDNS = DohProvider("AliDNS", "https://dns.alidns.com/dns-query", "223.5.5.5")
    val Cloudflare = DohProvider("Cloudflare", "https://cloudflare-dns.com/dns-query", "1.1.1.1")
    val Google = DohProvider("Google", "https://dns.google/dns-query", "8.8.8.8")
    val DNSPod = DohProvider("DNSPod", "https://doh.pub/dns-query", "119.29.29.29")

    val all = listOf(AliDNS, Cloudflare, Google, DNSPod)
    fun byUrl(url: String) = all.firstOrNull { it.url == url }
}
