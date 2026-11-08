package com.urmindkilla.svodkalocal.core.time

import com.urmindkilla.svodkalocal.core.model.Edition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class RunDecision {
    BUILD,
    RETRY_PARTIAL,
    SKIP_COMPLETE,
}

object EditionCalendar {
    const val ZONE_ID = "Asia/Dushanbe"
    val ZONE: ZoneId = ZoneId.of(ZONE_ID)
    const val WINDOW_HOURS = 36L
    val FUTURE_SKEW: Duration = Duration.ofHours(6)

    fun editionDate(now: Instant, zone: ZoneId = ZONE): LocalDate =
        LocalDate.ofInstant(now, zone)

    fun inWindow(published: Instant, now: Instant, windowHours: Long = WINDOW_HOURS): Boolean {
        if (published.isAfter(now.plus(FUTURE_SKEW))) return false
        return !published.isBefore(now.minus(Duration.ofHours(windowHours)))
    }

    fun decide(existing: Edition?, force: Boolean): RunDecision {
        if (force || existing == null) return RunDecision.BUILD
        return if (existing.partial) RunDecision.RETRY_PARTIAL else RunDecision.SKIP_COMPLETE
    }
}
