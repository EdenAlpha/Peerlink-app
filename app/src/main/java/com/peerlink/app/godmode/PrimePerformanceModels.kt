package com.peerlink.app.godmode

enum class PrimeLinkState { IDLE, CONNECTED, RECOVERING, DEGRADED }

enum class PrimeActionPhase { IDLE, WORKING, SUCCESS, ERROR }

data class PrimeActionFeedback(
    val action: String = "",
    val phase: PrimeActionPhase = PrimeActionPhase.IDLE,
    val message: String = "",
    val changedAtMs: Long = 0L,
)

data class PrimeExecResult(
    val output: String,
    val exitCode: Int,
    val ok: Boolean,
)

enum class PrimeGraphicsBackend {
    PENDING,
    MODERN_CUSTOM,
    LEGACY_DIRECT,
    DEVICE_CONFIG_PERFORMANCE,
    NONE,
}

data class PrimeCapabilities(
    val sdk: Int = android.os.Build.VERSION.SDK_INT,
    val uidState: Boolean = false,
    val processFreeze: Boolean = false,
    val processCompact: Boolean = false,
    val gameManager: Boolean = false,
    val gameDownscale: Boolean = false,
    val gamePerformanceMode: Boolean = false,
    val artCompile: Boolean = false,
    val surfaceFlingerTimeStats: Boolean = false,
    val fixedPerformanceMode: Boolean = false,
    val graphicsBackend: PrimeGraphicsBackend = PrimeGraphicsBackend.PENDING,
    val graphicsProbeDetail: String = "Graphics capability not verified yet",
) {
    companion object {
        fun coarse(): PrimeCapabilities {
            val sdk = android.os.Build.VERSION.SDK_INT
            return PrimeCapabilities(
                sdk = sdk,
                uidState = sdk >= 26,
                processFreeze = sdk >= 35, // Candidate only; refined by the actual shell probe.
                processCompact = sdk >= 31,
                gameManager = sdk >= 31,
                // Never expose graphics from SDK level alone. OEMs backport,
                // remove and alter GameManager shell surfaces.
                gameDownscale = false,
                gamePerformanceMode = false,
                artCompile = sdk >= 26,
                surfaceFlingerTimeStats = sdk >= 29,
                fixedPerformanceMode = sdk >= 30,
                graphicsBackend = if (sdk >= 31) PrimeGraphicsBackend.PENDING else PrimeGraphicsBackend.NONE,
                graphicsProbeDetail = if (sdk >= 31) "Verification pending" else "No standard Android Game Mode downscale path",
            )
        }
    }
}

enum class PrimeGraphicsMode { AUTO, MANUAL }
enum class PrimeArtMode { DEFAULT, PROFILE, FULL }
enum class PrimeMemoryAggression { BALANCED, AGGRESSIVE }

data class PrimeFrameStats(
    val averageFps: Float = 0f,
    val totalFrames: Int = 0,
    val jankyFrames: Int = 0,
    val gpuJankyFrames: Int = 0,
    val cpuJankyFrames: Int = 0,
    val recommendedScale: String? = null,
    val note: String = "Waiting for gameplay sample",
)
