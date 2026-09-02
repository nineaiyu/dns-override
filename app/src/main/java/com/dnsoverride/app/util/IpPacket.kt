package com.dnsoverride.app.util

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * IPv4 / TCP / UDP 报文解析与改写工具。
 *
 * 仅支持 IPv4（虚拟网卡未添加 IPv6 路由，强制 IPv4）。
 * 所有偏移量基于 [RFC 791](https://www.rfc-editor.org/rfc/rfc791) IPv4 头格式。
 */
object IpPacket {

    const val PROTO_ICMP = 1
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    /** IPv4 头长度（字节），按 IHL 字段计算。 */
    fun headerLength(packet: ByteArray): Int {
        require(packet.isNotEmpty()) { "empty packet" }
        val versionIhl = packet[0].toInt() and 0xff
        return (versionIhl and 0x0f) * 4
    }

    /** IP 协议号（如 6=TCP, 17=UDP）。 */
    fun protocol(packet: ByteArray): Int = packet[9].toInt() and 0xff

    fun srcIp(packet: ByteArray): ByteArray =
        packet.copyOfRange(12, 16)

    fun dstIp(packet: ByteArray): ByteArray =
        packet.copyOfRange(16, 20)

    fun srcPort(packet: ByteArray): Int {
        val ihl = headerLength(packet)
        return u16(packet, ihl)
    }

    fun dstPort(packet: ByteArray): Int {
        val ihl = headerLength(packet)
        return u16(packet, ihl + 2)
    }

    /** 交换源/目的 IP，并重算 IP 头校验和。 */
    fun swapIpEndpoints(packet: ByteArray) {
        // swap src/dst
        for (i in 0 until 4) {
            val tmp = packet[12 + i]
            packet[12 + i] = packet[16 + i]
            packet[16 + i] = tmp
        }
        recomputeIpChecksum(packet)
    }

    /** 交换 TCP/UDP 源/目的端口，并重算传输层校验和。 */
    fun swapTransportEndpoints(packet: ByteArray) {
        val ihl = headerLength(packet)
        // swap src/dst port (TCP 和 UDP 头前 4 字节布局相同)
        val tmp0 = packet[ihl]
        val tmp1 = packet[ihl + 1]
        packet[ihl] = packet[ihl + 2]
        packet[ihl + 1] = packet[ihl + 3]
        packet[ihl + 2] = tmp0
        packet[ihl + 3] = tmp1

        when (protocol(packet)) {
            PROTO_UDP -> {
                // UDP checksum 可选，置 0 表示未校验
                packet[ihl + 6] = 0
                packet[ihl + 7] = 0
            }
            PROTO_TCP -> recomputeTcpChecksum(packet)
        }
    }

    /** 重算 IPv4 头校验和。 */
    fun recomputeIpChecksum(packet: ByteArray) {
        val ihl = headerLength(packet)
        packet[10] = 0
        packet[11] = 0
        var sum = 0L
        var i = 0
        while (i < ihl) {
            sum += u16(packet, i).toLong()
            i += 2
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        val cksum = (sum.inv().toInt() and 0xffff)
        packet[10] = ((cksum shr 8) and 0xff).toByte()
        packet[11] = (cksum and 0xff).toByte()
    }

    /** 重算 TCP 校验和（含 IPv4 伪首部）。 */
    fun recomputeTcpChecksum(packet: ByteArray) {
        val ihl = headerLength(packet)
        val totalLen = u16(packet, 2)
        val tcpLen = totalLen - ihl
        // clear existing checksum
        packet[ihl + 16] = 0
        packet[ihl + 17] = 0

        var sum = 0L
        // pseudo header: src(4) + dst(4) + zero(1) + proto(1) + tcp-len(2)
        for (i in 12 until 20) sum += packet[i].toLong() and 0xff
        sum += PROTO_TCP.toLong()
        sum += tcpLen.toLong()

        var i = ihl
        // 按 16-bit 求和，奇数字节末尾补 0
        val end = ihl + tcpLen
        while (i < end - 1) {
            sum += u16(packet, i).toLong()
            i += 2
        }
        if (i < end) {
            sum += (packet[i].toLong() and 0xff) shl 8
        }
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        val cksum = (sum.inv().toInt() and 0xffff)
        packet[ihl + 16] = ((cksum shr 8) and 0xff).toByte()
        packet[ihl + 17] = (cksum and 0xff).toByte()
    }

    /** 构造一个 IP-in-TUN 回包的浅拷贝：交换端点并设置总长度。 */
    fun buildResponseTemplate(request: ByteArray, payloadLen: Int): ByteArray {
        val ihl = headerLength(request)
        val totalLen = ihl + payloadLen
        val out = request.copyOf(totalLen)
        swapIpEndpoints(out)
        // 写入新的总长度
        out[2] = ((totalLen shr 8) and 0xff).toByte()
        out[3] = (totalLen and 0xff).toByte()
        // Identification 设 0，避免与请求混淆（DF=1 不分片）
        out[4] = 0
        out[5] = 0
        out[6] = 0x40.toByte() // DF=1
        out[7] = 0
        out[8] = 64 // TTL
        recomputeIpChecksum(out)
        return out
    }

    /** 将 [ipBytes]（4 字节）转换为可读 IP 字符串。 */
    fun ipToString(ipBytes: ByteArray): String =
        InetAddress.getByAddress(ipBytes).hostAddress ?: "?"

    /**
     * 构造 ICMP Echo Reply（让 ping 已路由的 DNS IP 正常工作，便于排查）。
     * 输入非 Echo Request 返回 null。
     */
    fun buildIcmpEchoReply(request: ByteArray): ByteArray? {
        if (request.size < 28) return null
        if (protocol(request) != PROTO_ICMP) return null
        val ihl = headerLength(request)
        if ((request[ihl].toInt() and 0xff) != 8) return null // type != Echo Request
        val totalLen = u16(request, 2)
        if (totalLen <= ihl || totalLen > request.size) return null

        val out = request.copyOf(totalLen)
        swapIpEndpoints(out)
        out[ihl] = 0 // type = Echo Reply
        out[ihl + 2] = 0; out[ihl + 3] = 0 // 清零校验和后重算
        var sum = 0L
        var i = ihl
        while (i < out.size - 1) {
            sum += u16(out, i).toLong()
            i += 2
        }
        if (i < out.size) sum += (out[i].toLong() and 0xff) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xffff) + (sum shr 16)
        val cksum = (sum.inv().toInt() and 0xffff)
        out[ihl + 2] = ((cksum shr 8) and 0xff).toByte()
        out[ihl + 3] = (cksum and 0xff).toByte()
        return out
    }

    private fun u16(buf: ByteArray, off: Int): Int =
        ((buf[off].toInt() and 0xff) shl 8) or (buf[off + 1].toInt() and 0xff)
}
