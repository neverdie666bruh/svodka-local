package com.urmindkilla.svodkalocal.collector

import com.urmindkilla.svodkalocal.core.model.FeedTransport
import com.urmindkilla.svodkalocal.core.model.TransportResponse
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class JavaFeedTransport(
    private val allowLoopback: Boolean = false,
) : FeedTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun get(url: String): TransportResponse {
        var current = url
        repeat(6) {
            val rejection = UrlPolicy.rejection(current, allowLoopback)
            if (rejection != null) {
                return TransportResponse(null, null, rejection, 1, current)
            }
            val request = HttpRequest.newBuilder(URI.create(current))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
                .GET()
                .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val code = response.statusCode()
            if (code in 300..399) {
                val location = response.headers().firstValue("location").orElse(null)
                response.body().close()
                if (location.isNullOrBlank()) {
                    return TransportResponse(code, null, "редирект без адреса", 1, current)
                }
                current = URI(current).resolve(location).toString()
                return@repeat
            }
            val bytes = response.body().use { input ->
                val buffer = ByteArray(8192)
                val out = java.io.ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_BYTES) return TransportResponse(code, null, "ответ больше лимита", 1, current)
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            if (code !in 200..299) {
                return TransportResponse(code, bytes, "HTTP $code", 1, current)
            }
            return TransportResponse(code, bytes, null, 1, current)
        }
        return TransportResponse(null, null, "слишком много редиректов", 1, current)
    }

    companion object {
        const val USER_AGENT = "SvodkaLocalCollector/1.0 (deterministic RSS; excerpt only)"
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8"
        const val MAX_BYTES = 4_000_000
    }
}
