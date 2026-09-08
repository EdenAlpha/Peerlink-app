package com.peerlink.app.core

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashLogger — installs as the global UncaughtExceptionHandler.
 *
 * When the app crashes, it:
 *   1. Captures the full stack trace, thread name, and an AppState snapshot
 *   2. Writes everything to SharedPreferences BEFORE the process dies
 *      (SharedPreferences.commit() is synchronous — it completes before we call
 *       the system's default handler which kills the process)
 *   3. On next launch, the saved report is immediately readable from the admin panel
 *
 * Uses SharedPreferences (not a file) so there are zero permission requirements
 * and the write completes even if external storage is unavailable.
 */
object CrashLogger {

    private const val PREFS_NAME        = "peerlink_crash_log"
    private const val KEY_CRASH_REPORT  = "last_crash_report"
    private const val KEY_CRASH_TIME    = "last_crash_time_ms"
    private const val KEY_CRASH_COUNT   = "total_crash_count"
    private const val KEY_LIVE_LOG      = "live_log"
    // No cap on log/report size. The user explicitly wants full logs saved
    // to Downloads with no truncation. SharedPreferences can handle multi-MB
    // strings without issue; the only practical limit is device storage,
    // which is the user's call to make.
    //
    // HOWEVER: persistLiveLog() is NO LONGER called from appendLog(). It was
    // causing 10MB+ of SharedPreferences I/O per log line (with the uncapped
    // buffer) and killing the process on Android 15. It's now only called
    // from the crash handler (once, when the app crashes) and is throttled
    // to once per 30 seconds maximum as a safety net.
    @Volatile private var lastPersistMs: Long = 0L
    private const val PERSIST_THROTTLE_MS: Long = 30_000L

    private var appContext: Context? = null
    private var defaultHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * Call once from PeerLinkApp.onCreate().
     * Installs this logger as the process-wide uncaught exception handler.
     */
    fun install(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // ── Write crash report to SharedPreferences synchronously ──────────────
            // commit() (not apply()) guarantees the write completes before the
            // default handler terminates the process.
            try {
                val report = buildReport(thread, throwable)
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                val previousCount = prefs.getInt(KEY_CRASH_COUNT, 0)

                prefs.edit()
                    .putString(KEY_CRASH_REPORT, report)
                    .putLong(KEY_CRASH_TIME, System.currentTimeMillis())
                    .putInt(KEY_CRASH_COUNT, previousCount + 1)
                    .commit()   // ← synchronous, guaranteed before process death

            } catch (_: Throwable) {
                // Never let the crash logger itself crash — the default handler
                // still needs to run to produce a proper ANR/crash dialog.
            }

            // ── Hand off to Android's default handler ─────────────────────────────
            // This produces the normal "App has stopped" system dialog and writes
            // to logcat (so adb logcat still works alongside our logger).
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /** Returns the saved crash report, or null if no crash has been recorded. */
    fun getLastReport(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CRASH_REPORT, null)
    }

    /** Returns the timestamp (ms) of the last crash, or 0 if none. */
    fun getLastCrashTime(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_CRASH_TIME, 0L)
    }

    /** Returns how many crashes have been recorded since first install. */
    fun getCrashCount(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CRASH_COUNT, 0)
    }

    /** True if a crash report exists. */
    fun hasCrash(context: Context): Boolean = getLastReport(context) != null

    /** Permanently deletes the saved crash report. */
    fun clearReport(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CRASH_REPORT)
            .remove(KEY_CRASH_TIME)
            .apply()
    }
    /**
     * Persists the current in-memory log to SharedPreferences.
     *
     * IMPORTANT: This is NO LONGER called from every appendLog(). It was
     * causing catastrophic memory pressure (10MB+ of XML serialization per
     * log line, hundreds of times per second during gameplay) and was the
     * root cause of Android 15 killing the entire process mid-match.
     *
     * It's now only called from the crash handler (once, when the app
     * crashes) and is throttled to once per 30 seconds as a safety net.
     * The session file is the canonical log store and is what the export
     * button reads.
     */
    fun persistLiveLog() {
        val now = System.currentTimeMillis()
        if (now - lastPersistMs < PERSIST_THROTTLE_MS) return
        lastPersistMs = now
        val ctx = appContext ?: return
        val log = try { AppState.getLogs() } catch (_: Throwable) { return }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LIVE_LOG, log)
            .apply()   // async — never blocks the calling thread
    }

    /** Returns the live log snapshot. Always non-null — empty string if nothing logged yet. */
    fun getLiveLog(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LIVE_LOG, "") ?: ""


    // ─────────────────────────────────────────────────────────────────────────────
    // Report builder
    // ─────────────────────────────────────────────────────────────────────────────

    private fun buildReport(thread: Thread, throwable: Throwable): String {
        val sb = StringBuilder(4096)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

        sb.appendLine("══════════════════════════════════════════════════")
        sb.appendLine("  PEERLINK CRASH REPORT")
        sb.appendLine("══════════════════════════════════════════════════")
        sb.appendLine("Time       : $ts")
        sb.appendLine("Thread     : ${thread.name}  (id=${thread.id})")
        sb.appendLine("Priority   : ${thread.priority}")

        // ── Device info ──────────────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("── Device ─────────────────────────────────────────")
        sb.appendLine("Manufacturer : ${Build.MANUFACTURER}")
        sb.appendLine("Model        : ${Build.MODEL}")
        sb.appendLine("Android      : ${Build.VERSION.RELEASE}  (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Build        : ${Build.FINGERPRINT}")

        // ── AppState snapshot ────────────────────────────────────────────────────
        // Reading AppState here is safe: we are still in the same process, just
        // on the crashed thread. The JVM has not been killed yet.
        sb.appendLine()
        sb.appendLine("── AppState Snapshot ───────────────────────────────")
        try {
            sb.appendLine("isRunning        : ${AppState.isRunning.get()}")
            sb.appendLine("isPaired         : ${AppState.isPaired.get()}")
            sb.appendLine("connectionMode   : '${AppState.connectionMode}'")
            sb.appendLine("peerIp           : ${AppState.peerIp.get()?.hostAddress ?: "null"}")
            sb.appendLine("localIp          : ${AppState.localIp.get() ?: "null"}")
            sb.appendLine("myFabricatedIp   : ${AppState.myFabricatedIp}")
            sb.appendLine("peerFabricatedIp : ${AppState.peerFabricatedIp}")
            sb.appendLine("peerPort         : ${AppState.peerPort.get()}")
            sb.appendLine("tunneled         : ${AppState.tunneled.get()}")
            sb.appendLine("passedThrough    : ${AppState.passedThrough.get()}")
            sb.appendLine("isGodModeActive  : ${AppState.isGodModeActive.get()}")
            sb.appendLine("sessionId        : ${AppState.currentSessionId}")
            sb.appendLine("sessionAgeS      : ${AppState.getSessionDurationSeconds()}")
        } catch (e: Throwable) {
            sb.appendLine("(AppState read failed: ${e.message})")
        }

        // ── Stack trace ──────────────────────────────────────────────────────────
        sb.appendLine()
        sb.appendLine("── Exception ───────────────────────────────────────")
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            var trace = sw.toString()

            // Include full cause chain
            var cause = throwable.cause
            var depth = 0
            while (cause != null && depth < 6) {
                depth++
                val csw = StringWriter()
                cause.printStackTrace(PrintWriter(csw))
                trace += "\n\nCaused by (#$depth):\n${csw}"
                cause = cause.cause
            }

            // No truncation — full stack trace.
            sb.append(trace)
        } catch (e: Throwable) {
            sb.appendLine("(Stack trace capture failed: ${e.message})")
        }

        // ── Complete DATA FEED log — full capture, no truncation ──
        sb.appendLine()
        sb.appendLine("── Complete DATA FEED Log ──────────────────────────")
        try {
            val logs = AppState.getLogs()
            if (logs.isBlank()) {
                sb.appendLine("(no log entries)")
            } else {
                // No cap — full log.
                sb.append(logs)
            }
        } catch (e: Throwable) {
            sb.appendLine("(Log read failed: ${e.message})")
        }

        sb.appendLine()
        sb.appendLine("══════════════════════════════════════════════════")

        return sb.toString()
    }
}
