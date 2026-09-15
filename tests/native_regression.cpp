// Host regression harness. It includes the production C++ backend directly;
// only Android/JNI adapters are shimmed by tests/shim.
#include "../app/src/main/jni/peerlink_backend.cpp"
#include <chrono>
#include <future>
#include <iostream>
#include <thread>
#include <random>

static int checks = 0;
static int failures = 0;
static void check(const char *name, bool ok) {
    ++checks;
    if (!ok) ++failures;
    std::cout << (ok ? "PASS " : "FAIL ") << name << '\n';
}

static std::unique_ptr<BackendState> fresh() {
    auto state = std::make_unique<BackendState>();
    state->running = true;
    state->peer_fabricated_ip = {203, 0, 113, 2};
    state->my_fabricated_ip = {203, 0, 113, 1};
    state->peer_lan_ip = {192, 168, 43, 2};
    state->vpn_ipv4 = {10, 0, 0, 2};
    state->vpn_ipv6 = {0x20,0x01,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,2};
    return state;
}

static std::vector<uint8_t> outgoing(BackendState *s, uint16_t port, uint16_t remote,
                                     std::array<uint8_t,4> local = {10,0,0,2}) {
    const uint8_t payload[] = {0,0,0,1,0,0,0,2,0x45,0x67};
    return build_udp_ipv4_packet(local, s->peer_fabricated_ip, port, remote, payload, sizeof(payload));
}

static std::vector<uint8_t> incoming(BackendState *s, uint16_t port, uint16_t remote) {
    const uint8_t payload[] = {0,0,0,1,0,0,0,2,0x45,0x67};
    return build_udp_ipv4_packet(s->my_fabricated_ip, s->my_fabricated_ip,
                                 remote, port, payload, sizeof(payload));
}

static std::vector<uint8_t> stun(BackendState *s, uint16_t port, uint16_t type = 1) {
    uint8_t payload[20]{};
    write_u16(payload, 0, type); write_u32(payload, 4, kStunMagicCookie);
    const std::array<uint8_t,4> server{18,176,255,15};
    return build_udp_ipv4_packet(s->vpn_ipv4, server, port, 3478, payload, sizeof(payload));
}

static ParsedIpv4 last_injected(BackendState *s) {
    ParsedIpv4 packet{};
    if (!s->tun_inject_queue.empty()) {
        const auto &bytes = s->tun_inject_queue.back().bytes;
        (void)parse_ipv4(bytes.data(), bytes.size(), packet);
    }
    return packet;
}

static std::array<uint8_t,16> v6(unsigned last) {
    return {0x20,0x01,0x0d,0xb8,0,0,0,0,0,0,0,0,0,0,0,static_cast<uint8_t>(last)};
}

