package com.daturtleguy.turtletavern

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.daturtleguy.turtletavern.gotavern.Gotavern
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.zip.ZipInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressOverlay: View
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var configEdit: EditText
    private lateinit var logsText: TextView
    private lateinit var btnTabConfig: Button
    private lateinit var btnTabLogs: Button
    private val ui = Handler(Looper.getMainLooper())
    private var serverPort: Int = 0
    private var logsVisible = false
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

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
            allowContentAccess = false
        }

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
        findViewById<Button>(R.id.btn_save_config).setOnClickListener { saveConfig() }
        findViewById<Button>(R.id.btn_restart).setOnClickListener { restartServer() }
        findViewById<Button>(R.id.btn_import_backup).setOnClickListener {
            restorePicker.launch("application/zip")
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Mirrors console output into logcat (tag: TurtleTavern-Console)
            // so users can attach logs to bug reports.
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
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
                return try {
                    fileChooserLauncher.launch(fileChooserParams.createIntent())
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
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
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
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        Thread { boot() }.start()
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

    private fun refreshLogsNow() {
        Thread {
            val logs = try { Gotavern.logs() } catch (_: Throwable) { "" }
            ui.post { logsText.text = logs.ifEmpty { "(no log output)" } }
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
        val intent = Intent(this, KeepAliveService::class.java)
        if (enabled) {
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
        } else {
            stopService(intent)
        }
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
        val version = "${packageManager.getPackageInfo(packageName, 0).longVersionCode}-${Gotavern.version()}"
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

    override fun onDestroy() {
        ui.removeCallbacks(LOG_REFRESH_TICK)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TurtleTavern"
        private const val REFRESH_MS = 1000L
        private const val PREFS = "turtletavern"
        private const val KEY_KEEP_ALIVE = "keep_alive"
    }
}
