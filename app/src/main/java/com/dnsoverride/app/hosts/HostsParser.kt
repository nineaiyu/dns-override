package com.dnsoverride.app.hosts

/**
 * 解析标准 hosts 文件格式（与 /etc/hosts 兼容）。
 *
 * 支持的行格式：
 * - `IP domain1 [domain2 ...]  # 可选注释`
 * - `#` 开头的行和空行被跳过
 * - 行内 `#` 后的内容视为注释
 */
object HostsParser {

    data class ParsedRule(val domain: String, val ip: String, val note: String = "")

    fun parse(text: String): List<ParsedRule> {
        val out = mutableListOf<ParsedRule>()
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) {
                // 单独一行域名（订阅/拦截列表常见格式）→ 按 0.0.0.0 屏蔽处理
                val only = parts[0]
                if (looksLikeDomain(only)) out.add(ParsedRule(only.lowercase(), "0.0.0.0"))
                return@forEach
            }
            val ip = parts[0]
            if (!isValidIp(ip)) return@forEach
            for (i in 1 until parts.size) {
                val domain = parts[i].trim().lowercase()
                if (domain.isEmpty()) continue
                out.add(ParsedRule(domain, ip))
            }
        }
        return out
    }

    /** 粗略判断是否形如域名（含点、含字母、无冒号、长度合理，排除裸 IP）。 */
    private fun looksLikeDomain(token: String): Boolean =
        token.length in 4..253 &&
            token.contains('.') &&
            !token.contains(':') &&
            !token.startsWith('.') &&
            token.any { it.isLetter() || it == '_' } &&
            token.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }

    /** 是否为广告拦截风格（IP 是 0.0.0.0 / 127.0.0.1）。 */
    fun isAdBlockStyle(ip: String): Boolean =
        ip == "0.0.0.0" || ip == "127.0.0.1"

    private fun isValidIp(ip: String): Boolean = runCatching {
        val parts = ip.split(".")
        if (parts.size == 4) {
            parts.all { it.toIntOrNull() in 0..255 }
        } else {
            ip.contains(":") && ip.length <= 45
        }
    }.getOrDefault(false)
}
