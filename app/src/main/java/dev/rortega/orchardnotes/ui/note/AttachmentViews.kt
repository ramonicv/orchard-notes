package dev.rortega.orchardnotes.ui.note

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Attachment
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** What kind of embedded object a UTI denotes, for labels and icons. */
enum class AttachmentKind(val label: String, val icon: ImageVector) {
    Image("Image", Icons.Outlined.Image),
    Table("Table", Icons.Outlined.TableChart),
    Drawing("Drawing", Icons.Outlined.Brush),
    Scan("Scanned document", Icons.Outlined.DocumentScanner),
    Link("Link", Icons.Outlined.Link),
    Pdf("PDF", Icons.Outlined.PictureAsPdf),
    Audio("Audio recording", Icons.Outlined.Mic),
    Video("Video", Icons.Outlined.Movie),
    File("Attachment", Icons.Outlined.Attachment),
    ;

    companion object {
        private val IMAGE_UTIS = setOf("public.jpeg", "public.png", "public.heic", "public.heif", "public.tiff", "com.compuserve.gif", "public.gif", "org.webmproject.webp", "public.webp", "public.image")

        fun of(uti: String): AttachmentKind = when {
            uti in IMAGE_UTIS -> Image
            uti == "com.apple.notes.table" -> Table
            uti.startsWith("com.apple.drawing") || uti == "com.apple.paper" || uti.startsWith("com.apple.notes.sketch") -> Drawing
            uti == "com.apple.notes.gallery" -> Scan
            uti == "public.url" -> Link
            uti == "com.adobe.pdf" -> Pdf
            uti.contains("audio") || uti == "com.apple.m4a-audio" -> Audio
            uti.contains("movie") || uti == "public.mpeg-4" -> Video
            else -> File
        }
    }
}

/** A labeled card standing in for an attachment this app can't display inline. */
@Composable
fun AttachmentPlaceholder(typeUti: String, modifier: Modifier = Modifier, label: String? = null) {
    val kind = AttachmentKind.of(typeUti)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(kind.icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(22.dp))
            Text(label ?: kind.label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
