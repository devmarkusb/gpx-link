package org.cismypa.gpxlink

import android.Manifest
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.webkit.ValueCallback
import android.webkit.WebView
import android.view.View
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class MainActivityTest {
    @Implements(LocationManager::class)
    class CoarseLocationManager {
        @Implementation
        fun isProviderEnabled(provider: String) = true
        @Implementation
        fun getLastKnownLocation(provider: String): Location {
            if (provider != LocationManager.NETWORK_PROVIDER) {
                throw SecurityException("This provider requires precise location on Android 10")
            }
            return Location(provider).apply { latitude = 1.0; longitude = 2.0 }
        }
    }
    private class QueuedExecutor : AbstractExecutorService() {
        val tasks = mutableListOf<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }

    private class RecordingWebView(context: Context) : WebView(context) {
        val callbacks = mutableListOf<ValueCallback<String>?>()
        var loadedUrl: String? = null
        var historyUrl: String? = null
        override fun evaluateJavascript(script: String, resultCallback: ValueCallback<String>?) {
            callbacks.add(resultCallback)
        }
        override fun loadDataWithBaseURL(
            baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?,
        ) {
            loadedUrl = baseUrl
            this.historyUrl = historyUrl
        }
    }

    private fun activity(): Pair<MainActivity, RecordingWebView> {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.setTheme(R.style.Theme_GpxLink)
        val web = RecordingWebView(activity)
        ReflectionHelpers.setField(activity, "webView", web)
        ReflectionHelpers.setField(activity, "globalBusyProgress", LinearProgressIndicator(activity))
        ReflectionHelpers.setField(activity, "mapRenderExecutor", QueuedExecutor())
        ReflectionHelpers.setField(activity, "importExecutor", QueuedExecutor())
        ReflectionHelpers.setField(activity, "filePanel", View(activity))
        ReflectionHelpers.setField(activity, "mapPanel", View(activity))
        ReflectionHelpers.setField(activity, "toolbar", MaterialToolbar(activity))
        val items = ReflectionHelpers.getField<MutableList<GpxListItem>>(activity, "gpxItems")
        ReflectionHelpers.setField(activity, "adapter", GpxFileAdapter(items, {}, { _, _ -> }))
        return activity to web
    }

    private fun call(activity: MainActivity, name: String, vararg args: Any?) {
        val method = MainActivity::class.java.declaredMethods.single {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        method.invoke(activity, *args)
    }

    @Test
    fun explicitProjectViewIsNotRecapturedFromOldPage() {
        val (activity, web) = activity()
        val savedView = Triple(48.85, 2.35, 11)
        call(activity, "setLastMapView", savedView)
        // New and Load use the explicit view, bypassing the previous document.
        val reload = MainActivity::class.java.declaredMethods.single { it.name == "reloadMap" }
        if (reload.parameterCount == 0) call(activity, "reloadMap") else call(activity, "reloadMap", false)
        assertTrue(web.callbacks.isEmpty())
        assertEquals(savedView, ReflectionHelpers.getField<Any>(activity, "lastMapView"))
        val executor = ReflectionHelpers.getField<QueuedExecutor>(activity, "mapRenderExecutor")
        assertEquals(1, executor.tasks.size)
    }

    @Test
    fun oldPageFinishCannotCompleteANewerRender() {
        val (activity, web) = activity()
        ReflectionHelpers.setField(activity, "renderRequestGeneration", 1)
        ReflectionHelpers.setField(activity, "mapBusy", true)
        val html = JSONObject().put("ok", true).put("html", "<html></html>")
        call(activity, "applyMapPayload", 1, html, null)
        val firstUrl = web.loadedUrl
        ReflectionHelpers.setField(activity, "renderRequestGeneration", 2)
        call(activity, "applyMapPayload", 2, html, null)
        val currentUrl = web.loadedUrl
        assertNotEquals(firstUrl, currentUrl)
        assertEquals(currentUrl, web.historyUrl)
        call(activity, "onMapWebPageFinished", web, firstUrl)
        assertTrue(ReflectionHelpers.getField(activity, "mapBusy"))
        call(activity, "onMapWebPageFinished", web, currentUrl)
        assertFalse(ReflectionHelpers.getField(activity, "mapBusy"))
    }

    @Test
    fun deletingDuplicateProjectRowKeepsSharedImport() {
        val (activity, _) = activity()
        val path = File(activity.filesDir, "gpx_imports/shared.gpx")
        path.parentFile!!.mkdirs()
        path.writeText("<gpx/>")
        val items = ReflectionHelpers.getField<MutableList<GpxListItem>>(activity, "gpxItems")
        items.add(GpxListItem("First", path.path))
        items.add(GpxListItem("Second", path.path))
        ReflectionHelpers.setField(activity, "adapter", GpxFileAdapter(items, {}, { _, _ -> }))
        call(activity, "removeGpxItemAt", 0)
        assertTrue(path.isFile)
        assertEquals(1, items.size)
    }

    @Test
    fun newProjectDiscardsAnOlderPendingProjectLoad() {
        val (activity, _) = activity()
        val path = File(activity.filesDir, "old.gpx").apply { writeText("<gpx/>") }
        val files = JSONArray().put(JSONObject().put("path", path.path))
        call(activity, "applyLoadedProject", JSONObject(), files)
        call(activity, "promptNewProject")
        val executor = ReflectionHelpers.getField<QueuedExecutor>(activity, "importExecutor")
        executor.tasks.single().run()
        assertTrue(ReflectionHelpers.getField<MutableList<GpxListItem>>(activity, "gpxItems").isEmpty())
        assertTrue(path.isFile) // An external project file is never deleted.
    }

    @Test
    fun nonFiniteProjectViewIsIgnored() {
        val (activity, _) = activity()
        val method = MainActivity::class.java.getDeclaredMethod("parseJsMapView", String::class.java)
        method.isAccessible = true
        assertNull(method.invoke(activity, "[\"NaN\",2,3]"))
        assertNull(method.invoke(activity, "[1,2,1e309]"))
    }

    @Test
    @Config(shadows = [CoarseLocationManager::class])
    fun approximatePermissionUsesAnAccessibleProvider() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val (activity, web) = activity()
        call(activity, "centerMapOnCurrentLocation")
        assertEquals(1, web.callbacks.size)
    }

    @Test
    @Config(sdk = [29, 30])
    fun pendingLocationRequestsAreReplacedAndCancelled() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val (activity, web) = activity()
        val manager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadow = shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        call(activity, "requestSingleFreshLocation", manager, LocationManager.NETWORK_PROVIDER)
        assertEquals(1, shadow.locationUpdateListeners.size)
        call(activity, "requestSingleFreshLocation", manager, LocationManager.NETWORK_PROVIDER)
        assertEquals(1, shadow.locationUpdateListeners.size)
        call(activity, "cancelLocationRequest")
        assertTrue(shadow.locationUpdateListeners.isEmpty())
        assertFalse(ReflectionHelpers.getField(activity, "locationBusy"))
        shadow.simulateLocation(LocationManager.NETWORK_PROVIDER, Location(LocationManager.NETWORK_PROVIDER))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(web.callbacks.isEmpty())
    }
}
