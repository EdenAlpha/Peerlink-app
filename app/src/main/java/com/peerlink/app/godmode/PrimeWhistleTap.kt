package com.peerlink.app.godmode

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.Looper
import android.os.Process
import org.json.JSONObject

/**
 * PrimeWhistleTap — popup-free capture of the game's own audio output.
 *
 * WHY THIS EXISTS
 *  The final whistle is the most reliable full-time signal eFootball emits,
 *  but the app must never trust the microphone (external sounds, other apps,
 *  a TV in the same room) and must never show the MediaProjection warning
 *  dialog ("may capture passwords"). Both problems disappear with one move:
 *  run the capture inside Prime, which is an app_process holding shell uid.
 *
 * THE MECHANISM (proven precedent: scrcpy's audio source, yume-chan's PoC)
 *  Register an AudioPolicy mix with ROUTE_FLAG_LOOP_BACK_RENDER using the
 *  Audio Policy API. It is the same underlying path as AudioPlaybackCapture,
 *  but authorised by MODIFY_AUDIO_ROUTING — a permission the shell uid holds
 *  since Android 13 — instead of a MediaProjection consent dialog. The
 *  loop-back flag keeps the device's own playback alive, so the player keeps
 *  hearing the match while we listen (RemoteSubmix would mute the speaker).
 *
 *  Anti-spoof layers on top of the source choice live app-side: the capture
 *  only ever runs inside a locked match window with eFootball foreground.
 *
 * TWO ENTRY POINTS
 *  probe()      — fixed-length record used by Settings (the on-device test
 *                 that proves the tap works and lets the user listen to it).
 *  openSession()— open-ended record for the FT hold gesture: starts on press,
 *                 stops on release, capped at MAX_SESSION_SECONDS so a lost
 *                 release can never leak an eternal capture. Both share one
 *  reader thread draining the AudioRecord, because an undrained capture
 *  buffer overflows and the policy must be unregistered exactly once.
 *
 * REFLECTION POLICY
 *  android.media.audiopolicy.* members are not all present in the public SDK.
 *  PrimeServer already reaches hidden framework classes the same way
 *  (SurfaceControl display capture), so every audiopolicy entry point is
 *  resolved by name at runtime and any failure surfaces as a typed error
 *  string instead of a crash.
 *
 * VARIANTS (the on-device experiment picks the winner)
 *  usage      — capture USAGE_GAME + USAGE_MEDIA mixes, normal privilege.
 *  usage_priv — same, allowPrivilegedPlaybackCapture(true): also reaches
 *               apps that opted out of playback capture.
 *  uid        — capture only the game's own uid: nothing any other app
 *               plays can enter the tap at all.
 */
object PrimeWhistleTap {

    const val SAMPLE_RATE = 48_000
    const val MIN_SDK = Build.VERSION_CODES.TIRAMISU // 13: shell gained MODIFY_AUDIO_ROUTING
    const val MAX_SESSION_SECONDS = 60

    private const val MODIFY_AUDIO_ROUTING = "android.permission.MODIFY_AUDIO_ROUTING"

    class ProbeFailed(val reason: String, val detail: String = "") : Exception(reason)

    data class Outcome(val json: JSONObject, val wav: ByteArray?)

    /**
     * A live capture: reader thread accumulates PCM until stopAndDrain().
     * The Session owns the AudioRecord and the registered AudioPolicy and
     * guarantees both are released exactly once (reader thread end and
     * stopAndDrain both funnel into the idempotent close).
     */
    class Session internal constructor(
        private val info: JSONObject,
        private val sink: AudioRecord,
        private val policy: Any,
        private val audioManager: AudioManager,
    ) {
        private val buf = java.io.ByteArrayOutputStream()
        private val openedAt = System.currentTimeMillis()
        @Volatile private var running = false
        @Volatile private var closed = false
        private var reader: Thread? = null

        internal fun startReader() {
            running = true
            reader = Thread({
                val chunk = ByteArray(8_192)
                val deadline = openedAt + MAX_SESSION_SECONDS * 1_000L
                while (running && System.currentTimeMillis() < deadline) {
                    val n = runCatching { sink.read(chunk, 0, chunk.size) }.getOrDefault(-1)
                    when {
                        n > 0 -> synchronized(buf) { buf.write(chunk, 0, n) }
                        n < 0 -> break
                        else -> runCatching { Thread.sleep(10) }
                    }
                }
                running = false
                closeOnce()
            }, "whistle-tap-reader").apply {
                isDaemon = true
                start()
            }
        }

        val isActive: Boolean get() = running

        /** Stop reading, release everything, return info + raw PCM. */
        fun stopAndDrain(): Pair<JSONObject, ByteArray> {
            running = false
            reader?.join(2_000)
            closeOnce()
            val pcm = synchronized(buf) { buf.toByteArray() }
            info.put("bytes", pcm.size)
            info.put("peak", WhistleWav.peak(pcm))
            info.put("silent", pcm.isEmpty() || WhistleWav.isSilent(pcm))
            return info to pcm
        }

        private fun closeOnce() {
            if (closed) return
            closed = true
            runCatching { sink.stop() }
            runCatching { sink.release() }
            runCatching { invokeByName(audioManager, "unregisterAudioPolicy", policy) }
        }
    }

