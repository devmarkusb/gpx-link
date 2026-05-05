package org.cismypa.gpxlink

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.Message
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val gpxItems = mutableListOf<GpxListItem>()
    private lateinit var webView: WebView
    private lateinit var fileList: RecyclerView
    private lateinit var adapter: GpxFileAdapter
    private val importExecutor = Executors.newSingleThreadExecutor()

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            val ok =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (ok) {
                centerMapOnCurrentLocation()
            } else {
                Toast.makeText(this, R.string.location_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    private val openGpxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val uriSnapshot = uris.toList()
            importExecutor.execute {
                val imported = mutableListOf<GpxListItem>()
                val errors = mutableListOf<String>()
                var index = 0
                for (uri in uriSnapshot) {
                    try {
                        val path = copyUriToCache(uri, index)
                        val name = queryDisplayName(uri) ?: File(path).name
                        imported.add(GpxListItem(displayName = name, cachePath = path, checked = true))
                        index++
                    } catch (e: Exception) {
                        errors.add(readableError(e))
                    }
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    gpxItems.addAll(imported)
                    for (text in errors) {
                        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
                    }
                    adapter.notifyDataSetChanged()
                    persistGpxSelection()
                    reloadMap()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(application))
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)

        val panelVisible = savedInstanceState?.getBoolean(STATE_FILE_PANEL_VISIBLE, true) ?: true
        fileList = findViewById(R.id.file_list)
        fileList.visibility = if (panelVisible) View.VISIBLE else View.GONE
        toolbar.menu.findItem(R.id.action_toggle_file_list)?.isChecked = panelVisible

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> {
                    openGpxLauncher.launch(
                        arrayOf(
                            "application/gpx+xml",
                            "application/xml",
                            "text/xml",
                            "application/octet-stream",
                            "*/*",
                        ),
                    )
                    true
                }
                R.id.action_toggle_file_list -> {
                    val show = fileList.visibility != View.VISIBLE
                    fileList.visibility = if (show) View.VISIBLE else View.GONE
                    item.isChecked = show
                    true
                }
                else -> false
            }
        }

        fileList.layoutManager = LinearLayoutManager(this)
        loadPersistedGpxSelection()
        adapter = GpxFileAdapter(gpxItems) {
            persistGpxSelection()
            reloadMap()
        }
        fileList.adapter = adapter

        webView = findViewById(R.id.map_webview)
        setupWebView(webView)
        reloadMap()

        findViewById<FloatingActionButton>(R.id.fab_my_location).setOnClickListener {
            if (hasLocationPermission()) {
                centerMapOnCurrentLocation()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FILE_PANEL_VISIBLE, fileList.visibility == View.VISIBLE)
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                val ctx = view?.context ?: return false
                val popup = WebView(ctx)
                popup.settings.javaScriptEnabled = true
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: WebResourceRequest?,
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        return true
                    }
                }
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
        wv.webViewClient = WebViewClient()
    }

    private fun persistGpxSelection() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val arr = JSONArray()
        for (item in gpxItems) {
            arr.put(
                JSONObject().apply {
                    put("displayName", item.displayName)
                    put("cachePath", item.cachePath)
                    put("checked", item.checked)
                },
            )
        }
        prefs.edit().putString(KEY_GPX_ITEMS, arr.toString()).apply()
    }

    private fun loadPersistedGpxSelection() {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_GPX_ITEMS, null)
            ?: return
        val arr =
            try {
                JSONArray(raw)
            } catch (_: Exception) {
                return
            }
        gpxItems.clear()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val displayName = o.optString("displayName", "")
            val cachePath = o.optString("cachePath", "")
            val checked = o.optBoolean("checked", true)
            if (cachePath.isEmpty()) continue
            if (!File(cachePath).isFile()) continue
            val label = displayName.ifEmpty { File(cachePath).name }
            gpxItems.add(GpxListItem(displayName = label, cachePath = cachePath, checked = checked))
        }
    }

    private fun checkedPaths(): List<String> = gpxItems.filter { it.checked }.map { it.cachePath }

    private fun reloadMap() {
        renderAndLoad(checkedPaths())
    }

    private fun renderAndLoad(paths: List<String>) {
        val pathsJson = JSONArray(paths).toString()
        val jsonResult: String =
            try {
                val py = Python.getInstance()
                val module = py.getModule("bridge")
                module.callAttr("render", pathsJson).toString()
            } catch (e: Throwable) {
                Toast.makeText(
                    this,
                    e.message ?: getString(R.string.gpx_parse_error),
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
        val obj =
            try {
                JSONObject(jsonResult)
            } catch (_: Exception) {
                Toast.makeText(this, R.string.gpx_parse_error, Toast.LENGTH_LONG).show()
                return
            }
        if (!obj.optBoolean("ok", false)) {
            Toast.makeText(
                this,
                obj.optString("error", getString(R.string.gpx_parse_error)),
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (obj.optBoolean("warn_empty", false)) {
            Toast.makeText(this, R.string.gpx_empty_features, Toast.LENGTH_LONG).show()
        }
        val html = obj.getString("html")
        webView.loadDataWithBaseURL(
            "https://cdn.jsdelivr.net/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun copyUriToCache(uri: Uri, index: Int): String {
        val name = queryDisplayName(uri) ?: "import_$index.gpx"
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(cacheDir, "gpx_imports").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_${index}_$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open document")
        return out.absolutePath
    }

    private fun queryDisplayName(uri: Uri): String? {
        if (uri.scheme == "content") {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) return c.getString(i)
                }
            }
        }
        return uri.lastPathSegment
    }

    private fun readableError(error: Throwable): String {
        val msg = error.message?.trim()?.takeIf { it.isNotEmpty() }
        return msg ?: error.javaClass.simpleName.ifEmpty { "Import failed" }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun centerMapOnCurrentLocation() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val candidates =
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            )
        var newest: Location? = null
        for (p in candidates) {
            if (!lm.isProviderEnabled(p)) continue
            val loc = lm.getLastKnownLocation(p) ?: continue
            if (newest == null || loc.elapsedRealtimeNanos > newest.elapsedRealtimeNanos) {
                newest = loc
            }
        }
        if (newest != null) {
            injectUserLocationOnMap(newest.latitude, newest.longitude)
            return
        }
        val activeProvider =
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .find { lm.isProviderEnabled(it) }
        if (activeProvider == null) {
            Toast.makeText(this, R.string.location_provider_disabled, Toast.LENGTH_LONG).show()
            return
        }
        requestSingleFreshLocation(lm, activeProvider)
    }

    @SuppressLint("MissingPermission")
    private fun requestSingleFreshLocation(lm: LocationManager, provider: String) {
        Toast.makeText(this, R.string.location_searching, Toast.LENGTH_SHORT).show()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(this),
                ) { loc ->
                    if (loc != null) {
                        injectUserLocationOnMap(loc.latitude, loc.longitude)
                    } else {
                        Toast.makeText(this, R.string.location_error, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            injectUserLocationOnMap(location.latitude, location.longitude)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.location_provider_disabled,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    Looper.getMainLooper(),
                )
            }
        } catch (_: SecurityException) {
            Toast.makeText(this, R.string.location_error, Toast.LENGTH_LONG).show()
        }
    }

    private fun injectUserLocationOnMap(lat: Double, lng: Double) {
        val label = JSONObject.quote(getString(R.string.my_location_tooltip))
        webView.evaluateJavascript(
            "typeof gpxLinkSetUserLocation === 'function' && gpxLinkSetUserLocation($lat,$lng,$label)",
            null,
        )
    }

    private companion object {
        const val STATE_FILE_PANEL_VISIBLE = "file_panel_visible"
        const val PREFS_NAME = "gpxlink"
        const val KEY_GPX_ITEMS = "gpx_selection_v1"
    }
}
