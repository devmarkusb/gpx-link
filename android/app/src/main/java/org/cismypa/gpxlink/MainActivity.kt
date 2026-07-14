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
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    /**
     * WebView tag after each [applyMapPayload]: distinguishes stale [WebViewClient.onPageFinished]
     * callbacks and carries the pan/zoom we embedded (so [lastMapView] can be committed synchronously
     * without waiting for [WebView.evaluateJavascript]).
     */
    private data class MapWebState(val ticket: Int, val preservedPanZoom: Triple<Double, Double, Int>?)

    private data class ProjectFileEntry(
        val path: String,
        val uri: String?,
        val checked: Boolean,
        val label: String,
    )

    private val gpxItems = mutableListOf<GpxListItem>()
    private lateinit var toolbar: MaterialToolbar
    private lateinit var webView: WebView
    private lateinit var globalBusyProgress: LinearProgressIndicator
    private lateinit var filePanel: View
    private lateinit var mapPanel: View
    private lateinit var fileList: RecyclerView
    private lateinit var adapter: GpxFileAdapter
    private lateinit var adBannerContainer: FrameLayout
    private var removeAdsBilling: RemoveAdsBilling? = null
    private var mapBannerAdView: AdView? = null
    private var panelTransitionInterstitialAd: InterstitialAd? = null
    private var panelTransitionInterstitialLoading = false
    private var panelTransitionInterstitialShowing = false
    private var lastNotifiedAdFree: Boolean? = null
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
                        persistReadableDocumentUri(uri)
                        val path = copyUriToLocalImport(uri, index)
                        val name = queryDisplayName(uri) ?: File(path).name
                        imported.add(
                            GpxListItem(
                                displayName = name,
                                cachePath = path,
                                sourceUri = uri.toString(),
                                checked = true,
                            ),
                        )
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

    private var pendingSaveToDownloads = false

    private val requestWriteStoragePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && pendingSaveToDownloads) {
                pendingSaveToDownloads = false
                saveProjectToDownloads()
            } else {
                pendingSaveToDownloads = false
            }
        }

    private val saveProjectAsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                writeProjectJsonToUri(uri)
                persistProjectDocumentPermission(uri)
                Toast.makeText(this, R.string.project_saved_as, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        DocumentsContract.deleteDocument(contentResolver, uri)
                    }
                }
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
            persistReadableDocumentUri(uri)
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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applySystemBarInsets()

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

        val restoredMapView = savedInstanceState?.let { b ->
            if (b.containsKey(STATE_MAP_LAT) && b.containsKey(STATE_MAP_LON) && b.containsKey(STATE_MAP_ZOOM)) {
                Triple(
                    b.getDouble(STATE_MAP_LAT),
                    b.getDouble(STATE_MAP_LON),
                    b.getInt(STATE_MAP_ZOOM),
                )
            } else {
                null
            }
        } ?: loadPersistedMapView()
        if (restoredMapView != null) {
            lastMapView = restoredMapView
        }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_open -> {
                    launchGpxImport()
                    true
                }
                R.id.action_toggle_file_list -> {
                    val show = filePanel.visibility != View.VISIBLE
                    requestFilePanelVisible(show)
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

        adBannerContainer = findViewById(R.id.ad_banner_container)
        setupAdsAndBilling()
    }

    override fun onResume() {
        super.onResume()
        mapBannerAdView?.resume()
        removeAdsBilling?.refreshOwnedPurchases()
    }

    override fun onPause() {
        (webView.tag as? MapWebState)?.ticket?.let { captureMapViewIfCurrent(webView, it) }
        mapBannerAdView?.pause()
        super.onPause()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars =
                windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
            view.updatePadding(
                left = bars.left,
                top = bars.top,
                right = bars.right,
                bottom = bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun setupAdsAndBilling() {
        val billing =
            RemoveAdsBilling(
                activity = this,
                productId = BuildConfig.REMOVE_ADS_PRODUCT_ID,
                prefsName = PREFS_NAME,
                onAdFreeChanged = { adFree -> runOnUiThread { applyAdFreeUi(adFree) } },
            )
        removeAdsBilling = billing
        if (billing.isAdFreeCached()) {
            adBannerContainer.visibility = View.GONE
        }
        requestAdsConsentThen(this) {
            applyAdMobDebugTestDeviceConfiguration()
            MobileAds.initialize(this) {}
            removeAdsBilling?.start()
            maybeShowBannerAd()
            maybeLoadPanelTransitionInterstitialAd()
        }
    }

    private fun applyAdFreeUi(adFree: Boolean) {
        val previous = lastNotifiedAdFree
        if (adFree) {
            mapBannerAdView?.destroy()
            mapBannerAdView = null
            panelTransitionInterstitialAd = null
            panelTransitionInterstitialLoading = false
            adBannerContainer.removeAllViews()
            adBannerContainer.visibility = View.GONE
            if (previous == false) {
                Toast.makeText(this, R.string.remove_ads_thanks, Toast.LENGTH_LONG).show()
            }
        } else {
            adBannerContainer.visibility = View.VISIBLE
            maybeShowBannerAd()
            maybeLoadPanelTransitionInterstitialAd()
        }
        lastNotifiedAdFree = adFree
    }

    private fun maybeShowBannerAd() {
        val billing = removeAdsBilling ?: return
        if (billing.isAdFreeCached()) return
        if (mapBannerAdView != null) return
        val adView = AdView(this)
        adView.adUnitId = getString(R.string.admob_banner_unit_id)
        val adWidthDp =
            (resources.displayMetrics.widthPixels / resources.displayMetrics.density)
                .toInt()
                .coerceIn(320, 720)
        adView.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(this, adWidthDp))
        adView.adListener =
            object : AdListener() {
                override fun onAdLoaded() {
                    adBannerContainer.visibility = View.VISIBLE
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    adBannerContainer.visibility = View.GONE
                }
            }
        adBannerContainer.removeAllViews()
        adBannerContainer.addView(adView)
        mapBannerAdView = adView
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun maybeLoadPanelTransitionInterstitialAd() {
        val billing = removeAdsBilling ?: return
        if (billing.isAdFreeCached()) return
        if (panelTransitionInterstitialAd != null || panelTransitionInterstitialLoading) return
        panelTransitionInterstitialLoading = true
        InterstitialAd.load(
            this,
            getString(R.string.admob_interstitial_unit_id),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(interstitialAd: InterstitialAd) {
                    panelTransitionInterstitialLoading = false
                    if (isFinishing || isDestroyed || removeAdsBilling?.isAdFreeCached() == true) {
                        panelTransitionInterstitialAd = null
                        return
                    }
                    panelTransitionInterstitialAd = interstitialAd
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    panelTransitionInterstitialLoading = false
                    panelTransitionInterstitialAd = null
                }
            },
        )
    }

    private fun requestFilePanelVisible(visible: Boolean) {
        if ((filePanel.visibility == View.VISIBLE) == visible) return
        showPanelTransitionInterstitialThen {
            setFilePanelVisible(visible)
        }
    }

    private fun showPanelTransitionInterstitialThen(onFinished: () -> Unit) {
        val billing = removeAdsBilling
        val interstitialAd = panelTransitionInterstitialAd
        if (
            billing?.isAdFreeCached() == true ||
                interstitialAd == null ||
                panelTransitionInterstitialShowing
        ) {
            onFinished()
            maybeLoadPanelTransitionInterstitialAd()
            return
        }

        panelTransitionInterstitialAd = null
        panelTransitionInterstitialShowing = true
        var finished = false
        fun finishTransition() {
            if (finished) return
            finished = true
            panelTransitionInterstitialShowing = false
            if (isFinishing || isDestroyed) return
            onFinished()
            maybeLoadPanelTransitionInterstitialAd()
        }

        interstitialAd.fullScreenContentCallback =
            object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    finishTransition()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    finishTransition()
                }
            }
        interstitialAd.show(this)
    }

    private fun promptRemoveAdsPurchase() {
        val billing = removeAdsBilling ?: return
        if (billing.isAdFreeCached()) {
            Toast.makeText(this, R.string.remove_ads_already_owned, Toast.LENGTH_LONG).show()
            return
        }
        billing.launchRemoveAdsPurchase()
    }

    private fun restorePurchases() {
        removeAdsBilling?.refreshOwnedPurchases()
        Toast.makeText(this, R.string.restore_purchases_done, Toast.LENGTH_SHORT).show()
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

    private fun isAdFreeOwned(): Boolean =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(RemoveAdsBilling.PREF_KEY_AD_FREE, false)

    private fun markerLabelsFromPrefs(): String {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MARKER_LABELS, null)
        val v = raw?.trim()?.lowercase(Locale.ROOT)
        return when (v) {
            "on", "off", "auto" -> v
            else -> "auto"
        }
    }

    private fun setMarkerLabelMode(mode: String) {
        val m =
            when (mode.lowercase(Locale.ROOT)) {
                "on", "off", "auto" -> mode.lowercase(Locale.ROOT)
                else -> "auto"
            }
        if (m == markerLabelsFromPrefs()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_MARKER_LABELS, m).apply()
        reloadMap()
    }

    private fun markerLabelModeTitleRes(mode: String): Int =
        when (mode) {
            "on" -> R.string.marker_labels_on
            "off" -> R.string.marker_labels_off
            else -> R.string.marker_labels_auto
        }

    private fun markerLabelModeIndex(mode: String): Int =
        when (mode) {
            "on" -> 1
            "off" -> 2
            else -> 0
        }

    private fun markerLabelModeAt(index: Int): String =
        when (index) {
            1 -> "on"
            2 -> "off"
            else -> "auto"
        }

    private fun showMarkerLabelModeDialog() {
        val mode = markerLabelsFromPrefs()
        val labels: Array<CharSequence> =
            arrayOf(
                getString(R.string.marker_labels_auto),
                getString(R.string.marker_labels_on),
                getString(R.string.marker_labels_off),
            )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.marker_labels)
            .setSingleChoiceItems(labels, markerLabelModeIndex(mode)) { dialog, which ->
                setMarkerLabelMode(markerLabelModeAt(which))
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchGpxImport() {
        openGpxLauncher.launch(
            arrayOf(
                "application/gpx+xml",
                "application/xml",
                "text/xml",
                "application/octet-stream",
                "*/*",
            ),
        )
    }

    private fun showProjectMenu() {
        PopupMenu(this, toolbar, Gravity.START).apply {
            menuInflater.inflate(R.menu.menu_project, menu)
            menu.findItem(R.id.action_remove_ads)?.isVisible = !isAdFreeOwned()
            menu.findItem(R.id.menu_marker_labels)?.let { item ->
                val mode = markerLabelsFromPrefs()
                item.title =
                    getString(
                        R.string.marker_labels_current,
                        getString(markerLabelModeTitleRes(mode)),
                    )
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_project_new -> {
                        promptNewProject()
                        true
                    }
                    R.id.action_project_save -> {
                        promptSaveProjectToDownloads()
                        true
                    }
                    R.id.action_project_save_as -> {
                        saveProjectAsLauncher.launch("project.gpxlink.json")
                        true
                    }
                    R.id.action_project_load -> {
                        loadProjectLauncher.launch(
                            arrayOf("application/json", "application/*", "*/*"),
                        )
                        true
                    }
                    R.id.action_import_gpx -> {
                        launchGpxImport()
                        true
                    }
                    R.id.menu_marker_labels -> {
                        showMarkerLabelModeDialog()
                        true
                    }
                    R.id.action_remove_ads -> {
                        promptRemoveAdsPurchase()
                        true
                    }
                    R.id.action_restore_purchases -> {
                        restorePurchases()
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
            setLastMapView(null)
            reloadMap()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.project_new_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (item in gpxItems) {
                    deleteManagedImportPath(item.cachePath)
                }
                gpxItems.clear()
                adapter.notifyDataSetChanged()
                persistGpxSelection()
                setLastMapView(null)
                reloadMap()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun promptSaveProjectToDownloads() {
        val run = { saveProjectToDownloads() }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveToDownloads = true
            if (shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.project_storage_permission_for_downloads)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        requestWriteStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        pendingSaveToDownloads = false
                    }
                    .show()
            } else {
                requestWriteStoragePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            run()
        }
    }

    private fun saveProjectToDownloads() {
        try {
            val path =
                ProjectDownloadsExporter.exportText(
                    this,
                    buildProjectJson(),
                )
            Toast.makeText(
                this,
                getString(R.string.project_saved, path),
                Toast.LENGTH_LONG,
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                e.message ?: getString(R.string.project_save_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun writeProjectJsonToUri(uri: Uri) {
        val bytes = buildProjectJson().toByteArray(Charsets.UTF_8)
        var lastError: Exception? = null
        for (mode in arrayOf<String?>("wt", "w", null)) {
            try {
                val output =
                    if (mode == null) {
                        contentResolver.openOutputStream(uri)
                    } else {
                        contentResolver.openOutputStream(uri, mode)
                    }
                output?.use { os ->
                    os.write(bytes)
                    os.flush()
                } ?: error(getString(R.string.project_save_failed))
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException(getString(R.string.project_save_failed))
    }

    private fun buildProjectJson(): String {
        val files = JSONArray()
        for (item in gpxItems) {
            files.put(
                JSONObject().apply {
                    put("path", item.cachePath)
                    item.sourceUri?.takeIf { it.isNotBlank() }?.let { put("uri", it) }
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
                put(
                    "map_view",
                    lastMapView?.let { JSONArray(listOf(it.first, it.second, it.third)) }
                        ?: JSONObject.NULL,
                )
                put("splitter_state_b64", JSONObject.NULL)
                put("files", files)
            }
            .toString(2) + "\n"
    }

    private fun applyLoadedProject(root: JSONObject, files: JSONArray) {
        val entries = projectFileEntries(files)
        val showPanel = root.optBoolean("show_file_panel", true)
        val mapViewRaw = root.optJSONArray("map_view")?.toString()
        importBusy = true
        refreshGlobalBusyProgress()
        importExecutor.execute {
            val loaded = mutableListOf<GpxListItem>()
            var skipped = 0
            for ((index, entry) in entries.withIndex()) {
                try {
                    val item = loadProjectEntry(entry, index)
                    if (item == null) {
                        skipped++
                    } else {
                        loaded.add(item)
                    }
                } catch (_: Exception) {
                    skipped++
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val loadedPaths = loaded.mapNotNull { canonicalPathOrNull(it.cachePath) }.toSet()
                for (item in gpxItems) {
                    if (canonicalPathOrNull(item.cachePath) !in loadedPaths) {
                        deleteManagedImportPath(item.cachePath)
                    }
                }
                gpxItems.clear()
                gpxItems.addAll(loaded)
                adapter.notifyDataSetChanged()
                setFilePanelVisible(showPanel)
                persistGpxSelection()
                setLastMapView(mapViewRaw?.let { parseJsMapView(it) })
                importBusy = false
                refreshGlobalBusyProgress()
                reloadMap()
                if (skipped > 0) {
                    Toast.makeText(this, R.string.project_missing_paths, Toast.LENGTH_LONG).show()
                }
            }
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
        deleteManagedImportPath(removed.cachePath)
        adapter.notifyItemRemoved(position)
        val rest = gpxItems.size - position
        if (rest > 0) {
            adapter.notifyItemRangeChanged(position, rest)
        }
        persistGpxSelection()
        reloadMap()
    }

    override fun onDestroy() {
        mapBannerAdView?.destroy()
        mapBannerAdView = null
        panelTransitionInterstitialAd = null
        panelTransitionInterstitialLoading = false
        panelTransitionInterstitialShowing = false
        removeAdsBilling?.close()
        removeAdsBilling = null
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
            setLastMapView(preserved)
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
                parseJsMapView(raw)?.let { setLastMapView(it) }
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
            parseJsMapView(raw)?.let { setLastMapView(it) }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.setSupportMultipleWindows(true)
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (view === wv) {
                    onMapWebTitle(title)
                }
            }

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
                    item.sourceUri?.takeIf { it.isNotBlank() }?.let { put("sourceUri", it) }
                    put("checked", item.checked)
                },
            )
        }
        prefs.edit().putString(KEY_GPX_ITEMS, arr.toString()).apply()
    }

    private fun setLastMapView(view: Triple<Double, Double, Int>?) {
        lastMapView = view
        val edit = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
        if (view == null) {
            edit.remove(KEY_MAP_VIEW)
        } else {
            edit.putString(
                KEY_MAP_VIEW,
                JSONArray(listOf(view.first, view.second, view.third)).toString(),
            )
        }
        edit.apply()
    }

    private fun loadPersistedMapView(): Triple<Double, Double, Int>? {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_MAP_VIEW, null)
        return parseJsMapView(raw)
    }

    private fun onMapWebTitle(title: String?) {
        if (title == null || !title.startsWith(MAP_VIEW_TITLE_PREFIX)) return
        parseJsMapView(title.removePrefix(MAP_VIEW_TITLE_PREFIX))?.let { setLastMapView(it) }
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
            val sourceUri = o.optString("sourceUri", "").takeIf { it.isNotBlank() }
            val checked = o.optBoolean("checked", true)
            if (cachePath.isEmpty()) continue
            if (!File(cachePath).isFile()) continue
            val label = displayName.ifEmpty { File(cachePath).name }
            gpxItems.add(
                GpxListItem(
                    displayName = label,
                    cachePath = cachePath,
                    sourceUri = sourceUri,
                    checked = checked,
                ),
            )
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
                setLastMapView(currentMapView)
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
                    module.callAttr("render", pathsJson, mapViewJson, markerLabelsFromPrefs()).toString()
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

    private fun copyUriToLocalImport(uri: Uri, index: Int): String {
        val name = queryDisplayName(uri) ?: "import_$index.gpx"
        val safe = name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val dir = File(filesDir, "gpx_imports").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_${index}_$safe")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open document")
        return out.absolutePath
    }

    private fun projectFileEntries(files: JSONArray): List<ProjectFileEntry> {
        val entries = mutableListOf<ProjectFileEntry>()
        for (i in 0 until files.length()) {
            val o = files.optJSONObject(i)
            if (o == null) {
                val path = files.optString(i, "")
                if (path.isBlank()) continue
                val uri = path.takeIf { isContentUriString(it) }
                entries.add(
                    ProjectFileEntry(
                        path = if (uri == null) path else "",
                        uri = uri,
                        checked = true,
                        label = "",
                    ),
                )
                continue
            }
            val pathRaw = o.optString("path", "")
            val uriRaw = o.optString("uri", "").takeIf { it.isNotBlank() }
                ?: pathRaw.takeIf { isContentUriString(it) }
            val path = if (uriRaw == pathRaw) "" else pathRaw
            if (path.isBlank() && uriRaw.isNullOrBlank()) continue
            entries.add(
                ProjectFileEntry(
                    path = path,
                    uri = uriRaw,
                    checked = o.optBoolean("checked", true),
                    label = o.optString("label", ""),
                ),
            )
        }
        return entries
    }

    private fun loadProjectEntry(entry: ProjectFileEntry, index: Int): GpxListItem? {
        if (entry.path.isNotBlank()) {
            val f = File(entry.path)
            if (f.isFile()) {
                return GpxListItem(
                    displayName = entry.label.ifBlank { f.name },
                    cachePath = f.absolutePath,
                    sourceUri = entry.uri,
                    checked = entry.checked,
                )
            }
        }
        val uriRaw = entry.uri ?: return null
        val uri = Uri.parse(uriRaw)
        persistReadableDocumentUri(uri)
        val localPath = copyUriToLocalImport(uri, index)
        val label = entry.label.ifBlank { queryDisplayName(uri) ?: File(localPath).name }
        return GpxListItem(
            displayName = label,
            cachePath = localPath,
            sourceUri = uriRaw,
            checked = entry.checked,
        )
    }

    private fun isContentUriString(value: String): Boolean =
        runCatching { Uri.parse(value).scheme == "content" }.getOrDefault(false)

    private fun persistReadableDocumentUri(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun persistProjectDocumentPermission(uri: Uri) {
        if (uri.scheme != "content") return
        val flags =
            listOf(
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        for (flag in flags) {
            runCatching { contentResolver.takePersistableUriPermission(uri, flag) }
        }
    }

    private fun deleteManagedImportPath(path: String) {
        val target = canonicalFileOrNull(path) ?: return
        val managedRoots =
            listOf(
                File(filesDir, "gpx_imports"),
                File(cacheDir, "gpx_imports"),
            ).mapNotNull { root ->
                try {
                    root.canonicalFile
                } catch (_: Exception) {
                    null
                }
            }
        if (managedRoots.any { root -> target.path.startsWith(root.path + File.separator) }) {
            target.delete()
        }
    }

    private fun canonicalPathOrNull(path: String): String? = canonicalFileOrNull(path)?.path

    private fun canonicalFileOrNull(path: String): File? =
        try {
            File(path).canonicalFile
        } catch (_: Exception) {
            null
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
        const val KEY_MARKER_LABELS = "marker_labels_mode"
        const val KEY_MAP_VIEW = "last_map_view_v1"
        const val MAP_VIEW_TITLE_PREFIX = "gpx-link-map-view:"
        const val PROJECT_FORMAT = "gpx-link-project"
        const val PROJECT_VERSION = 1
    }
}
