package com.peerlink.app.network;

import java.util.Collection;
import java.util.Objects;

/** Android-independent decisions for a socket bound to one IPv4 LAN path.
 * The service serializes calls on its gameplay refresh lock. Times are elapsed
 * realtime, never the wall clock. No radio quality or reachability is inferred.
 */
public final class GameplayPathPolicy {
    private final long retryIntervalMs;
    private String boundIdentity;
    private String attemptedIdentity;
    private long retryAtMs;

    public GameplayPathPolicy(long retryIntervalMs) {
        if (retryIntervalMs < 0) throw new IllegalArgumentException("Negative retry interval");
        this.retryIntervalMs = retryIntervalMs;
    }

    public static boolean matchesLockedPath(String localIp, String interfaceName,
            String candidateInterface, Collection<String> candidateAddresses) {
        return localIp != null && !localIp.isEmpty() && interfaceName != null &&
                !interfaceName.isEmpty() && interfaceName.equals(candidateInterface) &&
                candidateAddresses.contains(localIp);
    }

    public static String identity(String networkId, String interfaceName, String localIp) {
        // IPv6 rotation, address ordering, DNS, RSSI, and validation do not
        // change the IPv4 socket's source path.
        return networkId + "|" + interfaceName + "|" + localIp;
    }

    public void rememberBound(String identity) {
        boundIdentity = identity;
        attemptedIdentity = null;
        retryAtMs = 0;
    }

    public void markLost() {
        boundIdentity = null;
    }

    public boolean needsRebind(String identity) {
        return identity != null && !identity.equals(boundIdentity);
    }

    public long retryDelayMs(String identity, long nowMs) {
        if (!Objects.equals(identity, attemptedIdentity)) return 0;
        return Math.max(0, retryAtMs - nowMs);
    }

    public void recordAttempt(String identity, long nowMs, boolean succeeded) {
        attemptedIdentity = identity;
        retryAtMs = nowMs + retryIntervalMs;
        if (succeeded) boundIdentity = identity;
    }
}
