package io.github.gpxlink

import android.annotation.SuppressLint
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import com.google.android.material.appbar.MaterialToolbar
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private val gpxItems = mutableListOf<GpxListItem>()
    private lateinit var webView: WebView
    private lateinit var fileList: RecyclerView
    private lateinit var adapter: GpxFileAdapter

    private val openGpxLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            var index = 0
            for (uri in uris) {
                try {
                    val path = copyUriToCache(uri, index)
                    val name = queryDisplayName(uri) ?: File(path).name
                    gpxItems.add(GpxListItem(displayName = name, cachePath = path, checked = true))
                    index++
                } catch (e: Exception) {
                    Toast.makeText(this, e.message ?: "Import failed", Toast.LENGTH_LONG).show()
                }
            }
            adapter.notifyDataSetChanged()
            reloadMap()
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
        adapter = GpxFileAdapter(gpxItems) { reloadMap() }
        fileList.adapter = adapter

        webView = findViewById(R.id.map_webview)
        setupWebView(webView)
        loadEmptyMap()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FILE_PANEL_VISIBLE, fileList.visibility == View.VISIBLE)
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

    private fun loadEmptyMap() {
        renderAndLoad(emptyList())
    }

    private fun checkedPaths(): List<String> = gpxItems.filter { it.checked }.map { it.cachePath }

    private fun reloadMap() {
        renderAndLoad(checkedPaths())
    }

    private fun renderAndLoad(paths: List<String>) {
        val py = Python.getInstance()
        val module = py.getModule("bridge")
        val pathsJson = JSONArray(paths).toString()
        val jsonResult = module.callAttr("render", pathsJson).toString()
        val obj = JSONObject(jsonResult)
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

    private companion object {
        const val STATE_FILE_PANEL_VISIBLE = "file_panel_visible"
    }
}
