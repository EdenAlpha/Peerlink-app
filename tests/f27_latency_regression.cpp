// Exercise production packet workers on local sockets. A paused diagnostics
// consumer must never prevent forwarding; this is not a Wi-Fi benchmark.
#include "../app/src/main/jni/peerlink_backend.cpp"
#include <future>
#include <iostream>

static int failures = 0, checks = 0;
static void check(const char *name, bool ok) {
    ++checks;
    if (!ok) ++failures;
    std::cout << (ok ? "PASS " : "FAIL ") << name << '\n';
}

int main() {
    {
        auto s = std::make_unique<BackendState>();
        JavaVM vm; vm.enable_host_thread = true; s->jvm = &vm;
        int fds[2];
        if (socketpair(AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK, 0, fds) != 0) return 2;
        s->peer_fd = fds[0]; s->running = true;
        s->sender_id = 123;
        s->peer_tx_kernel_timestamp_enabled = true;
        s->udp_trace_capacity = 64;
        s->udp_trace_buffer = std::make_unique<UdpTraceEvent[]>(64);
        std::unique_lock<std::mutex> stalled_collector(s->timestamp_mutex);
        std::thread sender([&] { peer_tx_loop(s.get()); });
        for (uint8_t n = 0; n < 32; ++n) {
            PeerTxJob job{};
            job.payload = {n, 0x24, 0x46, 0x68}; job.seq = n;
            job.t0_ns = job.s1_ns = monotonic_ns();
            enqueue_peer_tx(s.get(), std::move(job));
        }
        pollfd pfd{fds[1], POLLIN, 0};
        const bool immediate = poll(&pfd, 1, 200) > 0;
        check("first packet arrives while TX diagnostics are stalled", immediate);
        // Allow an old implementation to finish and report failure, not hang.
        if (!immediate) stalled_collector.unlock();
        bool intact = true;
        for (uint8_t n = 0; n < 32; ++n) {
            uint8_t frame[128]{};
            const bool ready = poll(&pfd, 1, 1000) > 0;
            const ssize_t length = ready ? recv(fds[1], frame, sizeof(frame), 0) : -1;
            intact &= length == kTunnelDiagHeaderSize + 4 &&
                      frame[kTunnelDiagHeaderSize] == n && frame[kTunnelDiagHeaderSize + 3] == 0x68;
        }
        check("entire burst arrives in FIFO order with intact payloads", intact);
        if (stalled_collector.owns_lock()) stalled_collector.unlock();
        request_backend_stop(s.get()); sender.join();
        check("diagnostic contention loses no gameplay packets", s->stats.tunnel_out_packets == 32 && s->stats.dropped_packets == 0);
        check("omitted timestamps preserve socket ID accounting", s->next_peer_tx_timestamp_id == 32);
        close(fds[0]); close(fds[1]); s->peer_fd = -1;
    }
    {
        auto s = std::make_unique<BackendState>(); s->running = true;
        std::unique_lock<std::mutex> stalled_logger(s->file_log_mutex);
        auto producer = std::async(std::launch::async, [&] {
            emit_native_log(nullptr, s.get(), kNativeLogWarn, false, "local test warning");
        });
        const bool returned = producer.wait_for(std::chrono::milliseconds(200)) == std::future_status::ready;
        check("packet warning never waits for a paused logger", returned);
        stalled_logger.unlock(); producer.get();
        check("omitted diagnostic warning is counted", s->deferred_file_logs_dropped == 1);
        std::unique_lock<std::mutex> stalled_timestamps(s->timestamp_mutex);
        auto receiver = std::async(std::launch::async, [&] {
            update_tx_trace_slot(s.get(), 0, SCM_TSTAMP_SND, realtime_ns());
        });
        check("receive worker never waits for a timestamp producer",
              receiver.wait_for(std::chrono::milliseconds(200)) == std::future_status::ready);
        stalled_timestamps.unlock(); receiver.get();
    }
    std::cout << checks << " checks, " << failures << " failures\n";
    return failures ? 1 : 0;
}
