package dev.rortega.orchardnotes.ui.browse

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Apple Notes-style list sections and timestamps. */
object NoteDates {

    /** Section header for a note modified at [millis], relative to [today]. */
    fun sectionFor(millis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val date = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "Previous 7 Days"
            days < 30 -> "Previous 30 Days"
            date.year == today.year -> date.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            else -> date.year.toString()
        }
    }

    /** Short timestamp for a list row: a time today, a weekday this week, else a date. */
    fun shortLabel(millis: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val dateTime = Instant.ofEpochMilli(millis).atZone(zone)
        val days = ChronoUnit.DAYS.between(dateTime.toLocalDate(), today)
        return when {
            days <= 0 -> dateTime.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
            days == 1L -> "Yesterday"
            days < 7 -> dateTime.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            else -> dateTime.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale))
        }
    }

    /** Full timestamp shown at the top of an open note. */
    fun longLabel(millis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(locale))
}
