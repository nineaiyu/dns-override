package com.dnsoverride.app.util

/**
 * DNS 报文编解码（RFC 1035 / RFC 6891）。
 *
 * 分两层 API，供 UDP 与 TCP 两种承载复用：
 * - 报文级（纯 DNS message 字节）：[buildOverrideMessage] / [buildEmptyAnswerMessage] /
 *   [truncateDnsMessage] / [hasEdnsOpt] 等；
 * - IP 包级（IP+UDP）：[buildResponse] / [buildNodataResponse] / [wrapUdpMessage]，
 *   在报文级之上包装，Question 的偏移量约定见各函数注释。
 */
object DnsProtocol {

    const val QTYPE_A = 1
    const val QTYPE_AAAA = 28
    const val QTYPE_CNAME = 5
    const val QTYPE_MX = 15
    const val QTYPE_OPT = 41

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    /** 对外通告的 EDNS UDP 缓冲区大小（写入 OPT RR 的 CLASS 字段）。 */
    const val EDNS_UDP_SIZE = 1232

    data class Question(
        val domain: String,
        val qtype: Int,
        val qclass: Int,
        /** Question 段起始偏移（相对传入的 DNS 报文字节数组，不含 IP/UDP 头）。 */
        val questionOffset: Int,
        /** Question 段结束偏移（不含），即下一个字节是 Answer 段起点。 */
        val questionEnd: Int
    )

    /**
     * 解析 DNS 查询报文（IP+UDP 包）中的第一条 Question。
     * 返回的偏移量为「包内偏移」（含 IP/UDP 头），与 [buildResponse] 等 IP 包级 API 配套。
     */
    fun parseQuestion(packet: ByteArray, dnsOffset: Int): Question? =
        parseQuestionMessage(packet, dnsOffset)

    /**
     * 解析纯 DNS 报文（不含 IP/UDP 头）中的第一条 Question，偏移量相对报文自身。
     * [base] 用于在完整 IP 包内直接解析（跳过 IP+UDP 头），返回的偏移量仍相对 [dns] 数组。
     */
    fun parseQuestionMessage(dns: ByteArray, base: Int = 0): Question? {
        if (dns.size < base + 12) return null
        val qdCount = u16(dns, base + 4)
        if (qdCount < 1) return null

        var pos = base + 12
        val labels = mutableListOf<String>()
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xff
            if (len == 0) {
                pos++
                break
            }
            if (len and 0xc0 != 0) return null // Question 中不应出现压缩指针
            if (pos + 1 + len > dns.size) return null
            labels.add(String(dns, pos + 1, len, Charsets.US_ASCII))
            pos += len + 1
        }
        if (pos + 4 > dns.size) return null
        val qtype = u16(dns, pos)
        val qclass = u16(dns, pos + 2)
        pos += 4

