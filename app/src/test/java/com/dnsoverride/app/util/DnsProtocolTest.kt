package com.dnsoverride.app.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证 DNS 响应包构造的正确性。
 *
 * 核心回归点：之前 buildResponse 没有交换 UDP 端口，
 * 导致响应 src=客户端端口、dst=53（反了），OS 丢弃，客户端"网络连接失败"。
 */
class DnsProtocolTest {

    @Test
    fun buildResponse_swapsPortsAndIps_correctly() {
        val request = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),      // VPN 虚拟地址
            srcPort = 54321,                        // 客户端随机端口
            dstIp = byteArrayOf(8, 8, 8, 8),       // DNS 服务器
            dstPort = 53,
            domain = "example.test",
            txId = 0x1234
        )

        val dnsOffset = 20 + 8 // IP(20) + UDP(8)
        val question = DnsProtocol.parseQuestion(request, dnsOffset)
        assertNotNull(question)
        assertEquals("example.test", question!!.domain)
        assertEquals(DnsProtocol.QTYPE_A, question.qtype)

        val response = DnsProtocol.buildResponse(request, dnsOffset, question, "192.168.0.200")

        // 1. UDP 端口必须交换：响应 src=53, dst=54321
        val respSrcPort = IpPacket.srcPort(response)
        val respDstPort = IpPacket.dstPort(response)
        assertEquals("响应 src port 应为 53", 53, respSrcPort)
        assertEquals("响应 dst port 应为客户端源端口 54321", 54321, respDstPort)

        // 2. IP 地址必须交换：响应 src=8.8.8.8, dst=10.0.0.2
        val respSrcIp = IpPacket.srcIp(response)
        val respDstIp = IpPacket.dstIp(response)
        assertArrayEquals("响应 src IP 应为 8.8.8.8", byteArrayOf(8, 8, 8, 8), respSrcIp)
        assertArrayEquals("响应 dst IP 应为 10.0.0.2", byteArrayOf(10, 0, 0, 2), respDstIp)

        // 3. IP 校验和必须有效
        assertTrue("IP 校验和应有效", isValidIpChecksum(response))

        // 4. DNS Transaction ID 必须匹配
        val dnsStart = 20 + 8
        val respTxId = ((response[dnsStart].toInt() and 0xff) shl 8) or
            (response[dnsStart + 1].toInt() and 0xff)
        assertEquals(0x1234, respTxId)

        // 5. DNS QR 位必须为 1（响应）
        val flagsHi = response[dnsStart + 2].toInt() and 0xff
        assertTrue("QR 位应为 1（响应包）", (flagsHi and 0x80) != 0)

        // 6. ANCOUNT 必须为 1
        val anCount = ((response[dnsStart + 6].toInt() and 0xff) shl 8) or
            (response[dnsStart + 7].toInt() and 0xff)
        assertEquals(1, anCount)

        // 7. Answer 段的 RDATA 必须是 192.168.0.200
        // 找到 Answer 段：跳过 DNS header(12) + Question 段
        val questionLen = question.questionEnd - (dnsOffset + 12)
        var p = dnsStart + 12 + questionLen
        p += 2 // NAME (压缩指针)
        p += 2 // TYPE
        p += 2 // CLASS
        p += 4 // TTL
        val rdlen = ((response[p].toInt() and 0xff) shl 8) or
            (response[p + 1].toInt() and 0xff)
        assertEquals(4, rdlen)
        p += 2
        assertArrayEquals(
            byteArrayOf(192.toByte(), 168.toByte(), 0, 200.toByte()),
            response.copyOfRange(p, p + 4)
        )
    }

    @Test
    fun buildNodataResponse_swapsPortsAndIps_correctly() {
        val request = buildDnsQueryPacket(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 12345,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 53,
            domain = "example.com",
            txId = 0xABCD
        )

        val dnsOffset = 20 + 8
        val question = DnsProtocol.parseQuestion(request, dnsOffset)!!
        val response = DnsProtocol.buildNodataResponse(request, dnsOffset, question)

        // 端口交换
        assertEquals(53, IpPacket.srcPort(response))
        assertEquals(12345, IpPacket.dstPort(response))

        // IP 交换
        assertArrayEquals(byteArrayOf(8, 8, 8, 8), IpPacket.srcIp(response))
        assertArrayEquals(byteArrayOf(10, 0, 0, 2), IpPacket.dstIp(response))

        // ANCOUNT=0 (NODATA)
        val dnsStart = 20 + 8
        val anCount = ((response[dnsStart + 6].toInt() and 0xff) shl 8) or
            (response[dnsStart + 7].toInt() and 0xff)
        assertEquals(0, anCount)

        // 校验和有效
        assertTrue(isValidIpChecksum(response))
    }

    // ------------------------- 报文级构造（UDP/TCP 共用） -------------------------

    @Test
    fun buildOverrideMessage_basic_a_response() {
        val query = DnsProtocol.buildQueryMessage("example.com", txId = 0x2468)
        val question = DnsProtocol.parseQuestionMessage(query)!!

        val msg = DnsProtocol.buildOverrideMessage(query, question, "192.168.0.200")

        assertEquals(0x2468, msg[0].toInt() and 0xff shl 8 or (msg[1].toInt() and 0xff))
        // QR=1
        assertTrue((msg[2].toInt() and 0x80) != 0)
        // ANCOUNT=1
        assertEquals(1, (msg[6].toInt() and 0xff) shl 8 or (msg[7].toInt() and 0xff))
        assertEquals("192.168.0.200", DnsProtocol.firstARecordIp(msg))
    }

    @Test
    fun buildOverrideMessage_v4_rule_aaaa_query_returns_nodata() {
        val query = DnsProtocol.buildQueryMessage("example.com", qtype = DnsProtocol.QTYPE_AAAA)
        val question = DnsProtocol.parseQuestionMessage(query)!!
        val msg = DnsProtocol.buildOverrideMessage(query, question, "1.2.3.4")
        // AAAA 查询 + v4 规则 → NODATA（ANCOUNT=0, RCODE=0），触发客户端回退 A
        assertEquals(0, (msg[6].toInt() and 0xff) shl 8 or (msg[7].toInt() and 0xff))
        assertEquals(0, msg[3].toInt() and 0x0f)
    }

    @Test
    fun buildOverrideMessage_v6_rule_aaaa_query_returns_aaaa() {
        val query = DnsProtocol.buildQueryMessage("example.com", qtype = DnsProtocol.QTYPE_AAAA)
        val question = DnsProtocol.parseQuestionMessage(query)!!
        val msg = DnsProtocol.buildOverrideMessage(query, question, "::1")
        assertEquals(1, (msg[6].toInt() and 0xff) shl 8 or (msg[7].toInt() and 0xff))
        // Answer TYPE = AAAA(28)：NAME(2字节压缩指针) 之后
        val questionLen = question.questionEnd - 12
        val typeOff = 12 + questionLen + 2
        assertEquals(
            DnsProtocol.QTYPE_AAAA,
            (msg[typeOff].toInt() and 0xff) shl 8 or (msg[typeOff + 1].toInt() and 0xff)
        )
    }

    @Test
    fun buildEmptyAnswerMessage_rcodes() {
        val query = DnsProtocol.buildQueryMessage("example.com", txId = 0x1111)
        val question = DnsProtocol.parseQuestionMessage(query)!!

        val nx = DnsProtocol.buildEmptyAnswerMessage(query, question, DnsProtocol.RCODE_NXDOMAIN)
        assertEquals(DnsProtocol.RCODE_NXDOMAIN, nx[3].toInt() and 0x0f)
        assertEquals(0, (nx[6].toInt() and 0xff) shl 8 or (nx[7].toInt() and 0xff))
        assertEquals(0x1111, (nx[0].toInt() and 0xff) shl 8 or (nx[1].toInt() and 0xff))

        val servfail = DnsProtocol.buildEmptyAnswerMessage(query, question, DnsProtocol.RCODE_SERVFAIL)
        assertEquals(DnsProtocol.RCODE_SERVFAIL, servfail[3].toInt() and 0x0f)
    }

    @Test
    fun edns_query_gets_opt_in_response() {
        val query = DnsProtocol.buildQueryMessage("example.com")
        // 附加 OPT RR（11 字节）：NAME(root,1) + TYPE(41,2) + CLASS(1232,2) + TTL(0,4) + RDLEN(0,2)
        val withOpt = query + byteArrayOf(
            0x00, 0x00, 41.toByte(), 0x04, 0xD0.toByte(), 0, 0, 0, 0, 0, 0
        )
        // ARCOUNT +1
        withOpt[10] = 1
        withOpt[11] = 1

        assertTrue(DnsProtocol.hasEdnsOpt(withOpt))
        assertFalse(DnsProtocol.hasEdnsOpt(query))

        val question = DnsProtocol.parseQuestionMessage(withOpt)!!
        val msg = DnsProtocol.buildOverrideMessage(withOpt, question, "1.2.3.4")
        // 响应应回带 OPT（ARCOUNT=1，末尾 TYPE=41）
        assertEquals(1, (msg[10].toInt() and 0xff) shl 8 or (msg[11].toInt() and 0xff))
        assertEquals(
            DnsProtocol.QTYPE_OPT,
            (msg[msg.size - 10].toInt() and 0xff) shl 8 or (msg[msg.size - 9].toInt() and 0xff)
        )
        assertEquals("1.2.3.4", DnsProtocol.firstARecordIp(msg))
    }

    @Test
    fun truncate_sets_tc_and_keeps_record_boundaries() {
        val query = DnsProtocol.buildQueryMessage("example.com")
        val question = DnsProtocol.parseQuestionMessage(query)!!
        val questionLen = question.questionEnd - 12

        // 构造一个 60 条 A 记录的大响应（每条 16 字节）
        val n = 60
        val big = ByteArray(12 + questionLen + n * 16)
        System.arraycopy(query, 0, big, 0, 12 + questionLen)
        big[6] = ((n shr 8) and 0xff).toByte(); big[7] = (n and 0xff).toByte()
        var p = 12 + questionLen
        repeat(n) {
            big[p++] = 0xc0.toByte(); big[p++] = 0x0c
            big[p++] = 0; big[p++] = 1
            big[p++] = 0; big[p++] = 1
            big[p++] = 0; big[p++] = 0; big[p++] = 0; big[p++] = 0
            big[p++] = 0; big[p++] = 4
            big[p++] = 1; big[p++] = 2; big[p++] = 3; big[p++] = 4
        }

        val maxBytes = 300
        val truncated = DnsProtocol.truncateDnsMessage(big, maxBytes)
        assertTrue("截断后不超过上限", truncated.size <= maxBytes)
        // TC=1
        assertTrue((truncated[2].toInt() and 0x02) != 0)
        // 保留的记录数与长度一致
        val kept = (truncated[6].toInt() and 0xff) shl 8 or (truncated[7].toInt() and 0xff)
        assertEquals(truncated.size, 12 + questionLen + kept * 16)
        assertTrue("应保留部分而非全部记录", kept in 1 until n)
        assertEquals("1.2.3.4", DnsProtocol.firstARecordIp(truncated))
    }

    @Test
    fun truncate_noop_when_already_small() {
        val query = DnsProtocol.buildQueryMessage("example.com")
        val question = DnsProtocol.parseQuestionMessage(query)!!
        val msg = DnsProtocol.buildOverrideMessage(query, question, "1.2.3.4")
        assertTrue(DnsProtocol.truncateDnsMessage(msg, 1500) === msg)
    }

    @Test
    fun buildQueryMessage_roundtrip_parse() {
        val query = DnsProtocol.buildQueryMessage("www.example.com", qtype = DnsProtocol.QTYPE_AAAA, txId = 0xBEEF)
        val q = DnsProtocol.parseQuestionMessage(query)!!
        assertEquals("www.example.com", q.domain)
        assertEquals(DnsProtocol.QTYPE_AAAA, q.qtype)
        assertEquals(0xBEEF, (query[0].toInt() and 0xff) shl 8 or (query[1].toInt() and 0xff))
    }

    // ------------------------- 辅助函数 -------------------------

    /** 构造一个最小可解析的 DNS 查询 IP+UDP 包。 */
    private fun buildDnsQueryPacket(
        srcIp: ByteArray,
        srcPort: Int,
        dstIp: ByteArray,
        dstPort: Int,
        domain: String,
        txId: Int
    ): ByteArray {
        // DNS payload: header(12) + question
        val labels = domain.split(".")
        val qname = mutableListOf<Byte>()
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            qname.add(bytes.size.toByte())
            qname.addAll(bytes.toList())
        }
        qname.add(0) // terminator
        // QTYPE=A(1) + QCLASS=IN(1)
        qname.add(0); qname.add(1)
        qname.add(0); qname.add(1)

        val dnsBytes = qname.toByteArray()
        val dnsLen = 12 + dnsBytes.size
        val udpLen = 8 + dnsLen
        val totalLen = 20 + udpLen

        val packet = ByteArray(totalLen)
        // IP header
        packet[0] = 0x45 // ver=4, ihl=5
        packet[2] = ((totalLen shr 8) and 0xff).toByte()
        packet[3] = (totalLen and 0xff).toByte()
        packet[8] = 64 // TTL
        packet[9] = 17 // proto=UDP
        System.arraycopy(srcIp, 0, packet, 12, 4)
        System.arraycopy(dstIp, 0, packet, 16, 4)
        IpPacket.recomputeIpChecksum(packet)

        // UDP header
        packet[20] = ((srcPort shr 8) and 0xff).toByte()
        packet[21] = (srcPort and 0xff).toByte()
        packet[22] = ((dstPort shr 8) and 0xff).toByte()
        packet[23] = (dstPort and 0xff).toByte()
        packet[24] = ((udpLen shr 8) and 0xff).toByte()
        packet[25] = (udpLen and 0xff).toByte()
        packet[26] = 0; packet[27] = 0 // checksum=0

        // DNS header
        packet[28] = ((txId shr 8) and 0xff).toByte()
        packet[29] = (txId and 0xff).toByte()
        packet[30] = 0x01 // flags: RD=1
        packet[31] = 0x00
        packet[32] = 0; packet[33] = 1 // QDCOUNT=1
        packet[34] = 0; packet[35] = 0 // ANCOUNT=0
        packet[36] = 0; packet[37] = 0 // NSCOUNT=0
        packet[38] = 0; packet[39] = 0 // ARCOUNT=0

        // DNS question
        System.arraycopy(dnsBytes, 0, packet, 40, dnsBytes.size)

        return packet
    }

    /** 验证 IP 头校验和是否正确（对整个 IP 头按 16-bit 反码求和，结果应为 0xFFFF）。 */
    private fun isValidIpChecksum(packet: ByteArray): Boolean {
        val ihl = IpPacket.headerLength(packet)
        var sum = 0L
        var i = 0
        while (i < ihl) {
            sum += ((packet[i].toInt() and 0xff) shl 8 or (packet[i + 1].toInt() and 0xff)).toLong()
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        return (sum and 0xffff) == 0xffffL
    }
}
