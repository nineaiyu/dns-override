package com.dnsoverride.app.service

import android.net.VpnService
import android.util.Log
import com.dnsoverride.app.util.DnsProtocol
import com.dnsoverride.app.util.IpPacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.random.Random

/**
 * 极简用户态 TCP 处理器，处理进入 TUN 的 TCP 流。
 *
 * 只实现 DNS 拦截场景所需的最小子集：三次握手 / 数据传输 / FIN / RST。
 * 不做窗口流控、拥塞控制与重传（TUN 是本地环回，内核缓冲充足，实际不丢包）。
 *
 * 两种模式：
 * - 目的端口 53：本地应答 DNS。报文按 RFC 1035 §4.2.2 的 2 字节长度前缀成帧，
 *   交给 [dnsResolver] 处理后原路返回 —— 修复旧实现收到 SYN 直接丢弃、
 *   导致「UDP 响应被截断后客户端转 TCP 重试永远失败」的问题；
 * - 其他端口：透明中继。用户侧由本状态机终结，网络侧开 protected socket 直连真实目标，
 *   修复旧实现把发往已路由 DNS IP 的 DoT(853) / DoH(443) 等非 53 流量直接黑洞的问题
 *   （典型受害：系统「私人 DNS（严格模式）」、1.1.1.1 App 等）。
 *
 * @param executor 共享执行器，用于异步执行 DNS 解析（阻塞上游查询不能卡住包循环线程）
 */