    /**
     * Fixed-length probe for Settings. Never throws — every failure becomes
     * ok=false with a machine-readable error, because the caller shows it to
     * the user.
     */
    fun probe(seconds: Int, variant: String, gamePackage: String): Outcome {
        val info = scaffoldInfo(seconds, variant)
        return try {
            val session = openSession(variant, gamePackage, info)
            session.startReader()
            Thread.sleep(seconds * 1_000L)
            val (finalInfo, pcm) = session.stopAndDrain()
            if (pcm.isEmpty()) throw ProbeFailed("no_data")
            finalInfo.put("ok", true)
            Outcome(finalInfo, WhistleWav.wrap(pcm, SAMPLE_RATE))
        } catch (e: ProbeFailed) {
            info.put("ok", false)
            info.put("error", e.reason)
            if (e.detail.isNotEmpty()) info.put("detail", e.detail)
            Outcome(info, null)
        } catch (t: Throwable) {
            info.put("ok", false)
            info.put("error", "exception")
            info.put("detail", "${t.javaClass.simpleName}: ${t.message}")
            Outcome(info, null)
        }
    }

    /**
     * Open an open-ended session (FT hold gesture). Throws [ProbeFailed]
     * with a typed reason; the caller reports it verbatim.
     */
    fun openSession(variant: String, gamePackage: String): Session =
        openSession(variant, gamePackage, scaffoldInfo(0, variant))

    private fun scaffoldInfo(seconds: Int, variant: String): JSONObject = JSONObject().apply {
        put("sdk", Build.VERSION.SDK_INT)
        put("uid", Process.myUid())
        put("variant", variant)
        put("seconds", seconds)
    }

    // ── capture pipeline ─────────────────────────────────────────────────────

    private fun openSession(variant: String, gamePackage: String, info: JSONObject): Session {
        if (Build.VERSION.SDK_INT < MIN_SDK) throw ProbeFailed("sdk_below_13", "sdk=${Build.VERSION.SDK_INT}")
        val ctx = systemContext()
        info.put("audioRoutingPerm", hasAudioRoutingPermission(ctx))
        val gameUid = gameUid(ctx, gamePackage)
        info.put("gameUid", gameUid)

        val mixRuleCls = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            .getDeclaredConstructor().newInstance()

        when (variant) {
            "uid" -> addMixRule(ruleBuilder, mixRuleCls, "RULE_MATCH_UID", gameUid)
            else -> {
                val usageRule = staticIntField(mixRuleCls, "RULE_MATCH_ATTRIBUTE_USAGE")
                addMixRule(ruleBuilder, usageRule, AudioAttributes.USAGE_GAME)
                addMixRule(ruleBuilder, usageRule, AudioAttributes.USAGE_MEDIA)
                if (variant == "usage_priv") {
                    invokeByName(ruleBuilder, "allowPrivilegedPlaybackCapture", true)
                    info.put("privileged", true)
                }
            }
        }
        invokeByName(ruleBuilder, "setTargetMixRole", staticField(mixRuleCls, "MIX_ROLE_PLAYERS"))
        val rule = invokeByName(ruleBuilder, "build")
            ?: throw ProbeFailed("rule_build_null")

        val mixCls = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            .getDeclaredConstructor(rule.javaClass).newInstance(rule)
        invokeByName(mixBuilder, "setFormat", mixFormat())
        invokeByName(mixBuilder, "setRouteFlags", staticIntField(mixCls, "ROUTE_FLAG_LOOP_BACK_RENDER"))
        val mix = invokeByName(mixBuilder, "build") ?: throw ProbeFailed("mix_build_null")

        val policyCls = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            .getDeclaredConstructor(Context::class.java).newInstance(ctx)
        invokeByName(policyBuilder, "addMix", mix)
        val policy = invokeByName(policyBuilder, "build") ?: throw ProbeFailed("policy_build_null")

        val audioManager = ctx.getSystemService(AudioManager::class.java)
            ?: throw ProbeFailed("no_audio_manager")
        val registerResult = try {
            invokeByName(audioManager, "registerAudioPolicy", policy) as? Int ?: -1
        } catch (se: SecurityException) {
            throw ProbeFailed("no_audio_routing_permission", se.message ?: "")
        }
        info.put("registerResult", registerResult)
        if (registerResult != 0) throw ProbeFailed("register_failed", "code=$registerResult")

        val sink = try {
            invokeByName(policy, "createAudioRecordSink", mix) as? AudioRecord
        } catch (t: Throwable) {
            runCatching { invokeByName(audioManager, "unregisterAudioPolicy", policy) }
            throw ProbeFailed("no_record_sink", t.message ?: "")
        } ?: throw ProbeFailed("no_record_sink")

        try {
            sink.startRecording()
            if (sink.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw ProbeFailed("record_not_started", "state=${sink.recordingState}")
            }
        } catch (t: Throwable) {
            runCatching { sink.release() }
            runCatching { invokeByName(audioManager, "unregisterAudioPolicy", policy) }
            throw ProbeFailed("record_not_started", t.message ?: "")
        }
        return Session(info, sink, policy, audioManager)
    }

