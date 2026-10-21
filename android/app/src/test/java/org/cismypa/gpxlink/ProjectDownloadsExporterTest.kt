package org.cismypa.gpxlink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ProjectDownloadsExporterTest {
    private class DownloadsProvider : ContentProvider() {
        val database = SQLiteDatabase.create(null)
        val files = mutableMapOf<Long, File>()
        init {
            database.execSQL(
                "CREATE TABLE downloads (_id INTEGER PRIMARY KEY, _display_name TEXT, " +
                    "relative_path TEXT, mime_type TEXT, is_pending INTEGER)",
            )
        }
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/json"
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = database.query("downloads", projection, selection, selectionArgs, null, null, sortOrder)
        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val id = database.insertOrThrow("downloads", null, values)
            files[id] = File.createTempFile("download", ".json")
            return Uri.withAppendedPath(uri, id.toString())
        }
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int =
            database.update("downloads", values, "_id = ?", arrayOf(uri.lastPathSegment))
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int =
            database.delete("downloads", "_id = ?", arrayOf(uri.lastPathSegment))
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            ParcelFileDescriptor.open(files.getValue(uri.lastPathSegment!!.toLong()), ParcelFileDescriptor.parseMode(mode))
    }

    @Test
    fun saveOnlyOverwritesTheExactProjectDirectory() {
        val provider = DownloadsProvider()
        val app = RuntimeEnvironment.getApplication()
        provider.attachInfo(app, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        try {
            val sibling = provider.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "project.gpxlink.json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MapsGpxBackup/")
                },
            )
            val siblingFile = provider.files.getValue(sibling.lastPathSegment!!.toLong())
            siblingFile.writeText("keep this backup")
            ProjectDownloadsExporter.exportText(app, "a long first project")
            ProjectDownloadsExporter.exportText(app, "new")
            assertEquals("keep this backup", siblingFile.readText())
            assertEquals(2, provider.files.size)
            assertEquals("new", provider.files.values.single { it != siblingFile }.readText())
        } finally {
            provider.files.values.forEach { it.delete() }
            provider.database.close()
        }
    }
}
