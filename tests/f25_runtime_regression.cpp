// Local-only lifecycle/diagnostic regressions against the production backend.
// No game client, external target, or live network is involved.
#include "../app/src/main/jni/peerlink_backend.cpp"
#include <future>
#include <iostream>

static int checks = 0, failures = 0;
static void check(const char *name, bool ok) {
    ++checks;
    if (!ok) ++failures;
    std::cout << (ok ? "PASS " : "FAIL ") << name << '\n';
}

int main() {
    {
        auto s = std::make_unique<BackendState>();
        s->running = true;
        s->stop_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
        int sockets[3][2];
        std::vector<std::future<int>> readers;
        std::atomic<int> waiting{0};
        for (int n = 0; n < 3; ++n) {
            check("create idle input socket", socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets[n]) == 0);
            readers.emplace_back(std::async(std::launch::async, [&, n] {
                waiting.fetch_add(1);
                short events = 0;
                return wait_for_backend_read(s.get(), sockets[n][0], events);
            }));
        }
        while (waiting.load() != 3) std::this_thread::yield();
        request_backend_stop(s.get());
        bool all_woken = true;
        for (int n = 0; n < 3; ++n) {
            const bool ready = readers[n].wait_for(std::chrono::seconds(1)) == std::future_status::ready;
            all_woken &= ready;
            if (!ready) (void)send(sockets[n][1], "x", 1, 0);
            all_woken &= readers[n].get() == 0;
            check("stop signal leaves input descriptor valid until workers finish", fcntl(sockets[n][0], F_GETFD) >= 0);
            close(sockets[n][0]); close(sockets[n][1]);
        }
        check("one cancellation wakes every idle input poller", all_woken);
    }
    {
        auto s = std::make_unique<BackendState>();
        s->running = true;
        auto peer = std::async(std::launch::async, [&] {
            PeerTxJob job;
            return wait_for_queue_pop(s->running, s->peer_tx_mutex, s->peer_tx_cv, s->peer_tx_queue, job);
        });
        auto bridge = std::async(std::launch::async, [&] {
            ByteBufferJob job;
            return wait_for_queue_pop(s->running, s->bridge_tx_mutex, s->bridge_tx_cv, s->bridge_tx_queue, job);
        });
        auto inject = std::async(std::launch::async, [&] {
            TunInjectJob job;
            return pop_tun_inject_job(s.get(), job, true);
        });
        request_backend_stop(s.get());
        check("idle peer transmitter wakes on stop", peer.wait_for(std::chrono::seconds(1)) == std::future_status::ready && !peer.get());
        check("idle bridge transmitter wakes on stop", bridge.wait_for(std::chrono::seconds(1)) == std::future_status::ready && !bridge.get());
        check("idle TUN injector wakes on stop", inject.wait_for(std::chrono::seconds(1)) == std::future_status::ready && !inject.get());
    }
    {
        auto s = std::make_unique<BackendState>();
        int sockets[2];
        check("create local transmit pair", socketpair(AF_UNIX, SOCK_DGRAM, 0, sockets) == 0);
        set_nonblocking(sockets[1]);
        s->peer_fd = sockets[1];
        s->running = true;
        s->peer_tx_kernel_timestamp_enabled = true;
        s->sender_id = 123;
        // Deliberately no trace ring: the ID must still advance after a send.
        JavaVM vm; vm.enable_host_thread = true; s->jvm = &vm;
        PeerTxJob job{}; job.payload = {0x12, 0x34}; job.seq = 1;
        enqueue_peer_tx(s.get(), std::move(job));
        std::thread sender([&] { peer_tx_loop(s.get()); });
        pollfd ready{sockets[0], POLLIN, 0};
        const int received = poll(&ready, 1, 1000);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(1);
        while (s->stats.tunnel_out_packets.load() == 0 && std::chrono::steady_clock::now() < deadline) std::this_thread::yield();
        request_backend_stop(s.get());
        sender.join();
        check("packet is transmitted when its diagnostic event is omitted", received > 0 && s->stats.tunnel_out_packets == 1);
        check("omitted diagnostic cannot shift kernel TX timestamp IDs", s->next_peer_tx_timestamp_id == 1);
        close(sockets[0]); close(sockets[1]); s->peer_fd = -1;
    }
    {
        auto s = std::make_unique<BackendState>();
        s->sender_id = 123;
        s->udp_trace_capacity = 64; // Force many wraps while exporting.
        s->udp_trace_buffer = std::make_unique<UdpTraceEvent[]>(64);
        std::atomic<int> finished{0};
        std::vector<std::thread> producers;
        for (int n = 0; n < 4; ++n) producers.emplace_back([&, n] {
            for (uint64_t i = 1; i <= 20000; ++i) {
                record_udp_trace(s.get(), kUdpTraceStagePeerSend, i * 1000, 4,
                    kUdpTraceFlagTunnel | kUdpTraceFlagStableKnown, 1000 + n, 2000,
                    32, i, 0x1234, 0xFFFFFFFFu, 123 + n);
            }
            finished.fetch_add(1);
        });
        int exports = 0;
        while (finished.load() != 4) { (void)dump_udp_trace(s.get()); ++exports; }
        for (auto &producer : producers) producer.join();
        check("concurrent trace producers complete 80000 write attempts across ring wrap", s->udp_trace_write_count == 80000);
        check("live exports finish while trace writers are active", exports > 0);
        check("trace slot guards are released after concurrent activity", [&] {
            bool ok = true;
            for (size_t i = 0; i < 64; ++i) {
                auto &slot = s->udp_trace_buffer[i];
                ok &= !slot.busy.test_and_set(); slot.busy.clear();
                ok &= slot.length == 32 && slot.dest_port == 2000;
                ok &= slot.source_port >= 1000 && slot.source_port <= 1003;
            }
            return ok;
        }());
    }
    std::cout << "SUMMARY checks=" << checks << " failures=" << failures << '\n';
    return failures ? 1 : 0;
}
