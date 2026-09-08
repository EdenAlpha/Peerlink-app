package com.peerlink.app.godmode.shizuku

import android.content.Context
import com.peerlink.app.godmode.PrimeAuth
import java.io.File

// ══════════════════════════════════════════════════════════════════
// SHIZUKU ORIGINAL (Starter.kt):
//
//   object Starter {
//       private val starterFile =
//           File(application.applicationInfo.nativeLibraryDir, "libshizuku.so")
//       val userCommand: String = starterFile.absolutePath
//       val internalCommand = "$userCommand --apk=${application.applicationInfo.sourceDir}"
//   }
//
// WHAT SHIZUKU ACTUALLY SENDS OVER THE ADB SHELL:
//   /data/app/moe.shizuku.privileged.api-.../lib/arm64/libshizuku.so \
//       --apk=/data/app/moe.shizuku.privileged.api-.../base.apk
//
// That command runs the NATIVE BINARY libshizuku.so which:
//   1. fork()        — creates child process
//   2. setsid()      — child becomes NEW session leader (detached from ADB shell)
//   3. chdir("/")    — no working dir dependency
//   4. dup2(/dev/null) — stdin/stdout/stderr closed
//   5. execvp(app_process) — replaces child with the Java VM running ShizukuService
//
// The parent exits immediately → ADB shell gets CLSE → connection closes cleanly.
// SIGHUP on session end only reaches the OLD session. The child is in its own
// session and is IMMUNE. The server lives.
//
// ──────────────────────────────────────────────────────────────────
// WHAT YOUR OLD CODE DID (WRONG):
//
//   return "CLASSPATH=$apk app_process /system/bin ... $entry >> ... 2>&1 &"
//
// That sends a raw shell string. The & backgrounds app_process but it is still
// in the SAME session as the ADB shell. When the ADB connection closes, SIGHUP
// is delivered to the entire session → app_process dies → PrimeServer never
// binds port 13373 → "PrimeServer did not bind in 10s" → ERROR.
//
// THE FIX: route through libpeerlinkstarter.so exactly as Shizuku does.
// ══════════════════════════════════════════════════════════════════

object PrimeShizukuStarter {

    /**
     * Builds the shell command sent via ADB to launch PrimeServer.
     *
     * Mirrors Shizuku's Starter.internalCommand exactly:
     *   - Points to libpeerlinkstarter.so in nativeLibraryDir
     *   - Passes --apk= so the native binary can find the dex classpath
     *
     * libpeerlinkstarter.so (starter.cpp) then:
     *   fork() → setsid() → chdir("/") → dup2(/dev/null) → execvp(app_process)
     *
     * The setsid() is what makes PrimeServer survive the ADB shell closing.
     */
    fun internalCommand(context: Context): String {
        val starterBinary = File(
            context.applicationInfo.nativeLibraryDir,
            "libpeerlinkstarter.so"
        )
        val apk = context.applicationInfo.sourceDir
        val token = PrimeAuth.token(context)
        // Identical pattern to Shizuku:  /path/to/libshizuku.so --apk=/path/to/base.apk
        // plus a per-install token passed only to PeerLink's detached server.
        fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
        check(starterBinary.isFile) { "Prime starter is missing from this APK" }
        return "${quote(starterBinary.absolutePath)} --apk=${quote(apk)} --token=${quote(token)}"
    }
}
