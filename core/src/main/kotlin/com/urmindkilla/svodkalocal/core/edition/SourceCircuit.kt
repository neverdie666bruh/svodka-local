package com.urmindkilla.svodkalocal.core.edition

import com.urmindkilla.svodkalocal.core.model.SourceHealth
import java.time.Duration
import java.time.Instant

object SourceCircuit {
    const val THRESHOLD = 4
    val TRANSPORT_PAUSE: Duration = Duration.ofHours(24)
    val MALFORMED_PAUSE: Duration = Duration.ofDays(3)
    val MAX_PAUSE: Duration = Duration.ofDays(7)

    fun malformed(error: String?): Boolean {
        val text = error?.lowercase().orEmpty()
        return text.contains("не разобрана") || text.contains("sax") || text.contains("doctype") || text.contains("это не rss")
    }

    fun pauseFor(failures: Int, badDocument: Boolean): Duration {
        val base = if (badDocument) MALFORMED_PAUSE else TRANSPORT_PAUSE
        val extra = (failures - THRESHOLD).coerceIn(0, 3)
        var pause = base
        repeat(extra) {
            val doubled = pause.multipliedBy(2)
            pause = if (doubled > MAX_PAUSE) MAX_PAUSE else doubled
        }
        return if (pause > MAX_PAUSE) MAX_PAUSE else pause
    }

    fun waiting(health: SourceHealth?, now: Instant, force: Boolean): Boolean {
        if (force || health == null || health.consecutiveFailures < THRESHOLD) return false
        val next = health.nextProbeAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return false
        return now.isBefore(next)
    }
}
