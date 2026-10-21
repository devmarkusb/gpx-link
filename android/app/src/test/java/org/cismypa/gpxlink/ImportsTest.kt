package org.cismypa.gpxlink

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImportsTest {
    private class DocumentProvider(private val source: File) : ContentProvider() {
        var denied = false
        override fun onCreate() = true
        override fun getType(uri: Uri) = "application/gpx+xml"
        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?,
        ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply {
            addRow(arrayOf("a".repeat(300) + ".gpx"))
        }
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            if (denied) throw SecurityException("Permission revoked")
            return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    @Test
    fun importsUseUniqueBoundedNamesAndCleanUpFailedCopies() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val source = File(activity.filesDir, "source.gpx").apply { writeText("<gpx/>") }
        val provider = DocumentProvider(source)
        provider.attachInfo(activity, ProviderInfo().apply { authority = "audit.documents" })
        ShadowContentResolver.registerProviderInternal("audit.documents", provider)
        val uri = Uri.parse("content://audit.documents/source")
        val method = MainActivity::class.java.getDeclaredMethod(
            "copyUriToLocalImport", Uri::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val first = File(method.invoke(activity, uri, 0) as String)
        val second = File(method.invoke(activity, uri, 0) as String)
        assertNotEquals(first, second)
        assertEquals("<gpx/>", first.readText())
        assertEquals("<gpx/>", second.readText())
        provider.denied = true
        assertThrows(InvocationTargetException::class.java) { method.invoke(activity, uri, 0) }
        assertEquals(setOf(first.name, second.name), first.parentFile!!.list()!!.toSet())
    }
}