int main() {
    {
        auto s = fresh();
        auto tx = outgoing(s.get(), 56008, 20769);
        handle_tun_ipv4(nullptr, s.get(), tx.data(), tx.size(), monotonic_ns());
        auto discovery = stun(s.get(), 60000);
        handle_tun_ipv4(nullptr, s.get(), discovery.data(), discovery.size(), monotonic_ns());
        auto rx = incoming(s.get(), 56008, 20769);
        inject_inner_ipv4_to_tun(nullptr, s.get(), rx.data(), rx.size(), kTunnelFlagFabricatedFlow, nullptr);
        const auto p = last_injected(s.get());
        check("STUN socket cannot steal active gameplay port", p.valid && p.dest_port == 56008);
        check("unsupported STUN is preserved for normal networking", s->bridge_tx_queue.size() == 1);
        check("peer source identity remains fabricated peer", p.source_ip == s->peer_fabricated_ip);
    }
    {
        auto s = fresh();
        const std::array<uint8_t,4> ip1{10,10,0,7}, ip2{10,20,0,8};
        auto a = outgoing(s.get(), 56008, 20769, ip1);
        auto b = outgoing(s.get(), 56009, 20770, ip2);
        handle_tun_ipv4(nullptr, s.get(), a.data(), a.size(), monotonic_ns());
        handle_tun_ipv4(nullptr, s.get(), b.data(), b.size(), monotonic_ns());
        auto ra = incoming(s.get(), 56008, 20769);
        auto rb = incoming(s.get(), 56009, 20770);
        inject_inner_ipv4_to_tun(nullptr, s.get(), ra.data(), ra.size(), kTunnelFlagFabricatedFlow, nullptr);
        auto pa = last_injected(s.get());
        inject_inner_ipv4_to_tun(nullptr, s.get(), rb.data(), rb.size(), kTunnelFlagFabricatedFlow, nullptr);
        auto pb = last_injected(s.get());
        check("simultaneous socket A keeps port and address", pa.dest_port == 56008 && pa.dest_ip == ip1);
        check("simultaneous socket B keeps port and address", pb.dest_port == 56009 && pb.dest_ip == ip2);
    }
    {
        auto s = fresh();
        const std::array<uint8_t,4> old_ip{10,10,0,7}, new_ip{10,20,0,8};
        auto old_tx = outgoing(s.get(), 56008, 20769, old_ip);
        auto new_tx = outgoing(s.get(), 56008, 20769, new_ip);
        handle_tun_ipv4(nullptr, s.get(), old_tx.data(), old_tx.size(), monotonic_ns());
        handle_tun_ipv4(nullptr, s.get(), new_tx.data(), new_tx.size(), monotonic_ns());
        auto rx = incoming(s.get(), 56008, 20769);
        inject_inner_ipv4_to_tun(nullptr, s.get(), rx.data(), rx.size(), 0, nullptr);
        check("same socket follows a verified local-address change", last_injected(s.get()).dest_ip == new_ip);
    }
    {
        auto s = fresh(); unsigned wrong = 0;
        for (unsigned n = 0; n < 1000; ++n) {
            const uint16_t active = static_cast<uint16_t>(20000 + n);
            const uint16_t discovery = static_cast<uint16_t>(40000 + n);
            auto tx = outgoing(s.get(), active, static_cast<uint16_t>(30000 + n));
            handle_tun_ipv4(nullptr, s.get(), tx.data(), tx.size(), monotonic_ns());
            auto request = stun(s.get(), discovery);
            handle_tun_ipv4(nullptr, s.get(), request.data(), request.size(), monotonic_ns());
            auto rx = incoming(s.get(), active, static_cast<uint16_t>(30000 + n));
            inject_inner_ipv4_to_tun(nullptr, s.get(), rx.data(), rx.size(), 0, nullptr);
            wrong += last_injected(s.get()).dest_port != active;
            s->tun_inject_queue.clear(); s->bridge_tx_queue.clear(); s->peer_tx_queue.clear();
        }
        check("1000 mixed port transitions have zero redirects", wrong == 0);
        check("endpoint table remains bounded", s->gameplay_endpoints.size() <= kMaxGameplayEndpoints);
    }
    {
        auto s = fresh();
        auto custom = stun(s.get(), 60001, 0x080a);
        handle_tun_ipv4(nullptr, s.get(), custom.data(), custom.size(), monotonic_ns());
        check("non-Binding STUN method is not swallowed", s->bridge_tx_queue.size() == 1 && s->stats.stun_intercepted_ipv4 == 0);

        ParsedIpv4 p{};
        auto bad_ip = outgoing(s.get(), 56008, 20769); write_u16(bad_ip.data(), 2, 60000);
        check("truncated IPv4 length is rejected", !parse_ipv4(bad_ip.data(), bad_ip.size(), p));
        auto bad_udp = outgoing(s.get(), 56008, 20769); write_u16(bad_udp.data(), 24, 60000);
        check("impossible UDP length is rejected", !parse_ipv4(bad_udp.data(), bad_udp.size(), p));
        auto fragment = outgoing(s.get(), 56008, 20769); write_u16(fragment.data(), 6, 1);
        check("fragment body is never parsed as UDP ports", parse_ipv4(fragment.data(), fragment.size(), p) && p.fragmented && p.source_port == 0);
    }
    {
        auto s = fresh();
        int sockets[2]{}; check("create UDP socketpair", socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) == 0);
        s->peer_fd = sockets[1]; s->peer_socket_connected = true; set_nonblocking(s->peer_fd);
        s->peer_tx_kernel_timestamp_enabled = true;
        const uint32_t before_id = s->next_peer_tx_timestamp_id;
        check("control datagrams preserve TX timestamp ID alignment",
              before_id == 0u && send_peer_control_byte(s.get(), 0xFCu) &&
              s->next_peer_tx_timestamp_id == before_id + 1u);
        uint8_t discard = 0;
        (void) recv(sockets[0], &discard, 1, 0);
        JavaVM vm; vm.enable_host_thread = true; s->jvm = &vm;
        std::thread receiver([&] { peer_rx_loop(s.get()); });
        (void)send(sockets[0], "", 0, 0);
        const uint8_t keepalive = 0xFF; (void)send(sockets[0], &keepalive, 1, 0);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(2);
        while (s->running && s->stats.keepalive_rx == 0 && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        check("empty UDP datagram does not stop backend", s->running && s->stats.keepalive_rx == 1);
        s->running = false; (void)send(sockets[0], &keepalive, 1, 0); receiver.join();
        close(sockets[0]); close(sockets[1]); s->peer_fd = -1;
    }
    {
        auto s = fresh();
        s->udp_trace_capacity = 8;
        s->udp_trace_buffer = std::make_unique<UdpTraceEvent[]>(s->udp_trace_capacity);
        const uint64_t user_rt_ns = 5'000'000'000ULL;
        const uint64_t ordinal = record_udp_trace(
                s.get(), kUdpTraceStagePeerSend, 1'000'000'000ULL, 4,
                kUdpTraceFlagTunnel, 56008, 20769, 64, 7, 0x12345678u,
                0xFFFFFFFFu, 222);
        PendingTxTimestamp pending{};
        pending.ordinal = ordinal;
        pending.seq = 7;
        pending.user_send_rt_ns = user_rt_ns;
        s->pending_tx_timestamps[0] = pending;
        // Exercise the legal reverse notification order: software first,
        // scheduler second.
        update_tx_trace_slot(s.get(), 0, SCM_TSTAMP_SND, user_rt_ns + 30'000ULL);
        update_tx_trace_slot(s.get(), 0, SCM_TSTAMP_SCHED, user_rt_ns + 10'000ULL);
        const auto &slot = s->udp_trace_buffer[(ordinal - 1) % s->udp_trace_capacity];
        check("out-of-order kernel TX stages join the correct packet",
              slot.tx_user_to_sched_us == 10u && slot.tx_user_to_soft_us == 30u &&
              slot.tx_sched_to_soft_us == 20u && s->pending_tx_timestamps.empty());
    }
    {
        auto s = fresh();
        JNIEnv env;
        auto response = incoming(s.get(), 60000, 3478);
        env.response_bytes.assign(response.begin(), response.end());
        s->callbacks = reinterpret_cast<void*>(1); s->fabricate_stun_mid = reinterpret_cast<void*>(1);
        auto active = outgoing(s.get(), 56008, 20769, {10,10,0,7});
        handle_tun_ipv4(&env, s.get(), active.data(), active.size(), monotonic_ns());
        auto discovery = stun(s.get(), 60000);
        handle_tun_ipv4(&env, s.get(), discovery.data(), discovery.size(), monotonic_ns());
        auto rx = incoming(s.get(), 56008, 20769);
        inject_inner_ipv4_to_tun(&env, s.get(), rx.data(), rx.size(), 0, nullptr);
        check("successful STUN callback cannot steal active flow", last_injected(s.get()).dest_port == 56008 && last_injected(s.get()).dest_ip == std::array<uint8_t,4>{10,10,0,7});
        check("successful STUN records only its own local socket", s->gameplay_endpoints.count(gameplay_endpoint_key(4,60000,0)) == 1);

        auto first_peer = incoming(s.get(), 60000, 22000);
        inject_inner_ipv4_to_tun(&env, s.get(), first_peer.data(), first_peer.size(), 0, nullptr);
        check("first inbound after STUN uses that socket's address", last_injected(s.get()).dest_port == 60000 && last_injected(s.get()).dest_ip == s->vpn_ipv4);
    }
    {
        auto s = fresh();
        const std::array<uint8_t,4> physical{10,10,0,7}, replacement{10,20,0,8};
        remember_gameplay_endpoint(s.get(), physical, 56008, s->peer_fabricated_ip, 20769, 100);
        auto local = s->vpn_ipv4; auto peer = s->peer_fabricated_ip;
        resolve_gameplay_addresses(s.get(), 56009, 20769, 101, local, peer);
        check("unknown port cannot borrow another socket address", local == s->vpn_ipv4);
        resolve_gameplay_addresses(s.get(), 56008, 20769, 100 + kGameplayEndpointTtlMs, local, peer);
        check("expired endpoint is discarded without redirect", local == s->vpn_ipv4 && s->gameplay_endpoints.empty());
        remember_gameplay_endpoint(s.get(), physical, 56008, s->peer_fabricated_ip, 20769, 1000);
        remember_gameplay_endpoint(s.get(), physical, 56009, s->peer_fabricated_ip, 20770, 1000);
        remember_gameplay_endpoint(s.get(), replacement, 56008, s->peer_fabricated_ip, 0, 1001);
        local = s->vpn_ipv4;
        resolve_gameplay_addresses(s.get(), 56008, 20769, 1002, local, peer);
        check("new STUN address retires that socket's old exact flow", local == replacement);
        local = s->vpn_ipv4;
        resolve_gameplay_addresses(s.get(), 56009, 20770, 1002, local, peer);
        check("address renewal leaves other sockets intact", local == physical);
        auto restarted = fresh(); local = restarted->vpn_ipv4;
        resolve_gameplay_addresses(restarted.get(), 56008, 20769, 1003, local, peer);
        check("restarted backend does not inherit previous mappings", local == restarted->vpn_ipv4);
        for (unsigned n = 1; n < 3000; ++n) {
            remember_gameplay_endpoint(s.get(), physical, static_cast<uint16_t>(n), s->peer_fabricated_ip, 30000, 2000 + n);
        }
        check("table cap enforced after 2999 distinct endpoints", s->gameplay_endpoints.size() == kMaxGameplayEndpoints);
    }
    {
        auto s = fresh();
        std::mt19937 random(0x504c);
        bool sizes_ok = true;
        for (unsigned n = 0; n < 50000; ++n) {
            std::vector<uint8_t> bytes(random() % 256);
            for (auto &byte : bytes) byte = static_cast<uint8_t>(random());
            ParsedIpv4 four{}; ParsedIpv6 six{};
            if (parse_ipv4(bytes.data(), bytes.size(), four)) {
                sizes_ok &= four.packet_length <= bytes.size();
                if (!four.fragmented && four.protocol == 17) sizes_ok &= four.udp_payload_offset + four.udp_payload_length <= four.packet_length;
            }
            if (parse_ipv6(bytes.data(), bytes.size(), six)) {
                sizes_ok &= six.packet_length <= bytes.size();
                if (six.next_header == 17) sizes_ok &= six.udp_payload_offset + six.udp_payload_length <= six.packet_length;
            }
        }
        check("50000 malformed-packet fuzz cases stay within packet bounds", sizes_ok);
        check("lowest and highest UDP destination ports are preserved",
              resolve_inbound_gameplay_port(s.get(), 1, 0, 0) == 1 && resolve_inbound_gameplay_port(s.get(), 65535, 0, 0) == 65535);
    }
    {
        auto s = fresh();
        const uint8_t payload[] = {1,2,3,4,5};
        const auto peer = ipv4_mapped_address(s->peer_fabricated_ip);
        auto out1 = build_udp_ipv6_packet(v6(7), peer, 57001, 22001, payload, sizeof(payload));
        auto out2 = build_udp_ipv6_packet(v6(8), peer, 57002, 22002, payload, sizeof(payload));
        handle_tun_ipv6(nullptr, s.get(), out1.data(), out1.size(), monotonic_ns());
        handle_tun_ipv6(nullptr, s.get(), out2.data(), out2.size(), monotonic_ns());
        auto in1 = build_udp_ipv6_packet(peer, ipv4_mapped_address(s->my_fabricated_ip), 22001, 57001, payload, sizeof(payload));
        auto in2 = build_udp_ipv6_packet(peer, ipv4_mapped_address(s->my_fabricated_ip), 22002, 57002, payload, sizeof(payload));
        inject_inner_ipv6_to_tun(nullptr, s.get(), in1.data(), in1.size(), 0, nullptr);
        ParsedIpv6 p1{}; auto q1=s->tun_inject_queue.back().bytes; (void)parse_ipv6(q1.data(),q1.size(),p1);
        inject_inner_ipv6_to_tun(nullptr, s.get(), in2.data(), in2.size(), 0, nullptr);
        ParsedIpv6 p2{}; auto q2=s->tun_inject_queue.back().bytes; (void)parse_ipv6(q2.data(),q2.size(),p2);
        check("IPv6 endpoint A remains independent", p1.dest_port == 57001 && p1.dest_ip == v6(7));
        check("IPv6 endpoint B remains independent", p2.dest_port == 57002 && p2.dest_ip == v6(8));
    }
    {
        auto s = fresh();
        std::vector<uint8_t> control{0x11};
        std::vector<uint8_t> game{0x22};
        check("enqueue control TUN job", enqueue_tun_inject(s.get(), std::move(control), TunInjectSource::kBridge, false));
        check("enqueue gameplay TUN job", enqueue_tun_inject(s.get(), std::move(game), TunInjectSource::kPeer, true));
        TunInjectJob first{}, second{};
        const bool first_ok = pop_tun_inject_job(s.get(), first, false);
        const bool second_ok = pop_tun_inject_job(s.get(), second, false);
        check("gameplay injection bypasses older control traffic",
              first_ok && second_ok && first.source == TunInjectSource::kPeer &&
              second.source == TunInjectSource::kBridge);
    }
    {
        auto s = fresh();
        std::unique_lock<std::mutex> hold_control(s->control_tun_inject_mutex);
        std::promise<bool> result;
        auto ready = result.get_future();
        std::thread gameplay_producer([&] {
            std::vector<uint8_t> game{0x33};
            result.set_value(enqueue_tun_inject(
                    s.get(), std::move(game), TunInjectSource::kPeer, true));
        });
        const bool independent = ready.wait_for(std::chrono::milliseconds(250)) ==
                                 std::future_status::ready;
        check("control producer lock cannot block gameplay enqueue", independent);
        hold_control.unlock();
        gameplay_producer.join();

        emit_native_log(nullptr, s.get(), kNativeLogWarn, false, "defer-me");
        check("runtime warnings are deferred off packet threads",
              s->deferred_file_logs.size() == 1 &&
              !s->deferred_file_logs.front().file_only);
    }
    {
        auto s = fresh();
        // F32 small-packet signal: the only per-packet end-of-match marker.
        // Real captures showed sub-55B game payloads ONLY in the final FT tail.
        std::array<uint8_t, 2048> payload{};
        for (size_t i = 0; i < payload.size(); ++i) payload[i] = static_cast<uint8_t>(i & 0xFFu);
        record_match_packet_signal(s.get(), 54, 100);
        record_match_packet_signal(s.get(), 54, 150);
        record_match_packet_signal(s.get(), 91, 200);   // normal gameplay size: ignored
        record_match_packet_signal(s.get(), 55, 250);   // boundary: 55 is NOT small
        record_match_packet_signal(s.get(), 39, 300);   // small but non-tail class: still counted
        check("small-packet counter counts sub-55B game payloads only",
              s->stats.small_game_packets == 3);
        check("small-packet last-seen tracks the most recent sub-55B payload",
              s->stats.small_game_last_ms == 300);
    }
    {
        auto s = fresh();
        PeerTxJob job{};
        bool filled = true;
        for (size_t i = 0; i < kMaxPeerTxQueue; ++i) {
            filled = enqueue_peer_tx(s.get(), job) && filled;
        }
        check("peer queue fills to its exact bound", filled);
        const long long before = s->stats.dropped_packets;
        check("peer queue overflow rejected", !enqueue_peer_tx(s.get(), job));
        check("queue overflow counted exactly once",
              s->stats.dropped_packets == before + 1 && s->stats.dropped_peer_tx == 1);
    }
    {
        auto s = fresh();
        s->sender_id = 222;
        s->udp_trace_capacity = 128;
        s->udp_trace_buffer = std::make_unique<UdpTraceEvent[]>(s->udp_trace_capacity);
        const uint64_t base_ns = 1'000'000'000ULL;
        for (uint64_t seq = 1; seq <= 20; ++seq) {
            const uint64_t rx_ns = base_ns + seq * 1'000'000ULL;
            const uint16_t flags = kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer |
                                   kUdpTraceFlagStableKnown;
            record_udp_trace(s.get(), kUdpTraceStagePeerRecv, rx_ns, 4, flags,
                             20769, 56008, 64, seq, 0x12345678u,
                             100, 111, 0, 0, 0, 0);
            record_udp_trace(s.get(), kUdpTraceStageTunInject, rx_ns + 10'000ULL,
                             4, flags | kUdpTraceFlagInject, 20769, 56008, 64,
                             seq, 0x12345678u, 0xFFFFFFFFu, 111);
            record_udp_trace(s.get(), kUdpTraceStageTunWrite, rx_ns + 30'000ULL,
                             4, flags | kUdpTraceFlagInject, 20769, 56008, 64,
                             seq, 0x12345678u, 0xFFFFFFFFu, 111);
        }
        const std::string trace = dump_udp_trace(s.get());
        check("truth trace preserves enqueue and actual TUN-write stages",
              trace.find("wifi_truth_trace version=2") != std::string::npos &&
              trace.find("rx_enqueue_to_write_us") != std::string::npos &&
              trace.find(",rx_written\n") != std::string::npos);
        const uint64_t next = s->udp_trace_write_count.load();
        auto &busy_slot = s->udp_trace_buffer[next % s->udp_trace_capacity];
        busy_slot.busy.test_and_set();
        const uint64_t skipped = record_udp_trace(
                s.get(), kUdpTraceStagePeerRecv, base_ns, 4,
                kUdpTraceFlagTunnel, 20769, 56008, 64, 21, 0x12345678u);
        busy_slot.busy.clear();
        check("busy diagnostic slot is skipped without blocking packet work",
              skipped == 0 && s->udp_trace_skipped.load() == 1);
        std::unique_lock<std::mutex> timestamp_lock(s->timestamp_mutex);
        auto export_result = std::async(std::launch::async, [&] { return dump_udp_trace(s.get()); });
        const bool export_ready = export_result.wait_for(std::chrono::seconds(1)) == std::future_status::ready;
        timestamp_lock.unlock();
        check("live export never acquires the packet sender timestamp mutex",
              export_ready && !export_result.get().empty());

        std::vector<uint8_t> packet{0x44};
        check("TUN queue preserves remote sender identity for trace joining",
              enqueue_tun_inject(s.get(), std::move(packet), TunInjectSource::kPeer,
                                 true, 0x12345678u, 21, 111));
        TunInjectJob queued{};
        check("queued TUN job retains trace sender identity",
              pop_tun_inject_job(s.get(), queued, false) && queued.sender_id == 111);
    }

    std::cout << "SUMMARY checks=" << checks << " failures=" << failures << '\n';
    return failures == 0 ? 0 : 1;
}
