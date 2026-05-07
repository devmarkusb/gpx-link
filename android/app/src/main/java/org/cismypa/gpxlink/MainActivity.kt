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
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /**
     * WebView tag after each [applyMapPayload]: distinguishes stale [WebViewClient.onPageFinished]
     * callbacks and carries the pan/zoom we embedded (so [lastMapView] can be committed synchronously
     * without waiting for [WebView.evaluateJavascript]).
     */
    private data class MapWebState(val ticket: Int, val preservedPanZoom: Triple<Double, Double, Int>?)

    private val gpxItems = mutableListOf<GpxListItem>()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var webView: WebView
    private lateinit var globalBusyProgress: LinearProgressIndicator
    private lateinit var filePanel: View
    private lateinit var mapPanel: View
    private lateinit var fileList: RecyclerView
    private lateinit var adapter: GpxFileAdapter
    private val importExecutor = Executors.newSingleThreadExecutor()
    private val mapRenderExecutor = Executors.newSingleThreadExecutor()
    private var importBusy = false
    private var mapBusy = false
    private var locationBusy = false
    /** Monotonic id for GPX Python render passes; bumped on each [reloadMap] start. */
    private var renderRequestGeneration = 0
    /** Monotonic id tied to successful [WebView.loadDataWithBaseURL] map loads for stale-finish guards. */
    private var committedMapWebGeneration = 0

    /** Last pan/zoom from the Leaflet map; passed into [reloadMap] so GPX toggles do not refit. */
    private var lastMapView: Triple<Double, Double, Int>? = null

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
            importBusy = true
            refreshGlobalBusyProgress()
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
                    importBusy = false
                    refreshGlobalBusyProgress()
                    reloadMap()
                }
            }
        }

    private val saveProjectLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    OutputStreamWriter(os, Charsets.UTF_8).use { w ->
                        w.write(buildProjectJson())
                    }
                } ?: error("no output stream")
                Toast.makeText(this, R.string.project_saved, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    e.message ?: getString(R.string.project_save_failed),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    private val loadProjectLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val text =
                try {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        e.message ?: getString(R.string.project_load_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                    null
                }
            if (text == null) return@registerForActivityResult
            try {
                val data = JSONObject(text)
                if (data.optString("format") != PROJECT_FORMAT) {
                    Toast.makeText(this, R.string.project_invalid, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                if (data.optInt("version") != PROJECT_VERSION) {
                    Toast.makeText(this, R.string.project_version, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val files = data.optJSONArray("files")
                if (files == null) {
                    Toast.makeText(this, R.string.project_no_files, Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                val applyNow = { applyLoadedProject(data, files) }
                if (gpxItems.isNotEmpty()) {
                    MaterialAlertDialogBuilder(this)
                        .setMessage(R.string.project_replace_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ -> applyNow() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    applyNow()
                }
            } catch (_: JSONException) {
                Toast.makeText(this, R.string.project_invalid, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(application))
        }

        globalBusyProgress = findViewById(R.id.global_busy_progress)
        globalBusyProgress.isIndeterminate = true

        toolbar = findViewById(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.navigationIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu_hamburger_24)
        toolbar.setNavigationContentDescription(R.string.menu_project_cd)
        toolbar.setNavigationOnClickListener { showProjectMenu() }

        val panelVisible = savedInstanceState?.getBoolean(STATE_FILE_PANEL_VISIBLE, true) ?: true
        filePanel = findViewById(R.id.file_panel)
        mapPanel = findViewById(R.id.map_panel)
        fileList = findViewById(R.id.file_list)
        setFilePanelVisible(panelVisible)

        savedInstanceState?.let { b ->
            if (b.containsKey(STATE_MAP_LAT) && b.containsKey(STATE_MAP_LON) && b.containsKey(STATE_MAP_ZOOM)) {
                lastMapView =
                    Triple(
                        b.getDouble(STATE_MAP_LAT),
                        b.getDouble(STATE_MAP_LON),
                        b.getInt(STATE_MAP_ZOOM),
                    )
            }
        }

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
                    val show = filePanel.visibility != View.VISIBLE
                    setFilePanelVisible(show)
                    true
                }
                else -> false
            }
        }

        fileList.layoutManager = LinearLayoutManager(this)
        loadPersistedGpxSelection()
        adapter =
            GpxFileAdapter(
                gpxItems,
                onChanged = {
                    persistGpxSelection()
                    reloadMap()
                },
                onItemLongPress = { anchor, position -> showGpxRowDeleteMenu(anchor, position) },
            )
        fileList.adapter = adapter

        findViewById<View>(R.id.btn_select_all_gpx).setOnClickListener { selectAllGpx() }
        findViewById<View>(R.id.btn_unselect_all_gpx).setOnClickListener { unselectAllGpx() }
        findViewById<View>(R.id.btn_fit_to_route).setOnClickListener { fitMapToVisibleSelection() }

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
        outState.putBoolean(STATE_FILE_PANEL_VISIBLE, filePanel.visibility == View.VISIBLE)
        lastMapView?.let {
            outState.putDouble(STATE_MAP_LAT, it.first)
            outState.putDouble(STATE_MAP_LON, it.second)
            outState.putInt(STATE_MAP_ZOOM, it.third)
        }
    }

    private fun setFilePanelVisible(visible: Boolean) {
        filePanel.visibility = if (visible) View.VISIBLE else View.GONE
        mapPanel.visibility = if (visible) View.GONE else View.VISIBLE
        updateFilePanelMenuItem(visible)
    }

    private fun updateFilePanelMenuItem(filePanelVisible: Boolean) {
        val item = toolbar.menu.findItem(R.id.action_toggle_file_list) ?: return
        item.isChecked = filePanelVisible
        item.title =
            getString(if (filePanelVisible) R.string.show_map else R.string.gpx_file_list)
        item.icon =
            ContextCompat.getDrawable(
                this,
                if (filePanelVisible) R.drawable.ic_map_24 else R.drawable.ic_gpx_list_24,
            )
    }

    private fun showProjectMenu() {
        PopupMenu(this, toolbar, Gravity.START).apply {
            menuInflater.inflate(R.menu.menu_project, menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_project_new -> {
                        promptNewProject()
                        true
                    }
                    R.id.action_project_save -> {
                        saveProjectLauncher.launch("project.gpxlink.json")
                        true
                    }
                    R.id.action_project_load -> {
                        loadProjectLauncher.launch(
                            arrayOf("application/json", "application/*", "*/*"),
                        )
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun promptNewProject() {
        if (gpxItems.isEmpty()) {
            lastMapView = null
            reloadMap()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.project_new_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (item in gpxItems) {
                    File(item.cachePath).delete()
                }
                gpxItems.clear()
                adapter.notifyDataSetChanged()
                persistGpxSelection()
                lastMapView = null
                reloadMap()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildProjectJson(): String {
        val files = JSONArray()
        for (item in gpxItems) {
            files.put(
                JSONObject().apply {
                    put("path", item.cachePath)
                    put("checked", item.checked)
                    put("label", item.displayName)
                },
            )
        }
        return JSONObject()
            .apply {
                put("format", PROJECT_FORMAT)
                put("version", PROJECT_VERSION)
                put("show_file_panel", filePanel.visibility == View.VISIBLE)
                put("map_view", JSONObject.NULL)
                put("splitter_state_b64", JSONObject.NULL)
                put("files", files)
            }
            .toString(2) + "\n"
    }

    private fun applyLoadedProject(root: JSONObject, files: JSONArray) {
        for (item in gpxItems) {
            File(item.cachePath).delete()
        }
        gpxItems.clear()
        var skipped = 0
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i) ?: continue
            val pathStr = o.optString("path", "")
            if (pathStr.isEmpty()) continue
            val f = File(pathStr)
            if (!f.isFile()) {
                skipped++
                continue
            }
            val checked = o.optBoolean("checked", true)
            val labelRaw = o.optString("label", "")
            val label = labelRaw.ifBlank { f.name }
            gpxItems.add(GpxListItem(displayName = label, cachePath = pathStr, checked = checked))
        }
        adapter.notifyDataSetChanged()
        val showPanel = root.optBoolean("show_file_panel", true)
        setFilePanelVisible(showPanel)
        persistGpxSelection()
        lastMapView = null
        reloadMap()
        if (skipped > 0) {
            Toast.makeText(this, R.string.project_missing_paths, Toast.LENGTH_LONG).show()
        }
    }

    private fun selectAllGpx() {
        var changed = false
        for (item in gpxItems) {
            if (!item.checked) {
                item.checked = true
                changed = true
            }
        }
        if (!changed) return
        adapter.notifyDataSetChanged()
        persistGpxSelection()
        reloadMap()
    }

    private fun unselectAllGpx() {
        var changed = false
        for (item in gpxItems) {
            if (item.checked) {
                item.checked = false
                changed = true
            }
        }
        if (!changed) return
        adapter.notifyDataSetChanged()
        persistGpxSelection()
        reloadMap()
    }

    private fun showGpxRowDeleteMenu(anchor: View, position: Int) {
        if (position !in gpxItems.indices) return
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.menu_gpx_row, menu)
            setOnMenuItemClickListener { item: MenuItem ->
                if (item.itemId == R.id.action_delete_gpx_row) {
                    removeGpxItemAt(position)
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    private fun removeGpxItemAt(position: Int) {
        if (position !in gpxItems.indices) return
        val removed = gpxItems.removeAt(position)
        File(removed.cachePath).delete()
        adapter.notifyItemRemoved(position)
        val rest = gpxItems.size - position
        if (rest > 0) {
            adapter.notifyItemRangeChanged(position, rest)
        }
        persistGpxSelection()
        reloadMap()
    }

    override fun onDestroy() {
        importExecutor.shutdownNow()
        mapRenderExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshGlobalBusyProgress() {
        val busy = importBusy || mapBusy || locationBusy
        globalBusyProgress.isVisible = busy
    }

    private fun applyMapPayload(
        rq: Int,
        jsonPayload: JSONObject,
        preservedPanZoom: Triple<Double, Double, Int>?,
    ) {
        if (rq != renderRequestGeneration) {
            // A newer [reloadMap] is in flight; keep mapBusy as that pass owns it.
            return
        }

        val html =
            if (jsonPayload.optBoolean("ok", false)) {
                jsonPayload.optString("html", "")
            } else {
                ""
            }

        if (html.isBlank() || !jsonPayload.optBoolean("ok", false)) {
            Toast.makeText(
                this,
                jsonPayload.optString("error", getString(R.string.gpx_parse_error)),
                Toast.LENGTH_LONG,
            ).show()
            mapBusy = false
            refreshGlobalBusyProgress()
            return
        }

        if (jsonPayload.optBoolean("warn_empty", false)) {
            Toast.makeText(this, R.string.gpx_empty_features, Toast.LENGTH_LONG).show()
        }

        committedMapWebGeneration += 1
        val ticket = committedMapWebGeneration
        webView.tag = MapWebState(ticket, preservedPanZoom)
        webView.loadDataWithBaseURL(
            "https://cdn.jsdelivr.net/",
            html,
            "text/html",
            "UTF-8",
            null,
        )
    }

    private fun onMapWebPageFinished(webViewFinished: WebView) {
        if (webViewFinished !== webView) return
        val state = webViewFinished.tag as? MapWebState ?: return
        if (state.ticket != committedMapWebGeneration) return
        if (!mapBusy) return
        mapBusy = false
        refreshGlobalBusyProgress()
        val preserved = state.preservedPanZoom
        if (preserved != null) {
            lastMapView = preserved
        } else {
            captureMapViewIfCurrent(webViewFinished, state.ticket)
        }
    }

    private fun captureMapViewIfCurrent(wv: WebView, ticket: Int) {
        wv.post {
            if ((wv.tag as? MapWebState)?.ticket != ticket) return@post
            wv.evaluateJavascript(
                "(function(){try{if(typeof map==='undefined'||!map.getCenter)return null;" +
                    "var c=map.getCenter();return[c.lat,c.lng,map.getZoom()];}catch(e){return null;}})()",
            ) { raw ->
                if ((wv.tag as? MapWebState)?.ticket != ticket) return@evaluateJavascript
                parseJsMapView(raw)?.let { lastMapView = it }
            }
        }
    }

    private fun parseJsMapView(raw: String?): Triple<Double, Double, Int>? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val arr = JSONArray(raw)
            if (arr.length() < 3) return null
            Triple(arr.getDouble(0), arr.getDouble(1), arr.getInt(2))
        } catch (_: Exception) {
            null
        }
    }

    private fun fitMapToVisibleSelection() {
        val paths = checkedPaths()
        if (paths.isEmpty()) {
            Toast.makeText(this, R.string.fit_map_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val pathsJson = JSONArray(paths).toString()
        mapRenderExecutor.execute {
            val jsonRaw: String? =
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("bridge")
                    module.callAttr("fit_bounds_corners", pathsJson).toString()
                } catch (_: Throwable) {
                    null
                }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyFitBoundsCornersResult(jsonRaw)
            }
        }
    }

    private fun applyFitBoundsCornersResult(jsonRaw: String?) {
        if (jsonRaw.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.gpx_parse_error), Toast.LENGTH_LONG).show()
            return
        }
        val obj =
            try {
                JSONObject(jsonRaw)
            } catch (_: Exception) {
                Toast.makeText(this, getString(R.string.gpx_parse_error), Toast.LENGTH_LONG).show()
                return
            }
        if (!obj.optBoolean("ok", false)) {
            when (obj.optString("error")) {
                "no_paths" ->
                    Toast.makeText(this, R.string.fit_map_need_selection, Toast.LENGTH_SHORT).show()
                "no_coordinates" ->
                    Toast.makeText(this, R.string.gpx_empty_features, Toast.LENGTH_LONG).show()
                else ->
                    Toast.makeText(
                        this,
                        obj.optString("error", getString(R.string.gpx_parse_error)),
                        Toast.LENGTH_LONG,
                    ).show()
            }
            return
        }
        val corners = obj.optJSONArray("corners") ?: return
        val lit = corners.toString()
        val ticket = (webView.tag as? MapWebState)?.ticket ?: return
        webView.evaluateJavascript(
            "(function(){try{map.fitBounds($lit,{animate:false});var c=map.getCenter();return[c.lat,c.lng,map.getZoom()];}catch(e){return null;}})()",
        ) { raw ->
            if ((webView.tag as? MapWebState)?.ticket != ticket) return@evaluateJavascript
            parseJsMapView(raw)?.let { lastMapView = it }
        }
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
        wv.webViewClient =
            object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view !== wv) return
                    onMapWebPageFinished(wv)
                }
            }
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
        renderRequestGeneration += 1
        val rq = renderRequestGeneration
        mapBusy = true
        refreshGlobalBusyProgress()
        captureMapViewForReload(rq)
    }

    private fun captureMapViewForReload(rq: Int) {
        webView.evaluateJavascript(
            "(function(){try{if(typeof map==='undefined'||!map.getCenter)return null;" +
                "var c=map.getCenter();return[c.lat,c.lng,map.getZoom()];}catch(e){return null;}})()",
        ) { raw ->
            if (isFinishing || isDestroyed) return@evaluateJavascript
            if (rq != renderRequestGeneration) return@evaluateJavascript
            val currentMapView = parseJsMapView(raw)
            if (currentMapView != null) {
                lastMapView = currentMapView
            }
            renderMapForCurrentSelection(rq, currentMapView ?: lastMapView)
        }
    }

    private fun renderMapForCurrentSelection(
        rq: Int,
        preservedPanZoom: Triple<Double, Double, Int>?,
    ) {
        val pathsJson = JSONArray(checkedPaths()).toString()
        val mapViewJson =
            preservedPanZoom?.let { JSONArray(listOf(it.first, it.second, it.third)).toString() }
                ?: "null"
        mapRenderExecutor.execute {
            val jsonRaw: String? =
                try {
                    val py = Python.getInstance()
                    val module = py.getModule("bridge")
                    module.callAttr("render", pathsJson, mapViewJson).toString()
                } catch (_: Throwable) {
                    null
                }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (rq != renderRequestGeneration) {
                    return@runOnUiThread
                }
                if (jsonRaw == null) {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.gpx_parse_error),
                        Toast.LENGTH_LONG,
                    ).show()
                    mapBusy = false
                    refreshGlobalBusyProgress()
                    return@runOnUiThread
                }
                val obj =
                    try {
                        JSONObject(jsonRaw)
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            R.string.gpx_parse_error,
                            Toast.LENGTH_LONG,
                        ).show()
                        mapBusy = false
                        refreshGlobalBusyProgress()
                        return@runOnUiThread
                    }
                applyMapPayload(rq, obj, preservedPanZoom)
            }
        }
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
        locationBusy = true
        refreshGlobalBusyProgress()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(
                    provider,
                    CancellationSignal(),
                    ContextCompat.getMainExecutor(this),
                ) { loc ->
                    locationBusy = false
                    refreshGlobalBusyProgress()
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
                            locationBusy = false
                            refreshGlobalBusyProgress()
                            injectUserLocationOnMap(location.latitude, location.longitude)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {
                            locationBusy = false
                            refreshGlobalBusyProgress()
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
            locationBusy = false
            refreshGlobalBusyProgress()
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
        const val STATE_MAP_LAT = "map_lat"
        const val STATE_MAP_LON = "map_lon"
        const val STATE_MAP_ZOOM = "map_zoom"
        const val PREFS_NAME = "gpxlink"
        const val KEY_GPX_ITEMS = "gpx_selection_v1"
        const val PROJECT_FORMAT = "gpx-link-project"
        const val PROJECT_VERSION = 1
    }
}
