package dev.rortega.orchardnotes.notes

import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.InlineSpan
import dev.rortega.orchardnotes.notes.doc.InlineStyle
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveMergeTest {
    private val bold = InlineStyle(bold = true)

    private fun p(text: String, kind: ParagraphKind = ParagraphKind.Body, done: Boolean = false, spans: List<InlineSpan>? = null) =
        FormatParagraph(kind = kind, done = done, text = text, spans = spans ?: if (text.isEmpty()) emptyList() else listOf(InlineSpan(text.length, InlineStyle.Plain)))

    private fun doc(vararg paragraphs: FormatParagraph) = ParagraphMerge.withOffsets(paragraphs.toList())

    private fun lines(vararg texts: String) = doc(*texts.map { p(it) }.toTypedArray())

    private fun text(paragraphs: List<FormatParagraph>) = paragraphs.joinToString("\n") { it.text }

    private fun merged(base: List<FormatParagraph>, ours: List<FormatParagraph>, theirs: List<FormatParagraph>) =
        LiveMerge.merge(base, ours, theirs).paragraphs

    @Test
    fun unchangedSidesGiveTheOtherSide() {
        val base = doc(p("Groceries", ParagraphKind.Title), p("milk", ParagraphKind.Checklist))
        val changed = doc(p("Groceries", ParagraphKind.Title), p("milk", ParagraphKind.Checklist, done = true), p("eggs", ParagraphKind.Checklist))
        assertTrue(NoteFormat.formatsEqual(base, merged(base, base, base)))
        assertTrue(NoteFormat.formatsEqual(changed, merged(base, changed, base)))
        assertTrue(NoteFormat.formatsEqual(changed, merged(base, base, changed)))
    }

    @Test
    fun editsToDifferentWordsOfOneParagraphBothSurvive() {
        val result = merged(lines("the quick brown fox"), lines("the slow brown fox"), lines("the quick brown dog"))
        assertEquals("the slow brown dog", text(result))
    }

    @Test
    fun severalEditsInOneLineOnEachSide() {
        val result = merged(lines("the quick brown fox"), lines("the slow brown cat"), lines("the quick red fox"))
        assertEquals("the slow red cat", text(result))
    }

    @Test
    fun typingAtTheSamePlaceKeepsBothTheirsFirst() {
        assertEquals("aYXb", text(merged(lines("ab"), lines("aXb"), lines("aYb"))))
    }

    @Test
    fun theSameEditOnBothSidesAppearsOnce() {
        assertEquals("a new line b", text(merged(lines("a b"), lines("a new line b"), lines("a new line b"))))
        assertEquals(listOf("one", "two", "three"), merged(lines("one", "three"), lines("one", "two", "three"), lines("one", "two", "three")).map { it.text })
    }

    @Test
    fun deletionsWinOverEditsInsideTheDeletedText() {
        assertEquals("hello ", text(merged(lines("hello world"), lines("hello "), lines("hello word"))))
    }

    @Test
    fun linesAddedOnBothSidesInDifferentPlaces() {
        val result = merged(lines("a", "b", "c"), lines("a", "ours", "b", "c"), lines("a", "b", "c", "theirs"))
        assertEquals(listOf("a", "ours", "b", "c", "theirs"), result.map { it.text })
    }

    @Test
    fun paragraphStyleFromOneSideAndTextFromTheOther() {
        val base = doc(p("Plan"), p("details"))
        val ours = doc(p("Plan", ParagraphKind.Heading), p("details"))
        val theirs = doc(p("Plan for May"), p("details"))
        val result = merged(base, ours, theirs)
        assertEquals("Plan for May", result[0].text)
        assertEquals(ParagraphKind.Heading, result[0].kind)
    }

    @Test
    fun checklistTickedElsewhereWhileTheItemIsBeingRetyped() {
        val base = doc(p("buy milk", ParagraphKind.Checklist))
        val ours = doc(p("buy oat milk", ParagraphKind.Checklist))
        val theirs = doc(p("buy milk", ParagraphKind.Checklist, done = true))
        val result = merged(base, ours, theirs).single()
        assertEquals("buy oat milk", result.text)
        assertTrue(result.done)
    }

    @Test
    fun aParagraphAddedAtTheEndKeepsItsOwnStyle() {
        val base = doc(p("Shopping"))
        val ours = doc(p("Shopping"), p("bread", ParagraphKind.Checklist))
        val theirs = doc(p("Shopping", ParagraphKind.Title))
        val result = merged(base, ours, theirs)
        assertEquals(listOf("Shopping", "bread"), result.map { it.text })
        assertEquals(ParagraphKind.Title, result[0].kind)
        assertEquals(ParagraphKind.Checklist, result[1].kind)
    }

    @Test
    fun boldAppliedHereSurvivesTheirTyping() {
        val base = lines("make it bold please")
        val ours = doc(p("make it bold please", spans = listOf(InlineSpan(8, InlineStyle.Plain), InlineSpan(4, bold), InlineSpan(7, InlineStyle.Plain))))
        val theirs = lines("make it bold now please")
        val result = merged(base, ours, theirs).single()
        assertEquals("make it bold now please", result.text)
        assertEquals(listOf(InlineSpan(8, InlineStyle.Plain), InlineSpan(4, bold), InlineSpan(11, InlineStyle.Plain)), result.spans)
    }

    @Test
    fun spansAlwaysCoverTheirParagraph() {
        val result = merged(lines("alpha", "beta"), lines("alpha one", "beta"), lines("alpha", "beta two", "gamma"))
        for (paragraph in result) assertEquals(paragraph.text.length, paragraph.spans.sumOf { it.length })
        assertEquals(text(result).length, result.last().start + result.last().text.length)
    }

    @Test
    fun theCursorFollowsRemoteTypingBeforeIt() {
        val base = lines("hello")
        val ours = lines("hello world")
        val theirs = lines(">> hello")
        val result = LiveMerge.merge(base, ours, theirs)
        assertEquals(">> hello world", text(result.paragraphs))
        // The cursor was at the end of "hello world".
        assertEquals(">> hello world".length, result.mapOursOffset("hello world".length))
        // ...or between "hello" and " world".
        assertEquals(">> hello".length, result.mapOursOffset("hello".length))
    }

    @Test
    fun theCursorInsideTextDeletedRemotelyLandsWhereItWas() {
        val result = LiveMerge.merge(lines("one big three"), lines("one big three"), lines("one three"))
        assertEquals("one three", text(result.paragraphs))
        assertEquals("one ".length, result.mapOursOffset("one bi".length))
    }

    @Test
    fun emojiAreNeverSplit() {
        val result = text(merged(lines("a😀b"), lines("a😁b"), lines("ab")))
        assertEquals("a😁b", result)
        assertFalse(result.withIndex().any { (i, c) -> Character.isHighSurrogate(c) && (i + 1 >= result.length || !Character.isLowSurrogate(result[i + 1])) })
    }

    @Test
    fun emptyNotes() {
        assertEquals(listOf(""), merged(emptyList(), emptyList(), emptyList()).map { it.text })
        assertEquals(listOf("hi"), merged(emptyList(), lines("hi"), emptyList()).map { it.text })
    }

    @Test
    fun largeNotesMergeQuickly() {
        val base = (1..3000).map { "line $it of a long note" }
        val ours = base.toMutableList().also { it[10] = "changed here"; it.add(2000, "inserted here") }
        val theirs = base.toMutableList().also { it[2500] = "changed there"; it.removeAt(5) }
        val started = System.nanoTime()
        val result = merged(lines(*base.toTypedArray()), lines(*ours.toTypedArray()), lines(*theirs.toTypedArray())).map { it.text }
        assertTrue("took too long", System.nanoTime() - started < 5_000_000_000L)
        assertTrue("changed here" in result && "inserted here" in result && "changed there" in result)
        assertFalse("line 6 of a long note" in result)
        assertEquals(base.size + 1 - 1, result.size)
    }

    @Test
    fun myersFindsALongestCommonSubsequence() {
        val random = java.util.Random(7)
        repeat(500) {
            val a = IntArray(random.nextInt(30)) { random.nextInt(4) }
            val b = IntArray(random.nextInt(30)) { random.nextInt(4) }
            val matches = Myers.matches(a, b, 1_000)!!
            assertEquals(lcsLength(a, b), matches.size)
            matches.zipWithNext().forEach { (p, q) -> assertTrue(q.first > p.first && q.second > p.second) }
            matches.forEach { (i, j) -> assertEquals(a[i], b[j]) }
        }
    }

    @Test
    fun myersGivesUpPastTheEditLimit() {
        assertEquals(null, Myers.matches(IntArray(50) { 1 }, IntArray(50) { 2 }, maxEdits = 10))
    }

    private fun lcsLength(a: IntArray, b: IntArray): Int {
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        return lengths[0][0]
    }
}
