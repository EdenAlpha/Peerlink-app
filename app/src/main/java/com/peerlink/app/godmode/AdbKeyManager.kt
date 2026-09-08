package com.peerlink.app.godmode

import android.content.Context
import android.util.Log
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import java.util.Locale
import javax.security.auth.x500.X500Principal

/**
 * Manages the RSA-2048 key pair and X.509 certificate used for ADB Wireless
 * Debugging authentication. Keys are persisted to the app's private internal
 * storage and survive across app restarts — this is the "Marriage Certificate"
 * that means users only pair once.
 */
object AdbKeyManager {

    private const val TAG = "AdbKeyManager"
    private const val PRIV_KEY_FILE = "adb_godmode.p8"    // PKCS#8 DER private key
    private const val PUB_KEY_FILE  = "adb_godmode.der"   // X.509 DER public key
    private const val CERT_FILE     = "adb_godmode.crt"   // X.509 DER certificate
    private const val PREFS         = "peerlink_prefs"
    private const val KEY_PAIRED    = "gm_adb_paired"

    // In-memory cache so we don't hit disk repeatedly
    @Volatile private var cachedPrivKey: PrivateKey? = null
    @Volatile private var cachedPubKey:  PublicKey?  = null
    @Volatile private var cachedCert:    X509Certificate? = null

    init {
        // Register BC provider once (no-op if already registered)
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  K E Y   &   C E R T   A C C E S S
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Returns the RSA private key, loading from disk or generating if absent.
     */
    fun getPrivateKey(context: Context): PrivateKey {
        cachedPrivKey?.let { return it }
        return loadOrGenerate(context).first
    }

    /**
     * Returns the RSA public key.
     */
    fun getPublicKey(context: Context): PublicKey {
        cachedPubKey?.let { return it }
        return loadOrGenerate(context).second
    }

    /**
     * Returns the X.509 self-signed certificate wrapping our RSA public key.
     * ADB uses this certificate as the "device identity" presented during mTLS.
     */
    fun getCertificate(context: Context): X509Certificate {
        cachedCert?.let { return it }
        return loadOrGenerateCert(context)
    }

    /**
     * Returns true if a key pair already exists on disk (regardless of pairing state).
     */
    fun hasKeys(context: Context): Boolean {
        return File(context.filesDir, PRIV_KEY_FILE).exists() &&
               File(context.filesDir, PUB_KEY_FILE).exists()
    }

    /**
     * Returns true if the user has successfully completed ADB wireless pairing.
     */
    fun isPaired(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_PAIRED, false) && hasKeys(context)
    }

    /**
     * Marks the device as (un)paired in persistent storage.
     */
    fun markPaired(context: Context, paired: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PAIRED, paired).apply()
        Log.i(TAG, "markPaired → $paired")
    }

    /**
     * Clears all stored keys and the pairing flag. User will need to re-pair.
     */
    fun clearAll(context: Context) {
        listOf(PRIV_KEY_FILE, PUB_KEY_FILE, CERT_FILE).forEach {
            File(context.filesDir, it).delete()
        }
        markPaired(context, false)
        cachedPrivKey = null
        cachedPubKey  = null
        cachedCert    = null
        Log.i(TAG, "Keys cleared — re-pairing required")
    }

    // ─────────────────────────────────────────────────────────────────────
    //  I N T E R N A L
    // ─────────────────────────────────────────────────────────────────────

    private fun loadOrGenerate(context: Context): Pair<PrivateKey, PublicKey> {
        val privFile = File(context.filesDir, PRIV_KEY_FILE)
        val pubFile  = File(context.filesDir, PUB_KEY_FILE)

        if (privFile.exists() && pubFile.exists()) {
            try {
                val factory = KeyFactory.getInstance("RSA")
                val priv = factory.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val pub  = factory.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                cachedPrivKey = priv
                cachedPubKey  = pub
                Log.d(TAG, "Loaded existing RSA key pair from disk")
                return Pair(priv, pub)
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted keys on disk — regenerating: ${e.message}")
                privFile.delete(); pubFile.delete()
            }
        }

        Log.i(TAG, "Generating new RSA-2048 key pair…")
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)

        cachedPrivKey = kp.private
        cachedPubKey  = kp.public

        // Invalidate cert so it gets regenerated with the new key
        File(context.filesDir, CERT_FILE).delete()
        cachedCert = null

        Log.i(TAG, "RSA-2048 key pair generated and saved")
        return Pair(kp.private, kp.public)
    }

    private fun loadOrGenerateCert(context: Context): X509Certificate {
        val certFile = File(context.filesDir, CERT_FILE)
        val privKey  = getPrivateKey(context)
        val pubKey   = getPublicKey(context)

        if (certFile.exists()) {
            try {
                val cf   = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(certFile.inputStream()) as X509Certificate
                cachedCert = cert
                return cert
            } catch (e: Exception) {
                Log.w(TAG, "Corrupted cert on disk — regenerating: ${e.message}")
                certFile.delete()
            }
        }

        Log.i(TAG, "Generating self-signed X.509 certificate…")

        val now     = Date()
        val expiry  = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000) // 10 years
        val subject = X500Name("CN=PeerLink,O=PeerLink,C=NG")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(System.currentTimeMillis()),
            now,
            expiry,
            subject,
            pubKey
        )

        // Do NOT force the "BC" provider — bcprov-jdk18on:1.77 conflicts with
        // Android's built-in BouncyCastle and fails to register SHA256withRSA
        // under that provider name.  Letting Java choose automatically works.
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .build(privKey)

        val cert = JcaX509CertificateConverter()
            .getCertificate(certBuilder.build(signer))

        certFile.writeBytes(cert.encoded)
        cachedCert = cert

        Log.i(TAG, "X.509 certificate generated and saved")
        return cert
    }
}
