package com.urmindkilla.svodkalocal.collector

import com.urmindkilla.svodkalocal.core.model.ConditionalFeedTransport
import com.urmindkilla.svodkalocal.core.model.FeedTransport
import com.urmindkilla.svodkalocal.core.model.TransportResponse
import com.urmindkilla.svodkalocal.core.net.BoundedBody
import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class JavaFeedTransport(
    private val allowLoopback: Boolean = false,
) : ConditionalFeedTransport {
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun get(url: String, etag: String?, modifiedSince: String?): TransportResponse =
        fetch(url, etag, modifiedSince)

    override fun get(url: String): TransportResponse = fetch(url, null, null)

    private fun fetch(url: String, etag: String?, modifiedSince: String?): TransportResponse {
        val deadline = System.nanoTime() + TOTAL_BUDGET.toNanos()
        var current = url
        var hops = 0
        while (hops < 6) {
            hops += 1
            if (System.nanoTime() >= deadline) {
                return TransportResponse(null, null, "общий таймаут 20 с", 1, current)
            }
            val rejection = UrlPolicy.rejection(current, allowLoopback)
            if (rejection != null) {
                return TransportResponse(null, null, rejection, 1, current)
            }
            val remaining = Duration.ofNanos((deadline - System.nanoTime()).coerceAtLeast(1_000_000L))
            val builder = HttpRequest.newBuilder(URI.create(current))
                .timeout(remaining)
                .header("User-Agent", USER_AGENT)
                .header("Accept", ACCEPT)
            if (!etag.isNullOrBlank()) builder.header("If-None-Match", etag)
            if (!modifiedSince.isNullOrBlank()) builder.header("If-Modified-Since", modifiedSince)
            val request = builder.GET().build()
            val response = try {
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                    .orTimeout(remaining.toMillis(), TimeUnit.MILLISECONDS)
                    .get()
            } catch (e: Exception) {
                return TransportResponse(null, null, "общий таймаут 20 с", 1, current)
            }
            val code = response.statusCode()
            if (code in 300..399) {
                val location = response.headers().firstValue("location").orElse(null)
                runCatching { response.body().close() }
                if (location.isNullOrBlank()) {
                    return TransportResponse(code, null, "редирект без адреса", 1, current)
                }
                current = URI(current).resolve(location).toString()
                continue
            }
            val bytes = try {
                BoundedBody.read(response.body(), MAX_BYTES, deadline)
            } catch (e: BoundedBody.ReadTimeout) {
                return TransportResponse(code, null, "общий таймаут 20 с", 1, current)
            } catch (e: BoundedBody.TooLarge) {
                return TransportResponse(code, null, "ответ больше лимита", 1, current)
            } finally {
                runCatching { response.body().close() }
            }
            val validators = headerPair(response)
            if (code == 304) {
                return TransportResponse(code, null, null, 1, current, validators.first, validators.second)
            }
            if (code !in 200..299) {
                return TransportResponse(code, bytes, "HTTP $code", 1, current, validators.first, validators.second)
            }
            return TransportResponse(code, bytes, null, 1, current, validators.first, validators.second)
        }
        return TransportResponse(null, null, "слишком много редиректов", 1, current)
    }

    private fun headerPair(response: java.net.http.HttpResponse<*>): Pair<String?, String?> {
        val etag = response.headers().firstValue("etag").orElse(null)
        val modified = response.headers().firstValue("last-modified").orElse(null)
        return etag to modified
    }

    companion object {
        const val USER_AGENT = "SvodkaLocalCollector/1.0 (deterministic RSS; excerpt only)"
        const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml, text/xml, */*;q=0.8"
        const val MAX_BYTES = 4_000_000
        val TOTAL_BUDGET: Duration = Duration.ofSeconds(20)
    }
}
