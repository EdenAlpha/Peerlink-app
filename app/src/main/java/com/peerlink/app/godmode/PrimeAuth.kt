package com.peerlink.app.godmode

import android.content.Context
import java.security.SecureRandom

/** Per-install authentication for the loopback PrimeServer. */
object PrimeAuth {
    private const val PREFS = "prime_auth"
    private const val KEY_TOKEN = "loopback_token_v1"
    private const val TOKEN_BYTES = 32

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedToken: String? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        token(context)
    }

    fun tokenOrNull(): String? = cachedToken ?: appContext?.let { token(it) }

    fun token(context: Context): String {
        appContext = context.applicationContext
        cachedToken?.let { return it }
        synchronized(this) {
            cachedToken?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(KEY_TOKEN, null)
                ?.takeIf(::isValidToken)
            val resolved = existing ?: newToken().also {
                // commit() is intentional: the token must be durable before it
                // is handed to a detached app_process server.
                check(prefs.edit().putString(KEY_TOKEN, it).commit()) {
                    "Could not persist Prime authentication token"
                }
            }
            cachedToken = resolved
            return resolved
        }
    }

    fun rotate(context: Context): String = synchronized(this) {
        val replacement = newToken()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().putString(KEY_TOKEN, replacement).commit()) {
            "Could not rotate Prime authentication token"
        }
        appContext = context.applicationContext
        cachedToken = replacement
        replacement
    }

    fun isValidToken(value: String): Boolean =
        value.length == TOKEN_BYTES * 2 && value.all { it in '0'..'9' || it in 'a'..'f' }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
