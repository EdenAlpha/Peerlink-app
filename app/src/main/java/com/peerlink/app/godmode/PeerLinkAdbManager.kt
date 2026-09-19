package com.peerlink.app.godmode

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.conscrypt.Conscrypt
import java.security.Security
import java.security.PrivateKey
import java.security.cert.Certificate

/**
 * PeerLinkAdbManager — bridges libadb-android with our AdbKeyManager.
 *
 * SINGLETON NOTE: After pair() completes, the internal libadb state is dirty.
 * Always call resetInstance() before using connect() on a fresh session —
 * especially during bootstrap (immediately after pairing).
 */
class PeerLinkAdbManager private constructor(context: Context) :
    AbsAdbConnectionManager() {

    companion object {
        private const val TAG = "PeerLinkAdbManager"

        @Volatile private var INSTANCE: PeerLinkAdbManager? = null

        fun getInstance(context: Context): PeerLinkAdbManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PeerLinkAdbManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }

        /**
         * Destroys the current singleton and creates a fresh one.
         *
         * MUST be called before connect() when the previous operation was pair().
         * libadb-android's AbsAdbConnectionManager holds internal TLS/stream state
         * from the pairing session — reusing it for connect() silently fails.
         *
         * Called by GodModeManager before bootstrap connect.
         */
        fun resetInstance(context: Context): PeerLinkAdbManager {
            synchronized(this) {
                try { INSTANCE?.disconnect() } catch (_: Exception) {}
                INSTANCE = null
                Log.i(TAG, "Singleton reset — fresh instance created for connect")
            }
            return getInstance(context)
        }

        /**
         * Install Conscrypt as the first security provider.
         * Call once from Application.onCreate() or before first ADB operation.
         * Safe to call multiple times — checks if already installed.
         */
        fun installConscrypt() {
            if (Security.getProviders().none { it.name == "Conscrypt" }) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1)
                Log.i(TAG, "Conscrypt installed as security provider #1")
            }
        }

    }

    private val appContext = context.applicationContext

    init {
        setApi(Build.VERSION.SDK_INT)
        setTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
        Log.d(TAG, "PeerLinkAdbManager created (API ${Build.VERSION.SDK_INT})")
    }

    override fun getPrivateKey(): PrivateKey {
        return AdbKeyManager.getPrivateKey(appContext)
    }

    override fun getCertificate(): Certificate {
        return AdbKeyManager.getCertificate(appContext)
    }

    override fun getDeviceName(): String {
        return "PeerLink"
    }
}