    private fun mixFormat(): AudioFormat = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build()

    // ── context & permission ────────────────────────────────────────────────

    /**
     * app_process has no ApplicationInfo; the system Context is what
     * AudioPolicy.Builder needs for attribution. Same route as yume-chan's
     * reference implementation.
     *
     * TRAP (field finding, first device test): ActivityThread.systemMain()
     * builds its H handler, which needs a Looper on the CALLING thread —
     * prime_worker threads are bare (only PrimeServerMain's main thread has
     * a looper, and that one belongs to a different thread). Without a
     * Looper, systemMain throws, the old code swallowed the exception, and
     * the app could only report the useless "no_system_context". Now: the
     * calling thread gets its own Looper first, and a failure carries the
     * real exception as detail so the next field report can be read.
     */
    private fun systemContext(): Context {
        val errors = mutableListOf<String>()
        if (Looper.myLooper() == null) {
            runCatching { Looper.prepare() }
                .onFailure { errors += "looper:${it.message}" }
        }
        // Some ROMs enforce hidden-API rules even in app_process; this
        // greylist call unlocks ActivityThread reflection. Harmless when
        // the ROM already allows it or blocks the call itself.
        runCatching {
            val vmCls = Class.forName("dalvik.system.VMRuntime")
            val runtime = vmCls.getDeclaredMethod("getRuntime").invoke(null)
            vmCls.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
                .invoke(runtime, arrayOf<String>(""))
        }.onFailure { errors += "hidden-api:${it.message}" }
        val first = runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val thread = invokeByName(atCls, "systemMain") ?: error("systemMain=null")
            extractSystemContext(atCls, thread) ?: error("mSystemContext missing")
        }
        first.getOrNull()?.let { return it }
        first.exceptionOrNull()?.let { errors += it.toString() }
        // systemMain may have installed the thread before a later step threw;
        // a second pass can still reach the context it created.
        val second = runCatching {
            val atCls = Class.forName("android.app.ActivityThread")
            val thread = invokeByName(atCls, "currentActivityThread")
                ?: error("currentActivityThread=null")
            extractSystemContext(atCls, thread) ?: error("mSystemContext missing")
        }
        second.getOrNull()?.let { return it }
        second.exceptionOrNull()?.let { errors += it.toString() }
        throw ProbeFailed("no_system_context", errors.joinToString(" | ").take(400))
    }

    private fun extractSystemContext(atCls: Class<*>, thread: Any): Context? =
        (runCatching { invokeByName(thread, "getSystemContext") }.getOrNull()
            ?: runCatching {
                atCls.getDeclaredField("mSystemContext")
                    .apply { isAccessible = true }
                    .get(thread)
            }.getOrNull()) as? Context

    private fun hasAudioRoutingPermission(ctx: Context): Boolean = runCatching {
        ctx.checkCallingOrSelfPermission(MODIFY_AUDIO_ROUTING) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun gameUid(ctx: Context, pkg: String): Int = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0).uid
    }.getOrDefault(-1)

    // ── reflection helpers ──────────────────────────────────────────────────

    private fun addMixRule(ruleBuilder: Any, ruleClsOrType: Any, value: Any?) {
        val ruleType = when (ruleClsOrType) {
            is Int -> ruleClsOrType
            is Class<*> -> staticIntField(ruleClsOrType, "RULE_MATCH_ATTRIBUTE_USAGE")
            else -> throw ProbeFailed("bad_rule_type")
        }
        invokeByName(ruleBuilder, "addMixRule", ruleType, value)
            ?: throw ProbeFailed("add_mix_rule_null")
    }

    private fun addMixRule(ruleBuilder: Any, ruleCls: Class<*>, field: String, value: Any?) {
        invokeByName(ruleBuilder, "addMixRule", staticIntField(ruleCls, field), value)
            ?: throw ProbeFailed("add_mix_rule_null", field)
    }

    /** Invoke the first method with matching name and arity; hidden-API safe. */
    private fun invokeByName(target: Any?, name: String, vararg args: Any?): Any? {
        val owner = target as? Class<*> ?: target?.javaClass ?: throw ProbeFailed("null_target", name)
        var cls: Class<*>? = owner
        while (cls != null) {
            for (m in cls.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == args.size) {
                    m.isAccessible = true
                    return if (target is Class<*>) m.invoke(null, *args) else m.invoke(target, *args)
                }
            }
            cls = cls.superclass
        }
        throw NoSuchMethodException("${owner.name}#$name/${args.size}")
    }

    private fun staticField(cls: Class<*>, name: String): Any? {
        var c: Class<*>? = cls
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(null)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw ProbeFailed("missing_field", "${cls.name}#$name")
    }

    private fun staticIntField(cls: Class<*>, name: String): Int =
        staticField(cls, name) as? Int ?: throw ProbeFailed("missing_int_field", "${cls.name}#$name")
}
