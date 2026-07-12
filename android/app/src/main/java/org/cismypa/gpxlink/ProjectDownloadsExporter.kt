package org.cismypa.gpxlink

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Writes project JSON into a user-visible folder under public Downloads
 * ([FOLDER_NAME]), using [MediaStore] on API 29+ and direct files on API 24–28
 * (requires [android.Manifest.permission.WRITE_EXTERNAL_STORAGE]).
 */
object ProjectDownloadsExporter {

    const val FOLDER_NAME = "gpx-link"

    private const val MIME_JSON = "application/json"

    /** Trailing slash matches what MediaStore persists for [RELATIVE_PATH]. */
    private val downloadsRelativePath: String
        get() = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/"

    /** Label for UI (not always a real absolute path on Android 10+). */
    fun displayPath(fileName: String): String = "Downloads/$FOLDER_NAME/$fileName"

    /**
     * Writes [text] into public Downloads under [FOLDER_NAME].
     * @return [displayPath] for the name actually used.
     */
    fun exportText(
        context: Context,
        text: String,
        preferredName: String = "project.gpxlink.json",
    ): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context.contentResolver, bytes, preferredName)
        } else {
            exportLegacy(bytes, preferredName)
        }
    }

    private fun exportLegacy(bytes: ByteArray, preferredName: String): String {
        val baseDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FOLDER_NAME,
        )
        baseDir.mkdirs()
        val dest = File(baseDir, preferredName)
        dest.outputStream().use { it.write(bytes) }
        return displayPath(dest.name)
    }

    private fun exportViaMediaStore(
        resolver: ContentResolver,
        bytes: ByteArray,
        preferredName: String,
    ): String {
        findExistingEntry(resolver, preferredName)?.let { (uri, displayName) ->
            writeToUri(resolver, uri, bytes)
            return displayPath(displayName)
        }
        val displayName = pickUniqueDisplayName(resolver, preferredName)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_JSON)
            put(MediaStore.MediaColumns.RELATIVE_PATH, downloadsRelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values)
            ?: error("MediaStore.insert returned null for $displayName")
        try {
            writeToUri(resolver, uri, bytes)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return displayPath(displayName)
    }

    private fun writeToUri(resolver: ContentResolver, uri: Uri, bytes: ByteArray) {
        resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) }
    }

    private fun pickUniqueDisplayName(resolver: ContentResolver, preferredName: String): String {
        if (findExistingEntry(resolver, preferredName) == null) return preferredName
        val stem = preferredName.substringBeforeLast('.', preferredName)
        val ext = if ('.' in preferredName) ".${preferredName.substringAfterLast('.')}" else ""
        for (n in 1..9999) {
            val candidate = "${stem}_$n$ext"
            if (findExistingEntry(resolver, candidate) == null) return candidate
        }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }

    private fun findExistingEntry(resolver: ContentResolver, displayName: String): Pair<Uri, String>? {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(displayName, "${downloadsRelativePath.trimEnd('/')}%")
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id) to name
        }
        return null
    }
}
