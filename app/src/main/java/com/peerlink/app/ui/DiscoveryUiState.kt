package com.peerlink.app.ui

/**
 * User-facing discovery state.
 *
 * This deliberately describes what PeerLink has actually proved.  In
 * particular, SCANNING means a LAN interface was resolved and both discovery
 * listeners were started; it is not used merely because the screen is open.
 */
enum class DiscoveryPhase {
    WAITING_FOR_PERMISSION,
    WAITING_FOR_WIFI,
    SCANNING,
    PEERS_FOUND,
    PAUSED,
    ERROR,
}

data class DiscoveryUiState(
    val phase: DiscoveryPhase = DiscoveryPhase.PAUSED,
    val message: String = "Discovery will start when PeerLink is ready",
    val interfaceName: String = "",
    val peerCount: Int = 0,
) {
    val canRetry: Boolean
        get() = phase == DiscoveryPhase.WAITING_FOR_PERMISSION ||
            phase == DiscoveryPhase.WAITING_FOR_WIFI ||
            phase == DiscoveryPhase.ERROR ||
            phase == DiscoveryPhase.PAUSED
}
