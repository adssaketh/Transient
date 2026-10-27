package com.demonlab.lune.tools

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Saves a small text report to a "logs" folder whenever something goes wrong, so it can be sent along with a bug report:
 *  - crash_*   an uncaught exception (the app is about to close)
 *  - lag_*     the screen froze for 2 seconds or more (stack of the frozen UI thread is included)
 *  - exit_*    last time the system ended the app for a bad reason (not responding, native crash, out of memory...)
 *  - memory_*  the system asked the app to free memory urgently
 *  - error_*   problems the app caught itself and chose to record
 *
 * Reports stay on the phone. They contain the device model, Android version, app version, a stack trace and the last lines of
 * this app's own log; nothing is sent anywhere unless the user shares the files. Turn it on or off in Settings.
 */
object DiagnosticLogger {
    private const val TAG = "DiagnosticLogger"
    private const val MAX_FILES = 40
    private const val LAG_THRESHOLD_MS = 2000L
    private const val LAG_MIN_GAP_MS = 20_000L
    private const val MEMORY_MIN_GAP_MS = 60_000L
    private const val PREFS = "diagnostic_logger"
    private const val KEY_LAST_EXIT = "last_exit_seen_v2"
    private const val FILE_PROVIDER = "com.transientplayer.app.fileprovider"

    @Volatile private var appContext: Context? = null
    @Volatile private var watchdog: Thread? = null
    @Volatile private var previousHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile private var lastLagAt = 0L
    @Volatile private var lastMemoryAt = 0L
    private val writeLock = Any()

