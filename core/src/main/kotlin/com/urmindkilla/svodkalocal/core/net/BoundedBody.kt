package com.urmindkilla.svodkalocal.core.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object BoundedBody {
    class ReadTimeout : Exception("чтение прервано по общему таймауту")
    class TooLarge : Exception("ответ больше лимита")

    fun read(input: InputStream, maxBytes: Int, deadlineNanos: Long): ByteArray {
        val future = CompletableFuture<ByteArray>()
        val worker = Thread({
            try {
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    if (System.nanoTime() >= deadlineNanos) throw ReadTimeout()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw TooLarge()
                    out.write(buffer, 0, read)
                }
                future.complete(out.toByteArray())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }, "svodka-body")
        worker.isDaemon = true
        worker.start()
        val waitMs = ((deadlineNanos - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            runCatching { input.close() }
            worker.join(1_000)
            throw ReadTimeout()
        } catch (e: Exception) {
            runCatching { input.close() }
            val cause = e.cause ?: e
            if (cause is ReadTimeout || cause is TooLarge) throw cause
            throw cause
        }
    }
}
