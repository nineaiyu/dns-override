package com.dnsoverride.app.doh

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DohClient RFC 8484 wire format 测试。
 *
 * 使用 MockWebServer 模拟 DoH 服务器，无需真实网络。
 */
class DohClientTest {

    @Test
    fun sends_wire_format_post() {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/dns-message")
                .setBody(Buffer().write(byteArrayOf(1, 2, 3)))
        )
        server.start()

        val provider = DohProvider("test", server.url("/dns-query").toString(), "127.0.0.1")
        val client = DohClient(provider, OkHttpClient())
        val resp = client.query(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))

        assertNotNull(resp)
        assertArrayEquals(byteArrayOf(1, 2, 3), resp)

        val recordedReq = server.takeRequest()
        assertEquals("POST", recordedReq.method)
        assertEquals("application/dns-message", recordedReq.getHeader("Content-Type"))
        assertEquals("application/dns-message", recordedReq.getHeader("Accept"))
        server.shutdown()
    }

    @Test
    fun returns_null_on_http_error() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()

        val provider = DohProvider("test", server.url("/dns-query").toString(), "127.0.0.1")
        val client = DohClient(provider, OkHttpClient())
        assertNull(client.query(byteArrayOf(1)))

        server.shutdown()
    }

    @Test
    fun returns_empty_on_empty_body() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200))
        server.start()

        val provider = DohProvider("test", server.url("/dns-query").toString(), "127.0.0.1")
        val client = DohClient(provider, OkHttpClient())
        // 200 + 空 body → 返回空 ByteArray（非 null），调用方需自行判断长度
        val resp = client.query(byteArrayOf(1))
        assertNotNull(resp)
        assertEquals(0, resp?.size)

        server.shutdown()
    }
}
