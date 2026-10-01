package dev.rortega.orchardnotes.ui.browse

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

class NoteDatesTest {
    private val today = LocalDate.of(2026, 10, 1)
    private fun at(date: LocalDate) = date.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun section(date: LocalDate) = NoteDates.sectionFor(at(date), today, ZoneOffset.UTC, Locale.US)

    @Test
    fun sectionsMatchAppleNotes() {
        assertEquals("Today", section(today))
        assertEquals("Yesterday", section(today.minusDays(1)))
        assertEquals("Previous 7 Days", section(today.minusDays(6)))
        assertEquals("Previous 30 Days", section(today.minusDays(20)))
        assertEquals("July", section(LocalDate.of(2026, 7, 4)))
        assertEquals("2024", section(LocalDate.of(2024, 12, 31)))
    }

    @Test
    fun shortLabels() {
        assertEquals("Yesterday", NoteDates.shortLabel(at(today.minusDays(1)), today, ZoneOffset.UTC, Locale.US))
        assertEquals("Monday", NoteDates.shortLabel(at(LocalDate.of(2026, 9, 28)), today, ZoneOffset.UTC, Locale.US))
        assertEquals("8/1/26", NoteDates.shortLabel(at(LocalDate.of(2026, 8, 1)), today, ZoneOffset.UTC, Locale.US))
    }
}
