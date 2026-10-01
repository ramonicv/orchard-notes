package dev.rortega.orchardnotes.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.rortega.orchardnotes.notes.doc.AttributeRun
import dev.rortega.orchardnotes.notes.doc.FormatParagraph
import dev.rortega.orchardnotes.notes.doc.NoteContent
import dev.rortega.orchardnotes.notes.doc.NoteFormat
import dev.rortega.orchardnotes.notes.doc.OBJECT_REPLACEMENT_CHARACTER
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.notes.doc.PlacedAttachment
import dev.rortega.orchardnotes.ui.theme.NoteTextStyles

/** List marker shown before a paragraph. */
@Immutable
sealed interface ListMarker {
    data object Bullet : ListMarker
    data object Dash : ListMarker
    data class Number(val value: Int) : ListMarker
    data class Checkbox(val done: Boolean) : ListMarker
}

@Immutable
private sealed interface Block {
    data class Text(val paragraphIndex: Int, val paragraph: FormatParagraph, val marker: ListMarker?, val text: AnnotatedString, val textStart: Int) : Block
    data class Code(val text: AnnotatedString, val quoteLevel: Int) : Block
    data class Attachment(val attachment: PlacedAttachment, val indent: Int) : Block
}

/**
 * Renders a note's paragraphs the way Apple Notes shows them. [onToggleChecklist]
 * receives the paragraph index of a tapped checkbox; [onTextTap] the text offset of a
 * tap on ordinary text (used to start editing at that spot).
 */
@Composable
fun NoteBody(
    content: NoteContent,
    paragraphs: List<FormatParagraph>,
    onToggleChecklist: ((Int) -> Unit)?,
    attachment: @Composable (PlacedAttachment) -> Unit,
    modifier: Modifier = Modifier,
    /** Receives the note-text offset of a tap on ordinary (non-link) text. */
    onTextTap: ((Int) -> Unit)? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val highlight = MaterialTheme.colorScheme.secondary.copy(alpha = 0.35f)
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val blocks = remember(content, paragraphs, linkColor, highlight) {
        buildBlocks(content, paragraphs, RenderColors(linkColor, highlight))
    }
    Column(modifier) {
        blocks.forEach { block ->
            when (block) {
                is Block.Text -> TextBlock(block, onToggleChecklist, onTextTap)
                is Block.Code -> QuoteIndented(block.quoteLevel) {
                    Surface(
                        color = codeBackground,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            block.text,
                            style = NoteTextStyles.body.copy(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 21.sp),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                is Block.Attachment -> Box(Modifier.padding(start = listIndent(block.indent), top = 4.dp, bottom = 4.dp)) {
                    attachment(block.attachment)
                }
            }
        }
    }
}

@Composable
private fun TextBlock(block: Block.Text, onToggleChecklist: ((Int) -> Unit)?, onTextTap: ((Int) -> Unit)?) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val paragraph = block.paragraph
    val style = paragraphTextStyle(paragraph.kind)
    val done = (block.marker as? ListMarker.Checkbox)?.done == true
    QuoteIndented(paragraph.blockQuoteLevel) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = if (paragraph.kind.isList) listIndent(paragraph.indent) else 0.dp,
                    top = if (paragraph.kind == ParagraphKind.Heading || paragraph.kind == ParagraphKind.Subheading) 6.dp else 0.dp,
                ),
            verticalAlignment = Alignment.Top,
        ) {
            block.marker?.let { marker ->
                Marker(marker, style, onClick = onToggleChecklist?.let { toggle -> { toggle(block.paragraphIndex) } })
            }
            Text(
                block.text,
                style = style,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                onTextLayout = { layout = it },
                modifier = Modifier.weight(1f).then(
                    if (onTextTap == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(block) {
                            detectTapGestures { position ->
                                val offset = layout?.getOffsetForPosition(position) ?: block.text.length
                                // Links open themselves; every other tap starts editing there.
                                if (block.text.getLinkAnnotations(offset, offset + 1).isEmpty()) onTextTap(block.textStart + offset)
                            }
                        }
                    },
                ),
            )
        }
    }
}

