package com.dnsoverride.app.service

import android.net.VpnService
import android.util.Log
import com.dnsoverride.app.util.IpPacket
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * 非 53 端口 UDP 流量的透明中继。
 *
 * 背景：TUN 对若干公共 DNS IP 加了 /32 路由，发往这些 IP 的**所有**流量都会进 TUN。
 * 旧实现把非 DNS 包直接丢弃，等于把「到这些 IP 的其他 UDP 端口」黑洞化，
 * 典型受害者是走 QUIC(HTTP/3) 的 DoH。本类用 protected socket 把这些流量转发到真实网络。
 */
class UdpRelay(
    private val vpnService: VpnService,
    private val sendToTun: (ByteArray) -> Unit
) {
    private class UdpFlow(
        val clientIp: ByteArray,
        val clientPort: Int,
        val socket: DatagramSocket
    ) {
        // 最近一次发送的目标（socket 不连接、按包发，响应也从这个 socket 回来）
        @Volatile var dstIp: ByteArray = ByteArray(0)
        @Volatile var dstPort: Int = 0
        @Volatile var lastActive: Long = System.currentTimeMillis()
    }

    private val flows = ConcurrentHashMap<Int, UdpFlow>()

    /** 包循环线程调用。发送可能短暂阻塞，但不做 DNS 之类的长阻塞。 */
    fun handlePacket(packet: ByteArray) {
        if (packet.size < 28) return
        val ihl = IpPacket.headerLength(packet)
        if (packet.size < ihl + 8) return

        val clientIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val clientPort = IpPacket.srcPort(packet)
        val dstPort = IpPacket.dstPort(packet)
        val payload = packet.copyOfRange(ihl + 8, packet.size)

        var flow = flows[clientPort]
        if (flow == null || flow.socket.isClosed) {
            val socket = DatagramSocket()
            if (!vpnService.protect(socket)) {
                socket.close()
                Log.w(TAG, "protect failed, drop udp flow $clientPort")
                return
            }
            socket.soTimeout = IDLE_TIMEOUT_MS.toInt()
            flow = UdpFlow(clientIp, clientPort, socket)
            flows[clientPort] = flow
            Thread({ relayLoop(flow) }, "udp-relay-$clientPort").apply {
                isDaemon = true
                start()
            }
        }

        flow.dstIp = dstIp
        flow.dstPort = dstPort
        flow.lastActive = System.currentTimeMillis()
        try {
            flow.socket.send(
                DatagramPacket(payload, payload.size, InetAddress.getByAddress(dstIp), dstPort)
            )
        } catch (e: IOException) {
            Log.w(TAG, "udp relay send failed: ${e.message}")
            removeFlow(flow)
        }
    }

    /** 每流一个接收线程：网络侧响应 → 构造 IP+UDP 包写回 TUN。 */
    private fun relayLoop(flow: UdpFlow) {
        val buf = ByteArray(4096)
        while (!flow.socket.isClosed) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                flow.socket.receive(pkt)
                if (pkt.length <= 0) continue
                val dstIp = flow.dstIp
                if (dstIp.size != 4) continue
                val data = pkt.data.copyOf(pkt.length)
                if (20 + 8 + data.size > MTU_LIMIT) continue // 超长包无法单帧写回，丢弃
                sendToTun(buildUdpPacket(dstIp, flow.dstPort, flow.clientIp, flow.clientPort, data))
                flow.lastActive = System.currentTimeMillis()
            } catch (e: java.net.SocketTimeoutException) {
                if (System.currentTimeMillis() - flow.lastActive > IDLE_TIMEOUT_MS) {
                    removeFlow(flow)
                    return
                }
            } catch (e: IOException) {
                removeFlow(flow)
                return
            }
        }
    }

    fun sweepIdle() {
        val now = System.currentTimeMillis()
        flows.values.removeAll { flow ->
            val idle = now - flow.lastActive > IDLE_TIMEOUT_MS
            if (idle) removeFlow(flow)
            idle
        }
    }

    fun closeAll() {
        flows.values.forEach { removeFlow(it) }
        flows.clear()
    }

    private fun removeFlow(flow: UdpFlow) {
        flows.remove(flow.clientPort)
        runCatching { flow.socket.close() }
    }

    private fun buildUdpPacket(
        srcIp: ByteArray, srcPort: Int,
        dstIp: ByteArray, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val udpLen = 8 + payload.size
        val totalLen = 20 + udpLen
        val out = ByteArray(totalLen)
        out[0] = 0x45
        out[2] = ((totalLen shr 8) and 0xff).toByte()
        out[3] = (totalLen and 0xff).toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40.toByte(); out[7] = 0
        out[8] = 64
        out[9] = IpPacket.PROTO_UDP.toByte()
        System.arraycopy(srcIp, 0, out, 12, 4)
        System.arraycopy(dstIp, 0, out, 16, 4)
        out[20] = ((srcPort shr 8) and 0xff).toByte()
        out[21] = (srcPort and 0xff).toByte()
        out[22] = ((dstPort shr 8) and 0xff).toByte()
        out[23] = (dstPort and 0xff).toByte()
        out[24] = ((udpLen shr 8) and 0xff).toByte()
        out[25] = (udpLen and 0xff).toByte()
        out[26] = 0; out[27] = 0 // UDP checksum=0
        System.arraycopy(payload, 0, out, 28, payload.size)
        IpPacket.recomputeIpChecksum(out)
        return out
    }

    companion object {
        private const val TAG = "UdpRelay"
        private const val MTU_LIMIT = 1500
        private const val IDLE_TIMEOUT_MS = 60_000L
    }
}
