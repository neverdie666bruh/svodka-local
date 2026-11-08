package com.urmindkilla.svodkalocal.collector

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

class TransportTest {
    @Test
    fun followsSafeRedirectAndRefusesPrivateTarget() {
        val hits = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/go") { exchange ->
            exchange.responseHeaders.add("Location", "/feed")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/feed") { exchange ->
            hits.incrementAndGet()
            val body = "<rss version=\"2.0\"><channel></channel></rss>".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val port = server.address.port
            val blocked = JavaFeedTransport(allowLoopback = false).get("http://127.0.0.1:$port/feed")
            assertNotNull(blocked.error)
            assertEquals(0, hits.get())
            val ok = JavaFeedTransport(allowLoopback = true).get("http://127.0.0.1:$port/go")
            assertEquals(200, ok.status)
            assertTrue(ok.body!!.decodeToString().contains("<rss"))
            assertEquals(1, hits.get())
            val privateRedirect = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            privateRedirect.createContext("/") { exchange ->
                exchange.responseHeaders.add("Location", "https://10.1.2.3/secret")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            privateRedirect.start()
            try {
                val response = JavaFeedTransport(allowLoopback = true).get("http://127.0.0.1:${privateRedirect.address.port}/")
                assertTrue(response.error?.contains("непубличный") == true || response.error?.contains("https") == true)
            } finally {
                privateRedirect.stop(0)
            }
        } finally {
            server.stop(0)
        }
    }
}
