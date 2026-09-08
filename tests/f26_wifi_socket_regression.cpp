// Local loopback/UNIX sockets only. Exercises production IO and JNI rebind
// transactions; the Android bind/protect callback is explicitly mocked.
#include "../app/src/main/jni/peerlink_backend.cpp"
#include <iostream>
#include <future>

static int checks = 0, failures = 0;
static void check(const char *name, bool ok) {
    ++checks;
    if (!ok) ++failures;
    std::cout << (ok ? "PASS " : "FAIL ") << name << std::endl;
}
struct LocalUdp {
    int fd = -1;
    sockaddr_in address{};
    LocalUdp() {
        fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0)
            throw std::runtime_error("loopback socket unavailable");
        socklen_t size = sizeof(address);
        if (getsockname(fd, reinterpret_cast<sockaddr*>(&address), &size) != 0)
            throw std::runtime_error("getsockname failed");
        // Production explicitly binds its advertised port. Linux can release a
        // port assigned by bind(:0) on AF_UNSPEC disconnect; reserve a free port
        // above, then bind it explicitly so this models the production socket.
        close(fd);
        fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
        if (fd < 0 || bind(fd, reinterpret_cast<sockaddr*>(&address), sizeof(address)) != 0)
            throw std::runtime_error("explicit loopback bind failed");
    }
    ~LocalUdp() { if (fd >= 0) close(fd); }
};
static bool ready(int fd, short events = POLLIN) {
    pollfd pfd{fd, events, 0};
    return poll(&pfd, 1, 1000) > 0 && (pfd.revents & events) != 0;
}
static std::unique_ptr<BackendState> connected(LocalUdp &local, const LocalUdp &peer) {
    auto s = std::make_unique<BackendState>();
    s->peer_fd = local.fd;
    s->peer_addr = peer.address;
    s->configured_local_lan_ip = {127, 0, 0, 1};
    s->has_configured_local_lan_ip = true;
    s->running = true;
    s->callbacks = reinterpret_cast<jobject>(1);
    s->prepare_peer_socket_mid = reinterpret_cast<jmethodID>(1);
    if (!connect_udp_socket(s->peer_fd, s->peer_addr)) throw std::runtime_error("local connect failed");
    s->peer_socket_connected = true;
    return s;
}
int main() {
    {
        auto s = std::make_unique<BackendState>();
        JNIEnv env;
        const auto handle = static_cast<jlong>(reinterpret_cast<intptr_t>(s.get()));
        s->running = true;
        s->stats.tunnel_out_packets = 42;
        Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativePollStats(&env, nullptr, handle);
        check("native liveness follows existing statistics in JNI array", env.result_longs.size() == 12 && env.result_longs[0] == 42 && env.result_longs[11] == 1);
        request_backend_stop(s.get());
        Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativePollStats(&env, nullptr, handle);
        check("fatal cancellation is visible to the VPN service", env.result_longs[0] == 42 && env.result_longs[11] == 0);
        Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativePollStats(&env, nullptr, 0);
        check("absent backend cannot report a live connection", env.result_longs[11] == 0);
    }
    {
        LocalUdp peer;
        uint16_t reserved;
        { LocalUdp temporary; reserved = ntohs(temporary.address.sin_port); }
        auto s = std::make_unique<BackendState>();
        s->peer_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
        s->configured_local_lan_ip = {127,0,0,1};
        s->has_configured_local_lan_ip = true;
        check("bind own advertised port independently of peer destination", bind_peer_source(s.get(), reserved));
        s->peer_addr = peer.address;
        check("connect to a peer with a different advertised port", connect_udp_socket(s->peer_fd, s->peer_addr));
        sockaddr_in bound{}; socklen_t size = sizeof(bound);
        getsockname(s->peer_fd, reinterpret_cast<sockaddr*>(&bound), &size);
        check("connected UDP retains the local advertised port", ntohs(bound.sin_port) == reserved && bound.sin_port != peer.address.sin_port);
        check("send on asymmetric local/peer ports", send_message(s->peer_fd, reinterpret_cast<const uint8_t*>("ok"), 2));
        uint8_t data[8]{};
        check("asymmetric port datagram arrives intact", ready(peer.fd) && recv(peer.fd, data, sizeof(data), 0) == 2 && data[0] == 'o');
        close(s->peer_fd);
        s->peer_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
        s->configured_local_lan_ip = {192,0,2,123}; // not configured locally; bind only, no traffic
        check("vanished source cannot silently fall back to wildcard", !bind_peer_source(s.get(), reserved) && errno == EADDRNOTAVAIL);
        close(s->peer_fd); s->peer_fd = -1;
    }
    {
        LocalUdp local, peer;
        auto s = connected(local, peer);
        JNIEnv env;
        env.prepare_result = JNI_FALSE;
        const auto handle = static_cast<jlong>(reinterpret_cast<intptr_t>(s.get()));
        check("preparation failure is reported even with a configured source", Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(&env, nullptr, handle) == JNI_FALSE);
        check("failed rebind restores connected mode", s->peer_socket_connected.load());
        check("native closes borrowed callback descriptor on failure", fcntl(env.prepared_fd, F_GETFD) == -1 && errno == EBADF);
        check("callback failure leaves original socket usable", fcntl(local.fd, F_GETFD) >= 0 && send_peer_control_byte(s.get(), 0xFF));
        uint8_t byte = 0;
        check("peer can still receive after failed rebind", ready(peer.fd) && recv(peer.fd, &byte, 1, 0) == 1 && byte == 0xFF);
        env.prepare_result = JNI_TRUE;
        check("successful callback allows exact socket recovery", Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(&env, nullptr, handle) == JNI_TRUE);
        check("native closes borrowed callback descriptor on success", fcntl(env.prepared_fd, F_GETFD) == -1);
        sockaddr_in after{}; socklen_t size = sizeof(after);
        getsockname(local.fd, reinterpret_cast<sockaddr*>(&after), &size);
        check("rebind preserves source IPv4 and advertised port", after.sin_addr.s_addr == local.address.sin_addr.s_addr && after.sin_port == local.address.sin_port);
        env.prepare_throws = true;
        check("callback exception cannot be misreported as success", Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(&env, nullptr, handle) == JNI_FALSE && !env.ExceptionCheck());
        check("native closes callback descriptor after exception", fcntl(env.prepared_fd, F_GETFD) == -1);
        s->running = false;
        const int calls = env.prepare_calls;
        check("stopped backend never rebinds", Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(&env, nullptr, handle) == JNI_FALSE && env.prepare_calls == calls);
        check("stopped backend does not send keepalives", !send_peer_control_byte(s.get(), 0xFF));
    }
    {
        LocalUdp local;
        auto s = std::make_unique<BackendState>();
        s->peer_fd = local.fd;
        { LocalUdp closedPeer; s->peer_addr = closedPeer.address; }
        connect_udp_socket(local.fd, s->peer_addr);
        const uint8_t byte = 1;
        check("generate socket error on an owned closed loopback port", send_message(local.fd, &byte, 1) && ready(local.fd, POLLERR));
        drain_peer_tx_timestamps(s.get());
        pollfd pfd{local.fd, POLLIN, 0};
        check("empty error queue does not leave RX spinning on POLLERR", poll(&pfd, 1, 0) == 0);
    }
    {
        LocalUdp local, peer;
        auto s = connected(local, peer);
        const int flags = SOF_TIMESTAMPING_SOFTWARE | SOF_TIMESTAMPING_TX_SCHED |
                          SOF_TIMESTAMPING_TX_SOFTWARE | SOF_TIMESTAMPING_OPT_ID | SOF_TIMESTAMPING_OPT_TSONLY;
        const bool timestamps = setsockopt(local.fd, SOL_SOCKET, SO_TIMESTAMPING, &flags, sizeof(flags)) == 0;
        check("kernel supports payload-free TX diagnostic timestamps", timestamps);
        s->udp_trace_capacity = 4;
        s->udp_trace_buffer = std::make_unique<UdpTraceEvent[]>(4);
        const uint64_t now = realtime_ns();
        const uint64_t ordinal = record_udp_trace(s.get(), kUdpTraceStagePeerSend, monotonic_ns(), 4,
                kUdpTraceFlagTunnel, 1000, 2000, 8, 1, 123, 0xFFFFFFFFu, 123);
        PendingTxTimestamp pending{}; pending.ordinal = ordinal; pending.seq = 1; pending.user_send_rt_ns = now;
        s->pending_tx_timestamps[0] = pending;
        const uint8_t payload[] = {1,2,3,4,5,6,7,8};
        check("timestamp request still transmits intact UDP payload", send_message(local.fd, payload, sizeof(payload)) && ready(peer.fd));
        check("TX notifications wake the diagnostic reader", ready(local.fd, POLLERR));
        drain_peer_tx_timestamps(s.get());
        const auto &slot = s->udp_trace_buffer[0];
        check("zero-payload error-queue records preserve scheduling timestamp", slot.tx_user_to_sched_us != 0xFFFFFFFFu);
        check("zero-payload error-queue records preserve send timestamp", slot.tx_user_to_soft_us != 0xFFFFFFFFu);
        check("both TX stages release pending diagnostic entry", s->pending_tx_timestamps.empty());
        pollfd pfd{local.fd, POLLIN, 0};
        check("payload-free TX queue drains fully", poll(&pfd, 1, 0) == 0);
    }
    {
        LocalUdp local, peer;
        auto s = connected(local, peer);
        uint8_t packet[128]{};
        check("send local datagram larger than receive buffer", sendto(peer.fd, packet, sizeof(packet), 0, reinterpret_cast<sockaddr*>(&local.address), sizeof(local.address)) == sizeof(packet));
        ready(local.fd);
        uint8_t small[8]; sockaddr_in from{}; socklen_t size = sizeof(from);
        uint64_t mono = 0, rt = 0; uint32_t us = 0;
        check("truncated receive cannot become a partial forwarded packet", recv_peer_packet_with_timestamp(s.get(), small, sizeof(small), &from, &size, &mono, &us, &rt) == -1 && errno == EMSGSIZE);
        check("empty UDP datagram is valid input", sendto(peer.fd, packet, 0, 0, reinterpret_cast<sockaddr*>(&local.address), sizeof(local.address)) == 0 && ready(local.fd));
        check("zero-length receive remains distinct from read error", recv_peer_packet_with_timestamp(s.get(), small, sizeof(small), &from, &size, &mono, &us, &rt) == 0);
    }
    {
        int pair[2]; socketpair(AF_UNIX, SOCK_SEQPACKET, 0, pair);
        close(pair[1]);
        int error = 0; const uint8_t byte = 1;
        check("bridge peer closure reports error without SIGPIPE termination", !send_message(pair[0], &byte, 1, nullptr, SendPolicy::kImmediateDrop, &error) && error == EPIPE);
        close(pair[0]);
    }
    {
        // Use the production TX queue, framing and mutex while repeatedly
        // reconnecting its socket. Android callback is a successful no-op.
        LocalUdp local, peer;
        auto s = connected(local, peer);
        JavaVM vm; vm.enable_host_thread = true; s->jvm = &vm;
        s->sender_id = 7;
        JNIEnv env; env.prepare_result = JNI_TRUE;
        std::thread sender([&] { peer_tx_loop(s.get()); });
        std::atomic<bool> active{true};
        std::atomic<int> rebinds{0};
        std::thread rebind([&] {
            const auto handle = static_cast<jlong>(reinterpret_cast<intptr_t>(s.get()));
            for (int n = 0; n < 32 && active.load(); ++n) {
                if (Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(&env, nullptr, handle)) ++rebinds;
                std::this_thread::yield();
            }
        });
        bool intact = true;
        for (uint64_t n = 1; n <= 64; ++n) {
            PeerTxJob job; job.seq = n; job.ip_version = 4; job.payload.assign(1400, static_cast<uint8_t>(n));
            enqueue_peer_tx(s.get(), std::move(job));
            uint8_t data[1600]{};
            if (!ready(peer.fd)) { intact = false; break; }
            const auto length = recv(peer.fd, data, sizeof(data), 0);
            TunnelDiagMeta meta{};
            intact &= length == 1456 && parse_tunnel_diag_meta(data, length > 0 ? length : 0, meta) && meta.seq == n;
            intact &= std::all_of(data + kTunnelDiagHeaderSize, data + 1456, [n](uint8_t x) { return x == static_cast<uint8_t>(n); });
        }
        active = false; rebind.join();
        request_backend_stop(s.get()); sender.join();
        check("local rebind transaction exercised alongside gameplay TX worker", rebinds > 0);
        check("64 MTU-sized frames remain intact and ordered across rebinds", intact && s->stats.tunnel_out_packets == 64 && s->stats.dropped_peer_tx == 0);
        check("1400-byte TUN MTU plus tunnel/IPv4/UDP headers fits 1500", 1400 + kTunnelDiagHeaderSize + 20 + 8 <= 1500);
    }
    std::cout << "SUMMARY checks=" << checks << " failures=" << failures << std::endl;
    return failures ? 1 : 0;
}