@Composable
private fun Marker(marker: ListMarker, style: TextStyle, onClick: (() -> Unit)?) {
    val markerModifier = Modifier.width(24.dp)
    when (marker) {
        is ListMarker.Checkbox -> Box(
            modifier = markerModifier
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() })
                .let { if (onClick != null) it.clip(CircleShape).clickable(onClick = onClick) else it },
            contentAlignment = Alignment.CenterStart,
        ) {
            Icon(
                if (marker.done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (marker.done) "Checked" else "Unchecked",
                tint = if (marker.done) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
        ListMarker.Bullet -> Text("•", style = style, modifier = markerModifier)
        ListMarker.Dash -> Text("–", style = style, modifier = markerModifier)
        is ListMarker.Number -> Text("${marker.value}.", style = style, modifier = markerModifier)
    }
}

@Composable
private fun QuoteIndented(level: Int, content: @Composable () -> Unit) {
    if (level <= 0) {
        content()
        return
    }
    Row(Modifier.height(IntrinsicSize.Min)) {
        repeat(level) {
            Box(
                Modifier
                    .padding(end = 10.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
            )
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

private fun listIndent(indent: Int) = (indent * 24).dp

@Composable
private fun paragraphTextStyle(kind: ParagraphKind): TextStyle = when (kind) {
    ParagraphKind.Title -> NoteTextStyles.title
    ParagraphKind.Heading -> NoteTextStyles.heading
    ParagraphKind.Subheading -> NoteTextStyles.subheading
    ParagraphKind.Monospaced -> NoteTextStyles.body.copy(fontFamily = FontFamily.Monospace)
    else -> NoteTextStyles.body
}

private data class RenderColors(val link: Color, val highlight: Color)

/** Groups paragraphs into renderable blocks: text with list markers, code blocks, and attachments. */
private fun buildBlocks(content: NoteContent, paragraphs: List<FormatParagraph>, colors: RenderColors): List<Block> {
    val attachmentsByOffset = content.attachments().associateBy { it.offset }
    val runOffsets = IntArray(content.attributeRuns.size).also { offsets ->
        var offset = 0
        content.attributeRuns.forEachIndexed { i, run ->
            offsets[i] = offset
            offset += run.length
        }
    }
    val blocks = mutableListOf<Block>()
    val numberCounters = mutableMapOf<Int, Int>()
    var code: MutableList<FormatParagraph>? = null

    fun flushCode() {
        val group = code ?: return
        val text = buildAnnotatedString {
            group.forEachIndexed { i, paragraph ->
                if (i > 0) append('\n')
                appendStyled(content, runOffsets, paragraph.start, paragraph.start + paragraph.text.length, colors)
            }
        }
        blocks += Block.Code(text, group.first().blockQuoteLevel)
        code = null
    }

    paragraphs.forEachIndexed { index, paragraph ->
        if (paragraph.kind == ParagraphKind.Monospaced && OBJECT_REPLACEMENT_CHARACTER !in paragraph.text) {
            if (code != null && code!!.last().blockQuoteLevel != paragraph.blockQuoteLevel) flushCode()
            (code ?: mutableListOf<FormatParagraph>().also { code = it }) += paragraph
            numberCounters.clear()
            return@forEachIndexed
        }
        flushCode()

        val marker = markerFor(paragraph, numberCounters)
        // Split the paragraph at attachment placeholders: text segments and attachment blocks, in order.
        var segmentStart = paragraph.start
        val end = paragraph.start + paragraph.text.length
        var markerPending = marker
        var emittedText = false
        for (offset in paragraph.start until end) {
            val placed = attachmentsByOffset[offset] ?: continue
            if (placed.isInline) continue
            if (offset > segmentStart) {
                blocks += Block.Text(index, paragraph, markerPending, annotated(content, runOffsets, segmentStart, offset, colors), segmentStart)
                markerPending = null
                emittedText = true
            }
            blocks += Block.Attachment(placed, if (paragraph.kind.isList) paragraph.indent + 1 else 0)
            segmentStart = offset + 1
        }
        if (segmentStart < end || !emittedText && segmentStart == paragraph.start) {
            blocks += Block.Text(index, paragraph, markerPending, annotated(content, runOffsets, segmentStart, end, colors), segmentStart)
        }
    }
    flushCode()
    return blocks
}

private val PlacedAttachment.isInline: Boolean get() = typeUti.startsWith("com.apple.notes.inlinetextattachment")

private fun markerFor(paragraph: FormatParagraph, counters: MutableMap<Int, Int>): ListMarker? {
    if (!paragraph.kind.isList) {
        counters.clear()
        return null
    }
    // Deeper levels restart whenever we come back up to a shallower item.
    counters.keys.filter { it > paragraph.indent }.forEach(counters::remove)
    return when (paragraph.kind) {
        ParagraphKind.NumberedList -> {
            val next = counters[paragraph.indent]?.plus(1) ?: NoteFormat.effectiveStart(paragraph.startNumber)
            counters[paragraph.indent] = next
            ListMarker.Number(next)
        }
        else -> {
            counters.remove(paragraph.indent)
            when (paragraph.kind) {
                ParagraphKind.DashList -> ListMarker.Dash
                ParagraphKind.Checklist -> ListMarker.Checkbox(paragraph.done)
                else -> ListMarker.Bullet
            }
        }
    }
}

private fun annotated(content: NoteContent, runOffsets: IntArray, start: Int, end: Int, colors: RenderColors): AnnotatedString =
    buildAnnotatedString { appendStyled(content, runOffsets, start, end, colors) }

/** Appends text[start, end) with the inline formatting of the runs covering it. */
private fun AnnotatedString.Builder.appendStyled(
    content: NoteContent,
    runOffsets: IntArray,
    start: Int,
    end: Int,
    colors: RenderColors,
) {
    val base = length
    append(content.text.substring(start, end).replace(OBJECT_REPLACEMENT_CHARACTER, INLINE_ATTACHMENT_GLYPH))
    content.attributeRuns.forEachIndexed { i, run ->
        val runStart = maxOf(runOffsets[i], start)
        val runEnd = minOf(runOffsets[i] + run.length, end)
        if (runStart >= runEnd) return@forEachIndexed
        val from = base + runStart - start
        val to = base + runEnd - start
        spanStyleFor(run, colors)?.let { addStyle(it, from, to) }
        if (run.link.isNotEmpty()) {
            addLink(
                LinkAnnotation.Url(
                    run.link,
                    TextLinkStyles(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)),
                ),
                from,
                to,
            )
        }
    }
}

private const val INLINE_ATTACHMENT_GLYPH = '#'

private fun spanStyleFor(run: AttributeRun, colors: RenderColors): SpanStyle? {
    val bold = run.fontHints and 1 != 0
    val italic = run.fontHints and 2 != 0
    val decorations = buildList {
        if (run.underline == 1) add(TextDecoration.Underline)
        if (run.strikethrough == 1) add(TextDecoration.LineThrough)
    }
    val color = run.color?.let { (r, g, b, a) ->
        // Pure grays are usually pasted default text colors; keep the theme's ink for them.
        if (maxOf(r, g, b) - minOf(r, g, b) < 0.08f) null else Color(r, g, b, a)
    }
    val superscript = run.superscript
    if (!bold && !italic && decorations.isEmpty() && color == null && run.emphasis == 0 && superscript == 0) return null
    return SpanStyle(
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = color ?: Color.Unspecified,
        background = if (run.emphasis != 0) colors.highlight else Color.Unspecified,
        baselineShift = when {
            superscript > 0 -> BaselineShift.Superscript
            superscript < 0 -> BaselineShift.Subscript
            else -> null
        },
        fontSize = if (superscript != 0) 0.75.em else androidx.compose.ui.unit.TextUnit.Unspecified,
    )
}
