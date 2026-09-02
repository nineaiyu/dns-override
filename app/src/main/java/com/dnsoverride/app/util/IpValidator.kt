package com.dnsoverride.app.util

import java.net.InetAddress

/**
 * 域名与 IP 格式校验工具。
 */
object IpValidator {

    private val DOMAIN_RE = Regex("^(\\*\\.)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+$")

    /** 域名或通配符域名是否合法（支持 `*.example.com`）。 */
    fun isValidDomain(d: String): Boolean =
        d.isNotBlank() && d.length <= 253 && DOMAIN_RE.matches(d)

    /** IPv4 或 IPv6 是否合法。 */
    fun isValidIp(ip: String): Boolean = runCatching {
        when {
            ip.contains(":") -> {
                // IPv6：InetAddress.getByName 会抛 UnknownHostException 表示非法
                InetAddress.getByName(ip)
                true
            }
            ip.contains(".") -> {
                // IPv4 必须是 4 段，避免 "1.2.3" 被 InetAddress 补全
                val parts = ip.split(".")
                parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }
            }
            else -> false
        }
    }.getOrDefault(false)
}
