package dev.rortega.orchardnotes.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import dev.rortega.orchardnotes.notes.doc.ParagraphKind
import dev.rortega.orchardnotes.ui.theme.NoteTextStyles

/**
 * The note editor: one text field rendering the note's formatting, with checklist
 * circles that toggle on tap.
 */
@Composable
fun NoteEditor(
    state: EditorState,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onToggleDone: (Int) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val colors = EditorColors(
        accent = MaterialTheme.colorScheme.secondary,
        muted = MaterialTheme.colorScheme.onSurfaceVariant,
        link = MaterialTheme.colorScheme.primary,
        attachment = MaterialTheme.colorScheme.secondary,
    )
    val layout = remember(state, colors) { EditorLayout(state, colors) }
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentLayout by rememberUpdatedState(layout)
    val toggle by rememberUpdatedState(onToggleDone)
    val tapSlop = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        visualTransformation = remember(layout) { EditorVisualTransformation(layout) },
        // Line height follows each line's font, so headings and body text can share one field.
        textStyle = NoteTextStyles.body.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = TextUnit.Unspecified),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        onTextLayout = { textLayout = it },
        modifier = modifier
            .focusRequester(focusRequester)
            // Checkbox taps are intercepted before the text field moves the cursor.
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    val result = textLayout ?: return@awaitEachGesture
                    val hit = currentLayout.checkboxes.firstOrNull { box ->
                        val bounds = result.getBoundingBox(box.range.first)
                        Rect(bounds.left - tapSlop, bounds.top, bounds.right + tapSlop, bounds.bottom).contains(down.position)
                    } ?: return@awaitEachGesture
                    down.consume()
                    val up = waitForUpOrCancellation(pass = PointerEventPass.Initial)
                    if (up != null) {
                        up.consume()
                        toggle(hit.lineIndex)
                    }
                }
            },
    )
}

/** Formatting controls shown above the keyboard while editing. */
@Composable
fun EditorToolbar(
    state: EditorState,
    selection: TextRange,
    onSetKind: (ParagraphKind) -> Unit,
    onToggleInline: (InlineAttribute) -> Unit,
    onIndent: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val range = selection.min until selection.max
    val currentKind = state.lines.getOrNull(state.lineIndexAt(selection.min))?.kind ?: ParagraphKind.Body
    var stylesOpen by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    IconButton(onClick = { stylesOpen = true }) { Icon(Icons.Filled.TextFields, contentDescription = "Paragraph style") }
                    DropdownMenu(expanded = stylesOpen, onDismissRequest = { stylesOpen = false }) {
                        listOf(
                            ParagraphKind.Title to "Title",
                            ParagraphKind.Heading to "Heading",
                            ParagraphKind.Subheading to "Subheading",
                            ParagraphKind.Body to "Body",
                            ParagraphKind.Monospaced to "Monospaced",
                        ).forEach { (kind, label) ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        style = when (kind) {
                                            ParagraphKind.Title -> MaterialTheme.typography.titleLarge
                                            ParagraphKind.Heading -> MaterialTheme.typography.titleMedium
                                            ParagraphKind.Monospaced -> MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                                            else -> MaterialTheme.typography.bodyLarge
                                        },
                                        color = if (kind == currentKind) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                                    )
                                },
                                onClick = {
                                    stylesOpen = false
                                    onSetKind(kind)
                                },
                            )
                        }
                    }
                }
                ToolbarToggle(Icons.Filled.Checklist, "Checklist", currentKind == ParagraphKind.Checklist) { onSetKind(ParagraphKind.Checklist) }
                ToolbarToggle(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", currentKind == ParagraphKind.BulletList || currentKind == ParagraphKind.DashList) {
                    onSetKind(ParagraphKind.BulletList)
                }
                ToolbarToggle(Icons.Filled.FormatListNumbered, "Numbered list", currentKind == ParagraphKind.NumberedList) { onSetKind(ParagraphKind.NumberedList) }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                ToolbarToggle(Icons.Filled.FormatBold, "Bold", state.isActive(range, InlineAttribute.Bold)) { onToggleInline(InlineAttribute.Bold) }
                ToolbarToggle(Icons.Filled.FormatItalic, "Italic", state.isActive(range, InlineAttribute.Italic)) { onToggleInline(InlineAttribute.Italic) }
                ToolbarToggle(Icons.Filled.FormatUnderlined, "Underline", state.isActive(range, InlineAttribute.Underline)) { onToggleInline(InlineAttribute.Underline) }
                ToolbarToggle(Icons.Filled.FormatStrikethrough, "Strikethrough", state.isActive(range, InlineAttribute.Strikethrough)) {
                    onToggleInline(InlineAttribute.Strikethrough)
                }
                VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp))
                IconButton(onClick = { onIndent(-1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentDecrease, contentDescription = "Decrease indent") }
                IconButton(onClick = { onIndent(1) }) { Icon(Icons.AutoMirrored.Filled.FormatIndentIncrease, contentDescription = "Increase indent") }
                Box(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun ToolbarToggle(icon: ImageVector, label: String, checked: Boolean, onClick: () -> Unit) {
    IconToggleButton(checked = checked, onCheckedChange = { onClick() }) {
        Box(
            modifier = Modifier.background(
                if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            ).padding(4.dp),
        ) {
            Icon(icon, contentDescription = label, tint = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Puts focus (and the keyboard) on the editor once it's shown. */
@Composable
fun RequestFocusOnce(focusRequester: FocusRequester) {
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
}