        return Question(
            domain = labels.joinToString(".").lowercase(),
            qtype = qtype,
            qclass = qclass,
            questionOffset = base + 12,
            questionEnd = pos
        )
    }

    // ----------------------------- 报文级响应构造 -----------------------------

    /**
     * 构造覆盖响应报文（Answer = [overrideIp]）。
     *
     * - 规则 IP 与查询类型不匹配（如 v4 规则遇到 AAAA 查询）→ 返回 NODATA，
     *   触发客户端回退到 A 记录。
     * - 查询带 EDNS OPT 时回带一个最小 OPT RR（部分严格实现的 resolver 需要）。
     *
     * @param query 原始查询报文（纯 DNS 字节）
     * @param question [parseQuestionMessage] 的结果（偏移相对 [query]）
     */
    fun buildOverrideMessage(
        query: ByteArray,
        question: Question,
        overrideIp: String,
        ttl: Int = 0
    ): ByteArray {
        val ipBytes = parseIpBytes(overrideIp)
            ?: throw IllegalArgumentException("invalid ip: $overrideIp")
        val isV6 = ipBytes.size == 16
        if ((isV6 && question.qtype != QTYPE_AAAA) || (!isV6 && question.qtype != QTYPE_A)) {
            return buildEmptyAnswerMessage(query, question, RCODE_NOERROR)
        }

        val questionLen = question.questionEnd - 12
        val answerLen = 2 + 2 + 2 + 4 + 2 + ipBytes.size
        val withOpt = hasEdnsOpt(query)
        val out = ByteArray(12 + questionLen + answerLen + if (withOpt) OPT_RR_LEN else 0)

        writeHeader(out, query, rcode = RCODE_NOERROR, anCount = 1, arCount = if (withOpt) 1 else 0)
        System.arraycopy(query, 12, out, 12, questionLen)

        var p = 12 + questionLen
        out[p++] = 0xc0.toByte(); out[p++] = 0x0c // NAME 压缩指针指向 QNAME
        val typeVal = if (isV6) QTYPE_AAAA else QTYPE_A
        out[p++] = ((typeVal shr 8) and 0xff).toByte(); out[p++] = (typeVal and 0xff).toByte()
        out[p++] = 0; out[p++] = 1 // CLASS IN
        // TTL 默认 0：规则响应不进入系统 resolver 缓存，规则启停/切换实时生效
        out[p++] = ((ttl shr 24) and 0xff).toByte()
        out[p++] = ((ttl shr 16) and 0xff).toByte()
        out[p++] = ((ttl shr 8) and 0xff).toByte()
        out[p++] = (ttl and 0xff).toByte()
        out[p++] = ((ipBytes.size shr 8) and 0xff).toByte()
        out[p++] = (ipBytes.size and 0xff).toByte()
        System.arraycopy(ipBytes, 0, out, p, ipBytes.size)
        if (withOpt) writeOptRr(out, p + ipBytes.size)
        return out
    }

    /**
     * 构造零 Answer 响应：rcode=0 即 NODATA，rcode=3 即 NXDOMAIN（屏蔽），
     * rcode=2 即 SERVFAIL（上游全部失败时快速失败，避免客户端干等超时）。
     */
    fun buildEmptyAnswerMessage(query: ByteArray, question: Question, rcode: Int): ByteArray {
        val questionLen = question.questionEnd - 12
        val withOpt = hasEdnsOpt(query)
        val out = ByteArray(12 + questionLen + if (withOpt) OPT_RR_LEN else 0)
        writeHeader(out, query, rcode, anCount = 0, arCount = if (withOpt) 1 else 0)
        System.arraycopy(query, 12, out, 12, questionLen)
        if (withOpt) writeOptRr(out, 12 + questionLen)
        return out
    }

    /**
     * 把 DNS 响应报文截断到 [maxDnsBytes] 以内（记录边界对齐）并置 TC 位，
     * 客户端会自动改用 TCP 重试。用于上游响应超过 TUN MTU、无法作为单个 UDP 包写回的场景。
     *
     * 报文异常时原样返回（调用方自行决定丢弃）。
     */
    fun truncateDnsMessage(dns: ByteArray, maxDnsBytes: Int): ByteArray {
        if (dns.size <= maxDnsBytes) return dns
        if (dns.size < 12) return dns

        val qd = u16(dns, 4)
        var pos = 12
        repeat(qd) {
            pos = skipQuestion(dns, pos) ?: return dns
        }
        val questionEnd = pos

        // 收集三个区段（Answer/Authority/Additional）中每条记录的字节范围
        val sectionCounts = intArrayOf(u16(dns, 6), u16(dns, 8), u16(dns, 10))
        val ranges = mutableListOf<Triple<Int, Int, Int>>() // from, to, section
        var sec = 0
        for (count in sectionCounts) {
            repeat(count) {
                val start = pos
                pos = skipName(dns, pos) ?: return dns
                if (pos + 10 > dns.size) return dns
                pos += 10 + u16(dns, pos + 8)
                if (pos > dns.size) return dns
                ranges.add(Triple(start, pos, sec))
            }
            sec++
        }

        val out = ByteArray(maxOf(maxDnsBytes, questionEnd))
        System.arraycopy(dns, 0, out, 0, 12)
        out[2] = (out[2].toInt() or 0x02).toByte() // TC=1
        System.arraycopy(dns, 12, out, 12, questionEnd)
        var w = questionEnd
        val kept = IntArray(3)
        for ((from, to, section) in ranges) {
            if (w + (to - from) > out.size) break
            System.arraycopy(dns, from, out, w, to - from)
            w += to - from
            kept[section]++
        }
        writeU16(out, 6, kept[0])
        writeU16(out, 8, kept[1])
        writeU16(out, 10, kept[2])
        return out.copyOf(w)
    }

    /** 查询/响应报文的 Additional 段是否包含 EDNS OPT 记录。报文异常返回 false。 */
    fun hasEdnsOpt(dns: ByteArray): Boolean {
        if (dns.size < 12) return false
        val qd = u16(dns, 4)
        var pos = 12
        repeat(qd) {
            pos = skipQuestion(dns, pos) ?: return false
        }
        val total = u16(dns, 6) + u16(dns, 8) + u16(dns, 10)
        repeat(total) {
            pos = skipName(dns, pos) ?: return false
            if (pos + 10 > dns.size) return false
            if (u16(dns, pos) == QTYPE_OPT) return true
            pos += 10 + u16(dns, pos + 8)
            if (pos > dns.size) return false
        }
        return false
    }

    /** 构造一条标准查询报文（用于诊断工具直连上游测试）。 */
    fun buildQueryMessage(domain: String, qtype: Int = QTYPE_A, txId: Int = 0x4D2D): ByteArray {
        val qname = encodeName(domain)
        val out = ByteArray(12 + qname.size + 4)
        writeU16(out, 0, txId)
        out[2] = 0x01 // RD=1
        writeU16(out, 4, 1)
        System.arraycopy(qname, 0, out, 12, qname.size)
        writeU16(out, 12 + qname.size, qtype)
        writeU16(out, 12 + qname.size + 2, 1) // CLASS IN
        return out
    }

    /** 提取响应报文中第一条 A 记录的 IP（用于日志与诊断展示），失败返回 null。 */
    fun firstARecordIp(dns: ByteArray): String? {
        try {
            if (dns.size < 12) return null
            val qd = u16(dns, 4)
            val an = u16(dns, 6)
            var pos = 12
            repeat(qd) {
                pos = skipQuestion(dns, pos) ?: return null
            }
            repeat(an) {
                pos = skipName(dns, pos) ?: return null
                if (pos + 10 > dns.size) return null
                val type = u16(dns, pos)
                val rdlen = u16(dns, pos + 8)
                pos += 10
                if (type == QTYPE_A && rdlen == 4 && pos + 4 <= dns.size) {
                    return "${dns[pos].toInt() and 0xff}.${dns[pos + 1].toInt() and 0xff}." +
                        "${dns[pos + 2].toInt() and 0xff}.${dns[pos + 3].toInt() and 0xff}"
                }
                pos += rdlen
            }
        } catch (_: Exception) {
        }
        return null
    }

    // ----------------------------- IP 包级包装 -----------------------------

    /** 构造完整 IP+UDP 响应包（IP 包级 API，测试兼容入口）。 */
    fun buildResponse(
        request: ByteArray,
        dnsOffset: Int,
        question: Question,
        overrideIp: String
    ): ByteArray {
        val dnsMsg = request.copyOfRange(dnsOffset, request.size)
        val msgQuestion = question.copy(
            questionOffset = question.questionOffset - dnsOffset,
            questionEnd = question.questionEnd - dnsOffset
        )
        return wrapUdpMessage(request, buildOverrideMessage(dnsMsg, msgQuestion, overrideIp))
    }

    /** 构造完整 IP+UDP NODATA 响应包（IP 包级 API，测试兼容入口）。 */
    fun buildNodataResponse(
        request: ByteArray,
        dnsOffset: Int,
        question: Question
    ): ByteArray {
        val dnsMsg = request.copyOfRange(dnsOffset, request.size)
        val msgQuestion = question.copy(
            questionOffset = question.questionOffset - dnsOffset,
            questionEnd = question.questionEnd - dnsOffset
        )
        return wrapUdpMessage(request, buildEmptyAnswerMessage(dnsMsg, msgQuestion, RCODE_NOERROR))
    }

    /**
     * 把 DNS 响应报文包装为可写入 TUN 的 IP+UDP 包（交换 IP/端口、重算校验和）。
     * [dnsMessage] 不会被修改。
     */
    fun wrapUdpMessage(requestPacket: ByteArray, dnsMessage: ByteArray): ByteArray {
        val ihl = IpPacket.headerLength(requestPacket)
        val udpLen = 8 + dnsMessage.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)
        System.arraycopy(requestPacket, 0, out, 0, ihl)
        // 交换 UDP 端口：响应 src=53, dst=客户端源端口
        out[ihl] = requestPacket[ihl + 2]
        out[ihl + 1] = requestPacket[ihl + 3]
        out[ihl + 2] = requestPacket[ihl]
        out[ihl + 3] = requestPacket[ihl + 1]
        out[ihl + 4] = ((udpLen shr 8) and 0xff).toByte()
        out[ihl + 5] = (udpLen and 0xff).toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0 // UDP checksum=0（IPv4 可选）
        System.arraycopy(dnsMessage, 0, out, ihl + 8, dnsMessage.size)
        IpPacket.swapIpEndpoints(out)
        out[2] = ((totalLen shr 8) and 0xff).toByte()
        out[3] = (totalLen and 0xff).toByte()
        out[8] = 64
        out[4] = 0; out[5] = 0
        out[6] = 0x40.toByte(); out[7] = 0
        IpPacket.recomputeIpChecksum(out)
        return out
    }

    // ----------------------------- TTL 改写 -----------------------------

    /**
     * 把 DNS 响应中所有 Answer / Authority / Additional 记录的 TTL 改为 [newTtl]。
     *
     * 用途：转发上游响应时压低 TTL，控制系统 resolver 的缓存时长——
     * 太长会导致启用规则后系统仍返回缓存的真实 IP，为 0 则系统完全不缓存、
     * 每次查询都要走上游（慢且放大上游故障），默认建议 10s 左右。
     *
     * @return true 表示成功修改；false 表示报文格式异常，未修改
     */
    fun rewriteTtl(dns: ByteArray, newTtl: Int): Boolean {
        if (dns.size < 12) return false
        val qdCount = u16(dns, 4)
        val anCount = u16(dns, 6)
        val nsCount = u16(dns, 8)
        val arCount = u16(dns, 10)
        val totalRecords = anCount + nsCount + arCount
        if (totalRecords == 0) return true

        var pos = 12
        repeat(qdCount) {
            pos = skipQuestion(dns, pos) ?: return false
        }

        val ttlBytes = byteArrayOf(
            ((newTtl shr 24) and 0xff).toByte(),
            ((newTtl shr 16) and 0xff).toByte(),
            ((newTtl shr 8) and 0xff).toByte(),
            (newTtl and 0xff).toByte()
        )
        repeat(totalRecords) {
            pos = skipName(dns, pos) ?: return false
            if (pos + 10 > dns.size) return false
            System.arraycopy(ttlBytes, 0, dns, pos + 4, 4)
            pos += 10 + u16(dns, pos + 8)
            if (pos > dns.size) return false
        }
        return true
    }

    // ----------------------------- 内部工具 -----------------------------

    private const val OPT_RR_LEN = 11 // NAME(1) + TYPE(2) + CLASS(2) + TTL(4) + RDLEN(2)

    private fun writeHeader(
        out: ByteArray,
        query: ByteArray,
        rcode: Int,
        anCount: Int,
        arCount: Int
    ) {
        out[0] = query[0]
        out[1] = query[1]
        // QR=1 + 保留请求 RD 位；RA=1 + rcode
        val rdBit = query[2].toInt() and 0x01
        out[2] = (0x80 or rdBit).toByte()
        out[3] = (0x80 or (rcode and 0x0f)).toByte()
        writeU16(out, 4, 1) // QDCOUNT
        writeU16(out, 6, anCount)
        writeU16(out, 8, 0)
        writeU16(out, 10, arCount)
    }

    private fun writeOptRr(out: ByteArray, off: Int) {
        out[off] = 0 // root NAME
        writeU16(out, off + 1, QTYPE_OPT)
        writeU16(out, off + 3, EDNS_UDP_SIZE)
        out[off + 5] = 0; out[off + 6] = 0; out[off + 7] = 0; out[off + 8] = 0 // TTL=0, DO=0
        writeU16(out, off + 9, 0)
    }

    private fun encodeName(domain: String): ByteArray {
        val out = mutableListOf<Byte>()
        val normalized = domain.trimEnd('.').lowercase()
        if (normalized.isNotEmpty()) {
            for (label in normalized.split('.')) {
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.add(bytes.size.toByte())
                out.addAll(bytes.toList())
            }
        }
        out.add(0)
        return out.toByteArray()
    }

    private fun skipQuestion(dns: ByteArray, start: Int): Int? {
        var pos = skipName(dns, start) ?: return null
        if (pos + 4 > dns.size) return null
        return pos + 4
    }

    private fun skipName(dns: ByteArray, start: Int): Int? {
        var pos = start
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xff
            if (len == 0) return pos + 1
            if (len and 0xc0 == 0xc0) return pos + 2
            if (len and 0xc0 != 0) return null
            pos += 1 + len
            if (pos > dns.size) return null
        }
        return null
    }

    /** 解析点分十进制 IPv4 或冒号分隔 IPv6，返回 4/16 字节数组，非法返回 null。 */
    private fun parseIpBytes(ip: String): ByteArray? = runCatching {
        java.net.InetAddress.getByName(ip).address
    }.getOrNull()

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)

    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v shr 8) and 0xff).toByte()
        buf[off + 1] = (v and 0xff).toByte()
    }
}
