package com.urmindkilla.svodkalocal.data

import com.urmindkilla.svodkalocal.core.net.UrlPolicy
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class ReaderClient {
    fun get(url: String): ByteArray {
        var current = url
        repeat(6) {
            val rejection = UrlPolicy.rejection(current)
            if (rejection != null) error(rejection)
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json, text/plain, */*;q=0.1")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("User-Agent", "SvodkaLokal/1.0")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: error("редирект без адреса")
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) error("HTTP $code")
                return connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > 2_000_000) error("ответ больше лимита")
                        out.write(buffer, 0, read)
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        error("слишком много редиректов")
    }
}
