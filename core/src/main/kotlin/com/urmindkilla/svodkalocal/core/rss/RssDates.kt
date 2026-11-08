package com.urmindkilla.svodkalocal.core.rss

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

object RssDates {
    private val patterns = listOf(
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEE, d MMM uuuu HH:mm:ss XX", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("EEE, d MMM uuuu HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss XX", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("d MMM uuuu HH:mm:ss z", Locale.ENGLISH),
        DateTimeFormatter.ISO_OFFSET_DATE_TIME,
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss XX", Locale.ENGLISH),
    )

    fun parse(raw: String?): Instant? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().replace("GMT", "Z").replace("UT", "Z")
        for (pattern in patterns) {
            try {
                return Instant.from(pattern.parse(text))
            } catch (_: DateTimeParseException) {
                // next
            }
        }
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