    /** Call once from Application.onCreate. */
    fun install(context: Context) {
        val app = context.applicationContext
        if (appContext != null) return
        appContext = app

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (isEnabled()) {
                    writeReport("crash", "Uncaught exception on thread \"${thread.name}\"", error, withLogcat = true)
                }
            } catch (_: Throwable) {
                // Never let the logger hide the real crash.
            }
            previousHandler?.uncaughtException(thread, error)
        }
        applyEnabled(app)
    }

    fun isEnabled(context: Context? = appContext): Boolean {
        val c = context ?: return false
        return runCatching { SettingsManager.getInstance(c).crashLogsEnabled }.getOrDefault(true)
    }

    /** Starts or stops the background parts to match the Settings switch. */
    fun applyEnabled(context: Context) {
        val app = context.applicationContext
        appContext = appContext ?: app
        if (isEnabled(app)) {
            startWatchdog()
            Thread({ recordPreviousExits(app) }, "transient-exit-log").apply { isDaemon = true; start() }
        } else {
            watchdog?.interrupt()
            watchdog = null
        }
    }

    /** Where the report files are kept: .../Android/data/<app>/files/logs (falls back to the app's private folder). */
    fun logsDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, "logs").apply { mkdirs() }
    }

    fun logFiles(context: Context): List<File> =
        logsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ------------------------------------------------------------------------------------------------------------
    // Playback event trace (logs/playback_trace.txt): one line per event, kept across restarts, exported with the zip.

    private const val TRACE_FILE = "playback_trace.txt"
    private const val TRACE_MAX_BYTES = 200_000L
    private val traceLock = Any()

    /** Adds "HH:mm:ss.SSS  message" to the playback trace. Cheap; used for pause/resume/focus/lifecycle events. */
    fun note(message: String) {
        val c = appContext ?: return
        if (!isEnabled(c)) return
        runCatching {
            synchronized(traceLock) {
                val f = File(logsDir(c), TRACE_FILE)
                if (f.length() > TRACE_MAX_BYTES) {
                    val keep = f.readText().takeLast((TRACE_MAX_BYTES / 2).toInt())
                    f.writeText(keep.substringAfter('\n'))
                }
                f.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + message + "\n")
            }
        }
    }

    /** "Who called me": the next few stack frames as Class.method:line, for note(). */
    fun callers(skip: Int = 2, count: Int = 3): String =
        Throwable().stackTrace.drop(skip).take(count)
            .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }

    fun clear(context: Context): Int {
        var removed = 0
        logFiles(context).forEach { if (it.delete()) removed++ }
        return removed
    }

    /** For code that catches a problem itself and wants it kept: DiagnosticLogger.error("Library", "Scan failed", e). */
    fun error(area: String, message: String, error: Throwable? = null) {
        val c = appContext ?: return
        if (!isEnabled(c)) return
        runCatching { writeReport("error", "$area: $message", error, withLogcat = true) }
    }

    /** Called from Application.onTrimMemory. */
    fun onLowMemory(level: Int) {
        val c = appContext ?: return
        if (!isEnabled(c)) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMemoryAt < MEMORY_MIN_GAP_MS) return
        lastMemoryAt = now
        Thread({
            runCatching { writeReport("memory", "System asked the app to free memory (trim level $level)", null, withLogcat = false) }
        }, "transient-memory-log").apply { isDaemon = true; start() }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Screen freezes

    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        val main = Handler(Looper.getMainLooper())
        val mainThread = Looper.getMainLooper().thread
        val thread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted && isEnabled()) {
                    val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                    val sentAt = SystemClock.elapsedRealtime()
                    main.post { answered.set(true) }
                    Thread.sleep(LAG_THRESHOLD_MS)
                    val slept = SystemClock.elapsedRealtime() - sentAt
                    // If this thread itself was suspended (phone asleep, process frozen) the numbers mean nothing.
                    if (!answered.get() && slept < LAG_THRESHOLD_MS + 1500) {
                        val first = mainThread.stackTrace
                        Thread.sleep(700)
                        val second = if (answered.get()) null else mainThread.stackTrace
                        // Wait for the screen to answer again, to learn how long it froze (stop waiting after 30 s).
                        var waited = 0L
                        while (!answered.get() && waited < 30_000L) {
                            Thread.sleep(250)
                            waited += 250
                        }
                        val frozenFor = SystemClock.elapsedRealtime() - sentAt
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLagAt >= LAG_MIN_GAP_MS) {
                            lastLagAt = now
                            writeLagReport(frozenFor, answered.get(), first, second)
                        }
                    }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
                // switched off
            } catch (t: Throwable) {
                Log.w(TAG, "Freeze watcher stopped: ${t.message}")
            }
        }, "transient-lag-watchdog")
        thread.isDaemon = true
        watchdog = thread
        thread.start()
    }

    private fun writeLagReport(frozenMs: Long, recovered: Boolean, first: Array<StackTraceElement>, second: Array<StackTraceElement>?) {
        val body = StringBuilder()
        body.append("The screen (UI thread) did not respond for about ").append(frozenMs).append(" ms")
        body.append(if (recovered) " and then recovered.\n" else ". It had not recovered 30 s later.\n")
        body.append("\nWhat the UI thread was doing when the freeze was noticed:\n")
        first.forEach { body.append("    at ").append(it).append('\n') }
        if (second != null) {
            body.append("\nAnd 0.7 s later:\n")
            second.forEach { body.append("    at ").append(it).append('\n') }
        }
        runCatching { writeRaw("lag", "UI freeze", body.toString(), withLogcat = true) }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Why the app was ended last time

    private fun recordPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_LAST_EXIT, 0L)
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            var newest = seen
            for (e in exits) {
                if (e.timestamp <= seen) continue
                newest = maxOf(newest, e.timestamp)
                val reason = when (e.reason) {
                    ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Killed: low memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Killed: too many resources used"
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Failed to start"
                    ApplicationExitInfo.REASON_SIGNALED -> "Killed by a signal"
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "A service the app depends on died"
                    else -> null // normal exits, user swipes, and Java crashes (those already have a crash_ file)
                } ?: continue
                val body = StringBuilder()
                body.append("The system ended the app: ").append(reason).append('\n')
                body.append("When: ").append(formatTime(e.timestamp)).append('\n')
                body.append("Description: ").append(e.description ?: "-").append('\n')
                body.append("Importance: ").append(e.importance).append("   Status: ").append(e.status)
                if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    body.append("  (signal ").append(signalName(e.status)).append(')')
                }
                body.append('\n')
                body.append("Process: ").append(e.processName).append("   Pid: ").append(e.pid).append('\n')
                if (e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    // The system keeps a binary crash dump; its readable text (library and function names) is what helps.
                    runCatching {
                        e.traceInputStream?.use { input ->
                            val raw = input.readBytes().take(400_000).toByteArray()
                            val text = readableStrings(raw)
                            if (text.isNotBlank()) body.append("\nReadable parts of the native crash dump:\n").append(text).append('\n')
                        }
                    }
                }
                if (e.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        e.traceInputStream?.use { input ->
                            val text = input.readBytes().take(250_000).toByteArray().toString(Charsets.UTF_8)
                            body.append("\nSystem trace:\n").append(text).append('\n')
                        }
                    }
                }
                writeRaw("exit", reason, body.toString(), withLogcat = false, timestamp = e.timestamp)
            }
            if (newest > seen) prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read earlier exits: ${t.message}")
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Writing

    private fun writeReport(kind: String, title: String, error: Throwable?, withLogcat: Boolean) {
        val body = StringBuilder()
        if (error != null) {
            body.append(Log.getStackTraceString(error)).append('\n')
        }
        writeRaw(kind, title, body.toString(), withLogcat)
    }

    private fun writeRaw(kind: String, title: String, details: String, withLogcat: Boolean, timestamp: Long = System.currentTimeMillis()) {
        val context = appContext ?: return
        synchronized(writeLock) {
            val dir = logsDir(context)
            val name = "${kind}_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(timestamp))}.txt"
            val out = StringBuilder()
            out.append("Transient report: ").append(kind).append('\n')
            out.append(title).append("\n\n")
            out.append(deviceInfo(context)).append('\n')
            out.append(details).append('\n')
            if (withLogcat) {
                val log = readOwnLogcat()
                if (log.isNotBlank()) out.append("\n----- last lines of this app's log -----\n").append(log).append('\n')
            }
            File(dir, name).writeText(out.toString())
            trim(dir)
        }
    }

    private fun deviceInfo(context: Context): String {
        val info = StringBuilder()
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        @Suppress("DEPRECATION")
        val code = pkg?.let { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else it.versionCode.toLong() }
        info.append("Report written by app: ").append(context.packageName).append(' ').append(pkg?.versionName ?: "?").append(" (").append(code ?: "?").append(")\n")
        info.append("Time: ").append(formatTime(System.currentTimeMillis())).append('\n')
        info.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
        info.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        info.append("CPU: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        val rt = Runtime.getRuntime()
        val mb = 1024 * 1024
        info.append("App memory: used ").append((rt.totalMemory() - rt.freeMemory()) / mb).append(" MB of ").append(rt.maxMemory() / mb).append(" MB\n")
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            info.append("Phone memory: free ").append(mi.availMem / mb).append(" MB of ").append(mi.totalMem / mb)
                .append(" MB, low-memory state: ").append(mi.lowMemory).append('\n')
        }
        runCatching {
            val s = SettingsManager.getInstance(context)
            info.append("Hi-Fi badge setting: ").append(s.enableHiFi).append('\n')
        }
        return info.toString()
    }

    private fun readOwnLogcat(lines: Int = 300, maxChars: Int = 60_000): String {
        return try {
            val process = ProcessBuilder("logcat", "-d", "-t", lines.toString(), "-v", "threadtime")
                .redirectErrorStream(true)
                .start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.destroy()
            // Keep reports small enough to share by chat.
            if (text.length > maxChars) text.takeLast(maxChars) else text
        } catch (_: Throwable) {
            ""
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private fun signalName(n: Int): String = when (n) {
        4 -> "4 SIGILL"
        6 -> "6 SIGABRT (the program stopped itself, usually after a failed check or an unhandled C++ error)"
        7 -> "7 SIGBUS"
        8 -> "8 SIGFPE"
        11 -> "11 SIGSEGV (bad memory access)"
        else -> n.toString()
    }

    /** Printable text runs (4+ characters) from a binary dump, de-duplicated, capped at about 12 000 characters. */
    private fun readableStrings(bytes: ByteArray): String {
        val seen = LinkedHashSet<String>()
        val run = StringBuilder()
        fun flush() {
            if (run.length >= 4) seen.add(run.toString())
            run.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) run.append(c.toChar()) else flush()
        }
        flush()
        val out = StringBuilder()
        for (line in seen) {
            if (out.length + line.length > 12_000) break
            out.append("    ").append(line).append('\n')
        }
        return out.toString()
    }

    private fun formatTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(ms))

    // ------------------------------------------------------------------------------------------------------------
    // Sending the files

    /** All reports in one zip, ready to attach. Null when there is nothing to send. */
    fun buildZip(context: Context): File? {
        val files = logFiles(context)
        if (files.isEmpty()) return null
        val dir = File(context.cacheDir, "log_share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "transient-logs-${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            files.forEach { f ->
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
            // A fresh copy of the app's own recent log, so something just heard (a gap in the sound, say) can be traced.
            // Export right after it happens: the system only keeps the last minute or so.
            val live = readOwnLogcat(2000, 600_000)
            if (live.isNotBlank()) {
                out.putNextEntry(ZipEntry("live_log_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.txt"))
                out.write(live.toByteArray())
                out.closeEntry()
            }
        }
        return zip
    }

    /** Opens the share sheet with the zip. Returns false when there are no reports. */
    fun share(context: Context): Boolean {
        val zip = buildZip(context) ?: return false
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER, zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Transient logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Transient logs").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /**
     * Copies the zip to Downloads/Transient/logs, where a file manager can see it (Android 10 and newer).
     * Returns the folder shown to the user, or null when it could not be done.
     */
    fun saveToDownloads(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val zip = buildZip(context) ?: return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zip.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Transient/logs")
            }
            val uri: Uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            context.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } } ?: return null
            "Download/Transient/logs"
        } catch (t: Throwable) {
            Log.w(TAG, "Could not save to Downloads: ${t.message}")
            null
        }
    }
}
