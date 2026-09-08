package com.peerlink.app.godmode

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException

/**
 * AdbPairingClient — thin wrapper around libadb-android's pairing API.
 *
 * STRATEGY: We no longer hand-roll SPAKE2 / TLS / AES-GCM ourselves.
 * libadb-android (by MuntashirAkon, 318 stars, same author as spake2-java)
 * handles all of it correctly and has been proven against real Android devices.
 *
 * libadb-android internally does:
 *   1. TLS 1.3 via Conscrypt (mutual — presents our cert automatically)
 *   2. SPAKE2 with Curve25519, identity "adb pair client\0"/"adb pair server\0"
 *   3. AES-128-GCM PeerInfo exchange with counter-based nonce
 *   4. Marks device as paired in its own state
 *
 * We still use AdbKeyManager for our key/cert storage so pairing persists
 * across app restarts. The AbsAdbConnectionManager subclass passes our
 * stored keys into libadb's TLS layer.
 */
class AdbPairingClient(private val context: Context) {

    companion object {
        private const val TAG = "AdbPairingClient"
    }

    sealed class Result {
        object Success : Result()
        data class Failure(val reason: String) : Result()
    }

    /**
     * Performs full pairing against adbd at host:port using the 6-digit pin.
     * Blocking — call from a background thread (IO dispatcher).
     */
    fun pair(host: String, port: Int, pin: String): Result {
        Log.i(TAG, "Pairing → $host:$port …")
        return try {
            val manager = PeerLinkAdbManager.getInstance(context)
            manager.pair(host, port, pin)
            Log.i(TAG, "✅ libadb-android pairing succeeded")
            AdbKeyManager.markPaired(context, true)
            Result.Success
        } catch (e: AdbPairingRequiredException) {
            // Should not happen — we're calling pair() ourselves
            Log.e(TAG, "Pairing required exception (unexpected): ${e.message}")
            Result.Failure("Pairing required: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed: ${e.message}", e)
            Result.Failure(e.message ?: "Unknown error")
        }
    }
}
