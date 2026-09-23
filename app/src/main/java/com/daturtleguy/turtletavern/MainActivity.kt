package com.daturtleguy.turtletavern

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import com.daturtleguy.turtletavern.gotavern.Gotavern
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressOverlay: View
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var configEdit: EditText
    private lateinit var logsText: TextView
    private lateinit var logsScroll: ScrollView
    private lateinit var btnTabConfig: Button
    private lateinit var btnTabLogs: Button
    private val ui = Handler(Looper.getMainLooper())
    private var serverPort: Int = 0
    private val lanRelay = LanTcpRelay()
    private var lanListenEnabled = false
    private lateinit var lanUrl: TextView
    private lateinit var lanPanel: View
    private lateinit var lanPanelText: TextView
    private var logsVisible = false
    private var logsUserTouched = false
    private var lastServerText = ""
    private var consumedConsole = 0L
    private var logsPrimed = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var rendererGonePending = false

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        val uris = if (result.resultCode == RESULT_OK) result.data?.let { WebChromeClient.FileChooserParams.parseResult(RESULT_OK, it) } else null
        callback?.onReceiveValue(uris ?: arrayOf())
    }

    private val restorePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            ui.post { drawer.closeDrawer(GravityCompat.START) }
            Thread { restoreFromFile(uri) }.start()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLog.init(this)
        setContentView(R.layout.activity_main)

        if (!isArm64()) {
            setContentView(android.R.layout.activity_list_item)
            showArchError()
            finish()
            return
        }

        webView = findViewById(R.id.web_view)
        progressOverlay = findViewById(R.id.progress_overlay)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        drawer = findViewById(R.id.drawer)
        configEdit = findViewById(R.id.config_edit)
        logsText = findViewById(R.id.logs_text)
        logsScroll = findViewById(R.id.panel_logs)
        // Following the tail is driven by *touch*, not by scroll events:
        // assigning new text resets the scroll position and made the old
        // listener think the user had scrolled away.
        logsScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                logsUserTouched = true
            }
            false
        }
        logsScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (logsUserTouched && logsScroll.scrollY + logsScroll.height >= logsText.height - 64) {
                logsUserTouched = false
            }
        }
        btnTabConfig = findViewById(R.id.btn_tab_config)
        btnTabLogs = findViewById(R.id.btn_tab_logs)

        // targetSdk 35+ enforces edge-to-edge: push app content above the
        // system (nav) bars instead of letting them draw over the UI.
        val content = findViewById<FrameLayout>(R.id.content_frame)
        val drawerPanel = findViewById<LinearLayout>(R.id.drawer_panel)
        val insetListener = View.OnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.setOnApplyWindowInsetsListener(insetListener)
        drawerPanel.setOnApplyWindowInsetsListener(insetListener)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            // SAF hands file pickers back as content:// URIs — with content
            // access disabled those uploads silently break, so every file
            // input in the frontend appeared to accept zips only.
            allowContentAccess = true
        }
        // Bridge for blob: downloads — evaluateJavascript can't await a Promise,
        // so the page posts the fetched bytes back through here instead.
        webView.addJavascriptInterface(BlobDownloader(), "TTBlobDownload")

        // Default policy waives the renderer whenever this activity is not
        // visible, which lets Android freeze it mid-generation. Keep it
        // important so running generations survive being backgrounded.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)

        btnTabConfig.setOnClickListener {
            showTab(config = true)
        }
        btnTabLogs.setOnClickListener {
            showTab(config = false)
        }
        val keepAliveSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.keep_alive_switch)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        keepAliveSwitch.isChecked = prefs.getBoolean(KEY_KEEP_ALIVE, false)
        keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_KEEP_ALIVE, checked).apply()
            applyKeepAlive(checked)
        }
        lanUrl = findViewById(R.id.lan_url)
        lanPanel = findViewById(R.id.lan_panel)
        lanPanelText = findViewById(R.id.lan_panel_text)
        prefs.edit().remove(KEY_LISTEN_LAN).apply()
        val lanSwitch = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.lan_switch)
        lanSwitch.isChecked = lanListenEnabled
        refreshLanUrl()
        lanSwitch.setOnCheckedChangeListener { _, checked ->
            lanListenEnabled = checked
            AppLog.i(TAG, "LAN listen toggled to $checked")
            if (checked) {
                refreshLanUrl()
                startLanRelayAsync()
            } else {
                stopLanRelay()
            }
        }
        findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { restartServer() }
        findViewById<Button>(R.id.btn_share_logs).setOnClickListener { shareLogs() }
        findViewById<Button>(R.id.btn_download_logs).setOnClickListener { downloadLogs() }
        findViewById<Button>(R.id.btn_import_backup).setOnClickListener {
            restorePicker.launch("application/zip")
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Mirrors console output into logcat (tag: TurtleTavern-Console)
            // so users can attach logs to bug reports.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                AppLog.console(consoleMessage.message())
                AppLog.i("Console", consoleMessage.message())
                return true
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                // Never filter the picker: pages pass accept= values that are
                // frequently bare extensions (".png", ".charx"), and Android
                // matches those against nothing, leaving an empty file list.
                // The page validates whatever comes back.
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("*/*"))
                    if (fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (t: Throwable) {
                    AppLog.e(TAG, "File chooser launch failed", t)
                    fileChooserCallback = null
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                // The ST frontend manages its own scrolling; the WebView itself
                // may only bounce on the exact base URL (root of our server).
                // Any other path (extension pages, etc.) keeps normal scrolling.
                val uri = try { Uri.parse(url) } catch (_: Throwable) { null }
                val isBaseUrl = uri != null && uri.host == "127.0.0.1" && uri.port == serverPort &&
                    (uri.path == "/" || uri.path.isNullOrEmpty())
                view.overScrollMode = if (isBaseUrl) {
                    View.OVER_SCROLL_NEVER
                } else {
                    View.OVER_SCROLL_IF_CONTENT_SCROLLS
                }
                view.isVerticalScrollBarEnabled = !isBaseUrl
                view.isHorizontalScrollBarEnabled = !isBaseUrl
                super.doUpdateVisitedHistory(view, url, isReload)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    AppLog.e(TAG, "WebView load error (${error.errorCode}): ${error.description} for ${request.url}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) {
                    AppLog.w(TAG, "WebView HTTP ${response.statusCode} for ${request.url}")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                AppLog.e(TAG, "WebView renderer gone (crashed=${detail.didCrash()})")
                // A dead WebView is never reusable, and rebuilding while hidden
                // would reload off-screen: wait until the activity is visible.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    if (!isFinishing) ui.post { recreate() }
                } else {
                    rendererGonePending = true
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                // Object args logged with console.log(obj) reach onConsoleMessage
                // as the literal "[object Object]" (information already lost), so
                // stringify them in-page before Chromium flattens them.
                injectConsolePatch()
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            // DownloadManager only accepts http(s) — SillyTavern exports (chat
            // txt, JSON, etc.) use blob: URLs, which used to crash here with
            // IllegalArgumentException: Can only download HTTP/HTTPS URIs.
            if (url.startsWith("blob:")) {
                downloadBlobUrl(url, contentDisposition, mimeType)
                return@setDownloadListener
            }
            try {
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    CookieManager.getInstance().getCookie(url)?.let { addRequestHeader("Cookie", it) }
                    addRequestHeader("User-Agent", userAgent)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                    allowScanningByMediaScanner()
                }
                (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                Toast.makeText(this, getString(R.string.downloading_file, fileName), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                AppLog.e(TAG, "Download failed", t)
                Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.START)) {
                    drawer.closeDrawer(GravityCompat.START)
                } else if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        Thread { boot() }.start()
    }

    private fun injectConsolePatch() {
        try {
            webView.evaluateJavascript(CONSOLE_PATCH_JS, null)
            webView.evaluateJavascript(DOWNLOAD_HOOK_JS, null)
        } catch (t: Throwable) {
            AppLog.w(TAG, "Console patch injection failed: " + t.message)
        }
    }

    private fun downloadBlobUrl(blobUrl: String, contentDisposition: String?, mimeType: String) {
        val fileName = URLUtil.guessFileName(blobUrl, contentDisposition, mimeType)
        Toast.makeText(this, getString(R.string.downloading_file, fileName), Toast.LENGTH_SHORT).show()
        val js = "(function(){var u=" + JSONObject.quote(blobUrl) +
            ",n=" + JSONObject.quote(fileName) +
            ",m=" + JSONObject.quote(mimeType.ifEmpty { "application/octet-stream" }) + ";" +
            "fetch(u).then(function(res){if(!res.ok)throw new Error('HTTP '+res.status);return res.blob();})" +
            ".then(function(blob){var r=new FileReader();" +
            "r.onload=function(){TTBlobDownload.onBlobData(r.result,n,m);};" +
            "r.onerror=function(){TTBlobDownload.onBlobError('read failed',n);};" +
            "r.readAsDataURL(blob);})" +
            ".catch(function(e){TTBlobDownload.onBlobError((e&&e.message)||String(e),n);});})();"
        try {
            webView.evaluateJavascript(js, null)
        } catch (t: Throwable) {
            AppLog.e(TAG, "Blob download failed", t)
            Toast.makeText(this, getString(R.string.download_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private inner class BlobDownloader {
        @JavascriptInterface
        fun onBlobData(dataUrl: String, fileName: String, mimeType: String) {
            Thread {
                try {
                    val comma = dataUrl.indexOf(',')
                    if (!dataUrl.startsWith("data:") || comma < 0) {
                        throw java.io.IOException("unexpected blob payload")
                    }
                    val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                    saveBlobBytesToDownloads(bytes, fileName, mimeType)
                } catch (t: Throwable) {
                    AppLog.e(TAG, "Blob download failed", t)
                    ui.post {
                        Toast.makeText(this@MainActivity, getString(R.string.download_failed), Toast.LENGTH_LONG).show()
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun onBlobError(error: String, fileName: String) {
            AppLog.e(TAG, "Blob download failed for $fileName: $error")
            ui.post {
                Toast.makeText(this@MainActivity, getString(R.string.download_failed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveBlobBytesToDownloads(bytes: ByteArray, fileName: String, mimeType: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw java.io.IOException("no Downloads collection available")
        resolver.openOutputStream(target).use { output ->
            requireNotNull(output) { "cannot open Downloads target" }
            output.write(bytes)
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        AppLog.i(TAG, "Blob download saved to Downloads/" + fileName + " (" + bytes.size + " bytes)")
        ui.post { Toast.makeText(this, getString(R.string.download_logs_done, fileName), Toast.LENGTH_LONG).show() }
    }

    private fun isArm64(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }

    private fun showTab(config: Boolean) {
        findViewById<View>(R.id.panel_config).visibility = if (config) View.VISIBLE else View.GONE
        findViewById<View>(R.id.panel_logs).visibility = if (config) View.GONE else View.VISIBLE
        btnTabConfig.isEnabled = !config
        btnTabLogs.isEnabled = config
        logsVisible = !config
        if (config) {
            loadConfigIntoEditor()
        } else {
            logsUserTouched = false
            logsPrimed = false
            lastServerText = ""
            consumedConsole = 0L
            refreshLogsNow()
            ui.postDelayed(LOG_REFRESH_TICK, REFRESH_MS)
        }
    }

    private fun loadConfigIntoEditor() {
        Thread {
            val file = File(filesDir, "config.yaml")
            val text = if (file.isFile) file.readText() else "# config.yaml not found"
            ui.post { configEdit.setText(text) }
        }.start()
    }

    private fun saveConfig() {
        val text = configEdit.text.toString()
        Thread {
            try {
                File(filesDir, "config.yaml").writeText(text)
                ui.post { Toast.makeText(this, "config.yaml saved — Restart to apply", Toast.LENGTH_SHORT).show() }
            } catch (t: Throwable) {
                ui.post { Toast.makeText(this, "Save failed: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // Appends only the new server output and console lines. Re-rendering the
    // whole buffer every tick reset the scroll position, which is what made the
    // panel bounce top→bottom every second.
    private fun refreshLogsNow() {
        Thread {
            val server = try { Gotavern.logs() } catch (_: Throwable) { "" }
            val (consoleNew, consoleIndex) = AppLog.consoleSince(consumedConsole)
            consumedConsole = consoleIndex

            val payloadBuilder = StringBuilder()
            val serverGrew = logsPrimed && server.startsWith(lastServerText)
            val reset = !serverGrew
            if (serverGrew) {
                payloadBuilder.append(server.substring(lastServerText.length))
            } else {
                payloadBuilder.append(server)
            }
            lastServerText = server
            if (consoleNew.isNotEmpty()) {
                if (payloadBuilder.isNotEmpty()) {
                    payloadBuilder.append('\n')
                }
                payloadBuilder.append(consoleNew)
            }
            val payload = payloadBuilder.toString()
            if (payload.isEmpty() && !reset) {
                return@Thread
            }

            ui.post {
                if (reset) {
                    logsText.text = payload.ifEmpty { "(no output yet)" }
                    logsPrimed = true
                } else {
                    logsText.append(if (logsText.text.isEmpty()) payload else "\n$payload")
                }
                if (logsText.length() > LOG_TEXT_MAX_CHARS) {
                    logsText.text = logsText.text.takeLast(LOG_TEXT_MAX_CHARS / 2)
                }
                if (!logsUserTouched) {
                    logsText.post { logsScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }.start()
    }

    private val LOG_REFRESH_TICK = object : Runnable {
        override fun run() {
            if (!logsVisible) return
            refreshLogsNow()
            ui.postDelayed(this, REFRESH_MS)
        }
    }

    private fun restartServer() {
        progressOverlay.visibility = View.VISIBLE
        status.text = "Restarting server…"
        progress.visibility = View.VISIBLE
        Thread {
            try {
                Gotavern.stop()
                val port = Gotavern.start(filesDir.absolutePath, 0)
                serverPort = port
                syncLanRelay()
                ui.post {
                    progressOverlay.visibility = View.GONE
                    webView.loadUrl("http://127.0.0.1:$port/")
                    drawer.closeDrawer(GravityCompat.START)
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Restart failed", t)
                ui.post {
                    progressOverlay.visibility = View.GONE
                    status.text = "Restart failed: ${t.message}"
                }
            }
        }.start()
    }

    // Client-side backup restore: stop the server up front, unzip, validate
    // (hashes optional, skippable), then replace the active user folder and
    // restart. The opaque overlay blocks the WebView for the whole flow.
    private fun restoreFromFile(uri: Uri) {
        var stopped = false
        val copiedBytes = LongArray(1)
        val lastProgressAt = LongArray(1)
        val extractedBytes = LongArray(1)
        try {
            ui.post {
                drawer.closeDrawer(GravityCompat.START)
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            }
            Gotavern.stop()
            stopped = true
            showProgress("Copying backup…", indeterminate = true)

            val localZip = File(cacheDir, "turtletavern-restore.zip")
            val zipSize = queryDocumentSize(uri)
            contentResolver.openInputStream(uri)!!.use { input ->
                localZip.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) {
                            break
                        }
                        output.write(buffer, 0, n)
                        copiedBytes[0] += n
                        if (zipSize > 0) {
                            setProgressPercent(copiedBytes[0], zipSize, "Copying backup…")
                        } else if (copiedBytes[0] >= lastProgressAt[0] + (256L shl 20)) {
                            lastProgressAt[0] = copiedBytes[0]
                            ui.post { status.text = "Copying backup… ${humanBytes(copiedBytes[0])}" }
                        }
                    }
                }
            }

            ui.post { status.text = "Validating backup…" }
            val staging = File(cacheDir, "restore-stage")
            staging.deleteRecursively()
            staging.mkdirs()

            val checksums = HashMap<String, String>()
            var scanned = 0
            var mismatched = 0
            java.util.zip.ZipFile(localZip).use { zip ->
                val manifestEntry = zip.getEntry("manifest.json")
                    ?: throw IllegalArgumentException("not a TurtleTavern backup (no manifest)")
                val manifest = JSONObject(
                    zip.getInputStream(manifestEntry).use { it.readBytes() }.decodeToString()
                )
                if (manifest.optString("format") != "turtletavern-backup") {
                    throw IllegalArgumentException("not a TurtleTavern backup")
                }
                val formatVersion = manifest.optInt("formatVersion", 0)
                if (formatVersion > 1) {
                    throw IllegalArgumentException("backup made by a newer version (format $formatVersion)")
                }
                val totalBackupBytes = manifest.optLong("totalBytes", 0L)

                val checksumEntry = zip.getEntry("checksums.sha256")
                if (checksumEntry != null) {
                    zip.getInputStream(checksumEntry).bufferedReader().use { reader ->
                        for (line in reader.readLines()) {
                            val trimmed = line.trimEnd('\r')
                            if (trimmed.isEmpty()) {
                                continue
                            }
                            val sep = trimmed.indexOf("  ")
                            if (sep == 64) {
                                checksums[trimmed.substring(sep + 2)] = trimmed.substring(0, sep)
                            }
                        }
                    }
                }

                for (entry in zip.entries()) {
                    if (entry.isDirectory || entry.name == "manifest.json" || entry.name == "checksums.sha256") {
                        continue
                    }
                    val rel = entry.name.replace('\\', '/')
                    if (rel.isEmpty() || rel.startsWith('/') || rel.startsWith("../") || rel == ".." || rel.contains("/../")) {
                        throw SecurityException("unsafe archive path: ${entry.name}")
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val digestBytes = ByteArray(65536)
                    zip.getInputStream(entry).use { input ->
                        val out = File(staging, rel)
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { output ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) {
                                    break
                                }
                                digest.update(buffer, 0, n)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    val hex = digest.digest().joinToString("") { "%02x".format(it) }
                    val want = checksums[rel]
                    if (want == null || !want.equals(hex, ignoreCase = true)) {
                        mismatched++
                    }
                    scanned++
                    extractedBytes[0] += entry.size
                    if (totalBackupBytes > 0) {
                        setProgressPercent(extractedBytes[0], totalBackupBytes, "Restoring… $scanned files, ${humanBytes(extractedBytes[0])}")
                    } else if (scanned % 5 == 0 || extractedBytes[0] >= lastProgressAt[0] + (256L shl 20)) {
                        lastProgressAt[0] = extractedBytes[0]
                        ui.post { status.text = "Restoring… $scanned files, ${humanBytes(extractedBytes[0])}" }
                    }
                }
            }

            if (scanned == 0) {
                throw IllegalArgumentException(getString(R.string.import_not_restorable))
            }

            if (mismatched > 0 && !askImportAnyway(mismatched)) {
                staging.deleteRecursively()
                localZip.delete()
                hideProgressAndUnlock()
                return
            }

            showProgress(getString(R.string.import_done), indeterminate = true)
            val userRoot = resolveUserRoot()
            userRoot.deleteRecursively()
            if (!staging.renameTo(userRoot)) {
                staging.copyRecursively(userRoot, overwrite = true)
                staging.deleteRecursively()
            }
            localZip.delete()

            val port = Gotavern.start(filesDir.absolutePath, 0)
            serverPort = port
            syncLanRelay()
            hideProgressAndUnlock()
            ui.post { webView.loadUrl("http://127.0.0.1:$port/") }
        } catch (t: Throwable) {
            AppLog.e(TAG, "Restore failed", t)
            hideProgressAndUnlock()
            ui.post { Toast.makeText(this, getString(R.string.import_failed, t.message ?: t.toString()), Toast.LENGTH_LONG).show() }
            if (stopped) {
                try {
                    val port = Gotavern.start(filesDir.absolutePath, 0)
                    serverPort = port
                    syncLanRelay()
                    hideProgressAndUnlock()
                    ui.post { webView.loadUrl("http://127.0.0.1:$port/") }
                } catch (t2: Throwable) {
                    AppLog.e(TAG, "server restart after failed restore failed", t2)
                    hideProgressAndUnlock()
                }
            } else {
                hideProgressAndUnlock()
            }
        }
    }

    private fun showProgress(message: String, indeterminate: Boolean) {
        ui.post {
            progressOverlay.visibility = View.VISIBLE
            progress.isIndeterminate = indeterminate
            if (!indeterminate) {
                progress.progress = 0
            }
            status.text = message
        }
    }

    private fun setProgressPercent(done: Long, total: Long, message: String) {
        if (total <= 0) {
            return
        }
        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
        ui.post {
            progress.isIndeterminate = false
            progress.max = 100
            progress.progress = pct
            status.text = message
        }
    }

    private fun hideProgressAndUnlock() {
        ui.post {
            progressOverlay.visibility = View.GONE
            drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            status.visibility = View.VISIBLE
        }
    }

    private fun queryDocumentSize(uri: Uri): Long {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    cursor.getLong(0)
                } else {
                    0L
                }
            } ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun humanBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.size - 1) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "${value.toLong()} B" else String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
    }

    private fun askImportAnyway(count: Int): Boolean {
        val latch = CountDownLatch(1)
        var accepted = false
        ui.post {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.import_backup))
                .setMessage(getString(R.string.import_hash_warning) + " (files: $count)")
                .setPositiveButton(getString(R.string.import_anyway)) { _, _ -> accepted = true; latch.countDown() }
                .setNegativeButton(getString(R.string.import_cancel)) { _, _ -> latch.countDown() }
                .setOnCancelListener { latch.countDown() }
                .show()
        }
        latch.await()
        return accepted
    }

    private fun resolveUserRoot(): File {
        val dataDir = File(filesDir, "data")
        val defaultUser = File(dataDir, "default-user")
        if (defaultUser.isDirectory) {
            return defaultUser
        }
        val children = dataDir.listFiles { f: File -> f.isDirectory && f.name != "backups" } ?: arrayOf()
        if (children.size == 1) {
            return children[0]
        }
        return defaultUser
    }

    private fun applyKeepAlive(enabled: Boolean) {
        AppLog.i(TAG, "applyKeepAlive($enabled)")
        val intent = Intent(this, KeepAliveService::class.java)
        if (enabled) {
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } else {
            stopService(intent)
        }
    }

    private fun lanEnabled(): Boolean = lanListenEnabled

    private fun syncLanRelay() {
        if (!lanEnabled()) {
            lanRelay.stop()
            ui.post { refreshLanUrl() }
            return
        }
        startLanRelayAsync()
    }

    private fun startLanRelayAsync() {
        if (serverPort == 0) {
            ui.post { refreshLanUrl() }
            return
        }
        val target = serverPort
        Thread {
            try {
                val bound = lanRelay.start(LAN_PORT_DEFAULT, target)
                AppLog.i(TAG, "LAN relay up on $bound -> 127.0.0.1:$target")
                ui.post { refreshLanUrl() }
                ui.post {
                    val ips = LanTcpRelay.lanIPv4Addresses()
                    val first = ips.firstOrNull() ?: "this device"
                    Toast.makeText(this, "LAN: http://$first:$bound", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "LAN relay failed", t)
                ui.post {
                    Toast.makeText(this, getString(R.string.lan_failed, t.message ?: t.toString()), Toast.LENGTH_LONG).show()
                    refreshLanUrl()
                }
            }
        }.start()
    }

    private fun stopLanRelay() {
        lanRelay.stop()
        refreshLanUrl()
    }

    private fun refreshLanUrl() {
        if (!::lanUrl.isInitialized) return
        if (!lanEnabled()) {
            lanUrl.visibility = View.GONE
        } else {
            lanUrl.visibility = View.VISIBLE
            if (!lanRelay.isRunning()) {
                lanUrl.text = getString(R.string.lan_url_none)
            } else {
                val port = lanRelay.boundPort
                val ips = LanTcpRelay.lanIPv4Addresses()
                lanUrl.text = if (ips.isEmpty()) {
                    "LAN: http://<this device>:$port"
                } else {
                    ips.joinToString("\n") { "LAN: http://$it:$port" }
                }
            }
        }
        applyLanModeUi()
    }

    private fun applyLanModeUi() {
        if (!::lanPanel.isInitialized) return
        if (!lanEnabled()) {
            lanPanel.visibility = View.GONE
            val wasHidden = webView.visibility != View.VISIBLE
            webView.visibility = View.VISIBLE
            webView.onResume()
            if (wasHidden && serverPort != 0) {
                webView.loadUrl("http://127.0.0.1:$serverPort/")
            }
            return
        }
        webView.onPause()
        webView.visibility = View.GONE
        lanPanel.visibility = View.VISIBLE
        lanPanelText.text = if (lanRelay.isRunning()) {
            val port = lanRelay.boundPort
            val ips = LanTcpRelay.lanIPv4Addresses()
            val urls = if (ips.isEmpty()) {
                "http://<this device>:$port"
            } else {
                ips.joinToString("\n") { "http://$it:$port" }
            }
            getString(R.string.lan_panel_active) + "\n" + urls
        } else {
            getString(R.string.lan_url_none)
        }
    }

    // Packages the COMPLETE log files (plus the in-memory server ring buffer and
    // browser console) into one zip. Nothing is truncated: big reports are
    // exactly the ones we need.
    private fun buildLogsZip(): File {
        val shareDir = File(cacheDir, "logs").apply { mkdirs() }
        shareDir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(3)
            ?.forEach { it.delete() }

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(shareDir, "turtletavern-logs-$stamp.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }

            AppLog.logDirectory
                ?.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedBy { it.name }
                ?.forEach { file -> put(file.name, file.readBytes()) }

            put("server-logs.txt", (try { Gotavern.logs() } catch (_: Throwable) { "" }).toByteArray())
            put("browser-console.txt", AppLog.consoleDump().toByteArray())
            put("device-info.txt", buildDeviceReport().toByteArray())
        }
        AppLog.i(TAG, "Log bundle ready: ${zipFile.name} (${zipFile.length() / 1024} KiB)")
        return zipFile
    }

    private fun shareLogs() {
        ui.post { Toast.makeText(this, getString(R.string.share_logs_preparing), Toast.LENGTH_SHORT).show() }
        Thread {
            try {
                val zipFile = buildLogsZip()
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_logs_subject))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                ui.post { startActivity(Intent.createChooser(send, getString(R.string.share_logs))) }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Sharing logs failed", t)
                ui.post {
                    Toast.makeText(this, getString(R.string.share_logs_failed, t.message ?: t.toString()), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun downloadLogs() {
        ui.post { Toast.makeText(this, getString(R.string.share_logs_preparing), Toast.LENGTH_SHORT).show() }
        Thread {
            try {
                val zipFile = buildLogsZip()
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, zipFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.IOException("no Downloads collection available")
                resolver.openOutputStream(target).use { output ->
                    requireNotNull(output) { "cannot open Downloads target" }
                    zipFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(target, values, null, null)
                AppLog.i(TAG, "Log bundle downloaded to Downloads/${zipFile.name}")
                ui.post { Toast.makeText(this, getString(R.string.download_logs_done, zipFile.name), Toast.LENGTH_LONG).show() }
            } catch (t: Throwable) {
                AppLog.e(TAG, "Downloading logs failed", t)
                ui.post {
                    Toast.makeText(this, getString(R.string.download_logs_failed, t.message ?: t.toString()), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun buildDeviceReport(): String = buildString {
        val info = try { packageManager.getPackageInfo(packageName, 0) } catch (_: Throwable) { null }
        append("TurtleTavern (Go) ").append(info?.versionName ?: "?").append(" (").append(info?.longVersionCode ?: 0).append(")\n")
        append("backend: ").append(try { Gotavern.version() } catch (_: Throwable) { "?" }).append('\n')
        append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("abis: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("server port: ").append(serverPort).append('\n')
        append("lan relay: ").append(if (lanRelay.isRunning()) "0.0.0.0:" + lanRelay.boundPort + " -> 127.0.0.1:" + serverPort else "off").append('\n')
        append("log dir: ").append(AppLog.logDirectory?.absolutePath ?: "?").append('\n')
    }

    private fun boot() {
        try {
            val root = ensureBootstrap()
            ui.post {
                status.text = "Starting server…"
                loadConfigIntoEditor()
            }
            val port = Gotavern.start(root.absolutePath, 0)
            serverPort = port
            AppLog.i(TAG, "Server ready on port $port")
            syncLanRelay()
            ui.post {
                progressOverlay.visibility = View.GONE
                status.visibility = View.GONE
                webView.loadUrl("http://127.0.0.1:$port/")
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to start TurtleTavern", t)
            ui.post { showError(t) }
        }
    }

    private fun ensureBootstrap(): File {
        val root = filesDir
        val marker = File(root, ".bootstrap-version")
        val info = packageManager.getPackageInfo(packageName, 0)
        // lastUpdateTime is part of the key on purpose: the frontend ships in this
        // APK, and a release that only changes assets keeps the same versionCode,
        // so an install would otherwise keep serving the previously extracted
        // frontend forever.
        val version = "${info.longVersionCode}-${info.lastUpdateTime}-${Gotavern.version()}"
        if (marker.isFile && marker.readText().trim() == version) {
            return root
        }

        AppLog.i(TAG, "Extracting bundled frontend ($version)")
        val rootPath = root.canonicalPath + File.separator
        assets.open("bootstrap.zip").use { raw ->
            ZipInputStream(BufferedInputStream(raw)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val target = File(root, entry.name)
                    if (!target.canonicalPath.startsWith(rootPath)) {
                        throw SecurityException("Blocked archive entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else if (entry.name == "config.yaml" && target.isFile) {
                        // User state: the drawer's Config editor writes this file, and
                        // re-extracting must not reset someone's settings.
                        AppLog.i(TAG, "Keeping existing config.yaml")
                    } else {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        marker.writeText(version)
        return root
    }

    private fun showArchError() {
        showErrorText(
            "<h2>Arch not supported</h2><p>This build is arm64-only. " +
            "x86 / x86_64 devices and emulators are not supported Android targets. " +
            "Install TurtleTavern on an ARM64 device.</p>"
        )
    }

    private fun showError(t: Throwable) {
        progressOverlay.visibility = View.GONE
        status.visibility = View.GONE
        val message = (t.message ?: t.toString())
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        showErrorText(
            "<h2>TurtleTavern failed to start</h2><pre>$message</pre>"
        )
    }

    private fun showErrorText(htmlBody: String) {
        progressOverlay.visibility = View.GONE
        status.visibility = View.GONE
        webView.loadDataWithBaseURL(
            null,
            "<html><body style='font-family:sans-serif;padding:2em;color:#eee;background:#1b1b1f'>" +
                htmlBody + "</body></html>",
            "text/html",
            "utf-8",
            null,
        )
    }

    // Chromium's WebView can desync after a window-focus loss with an editable
    // focused (e.g. task-switch away with the keyboard up): on resume,
    // document.activeElement still reports the textarea but the view-level IME
    // session is dead, so taps and JS .focus() no longer open the keyboard —
    // only recreating the view fixed it. clearFocus()+requestFocus() re-arms
    // the editable-focus session when the window regains focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        // The drawer's config editor is a real EditText owned by the activity:
        // if the user was typing there, leave its focus alone.
        if (configEdit.hasFocus()) return
        AppLog.i(TAG, "window focus regained — re-arming WebView focus")
        webView.clearFocus()
        webView.requestFocus()
        // If the page thinks an editable is still focused, bounce its DOM focus
        // so the re-armed IME session attaches to it. Running before the WebView
        // is loaded is harmless (no-op when body is the active element).
        ui.post {
            webView.evaluateJavascript(
                "(function(){var a=document.activeElement;" +
                    "if(a&&(a.id==='send_textarea'||a.tagName==='TEXTAREA'||a.tagName==='INPUT')){" +
                    "a.blur();a.focus();}})()",
                null,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.i(TAG, "onStart")
        // Do not remove: the switch is restored from prefs before its listener
        // is attached, so that listener never fires for the restored value.
        applyKeepAlive(getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_KEEP_ALIVE, false))
        if (rendererGonePending) {
            rendererGonePending = false
            AppLog.i(TAG, "recovering the WebView after the renderer died in the background")
            recreate()
        }
    }

    // singleTask means a launcher tap reuses this instance instead of stacking a
    // second activity (and a second WebView, which reloaded the whole page).
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        AppLog.i(TAG, "onNewIntent (launcher tapped, existing activity reused)")
    }

    override fun onStop() {
        AppLog.i(TAG, "onStop (activity went to background)")
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        AppLog.w(TAG, "onTrimMemory($level) — system is pressuring the app")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppLog.w(TAG, "onLowMemory — system is critically low on memory")
        super.onLowMemory()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy (isFinishing=$isFinishing)")
        lanRelay.stop()
        ui.removeCallbacks(LOG_REFRESH_TICK)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TurtleTavern"
        private const val REFRESH_MS = 1000L
        private const val LOG_TEXT_MAX_CHARS = 400_000
        private const val PREFS = "turtletavern"
        private const val KEY_KEEP_ALIVE = "keep_alive"
        private const val KEY_LISTEN_LAN = "listen_lan"
        private const val LAN_PORT_DEFAULT = 8000
        private const val CONSOLE_PATCH_JS = """(function(){if(window.__ttConsolePatched)return;window.__ttConsolePatched=true;function str(v){if(typeof v==='string')return v;if(v===null)return 'null';if(v===undefined)return 'undefined';if(v instanceof Error)return v.stack||String(v);if(typeof v==='object'){try{var seen=new Set();return JSON.stringify(v,function(k,x){if(typeof x==='object'&&x!==null){if(seen.has(x))return '[Circular]';seen.add(x);}if(x instanceof Error)return x.stack||x.message;return x;});}catch(e){try{return String(v);}catch(_){return '[Object]';}}}try{return String(v);}catch(e){return '[unprintable]';}}['log','info','warn','error','debug'].forEach(function(k){try{var o=console[k]?console[k].bind(console):null;console[k]=function(){var m=Array.prototype.map.call(arguments,str).join(' ');if(o){try{o(m);}catch(e){}}};}catch(e){}});})();"""
        private const val DOWNLOAD_HOOK_JS = """(function(){if(window.__ttDownloadHook)return;window.__ttDownloadHook=true;try{var origRevoke=URL.revokeObjectURL.bind(URL);URL.revokeObjectURL=function(u){try{setTimeout(function(){try{origRevoke(u);}catch(e){}},60000);}catch(e){}};}catch(e){}function isBlobDl(a){return(a.getAttribute('href')||'').indexOf('blob:')===0;}function saveBlob(a){var name=a.getAttribute('download')||'download';var href=a.getAttribute('href')||'';fetch(href).then(function(res){if(!res.ok)throw new Error('HTTP '+res.status);return res.blob();}).then(function(blob){var r=new FileReader();r.onload=function(){try{TTBlobDownload.onBlobData(r.result,name,blob.type||'application/octet-stream');}catch(e){}};r.onerror=function(){try{TTBlobDownload.onBlobError('read failed',name);}catch(e){}};r.readAsDataURL(blob);}).catch(function(e){try{TTBlobDownload.onBlobError((e&&e.message)||String(e),name);}catch(x){}});}document.addEventListener('click',function(ev){try{var a=ev.target&&ev.target.closest?ev.target.closest('a[download]'):null;if(!a||!isBlobDl(a))return;ev.preventDefault();ev.stopPropagation();saveBlob(a);}catch(e){}},true);try{var origClick=HTMLAnchorElement.prototype.click;HTMLAnchorElement.prototype.click=function(){try{if(this.hasAttribute('download')&&isBlobDl(this)){saveBlob(this);return;}}catch(e){}return origClick.apply(this,arguments);};}catch(e){}})();"""
    }
}
