import com.peerlink.app.network.UdpProxySockets;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Actual JVM UDP proxy IO on ::1 / 127.0.0.1. No Android or Internet traffic. */
public final class UdpProxySocketsRegression {
    private static int checks;
    private static void check(String label, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(label);
        System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        for (String loopback : new String[]{"127.0.0.1", "::1"}) {
            boolean v6 = loopback.contains(":");
            try (DatagramChannel server = DatagramChannel.open(v6 ? StandardProtocolFamily.INET6 : StandardProtocolFamily.INET)) {
                server.bind(new InetSocketAddress(InetAddress.getByName(loopback), 0));
                server.configureBlocking(false);
                AtomicReference<DatagramSocket> borrowed = new AtomicReference<>();
                try (DatagramChannel proxy = UdpProxySockets.openConnected((InetSocketAddress)server.getLocalAddress(), socket -> {
                    borrowed.set(socket);
                    check(loopback + " preparation precedes connect", !socket.isConnected());
                    return true;
                }); Selector selector = Selector.open()) {
                    check(loopback + " proxy supports connected read/write", proxy.isConnected() && !proxy.isBlocking());
                    server.register(selector, SelectionKey.OP_READ);
                    byte[] payload = {4, 3, 2, 1};
                    proxy.write(ByteBuffer.wrap(payload));
                    check(loopback + " destination receives", selector.select(1000) > 0);
                    ByteBuffer received = ByteBuffer.allocate(32);
                    var source = server.receive(received);
                    check(loopback + " outbound bytes preserved", source != null && Arrays.equals(payload, Arrays.copyOf(received.array(), received.position())));
                    server.keyFor(selector).cancel();
                    selector.selectNow(); selector.selectedKeys().clear();
                    proxy.register(selector, SelectionKey.OP_READ);
                    server.send(ByteBuffer.wrap(payload), source);
                    check(loopback + " reply becomes readable", selector.select(1000) > 0);
                    received.clear();
                    check(loopback + " connected read returns reply without NotYetConnectedException", proxy.read(received) == 4 && received.get(0) == 4);
                }
                check(loopback + " proxy owns and closes its socket", borrowed.get().isClosed());
                try {
                    UdpProxySockets.openConnected((InetSocketAddress)server.getLocalAddress(), socket -> { borrowed.set(socket); return false; });
                    throw new AssertionError("unprotected socket accepted");
                } catch (java.io.IOException expected) {
                    check(loopback + " failed preparation closes socket", borrowed.get().isClosed());
                }
                try {
                    UdpProxySockets.openConnected((InetSocketAddress)server.getLocalAddress(), socket -> {
                        borrowed.set(socket); throw new java.io.IOException("mock bind error");
                    });
                    throw new AssertionError("bind exception ignored");
                } catch (java.io.IOException expected) {
                    check(loopback + " preparation exception closes socket", borrowed.get().isClosed());
                }
            }
        }
        System.out.println("SUMMARY checks=" + checks + " failures=0");
    }
}
