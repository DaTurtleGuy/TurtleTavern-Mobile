package com.daturtleguy.turtletavern

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Heavy-weight in-app logging: everything mirrors to logcat AND to a daily
 * rotating log file in the external app files dir, so non-USB users can share
 * logs. Keeps the last 7 files. Crashes are captured by default.
 */
object AppLog {

    @Volatile
    private var logDir: File? = null

    private val lineFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val currentFile = AtomicReference<File>()
    private val io = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "logs").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("Crash", "Uncaught exception on ${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        i("AppLog", "Logging initialized")
        prune()
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        write("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        write("E", tag, message, throwable)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val dir = logDir ?: return
        io.execute {
            try {
                val stamp = fileFormat.format(Date())
                var file = currentFile.get()
                if (file == null || !file.name.endsWith(".log")) {
                    file = File(dir, "app-$stamp.log")
                    currentFile.set(file)
                }
                if (file.length() > 8L shl 20) {
                    rotate(dir, file.name)
                    file = File(dir, "app-$stamp.log")
                    currentFile.set(file)
                }
                val line = StringBuilder()
                    .append('[')
                    .append(lineFormat.format(System.currentTimeMillis()))
                    .append("] ")
                    .append(level)
                    .append('/')
                    .append(tag)
                    .append(": ")
                    .append(message)
                if (throwable != null) {
                    line.append("\n  ")
                        .append(throwable.stackTraceToString().lines().take(10).joinToString("\n  "))
                }
                file.appendText(line.toString() + "\n")
            } catch (_: Throwable) {
                // logging must never break the app
            }
        }
    }

    private fun rotate(dir: File, fileName: String) {
        File(dir, "$fileName.1").delete()
        File(dir, fileName).renameTo(File(dir, "$fileName.1"))
        currentFile.set(null)
    }

    private fun prune() {
        val files = logDir?.listFiles { f: File -> f.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() } ?: return
        files.drop(7).forEach { it.delete() }
    }
}
