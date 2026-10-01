package dev.rortega.orchardnotes.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.rortega.orchardnotes.cloudkit.CkRecord
import dev.rortega.orchardnotes.cloudkit.CloudKitClient
import dev.rortega.orchardnotes.cloudkit.NotesZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Loads the picture behind an inline attachment: photos, and preview images of
 * drawings, scans and documents. Images are cached on disk (so they show offline)
 * and in memory (so scrolling is smooth).
 *
 * An attachment's image can live in several places, tried in order: the separate
 * `Media` record's `Asset` (originals of photos), `PrimaryAsset`, `FallbackImage`
 * (drawings) and the first of `PreviewImages`.
 */
class AttachmentImages(
    private val cloudKit: CloudKitClient,
    private val cacheDir: File,
) {
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val locks = mutableMapOf<String, Mutex>()

    /**
     * The attachment's image scaled to at most [maxWidthPx] wide, or null if it has none.
     * [zone] is the zone of the note it's in (a shared note's attachments are in its sharer's).
     */
    suspend fun load(identifier: String, maxWidthPx: Int, zone: NotesZone = NotesZone.Private): Bitmap? {
        val key = "$identifier@$maxWidthPx"
        memory.get(key)?.let { return it }
        val lock = synchronized(locks) { locks.getOrPut(identifier) { Mutex() } }
        return lock.withLock {
            memory.get(key) ?: withContext(Dispatchers.IO) {
                val file = File(cacheDir, cacheName(identifier))
                if (!file.exists()) {
                    val bytes = fetch(identifier, zone) ?: return@withContext null
                    cacheDir.mkdirs()
                    file.writeBytes(bytes)
                }
                decode(file, maxWidthPx)?.also { memory.put(key, it) }
            }
        }
    }

    fun clear() {
        memory.evictAll()
        cacheDir.deleteRecursively()
    }

    private suspend fun fetch(identifier: String, zone: NotesZone): ByteArray? {
        val attachment = cloudKit.lookup(identifier, zone) ?: return null
        for (url in candidateUrls(attachment, zone)) {
            val bytes = runCatching { cloudKit.download(url) }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private suspend fun candidateUrls(attachment: CkRecord, zone: NotesZone): List<String> = buildList {
        attachment.reference("Media")?.let { media ->
            cloudKit.lookup(media, zone)?.let { record -> assetUrl(record.value("Asset"))?.let(::add) }
        }
        listOf("PrimaryAsset", "FallbackImage").forEach { field -> assetUrl(attachment.value(field))?.let(::add) }
        (attachment.value("PreviewImages") as? JsonArray)?.firstNotNullOfOrNull(::assetUrl)?.let(::add)
    }

    private fun decode(file: File, maxWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidthPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun cacheName(identifier: String): String =
        MessageDigest.getInstance("SHA-256").digest(identifier.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MEMORY_CACHE_BYTES = 32 * 1024 * 1024

        /** The download URL of an ASSETID value, with CloudKit's filename placeholder filled in. */
        fun assetUrl(value: JsonElement?): String? {
            val url = ((value as? JsonObject)?.get("downloadURL") as? JsonPrimitive)?.contentOrNull ?: return null
            return url.replace("\${f}", "file").replace("%24%7Bf%7D", "file")
        }
    }
}