class TcpFlowHandler(
    private val vpnService: VpnService,
    private val executor: Executor,
    private val dnsResolver: (ByteArray) -> ByteArray?,
    private val sendToTun: (ByteArray) -> Unit
) {
    private val flows = ConcurrentHashMap<String, TcpFlow>()

    /** 包循环线程调用。只做状态簿记与队列入队，阻塞操作全部移交执行器/独立线程。 */
    fun handlePacket(packet: ByteArray) {
        if (packet.size < 40) return
        if ((packet[0].toInt() and 0xf0) != 0x40) return
        if (IpPacket.protocol(packet) != IpPacket.PROTO_TCP) return
        val ihl = IpPacket.headerLength(packet)
        if (packet.size < ihl + 20) return

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)
        val srcPort = u16(packet, ihl)
        val dstPort = u16(packet, ihl + 2)
        val seq = u32(packet, ihl + 4)
        val dataOffset = ((packet[ihl + 12].toInt() and 0xf0) shr 4) * 4
        val flags = packet[ihl + 13].toInt() and 0xff
        if (ihl + dataOffset > packet.size) return
        val payload = if (packet.size > ihl + dataOffset) {
            packet.copyOfRange(ihl + dataOffset, packet.size)
        } else null

        val key = "${IpPacket.ipToString(srcIp)}:$srcPort"

        if (flags and FLAG_RST != 0) {
            flows.remove(key)?.close(sendRst = false)
            return
        }

        if (flags and FLAG_SYN != 0 && flags and FLAG_ACK == 0) {
            val existing = flows[key]
            if (existing != null && !existing.established && existing.synSeq == seq) {
                existing.resendSynAck() // SYN 重传：直接补发 SYN-ACK，不换 ISN
            } else {
                flows.remove(key)?.close(sendRst = true)
                val flow = TcpFlow(key, srcIp, srcPort, dstIp, dstPort)
                flows[key] = flow
                flow.onSyn(seq)
            }
            return
        }

        flows[key]?.onSegment(seq, flags, payload)
    }

    /** 回收空闲流。由包循环定期调用。 */
    fun sweepIdle() {
        val now = System.currentTimeMillis()
        flows.values.removeAll { flow ->
            val idle = now - flow.lastActive > IDLE_TIMEOUT_MS
            if (idle) flow.close(sendRst = true)
            idle
        }
    }

    fun closeAll() {
        flows.values.forEach { it.close(sendRst = false) }
        flows.clear()
    }

    private fun u16(p: ByteArray, off: Int): Int =
        ((p[off].toInt() and 0xff) shl 8) or (p[off + 1].toInt() and 0xff)

    private fun u32(p: ByteArray, off: Int): Long =
        ((p[off].toInt() and 0xff).toLong() shl 24) or
            ((p[off + 1].toInt() and 0xff).toLong() shl 16) or
            ((p[off + 2].toInt() and 0xff).toLong() shl 8) or
            (p[off + 3].toInt() and 0xff).toLong()

    // ----------------------------- 单条 TCP 流 -----------------------------

    private inner class TcpFlow(
        val key: String,
        val clientIp: ByteArray,
        val clientPort: Int,
        val serverIp: ByteArray,
        val serverPort: Int
    ) {
        private val isDns = serverPort == 53
        private val ourIsn: Long = Random.nextLong(0, 0x7fffffffL)

        var synSeq: Long = -1
            private set
        var established: Boolean = false
            private set

        private var sendNext = 0L      // 我们下一个待发送序号
        private var recvNext = 0L      // 期望的下一个客户端字节
        private var finReceived = false
        private var finSent = false
        private var closed = false

        // DNS 模式：2 字节长度前缀成帧的重组缓冲 + 待解析队列
        private var inBuf = ByteArray(0)
        private val pendingQueries = ArrayDeque<ByteArray>()
        private var draining = false

        // 中继模式
        private var relaySocket: Socket? = null
        private var relayOut: OutputStream? = null
        private val relayPending = java.io.ByteArrayOutputStream()
        private var relayConnected = false

        @Volatile var lastActive: Long = System.currentTimeMillis()

        fun onSyn(seq: Long) {
            synchronized(this) {
                if (closed) return
                synSeq = seq
                established = false
                recvNext = wrap32(seq + 1)
                sendNext = wrap32(ourIsn + 1)
                sendSegment(FLAG_SYN or FLAG_ACK, ourIsn, recvNext, null, MSS_OPTIONS)
                if (!isDns) openRelayAsync()
            }
        }

        fun resendSynAck() {
            synchronized(this) {
                if (closed || established) return
                sendSegment(FLAG_SYN or FLAG_ACK, ourIsn, recvNext, null, MSS_OPTIONS)
            }
        }

        fun onSegment(seq: Long, flags: Int, payload: ByteArray?) {
            synchronized(this) {
                if (closed) {
                    flows.remove(key)
                    return
                }
                lastActive = System.currentTimeMillis()
                if (flags and FLAG_ACK != 0) established = true

                var needAck = false
                if (payload != null && payload.isNotEmpty()) {
                    if (seq == recvNext) {
                        recvNext = wrap32(recvNext + payload.size)
                        consume(payload)
                        needAck = true
                    } else if (wrap32(seq + payload.size) <= recvNext) {
                        needAck = true // 重复段，补 ACK 让客户端停止重传
                    } else {
                        needAck = true // 乱序段，ACK 当前期望序号促使其重传
                    }
                }
                if (flags and FLAG_FIN != 0 && !finReceived) {
                    finReceived = true
                    recvNext = wrap32(recvNext + 1)
                    needAck = true
                    onClientFin()
                }

                // 数据/FIN 立即确认；DNS 响应数据稍后由执行器线程异步发出（自带 ACK）
                if (needAck && !closed && !finSent) {
                    sendSegment(FLAG_ACK, sendNext, recvNext, null)
                }
            }
        }

        private fun consume(data: ByteArray) {
            if (isDns) {
                inBuf += data
                // 提取完整的 <2字节长度><DNS报文> 帧
                while (inBuf.size >= 2) {
                    val len = ((inBuf[0].toInt() and 0xff) shl 8) or (inBuf[1].toInt() and 0xff)
                    if (inBuf.size < 2 + len) break
                    val msg = inBuf.copyOfRange(2, 2 + len)
                    inBuf = inBuf.copyOfRange(2 + len, inBuf.size)
                    enqueueQuery(msg)
                }
            } else {
                if (relayConnected) {
                    runCatching {
                        relayOut?.write(data)
                        relayOut?.flush()
                    }.onFailure { close(sendRst = true) }
                } else {
                    relayPending.write(data) // socket 尚未连上，先缓冲
                }
            }
        }

        private fun hasPendingWork(): Boolean = synchronized(this) {
            draining || pendingQueries.isNotEmpty() || inBuf.size >= 2
        }

        private fun enqueueQuery(msg: ByteArray) {
            pendingQueries.add(msg)
            if (!draining) {
                draining = true
                executor.execute { drainQueries() }
            }
        }

        /** 在共享执行器线程中串行解析本流待处理的查询，保证响应在连接上有序。 */
        private fun drainQueries() {
            while (true) {
                val msg = synchronized(this) {
                    if (closed) { draining = false; return }
                    pendingQueries.removeFirstOrNull() ?: run { draining = false; return }
                }
                // 解析失败（如报文非法）时仍回一个 SERVFAIL，避免客户端一直挂起导致「网站打不开」。
                val resp = runCatching { dnsResolver(msg) }.getOrNull()
                    ?: runCatching {
                        val q = DnsProtocol.parseQuestionMessage(msg) ?: return@runCatching null
                        DnsProtocol.buildEmptyAnswerMessage(msg, q, DnsProtocol.RCODE_SERVFAIL)
                    }.getOrNull()
                synchronized(this) {
                    if (closed) return
                    if (resp != null && resp.size <= 0xffff) {
                        val framed = ByteArray(2 + resp.size)
                        framed[0] = ((resp.size shr 8) and 0xff).toByte()
                        framed[1] = (resp.size and 0xff).toByte()
                        System.arraycopy(resp, 0, framed, 2, resp.size)
                        sendData(framed)
                    }
                    // 队列排空且客户端已 FIN → 收尾（draining 在队列空时已被置回 false）
                    if (pendingQueries.isEmpty() && finReceived && !finSent) {
                        sendFin()
                    }
                }
            }
        }

        private fun onClientFin() {
            if (isDns) {
                if (!hasPendingWork()) sendFin()
            } else {
                runCatching { relayOut?.close() } // 半关闭：不再有上行数据
            }
        }

        private fun sendFin() {
            if (finSent || closed) return
            finSent = true
            sendSegment(FLAG_FIN or FLAG_ACK, sendNext, recvNext, null)
            closeQuietly()
            flows.remove(key)
        }

        // ----------------------------- 中继模式 -----------------------------

        private fun openRelayAsync() {
            Thread({
                try {
                    val socket = Socket()
                    if (!vpnService.protect(socket)) throw IOException("protect failed")
                    socket.tcpNoDelay = true
                    socket.connect(
                        java.net.InetSocketAddress(InetAddress.getByAddress(serverIp), serverPort),
                        RELAY_CONNECT_TIMEOUT_MS
                    )
                    synchronized(this) {
                        if (closed) {
                            runCatching { socket.close() }
                            return@Thread
                        }
                        relaySocket = socket
                        relayOut = socket.getOutputStream()
                        relayConnected = true
                        relayPending.toByteArray().let { if (it.isNotEmpty()) relayOut?.write(it) }
                        relayPending.reset()
                    }
                    relayReadLoop(socket.getInputStream())
                } catch (e: Exception) {
                    Log.w(TAG, "relay connect $serverIp:$serverPort failed: ${e.message}")
                    close(sendRst = true)
                }
            }, "tcp-relay-${IpPacket.ipToString(serverIp)}:$serverPort").apply {
                isDaemon = true
                start()
            }
        }

        private fun relayReadLoop(input: InputStream) {
            val buf = ByteArray(16 * 1024)
            try {
                while (!closed) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    synchronized(this) {
                        if (closed) return
                        sendData(buf.copyOf(n))
                    }
                }
                synchronized(this) {
                    // 对端关闭且无更多数据 → 正常结束
                    if (!finSent) sendFin()
                }
            } catch (e: Exception) {
                close(sendRst = true)
            }
        }

        // ----------------------------- 发包与关闭 -----------------------------

        /** 分段发送数据（段大小 ≤ MSS，避免超过 TUN MTU）。 */
        private fun sendData(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val len = minOf(MSS_SEGMENT, data.size - off)
                val chunk = data.copyOfRange(off, off + len)
                sendSegment(FLAG_PSH or FLAG_ACK, sendNext, recvNext, chunk)
                sendNext = wrap32(sendNext + len)
                off += len
            }
        }

        private fun sendSegment(flags: Int, seq: Long, ack: Long, payload: ByteArray?, options: ByteArray? = null) {
            if (closed && flags and FLAG_RST == 0) return
            sendToTun(buildTcpPacket(clientIp, clientPort, serverIp, serverPort, seq, ack, flags, payload, options))
        }

        fun close(sendRst: Boolean) {
            synchronized(this) {
                if (sendRst && !closed) {
                    closed = true
                    sendSegment(FLAG_RST or FLAG_ACK, sendNext, recvNext, null)
                } else {
                    closed = true
                }
                closeQuietly()
            }
            flows.remove(key)
        }

        private fun closeQuietly() {
            closed = true
            runCatching { relayOut?.close() }
            runCatching { relaySocket?.close() }
            relayConnected = false
        }
    }

    // ----------------------------- 包构造 -----------------------------

    private fun buildTcpPacket(
        clientIp: ByteArray, clientPort: Int,
        serverIp: ByteArray, serverPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray?, options: ByteArray? = null
    ): ByteArray {
        val optLen = options?.size ?: 0
        val headerLen = 20 + optLen
        val totalLen = 20 + headerLen + (payload?.size ?: 0)
        val out = ByteArray(totalLen)
        // IP 头
        out[0] = 0x45
        out[2] = ((totalLen shr 8) and 0xff).toByte()
        out[3] = (totalLen and 0xff).toByte()
        out[4] = 0; out[5] = 0
        out[6] = 0x40.toByte(); out[7] = 0 // DF
        out[8] = 64
        out[9] = IpPacket.PROTO_TCP.toByte()
        System.arraycopy(serverIp, 0, out, 12, 4)
        System.arraycopy(clientIp, 0, out, 16, 4)
        // TCP 头
        val t = 20
        out[t] = ((serverPort shr 8) and 0xff).toByte()
        out[t + 1] = (serverPort and 0xff).toByte()
        out[t + 2] = ((clientPort shr 8) and 0xff).toByte()
        out[t + 3] = (clientPort and 0xff).toByte()
        writeU32(out, t + 4, seq)
        writeU32(out, t + 8, ack)
        out[t + 12] = ((headerLen / 4) shl 4).toByte()
        out[t + 13] = flags.toByte()
        out[t + 14] = 0xff.toByte(); out[t + 15] = 0xff.toByte() // window
        out[t + 16] = 0; out[t + 17] = 0 // checksum 占位
        out[t + 18] = 0; out[t + 19] = 0
        if (optLen > 0) System.arraycopy(options!!, 0, out, t + 20, optLen)
        if (payload != null && payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, t + headerLen, payload.size)
        }
        IpPacket.recomputeIpChecksum(out)
        IpPacket.recomputeTcpChecksum(out)
        return out
    }

    private fun writeU32(buf: ByteArray, off: Int, v: Long) {
        buf[off] = ((v shr 24) and 0xff).toByte()
        buf[off + 1] = ((v shr 16) and 0xff).toByte()
        buf[off + 2] = ((v shr 8) and 0xff).toByte()
        buf[off + 3] = (v and 0xff).toByte()
    }

    private fun wrap32(v: Long): Long = v and 0xffffffffL

    companion object {
        private const val TAG = "TcpFlowHandler"
        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10

        /** SYN-ACK 携带的 MSS 选项（MSS=1400 + NOP 填充到 8 字节）。 */
        private val MSS_OPTIONS = byteArrayOf(2, 4, 0x05, 0x78, 1, 1, 1, 1)

        /** 单段最大数据量：IP(20)+TCP(20)+数据 ≤ 1440，留出余量不超 MTU 1500。 */
        private const val MSS_SEGMENT = 1400

        private const val IDLE_TIMEOUT_MS = 60_000L
        private const val RELAY_CONNECT_TIMEOUT_MS = 10_000
    }
}
