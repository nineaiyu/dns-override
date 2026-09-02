package com.dnsoverride.app.doh

import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * DNS-over-HTTPS 客户端（RFC 8484 wire format）。
 *
 * 关键设计：用 bootstrap IP 直连 DoH 服务器，避免 DNS 解析 DoH 域名时形成回环。
 */
class DohClient(
    private val provider: DohProvider,
    private val okHttp: OkHttpClient = defaultClient(provider.bootstrapIp)
) {
    /**
     * 发送 DNS 查询报文，返回 DNS 响应字节。
     * @param dnsQuery 完整 DNS 报文（不含 IP/UDP 头）
     */
    fun query(dnsQuery: ByteArray): ByteArray? = runCatching {
        val req = Request.Builder()
            .url(provider.url)
            .post(dnsQuery.toRequestBody("application/dns-message".toMediaType()))
            .header("Accept", "application/dns-message")
            .build()
        okHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    }.getOrNull()

    companion object {
        /**
         * 按 provider URL 复用 [OkHttpClient]。
         *
         * 每次「设置变更 → VPN RELOAD」都新建 OkHttpClient 会连带新建连接池与线程池，
         * 频繁切换设置时这些实例不会被回收，属于典型的连接/线程泄漏。
         */
        private val clientCache = ConcurrentHashMap<String, OkHttpClient>()

        /**
         * 获取（或首次创建）绑定到该 provider 的客户端。
         *
         * 刻意不用 `computeIfAbsent` / `putIfAbsent`：这两个方法是 API 24 才加入
         * `ConcurrentHashMap` 的，minSdk 21 上调用会抛 `NoSuchMethodError`。
         */
        fun forProvider(provider: DohProvider): DohClient {
            clientCache[provider.url]?.let { return DohClient(provider, it) }
            return synchronized(clientCache) {
                val existing = clientCache[provider.url]
                if (existing != null) {
                    DohClient(provider, existing)
                } else {
                    val created = defaultClient(provider.bootstrapIp)
                    clientCache[provider.url] = created
                    DohClient(provider, created)
                }
            }
        }

        private fun defaultClient(bootstrapIp: String): OkHttpClient {
            val dns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByAddress(hostname, InetAddress.getByName(bootstrapIp).address))
            }
            return OkHttpClient.Builder()
                .dns(dns)
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(3, TimeUnit.SECONDS)
                .build()
        }
    }
}
