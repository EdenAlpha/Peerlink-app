package com.peerlink.app.godmode

import android.os.Looper

// ══════════════════════════════════════════════════════════════════
// SHIZUKU ORIGINAL (ServiceStarter.java — the app_process entry point):
//
//   public static void main(String[] args) {
//       if (Looper.getMainLooper() == null) {    ← NULL CHECK — critical
//           Looper.prepareMainLooper();
//       }
//       // ... service setup ...
//       Looper.loop();
//       System.exit(0);
//   }
//
// WHY THE NULL CHECK EXISTS:
//   On some Android ROMs, app_process itself calls Looper.prepareMainLooper()
//   before invoking your main(). If you call it again unconditionally,
//   Android throws:
//     IllegalStateException: The main Looper has already been prepared.
//   The process dies before PrimeServer ever binds port 13373.
//
// ──────────────────────────────────────────────────────────────────
// WHAT YOUR OLD CODE DID (WRONG):
//
//   fun main(args: Array<String>) {
//       Looper.prepareMainLooper()   ← NO null check → crash on affected ROMs
//       PrimeServer.runForever()     ← blocks forever, never returns
//       Looper.loop()               ← DEAD CODE — unreachable, misleading
//   }
//
// THE FIX:
//   1. Guard prepareMainLooper() with a null check — identical to Shizuku.
//   2. Remove Looper.loop() — runForever() blocks in an infinite accept() loop
//      and never returns, so Looper.loop() is dead code. Having it implies
//      runForever() returns, which it doesn't. Removing it matches Shizuku's
//      architecture where the server loop IS the main loop.
// ══════════════════════════════════════════════════════════════════

object PrimeServerMain {

    @JvmStatic
    fun main(args: Array<String>) {
        // Guard matches Shizuku's ServiceStarter.java exactly.
        // Some ROMs (XOS, MIUI, OxygenOS) have app_process call
        // prepareMainLooper() before reaching here. Calling it twice
        // throws IllegalStateException and kills PrimeServer before
        // it binds port 13373.
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper()
        }

        // runForever() is an infinite ServerSocket.accept() loop.
        // It never returns. Looper.loop() after it is unreachable dead code
        // — removed to match Shizuku's pattern and avoid confusion.
        val token = args.firstOrNull { it.startsWith("--token=") }
            ?.substringAfter("--token=")
            .orEmpty()
        if (!PrimeAuth.isValidToken(token)) {
            System.err.println("PrimeServer refused to start without a valid authentication token")
            return
        }
        PrimeServer.runForever(token)
    }
}
