package com.peerlink.app.network;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.channels.DatagramChannel;

/** Shared socket creation for IPv4/IPv6 Internet passthrough. */
public final class UdpProxySockets {
    private UdpProxySockets() {}

    public interface Preparer {
        boolean prepare(DatagramSocket socket) throws IOException;
    }

    public static DatagramChannel openConnected(InetSocketAddress peer, Preparer preparer) throws IOException {
        DatagramChannel channel = DatagramChannel.open(peer.getAddress() instanceof Inet6Address
                ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET);
        boolean success = false;
        try {
            channel.configureBlocking(false);
            if (!preparer.prepare(channel.socket())) throw new IOException("VPN socket protection failed");
            // read()/write() require a connected DatagramChannel. A proxy uses
            // its own ephemeral OS port; the original flow stays in its map.
            channel.connect(peer);
            success = true;
            return channel;
        } finally {
            if (!success) channel.close();
        }
    }
}
