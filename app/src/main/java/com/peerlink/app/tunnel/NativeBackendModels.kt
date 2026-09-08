package com.peerlink.app.tunnel

/** Static configuration for the native packet backend. */
data class NativeBackendConfig(
    val peerLanIp: String,
    val peerPort: Int,
    val myFabricatedIp: String,
    val peerFabricatedIp: String,
    /** Exact peer-reachable source IPv4 locked before VPN startup. */
    val localLanIp: String,
    /** Kernel interface index for the same LAN path (SoftAP or wlan). */
    val localInterfaceIndex: Int,
    /** Our advertised listener; the peer may advertise a different destination port. */
    val localPort: Int = 17024,
    val vpnAddress: String = "10.0.0.2",
    val vpnAddressIpv6: String = "fd00::2",
    val mtu: Int = 1400,
    /** Private app-file path for the zero-formatting full wire capture. */
    val rawCapturePath: String = "",
)

/** Pollable counters exported by the native backend. */
data class NativeBackendStats(
    val tunnelOutPackets: Long,
    val tunnelOutBytes: Long,
    val tunnelInPackets: Long,
    val tunnelInBytes: Long,
    val stunInterceptedIpv4: Long,
    val stunInterceptedIpv6: Long,
    val passthroughToJvmPackets: Long,
    val passthroughToTunPackets: Long,
    val droppedPackets: Long,
    val keepaliveTx: Long,
    val keepaliveRx: Long,
    val backendRunning: Boolean = false,
) {
    val totalTunneledPackets: Long get() = tunnelOutPackets + tunnelInPackets

    companion object {
        val EMPTY = NativeBackendStats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        fun fromRaw(raw: LongArray?): NativeBackendStats {
            if (raw == null || raw.size < 12) return EMPTY
            return NativeBackendStats(
                tunnelOutPackets = raw[0],
                tunnelOutBytes = raw[1],
                tunnelInPackets = raw[2],
                tunnelInBytes = raw[3],
                stunInterceptedIpv4 = raw[4],
                stunInterceptedIpv6 = raw[5],
                passthroughToJvmPackets = raw[6],
                passthroughToTunPackets = raw[7],
                droppedPackets = raw[8],
                keepaliveTx = raw[9],
                keepaliveRx = raw[10],
                backendRunning = raw[11] != 0L,
            )
        }
    }
}


/** Integrity counters for the full-fidelity binary wire capture. */
data class RawCaptureStats(
    val packets: Long = 0L,
    val bytes: Long = 0L,
    val queueDrops: Long = 0L,
    val oversizedDrops: Long = 0L,
    val writeErrors: Long = 0L,
    val fileBytes: Long = 0L,
) {
    val complete: Boolean get() = queueDrops == 0L && oversizedDrops == 0L && writeErrors == 0L

    companion object {
        fun fromRaw(raw: LongArray?): RawCaptureStats {
            if (raw == null || raw.size < 6) return RawCaptureStats()
            return RawCaptureStats(
                packets = raw[0].coerceAtLeast(0L),
                bytes = raw[1].coerceAtLeast(0L),
                queueDrops = raw[2].coerceAtLeast(0L),
                oversizedDrops = raw[3].coerceAtLeast(0L),
                writeErrors = raw[4].coerceAtLeast(0L),
                fileBytes = raw[5].coerceAtLeast(0L),
            )
        }
    }
}
