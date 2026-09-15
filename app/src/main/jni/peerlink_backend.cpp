#include "peerlink_backend.h"

#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <time.h>
#include <linux/errqueue.h>
#include <sys/eventfd.h>
#include <linux/net_tstamp.h>
#include <sys/poll.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/uio.h>
#include <sys/un.h>
#include <unistd.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <climits>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace {

constexpr const char *kLogTag = "PeerLinkNative";
constexpr size_t kMaxPacketSize = 65535;
constexpr uint8_t kProtocolUdp = 17;
constexpr uint8_t kProtocolTcp = 6;
constexpr uint32_t kStunMagicCookie = 0x2112A442u;
constexpr uint16_t kStunPortStart = 3478;
constexpr uint16_t kStunPortEnd = 3481;
// Keepalives only preserve an otherwise-idle peer path. Gameplay sends update
// last_keepalive_sent_ns, so there is no reason to wake the socket 10-20 times
// per second while real traffic is already flowing.
constexpr uint64_t kKeepaliveIntervalNs = 1000000000ULL;
constexpr uint64_t kKeepaliveFastNs = 500000000ULL;
constexpr uint64_t kKeepaliveWarmNs = 750000000ULL;
constexpr uint64_t kGameplayHintWindowMs = 2000ULL;
constexpr int kGameplayHintRateThresholdPps = 22;
constexpr uint64_t kGameplayEndpointTtlMs = 120000ULL;
constexpr size_t kMaxGameplayEndpoints = 1024;
constexpr uint32_t kTunnelDiagMagic = 0x504C4447u;
constexpr uint8_t kTunnelDiagVersion = 2;
constexpr uint16_t kTunnelDiagHeaderSize = 56;
constexpr int kTunnelFlagFabricatedFlow = 1 << 0;
constexpr int kTunnelFlagForcedUdp = 1 << 1;
constexpr int kNativeLogInfo = 1;
constexpr int kNativeLogWarn = 2;
constexpr int kNativeLogError = 3;
constexpr uint64_t kGapLogSevereMs = 150ULL;  // Diagnostic: warn + UI for these large gaps
constexpr uint64_t kGapLogNoticeMs = 60ULL;   // Always file-log gaps >= this (the felt micro-stutter band)
constexpr uint64_t kGapLogSampleMs = 1000ULL;  // Below notice: file-log at most once per tap per this interval
constexpr uint64_t kSummaryLogIntervalMs = 5000ULL;
constexpr size_t kFlowLogBitCount = 4096;
constexpr size_t kFlowLogWordCount = kFlowLogBitCount / 64;
// F23: the legacy six-stage timing trace duplicated packet-path work and
// allocated a large ring. Full-fidelity PCAPNG capture supersedes it.
// F24: compact timing/metadata trace only. No packet payload bytes are retained.
// 32768 six-stage events is enough for the end-of-match analysis window while
// keeping the ring much smaller than the old 131072-event diagnostic build.
constexpr size_t kUdpTraceCapacity = 32768;
// Keep verbose per-flow/action text diagnostics disabled; the CSV timing ring
// is the only packet-level diagnostic enabled in this build.
constexpr bool kPacketDiagnosticsEnabled = false;

// Full raw-IP capture. The VpnService MTU is 1400, so 2048 bytes safely holds
// every packet produced by the TUN while keeping the two SPSC rings small
// (~4 MiB total). If an OEM violates the MTU, forwarding still wins and an
// explicit oversized-drop counter exposes that the capture is incomplete.
constexpr size_t kRawCaptureMaxPacket = 2048;
constexpr size_t kRawCaptureRingCapacity = 1024;
constexpr size_t kRawCaptureStdioBuffer = 1024 * 1024;
constexpr uint8_t kUdpTraceStageTunRead = 1;
constexpr uint8_t kUdpTraceStageClassifiedTunnel = 2;
constexpr uint8_t kUdpTraceStagePeerSend = 3;
constexpr uint8_t kUdpTraceStagePeerRecv = 4;
constexpr uint8_t kUdpTraceStageTunInject = 5;
constexpr uint8_t kUdpTraceStageTunWrite = 6;
constexpr uint16_t kUdpTraceFlagStun = 1u << 0;
constexpr uint16_t kUdpTraceFlagPeerFabricated = 1u << 1;
constexpr uint16_t kUdpTraceFlagTunnel = 1u << 2;
constexpr uint16_t kUdpTraceFlagFromPeer = 1u << 3;
constexpr uint16_t kUdpTraceFlagInject = 1u << 4;
constexpr uint16_t kUdpTraceFlagStableKnown = 1u << 5;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kLogTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kLogTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kLogTag, __VA_ARGS__)

struct ParsedIpv4 {
    bool valid = false;
    size_t packet_length = 0;
    uint16_t header_length = 0;
    uint16_t total_length = 0;
    uint8_t protocol = 0;
    uint16_t source_port = 0;
    uint16_t dest_port = 0;
    size_t udp_payload_offset = 0;
    size_t udp_payload_length = 0;
    bool is_stun = false;
    bool is_stun_binding_request = false;
    bool fragmented = false;
    std::array<uint8_t, 4> source_ip{};
    std::array<uint8_t, 4> dest_ip{};
};

struct ParsedIpv6 {
    bool valid = false;
    size_t packet_length = 0;
    uint8_t next_header = 0;
    uint16_t source_port = 0;
    uint16_t dest_port = 0;
    size_t udp_payload_offset = 0;
    size_t udp_payload_length = 0;
    bool is_stun = false;
    bool is_stun_binding_request = false;
    std::array<uint8_t, 16> source_ip{};
    std::array<uint8_t, 16> dest_ip{};
};

struct TunnelDiagMeta {
    bool valid = false;
    int sender_id = 0;
    uint32_t flow_hash = 0;
    uint64_t seq = 0;
    uint64_t t0_ns = 0;
    uint64_t s1_ns = 0;
    uint64_t send_attempt_ns = 0;
    uint32_t payload_length = 0;
    uint16_t header_size = 0;
    int flags = 0;
};

struct UdpTraceData {
    uint64_t ns = 0;
    uint64_t sender_t0_ns = 0;
    uint64_t sender_s1_ns = 0;
    uint64_t sender_send_attempt_ns = 0;
    uint64_t kernel_rx_rt_ns = 0;
    uint64_t tunnel_seq = 0;
    uint32_t flow_hash = 0;
    uint32_t sender_id = 0;
    uint32_t kernel_to_user_us = 0xFFFFFFFFu;
    uint32_t tx_user_to_sched_us = 0xFFFFFFFFu;
    uint32_t tx_user_to_soft_us = 0xFFFFFFFFu;
    uint32_t tx_sched_to_soft_us = 0xFFFFFFFFu;
    uint16_t length = 0;
    uint16_t source_port = 0;
    uint16_t dest_port = 0;
    uint8_t stage = 0;
    uint8_t ip_version = 0;
    uint16_t flags = 0;
};

struct UdpTraceEvent : UdpTraceData {
    std::atomic<uint64_t> commit_id{0};
    std::atomic_flag busy = ATOMIC_FLAG_INIT;
};

// A diagnostic may be omitted under contention. A packet must never wait for
// the exporter, another producer, or a late kernel timestamp to release a slot.
struct TryTraceSlotGuard {
    UdpTraceEvent &slot;
    const bool acquired;
    explicit TryTraceSlotGuard(UdpTraceEvent &event)
        : slot(event), acquired(!event.busy.test_and_set(std::memory_order_acquire)) {}
    ~TryTraceSlotGuard() { if (acquired) slot.busy.clear(std::memory_order_release); }
};

struct PendingTxTimestamp {
    uint64_t ordinal = 0;
    uint64_t seq = 0;
    uint64_t user_send_rt_ns = 0;
    uint64_t tx_sched_rt_ns = 0;
    uint64_t tx_soft_rt_ns = 0;
    bool have_sched = false;
    bool have_soft = false;
};

struct GameplayEndpoint {
    std::array<uint8_t, 16> local_ip{};
    std::array<uint8_t, 16> peer_ip{};
    uint64_t last_outbound_ms = 0;
};

struct BackendStats {
    std::atomic<long long> tunnel_out_packets{0};
    std::atomic<long long> tunnel_out_bytes{0};
    std::atomic<long long> tunnel_in_packets{0};
    std::atomic<long long> tunnel_in_bytes{0};
    std::atomic<long long> stun_intercepted_ipv4{0};
    std::atomic<long long> stun_intercepted_ipv6{0};
    std::atomic<long long> passthrough_to_jvm_packets{0};
    std::atomic<long long> passthrough_to_tun_packets{0};
    std::atomic<long long> dropped_packets{0};
    std::atomic<long long> dropped_peer_tx{0};
    std::atomic<long long> dropped_bridge_tx{0};
    std::atomic<long long> dropped_tun_inject{0};
    std::atomic<long long> dropped_parse_or_policy{0};
    std::atomic<long long> peer_socket_events{0};
    std::atomic<long long> keepalive_tx{0};
    std::atomic<long long> keepalive_rx{0};
    // F32 end-of-match signal: count and last-seen time of small (<55B) game
    // payloads. Validated across both real captures: the uniform-54B tail is
    // the only sub-55B traffic outside ordinary gameplay.
    std::atomic<long long> small_game_packets{0};
    std::atomic<long long> small_game_last_ms{0};
};

enum class TunInjectSource {
    kPeer,
    kBridge,
    kStun,
};

struct PeerTxJob {
    std::vector<uint8_t> payload;
    uint64_t seq = 0;
    uint64_t t0_ns = 0;
    uint64_t s1_ns = 0;
    uint32_t flow_hash = 0;
    int tunnel_flags = 0;
    uint8_t ip_version = 0;
    uint16_t source_port = 0;
    uint16_t dest_port = 0;
};

struct ByteBufferJob {
    std::vector<uint8_t> bytes;
};

struct TunInjectJob {
    std::vector<uint8_t> bytes;
    TunInjectSource source = TunInjectSource::kPeer;
    bool count_as_tunnel = false;
    uint32_t flow_hash = 0;
    uint32_t sender_id = 0;
    uint64_t enqueue_ns = 0;
    uint64_t tunnel_seq = 0;
};

struct DeferredNativeLog {
    int level = kNativeLogInfo;
    bool file_only = true;
    std::string message;
};

// F32: the packet score-detection path (telemetry rings, PMT2 serialization,
// goal decode) is removed entirely. The only surviving per-packet signal is a
// tiny pair of counters used by the capture trigger: small game payloads
// (<55 bytes). In every real capture the end-of-match tail is the ONLY place
// where sub-55B game payloads appear (uniform 54B @ ~38ms cadence), while
// goals, half-time, replays and transient dips (lowest observed: 17pps) never
// produce them. The engine watches these counters plus the PPS feed to time
// screen capture; no payload content is decoded anywhere.
constexpr size_t kSmallGamePayloadMax = 55;

struct RawCaptureSlot {
    uint64_t mono_ns = 0;
    uint16_t length = 0;
    std::array<uint8_t, kRawCaptureMaxPacket> bytes{};
};

struct RawCaptureRing {
    std::unique_ptr<RawCaptureSlot[]> slots;
    std::atomic<uint64_t> write_index{0};
    std::atomic<uint64_t> read_index{0};
};

struct BackendState {
    JavaVM *jvm = nullptr;
    jobject callbacks = nullptr;
    jmethodID prepare_peer_socket_mid = nullptr;
    jmethodID fabricate_stun_mid = nullptr;
    jmethodID native_log_mid = nullptr;

    int tun_fd = -1;
    int peer_fd = -1;
    int bridge_fd = -1;
    int bridge_fd_for_jvm = -1;
    // Kept readable until every worker has joined: one stop wakes all pollers.
    int stop_fd = -1;
    ~BackendState() { if (stop_fd >= 0) close(stop_fd); }
    int mtu = 1400;
    std::atomic<bool> peer_socket_connected{false};

    sockaddr_in peer_addr{};
    std::array<uint8_t, 4> peer_lan_ip{};
    std::array<uint8_t, 4> configured_local_lan_ip{};
    bool has_configured_local_lan_ip = false;
    unsigned int configured_local_if_index = 0;

    std::mutex peer_probe_mutex;
    std::condition_variable peer_probe_cv;
    std::atomic<bool> peer_probe_ack{false};
    std::array<uint8_t, 4> my_fabricated_ip{};
    std::array<uint8_t, 4> peer_fabricated_ip{};
    std::array<uint8_t, 4> vpn_ipv4{};
    std::array<uint8_t, 16> vpn_ipv6{};

    // Last observed gameplay ports are diagnostic only, never routing authority.
    std::atomic<bool> has_stable_game_port{false};
    std::atomic<int> stable_game_port{0};
    std::atomic<int> stable_remote_port{0};
    std::atomic<uint64_t> stable_game_port_last_seen_ms{0};
    // Key: IP family, LOCAL destination port, REMOTE source port. Remote port
    // zero denotes a local STUN binding, usable before the first peer packet.
    // Owned by this backend session; all access is protected by flow_mutex.
    std::unordered_map<uint64_t, GameplayEndpoint> gameplay_endpoints;
    uint64_t last_endpoint_cleanup_ms = 0;

    bool all_game_udp_tunnel_mode_active = false;
    uint64_t gameplay_start_hint_ms = 0;
    std::atomic<uint64_t> game_traffic_start_ms{0};
    std::deque<uint64_t> recent_gameplay_udp_times_ms;

    std::atomic<bool> running{false};
    std::thread tun_thread;
    std::thread peer_rx_thread;
    std::thread peer_tx_thread;
    std::thread bridge_rx_thread;
    std::thread bridge_tx_thread;
    std::thread tun_inject_thread;
    std::thread keepalive_thread;
    std::thread file_log_thread;
    std::thread raw_capture_thread;

    // F23 raw-wire capture: producers are the outbound TUN reader and inbound
    // TUN writer; the low-priority writer is the only thread touching FILE*.
    RawCaptureRing raw_capture_out;
    RawCaptureRing raw_capture_in;
    std::string raw_capture_path;
    FILE *raw_capture_file = nullptr;
    std::atomic<bool> raw_capture_enabled{false};
    std::atomic<bool> raw_capture_stop{false};
    std::mutex raw_capture_wait_mutex;
    std::condition_variable raw_capture_cv;
    std::mutex raw_capture_flush_mutex;
    std::condition_variable raw_capture_flush_cv;
    std::atomic<uint64_t> raw_capture_flush_requested{0};
    std::atomic<uint64_t> raw_capture_flush_completed{0};
    std::atomic<uint64_t> raw_capture_packets{0};
    std::atomic<uint64_t> raw_capture_bytes{0};
    std::atomic<uint64_t> raw_capture_queue_drops{0};
    std::atomic<uint64_t> raw_capture_oversized_drops{0};
    std::atomic<uint64_t> raw_capture_write_errors{0};
    std::atomic<uint64_t> raw_capture_file_bytes{0};
    uint64_t raw_capture_base_mono_ns = 0;
    uint64_t raw_capture_base_real_ns = 0;

    std::mutex keepalive_mutex;
    std::condition_variable keepalive_cv;
    BackendStats stats;

    std::mutex peer_tx_mutex;
    std::condition_variable peer_tx_cv;
    std::deque<PeerTxJob> peer_tx_queue;

    std::mutex bridge_tx_mutex;
    std::condition_variable bridge_tx_cv;
    std::deque<ByteBufferJob> bridge_tx_queue;

    // Gameplay and control producers never share a queue lock. A burst of
    // DNS/STUN/ordinary Internet packets therefore cannot block peer gameplay
    // while it is being enqueued. The small wait mutex only closes the
    // condition-variable sleep/notify race; it never protects packet data.
    std::mutex game_tun_inject_mutex;
    std::mutex control_tun_inject_mutex;
    std::mutex tun_inject_wait_mutex;
    std::condition_variable tun_inject_cv;
    std::atomic<size_t> game_tun_inject_depth{0};
    std::atomic<size_t> control_tun_inject_depth{0};
    // Peer gameplay always drains before control/bridge traffic. This avoids
    // a DNS/download/STUN burst sitting in front of time-sensitive packets.
    std::deque<TunInjectJob> tun_inject_queue;
    std::deque<TunInjectJob> control_tun_inject_queue;

    std::mutex flow_mutex;
    std::mutex summary_mutex;
    std::mutex peer_send_mutex;
    std::mutex file_log_mutex;
    std::condition_variable file_log_cv;
    std::deque<DeferredNativeLog> deferred_file_logs;
    std::mutex timestamp_mutex;

    uint64_t outbound_seq = 0;
    std::atomic<uint64_t> last_keepalive_sent_ns{0};
    uint64_t last_tun_read_ns = 0;
    uint64_t last_tunnel_send_ns = 0;
    uint64_t last_peer_rx_ns = 0;
    uint64_t last_tun_gap_log_ms = 0;
    uint64_t last_tunnel_send_gap_log_ms = 0;
    uint64_t last_peer_rx_gap_log_ms = 0;
    std::atomic<uint64_t> next_summary_log_ms{0};
    uint64_t last_summary_log_ms = 0;
    long long last_summary_tunnel_out_packets = 0;
    long long last_summary_tunnel_in_packets = 0;
    long long last_summary_stun_intercepted = 0;
    long long last_summary_bridged_to_jvm_packets = 0;
    long long last_summary_injected_from_peer_packets = 0;
    long long last_summary_dropped_packets = 0;
    std::array<std::atomic<uint64_t>, kFlowLogWordCount> first_seen_flow_bits{};
    std::unique_ptr<UdpTraceEvent[]> udp_trace_buffer;
    size_t udp_trace_capacity = 0;
    std::atomic<uint64_t> udp_trace_write_count{0};
    // Only exporters share this mutex. Packet workers use nonblocking slots.
    std::mutex udp_trace_dump_mutex;
    std::atomic<uint64_t> udp_trace_skipped{0};
    bool peer_rx_kernel_timestamp_enabled = false;
    bool peer_tx_kernel_timestamp_enabled = false;
    std::unordered_map<uint64_t, PendingTxTimestamp> pending_tx_timestamps;
    // Linux SOF_TIMESTAMPING_OPT_ID starts datagram IDs at zero each time the
    // option is enabled. This mirror must start at the same value or every
    // kernel TX timestamp is joined to the following packet.
    uint32_t next_peer_tx_timestamp_id = 0u;
    std::atomic<uint64_t> deferred_file_logs_dropped{0};
    int sender_id = 0;

    // ─── Realtime scheduling: kernel TIDs of the hot datapath loops ──────
    // Registered by each latency-critical loop for diagnostics. The JVM only
    // attempts SCHED_FIFO when its command identity is actually root; Android
    // shell uid 2000 is not assumed to hold CAP_SYS_NICE.
    std::mutex hot_tids_mutex;
    std::vector<int> hot_tids;

    // ─── Per-tap sub-notice gap-log throttle (cuts per-packet JNI logging) ─
    uint64_t last_tun_subsevere_log_ms = 0;
    uint64_t last_rx_subsevere_log_ms = 0;
    uint64_t last_tx_subsevere_log_ms = 0;

    // ─── Local processing-latency probe (peer_tx thread only; no locks) ──
    uint64_t tx_lat_window_start_ms = 0;
    uint64_t tx_lat_max_ns = 0;
    uint64_t tx_lat_sum_ns = 0;
    uint64_t tx_lat_count = 0;

    // Inbound batching/wakeup diagnosis. peer_rx_loop is the sole writer.
    uint64_t rx_batch_window_start_ms = 0;
    uint64_t rx_batch_wakes = 0;
    uint64_t rx_batch_packets = 0;
    uint64_t rx_batch_max = 0;
    uint64_t rx_batch_delayed_wakes = 0;
    uint64_t rx_batch_max_kernel_to_user_us = 0;
    uint64_t rx_batch_max_kernel_span_us = 0;

    // TUN injection queue age. tun_inject_loop is the sole writer.
    uint64_t inject_lat_window_start_ms = 0;
    uint64_t inject_lat_sum_ns = 0;
    uint64_t inject_lat_max_ns = 0;
    uint64_t inject_lat_count = 0;
    uint64_t inject_peak_game_depth = 0;
    uint64_t inject_peak_control_depth = 0;


};

constexpr size_t kMaxPeerTxQueue = 1024;
constexpr size_t kMaxBridgeTxQueue = 2048;
constexpr size_t kMaxTunInjectQueue = 2048;
constexpr size_t kMaxControlTunInjectQueue = 2048;
constexpr size_t kMaxDeferredFileLogs = 65536;
constexpr size_t kPeerDedupWindowSize = 512;

uint64_t monotonic_ns();
bool stable_gameplay_known(BackendState *state);
int load_stable_game_port(BackendState *state);
int load_stable_remote_port(BackendState *state);

void request_backend_stop(BackendState *state) {
    if (state == nullptr) return;
    state->running.store(false, std::memory_order_release);
    if (state->stop_fd >= 0) {
        const uint64_t wake = 1;
        ssize_t result;
        do { result = write(state->stop_fd, &wake, sizeof(wake)); }
        while (result < 0 && errno == EINTR);
    }
    // Close each predicate-to-wait gap before notifying. Descriptors remain
    // open and immutable until all readers/writers have exited.
    { std::lock_guard<std::mutex> lock(state->peer_tx_mutex); }
    state->peer_tx_cv.notify_all();
    { std::lock_guard<std::mutex> lock(state->bridge_tx_mutex); }
    state->bridge_tx_cv.notify_all();
    { std::lock_guard<std::mutex> lock(state->tun_inject_wait_mutex); }
    state->tun_inject_cv.notify_all();
    { std::lock_guard<std::mutex> lock(state->peer_probe_mutex); }
    state->peer_probe_cv.notify_all();
    { std::lock_guard<std::mutex> lock(state->keepalive_mutex); }
    state->keepalive_cv.notify_all();
    { std::lock_guard<std::mutex> lock(state->file_log_mutex); }
    state->file_log_cv.notify_all();
}

int wait_for_backend_read(BackendState *state, int fd, short &events) {
    pollfd descriptors[2]{{fd, POLLIN, 0}, {state->stop_fd, POLLIN, 0}};
    int ready;
    do {
        ready = poll(descriptors, 2, -1);
    } while (ready < 0 && errno == EINTR && state->running.load(std::memory_order_acquire));
    if (!state->running.load(std::memory_order_acquire) || descriptors[1].revents != 0) return 0;
    events = descriptors[0].revents;
    return ready;
}

template <typename T>
bool wait_for_queue_pop(std::atomic<bool> &running,
                        std::mutex &mutex,
                        std::condition_variable &cv,
                        std::deque<T> &queue,
                        T &out) {
    std::unique_lock<std::mutex> lock(mutex);
    cv.wait(lock, [&]() { return !running.load(std::memory_order_acquire) || !queue.empty(); });
    if (!running.load(std::memory_order_acquire) || queue.empty()) return false;
    out = std::move(queue.front());
    queue.pop_front();
    return true;
}

bool enqueue_peer_tx(BackendState *state, PeerTxJob job) {
    std::lock_guard<std::mutex> lock(state->peer_tx_mutex);
    if (state->peer_tx_queue.size() >= kMaxPeerTxQueue) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_peer_tx.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    state->peer_tx_queue.push_back(std::move(job));
    state->peer_tx_cv.notify_one();
    return true;
}

bool enqueue_bridge_tx(BackendState *state, std::vector<uint8_t> &&bytes) {
    std::lock_guard<std::mutex> lock(state->bridge_tx_mutex);
    if (state->bridge_tx_queue.size() >= kMaxBridgeTxQueue) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_bridge_tx.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    state->bridge_tx_queue.push_back(ByteBufferJob{std::move(bytes)});
    state->bridge_tx_cv.notify_one();
    return true;
}

bool enqueue_tun_inject(BackendState *state,
                        std::vector<uint8_t> &&bytes,
                        TunInjectSource source,
                        bool count_as_tunnel,
                        uint32_t flow_hash = 0,
                        uint64_t tunnel_seq = 0,
                        uint32_t sender_id = 0) {
    TunInjectJob job{};
    job.bytes = std::move(bytes);
    job.source = source;
    job.count_as_tunnel = count_as_tunnel;
    job.flow_hash = flow_hash;
    job.sender_id = sender_id;
    job.enqueue_ns = monotonic_ns();
    job.tunnel_seq = tunnel_seq;
    if (source == TunInjectSource::kPeer) {
        std::lock_guard<std::mutex> lock(state->game_tun_inject_mutex);
        if (state->tun_inject_queue.size() >= kMaxTunInjectQueue) {
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            state->stats.dropped_tun_inject.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        state->tun_inject_queue.push_back(std::move(job));
        state->game_tun_inject_depth.fetch_add(1, std::memory_order_release);
    } else {
        std::lock_guard<std::mutex> lock(state->control_tun_inject_mutex);
        if (state->control_tun_inject_queue.size() >= kMaxControlTunInjectQueue) {
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            state->stats.dropped_tun_inject.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        state->control_tun_inject_queue.push_back(std::move(job));
        state->control_tun_inject_depth.fetch_add(1, std::memory_order_release);
    }
    // Synchronize with the wait transition so a producer cannot notify in the
    // tiny interval between a false predicate and the consumer sleeping.
    {
        std::lock_guard<std::mutex> wait_lock(state->tun_inject_wait_mutex);
    }
    state->tun_inject_cv.notify_one();
    return true;
}

bool pop_tun_inject_job(BackendState *state, TunInjectJob &out, bool wait) {
    if (wait) {
        std::unique_lock<std::mutex> wait_lock(state->tun_inject_wait_mutex);
        state->tun_inject_cv.wait(wait_lock, [&]() {
            return !state->running.load(std::memory_order_acquire) ||
                   state->game_tun_inject_depth.load(std::memory_order_acquire) > 0 ||
                   state->control_tun_inject_depth.load(std::memory_order_acquire) > 0;
        });
    }
    {
        std::lock_guard<std::mutex> game_lock(state->game_tun_inject_mutex);
        if (!state->tun_inject_queue.empty()) {
            state->inject_peak_game_depth = std::max<uint64_t>(
                    state->inject_peak_game_depth, state->tun_inject_queue.size());
            out = std::move(state->tun_inject_queue.front());
            state->tun_inject_queue.pop_front();
            state->game_tun_inject_depth.fetch_sub(1, std::memory_order_release);
            return true;
        }
    }
    {
        std::lock_guard<std::mutex> control_lock(state->control_tun_inject_mutex);
        if (!state->control_tun_inject_queue.empty()) {
            state->inject_peak_control_depth = std::max<uint64_t>(
                    state->inject_peak_control_depth, state->control_tun_inject_queue.size());
            out = std::move(state->control_tun_inject_queue.front());
            state->control_tun_inject_queue.pop_front();
            state->control_tun_inject_depth.fetch_sub(1, std::memory_order_release);
            return true;
        }
    }
    return false;
}

uint64_t monotonic_ns() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

uint64_t monotonic_ms() {
    return monotonic_ns() / 1000000ULL;
}

// F32: the only surviving per-packet signal. A small game payload (<55B) is
// the validated end-of-match tail marker; two plain counters feed the stats
// poll. No payload content is retained or decoded.
void record_match_packet_signal(BackendState *state,
                                size_t payload_length,
                                uint64_t now_ms) {
    if (payload_length < kSmallGamePayloadMax) {
        state->stats.small_game_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.small_game_last_ms.store(now_ms, std::memory_order_release);
    }
}


static inline void pcap_push_u16(std::vector<uint8_t> &out, uint16_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xFFu));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFFu));
}

static inline void pcap_push_u32(std::vector<uint8_t> &out, uint32_t value) {
    out.push_back(static_cast<uint8_t>(value & 0xFFu));
    out.push_back(static_cast<uint8_t>((value >> 8) & 0xFFu));
    out.push_back(static_cast<uint8_t>((value >> 16) & 0xFFu));
    out.push_back(static_cast<uint8_t>((value >> 24) & 0xFFu));
}

static inline void pcap_push_u64(std::vector<uint8_t> &out, uint64_t value) {
    pcap_push_u32(out, static_cast<uint32_t>(value & 0xFFFFFFFFULL));
    pcap_push_u32(out, static_cast<uint32_t>(value >> 32));
}

bool raw_capture_write(BackendState *state, const void *data, size_t length) {
    if (state == nullptr || state->raw_capture_file == nullptr || data == nullptr || length == 0) return false;
    if (std::fwrite(data, 1, length, state->raw_capture_file) != length) {
        state->raw_capture_write_errors.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    state->raw_capture_file_bytes.fetch_add(static_cast<uint64_t>(length), std::memory_order_relaxed);
    return true;
}

bool raw_capture_write_vector(BackendState *state, const std::vector<uint8_t> &block) {
    return raw_capture_write(state, block.data(), block.size());
}

void raw_capture_write_section(BackendState *state) {
    // Section Header Block, little-endian section.
    std::vector<uint8_t> block;
    block.reserve(28);
    pcap_push_u32(block, 0x0A0D0D0Au);
    pcap_push_u32(block, 28u);
    pcap_push_u32(block, 0x1A2B3C4Du);
    pcap_push_u16(block, 1u);
    pcap_push_u16(block, 0u);
    pcap_push_u64(block, 0xFFFFFFFFFFFFFFFFULL);
    pcap_push_u32(block, 28u);
    (void) raw_capture_write_vector(state, block);
}

void raw_capture_write_idb(BackendState *state, const char *name) {
    // LINKTYPE_RAW (101): packet bytes begin with the IPv4/IPv6 header.
    const size_t name_len = std::strlen(name);
    const size_t padded_name = (name_len + 3u) & ~3u;
    const uint32_t total = static_cast<uint32_t>(20u + 4u + padded_name + 4u);
    std::vector<uint8_t> block;
    block.reserve(total);
    pcap_push_u32(block, 0x00000001u);
    pcap_push_u32(block, total);
    pcap_push_u16(block, 101u);
    pcap_push_u16(block, 0u);
    pcap_push_u32(block, 65535u);
    // if_name option (2)
    pcap_push_u16(block, 2u);
    pcap_push_u16(block, static_cast<uint16_t>(name_len));
    block.insert(block.end(), name, name + name_len);
    while (block.size() % 4u != 0u) block.push_back(0u);
    // end-of-options
    pcap_push_u16(block, 0u);
    pcap_push_u16(block, 0u);
    pcap_push_u32(block, total);
    (void) raw_capture_write_vector(state, block);
}

void raw_capture_write_packet(BackendState *state, uint32_t interface_id, const RawCaptureSlot &slot) {
    uint64_t real_ns = state->raw_capture_base_real_ns;
    if (slot.mono_ns >= state->raw_capture_base_mono_ns) {
        real_ns += slot.mono_ns - state->raw_capture_base_mono_ns;
    }
    const uint64_t ts_us = real_ns / 1000ULL;  // default PCAPNG resolution is microseconds
    const uint32_t padded = (static_cast<uint32_t>(slot.length) + 3u) & ~3u;
    const uint32_t total = 32u + padded;
    std::vector<uint8_t> block;
    block.reserve(total);
    pcap_push_u32(block, 0x00000006u);  // Enhanced Packet Block
    pcap_push_u32(block, total);
    pcap_push_u32(block, interface_id);
    pcap_push_u32(block, static_cast<uint32_t>(ts_us >> 32));
    pcap_push_u32(block, static_cast<uint32_t>(ts_us & 0xFFFFFFFFULL));
    pcap_push_u32(block, static_cast<uint32_t>(slot.length));
    pcap_push_u32(block, static_cast<uint32_t>(slot.length));
    block.insert(block.end(), slot.bytes.begin(), slot.bytes.begin() + slot.length);
    while (block.size() < total - 4u) block.push_back(0u);
    pcap_push_u32(block, total);
    (void) raw_capture_write_vector(state, block);
}

bool raw_capture_ring_empty(const RawCaptureRing &ring) {
    return ring.read_index.load(std::memory_order_acquire) >=
           ring.write_index.load(std::memory_order_acquire);
}

bool raw_capture_drain_one(BackendState *state, RawCaptureRing &ring, uint32_t interface_id) {
    const uint64_t read = ring.read_index.load(std::memory_order_relaxed);
    const uint64_t write = ring.write_index.load(std::memory_order_acquire);
    if (read >= write) return false;
    const RawCaptureSlot &slot = ring.slots[read % kRawCaptureRingCapacity];
    raw_capture_write_packet(state, interface_id, slot);
    ring.read_index.store(read + 1u, std::memory_order_release);
    return true;
}

void enqueue_raw_capture(BackendState *state,
                         bool outgoing,
                         const uint8_t *packet,
                         size_t packet_length,
                         uint64_t mono_ns) {
    if (state == nullptr || packet == nullptr || packet_length == 0 ||
        !state->raw_capture_enabled.load(std::memory_order_acquire)) return;
    if (packet_length > kRawCaptureMaxPacket) {
        state->raw_capture_oversized_drops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    RawCaptureRing &ring = outgoing ? state->raw_capture_out : state->raw_capture_in;
    const uint64_t write = ring.write_index.load(std::memory_order_relaxed);
    const uint64_t read = ring.read_index.load(std::memory_order_acquire);
    if (write - read >= kRawCaptureRingCapacity) {
        // Capture must never back-pressure gameplay. Drop capture only, expose it.
        state->raw_capture_queue_drops.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    RawCaptureSlot &slot = ring.slots[write % kRawCaptureRingCapacity];
    slot.mono_ns = mono_ns;
    slot.length = static_cast<uint16_t>(packet_length);
    std::memcpy(slot.bytes.data(), packet, packet_length);
    ring.write_index.store(write + 1u, std::memory_order_release);
    state->raw_capture_packets.fetch_add(1, std::memory_order_relaxed);
    state->raw_capture_bytes.fetch_add(static_cast<uint64_t>(packet_length), std::memory_order_relaxed);
    if (write == read) state->raw_capture_cv.notify_one();
}

void raw_capture_writer_loop(BackendState *state) {
    // The capture writer must never compete with the latency-critical peer/TUN
    // threads. Nice +10 is intentionally best-effort and safe for app uid.
    (void) setpriority(PRIO_PROCESS, 0, 10);
    uint64_t packets_since_yield = 0;
    while (!state->raw_capture_stop.load(std::memory_order_acquire) ||
           !raw_capture_ring_empty(state->raw_capture_out) ||
           !raw_capture_ring_empty(state->raw_capture_in)) {
        bool did_work = false;
        for (int i = 0; i < 256; ++i) {
            const bool a = raw_capture_drain_one(state, state->raw_capture_out, 0u);
            const bool b = raw_capture_drain_one(state, state->raw_capture_in, 1u);
            if (!a && !b) break;
            did_work = true;
            packets_since_yield += static_cast<uint64_t>(a) + static_cast<uint64_t>(b);
        }
        const uint64_t requested = state->raw_capture_flush_requested.load(std::memory_order_acquire);
        if (requested > state->raw_capture_flush_completed.load(std::memory_order_acquire) &&
            raw_capture_ring_empty(state->raw_capture_out) && raw_capture_ring_empty(state->raw_capture_in)) {
            if (state->raw_capture_file != nullptr) std::fflush(state->raw_capture_file);
            state->raw_capture_flush_completed.store(requested, std::memory_order_release);
            state->raw_capture_flush_cv.notify_all();
        }
        if (!did_work) {
            std::unique_lock<std::mutex> lock(state->raw_capture_wait_mutex);
            state->raw_capture_cv.wait_for(lock, std::chrono::milliseconds(25));
        } else if (packets_since_yield >= 1024u) {
            packets_since_yield = 0;
            std::this_thread::yield();
        }
    }
    if (state->raw_capture_file != nullptr) {
        std::fflush(state->raw_capture_file);
        const int fd = fileno(state->raw_capture_file);
        if (fd >= 0) (void) fsync(fd);
        std::fclose(state->raw_capture_file);
        state->raw_capture_file = nullptr;
    }
}

bool start_raw_capture(BackendState *state, const std::string &path) {
    if (state == nullptr || path.empty()) return false;
    state->raw_capture_out.slots = std::unique_ptr<RawCaptureSlot[]>(new (std::nothrow) RawCaptureSlot[kRawCaptureRingCapacity]);
    state->raw_capture_in.slots = std::unique_ptr<RawCaptureSlot[]>(new (std::nothrow) RawCaptureSlot[kRawCaptureRingCapacity]);
    if (!state->raw_capture_out.slots || !state->raw_capture_in.slots) {
        state->raw_capture_write_errors.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    state->raw_capture_path = path;
    state->raw_capture_file = std::fopen(path.c_str(), "wb");
    if (state->raw_capture_file == nullptr) {
        state->raw_capture_write_errors.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    (void) std::setvbuf(state->raw_capture_file, nullptr, _IOFBF, kRawCaptureStdioBuffer);
    state->raw_capture_base_mono_ns = monotonic_ns();
    timespec rt{};
    clock_gettime(CLOCK_REALTIME, &rt);
    state->raw_capture_base_real_ns = static_cast<uint64_t>(rt.tv_sec) * 1000000000ULL + static_cast<uint64_t>(rt.tv_nsec);
    raw_capture_write_section(state);
    raw_capture_write_idb(state, "peerlink-out");
    raw_capture_write_idb(state, "peerlink-in");
    if (state->raw_capture_write_errors.load(std::memory_order_relaxed) != 0) {
        std::fclose(state->raw_capture_file);
        state->raw_capture_file = nullptr;
        return false;
    }
    state->raw_capture_stop.store(false, std::memory_order_release);
    state->raw_capture_enabled.store(true, std::memory_order_release);
    return true;
}

std::array<uint64_t, 6> raw_capture_stats(BackendState *state) {
    if (state == nullptr) return {0,0,0,0,0,0};
    return {
        state->raw_capture_packets.load(std::memory_order_acquire),
        state->raw_capture_bytes.load(std::memory_order_acquire),
        state->raw_capture_queue_drops.load(std::memory_order_acquire),
        state->raw_capture_oversized_drops.load(std::memory_order_acquire),
        state->raw_capture_write_errors.load(std::memory_order_acquire),
        state->raw_capture_file_bytes.load(std::memory_order_acquire),
    };
}

void request_raw_capture_flush(BackendState *state) {
    if (state == nullptr || !state->raw_capture_enabled.load(std::memory_order_acquire)) return;
    const uint64_t request = state->raw_capture_flush_requested.fetch_add(1, std::memory_order_acq_rel) + 1u;
    state->raw_capture_cv.notify_one();
    std::unique_lock<std::mutex> lock(state->raw_capture_flush_mutex);
    (void) state->raw_capture_flush_cv.wait_for(
        lock,
        std::chrono::seconds(2),
        [&] { return state->raw_capture_flush_completed.load(std::memory_order_acquire) >= request; });
}

uint64_t realtime_ns() {
    timespec ts{};
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

uint64_t timespec_to_ns(const timespec &ts) {
    return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

const char *udp_trace_stage_name(uint8_t stage) {
    switch (stage) {
        case kUdpTraceStageTunRead: return "UDP_TUN_READ";
        case kUdpTraceStageClassifiedTunnel: return "UDP_CLASSIFIED_TUNNEL";
        case kUdpTraceStagePeerSend: return "UDP_PEER_SEND";
        case kUdpTraceStagePeerRecv: return "UDP_PEER_RECV";
        case kUdpTraceStageTunInject: return "UDP_TUN_INJECT";
        case kUdpTraceStageTunWrite: return "UDP_TUN_WRITE";
        default: return "UDP_UNKNOWN";
    }
}

std::string udp_trace_flags_to_string(uint16_t flags) {
    std::string out;
    auto append = [&](const char *name) {
        if (!out.empty()) out += '|';
        out += name;
    };
    if ((flags & kUdpTraceFlagStun) != 0) append("stun");
    if ((flags & kUdpTraceFlagPeerFabricated) != 0) append("peerFab");
    if ((flags & kUdpTraceFlagTunnel) != 0) append("tunnel");
    if ((flags & kUdpTraceFlagFromPeer) != 0) append("fromPeer");
    if ((flags & kUdpTraceFlagInject) != 0) append("inject");
    if ((flags & kUdpTraceFlagStableKnown) != 0) append("stableKnown");
    if (out.empty()) out = "none";
    return out;
}

uint64_t record_udp_trace(BackendState *state,
                         uint8_t stage,
                         uint64_t now_ns,
                         uint8_t ip_version,
                         uint16_t flags,
                         uint16_t source_port,
                         uint16_t dest_port,
                         size_t length,
                         uint64_t tunnel_seq,
                         uint32_t flow_hash,
                         uint32_t kernel_to_user_us = 0xFFFFFFFFu,
                         uint32_t sender_id = 0,
                         uint64_t sender_t0_ns = 0,
                         uint64_t sender_s1_ns = 0,
                         uint64_t sender_send_attempt_ns = 0,
                         uint64_t kernel_rx_rt_ns = 0) {
    if (state == nullptr || state->udp_trace_buffer == nullptr || state->udp_trace_capacity == 0) return 0;
    const uint64_t ordinal = state->udp_trace_write_count.fetch_add(1, std::memory_order_relaxed) + 1ULL;
    UdpTraceEvent &slot = state->udp_trace_buffer[(ordinal - 1ULL) % state->udp_trace_capacity];
    TryTraceSlotGuard guard(slot);
    if (!guard.acquired || slot.commit_id.load(std::memory_order_relaxed) >= ordinal) {
        state->udp_trace_skipped.fetch_add(1, std::memory_order_relaxed);
        return 0;
    }
    slot.commit_id.store(0ULL, std::memory_order_relaxed);
    slot.ns = now_ns;
    slot.sender_t0_ns = sender_t0_ns;
    slot.sender_s1_ns = sender_s1_ns;
    slot.sender_send_attempt_ns = sender_send_attempt_ns;
    slot.kernel_rx_rt_ns = kernel_rx_rt_ns;
    slot.tunnel_seq = tunnel_seq;
    slot.flow_hash = flow_hash;
    slot.sender_id = sender_id;
    slot.kernel_to_user_us = kernel_to_user_us;
    slot.tx_user_to_sched_us = 0xFFFFFFFFu;
    slot.tx_user_to_soft_us = 0xFFFFFFFFu;
    slot.tx_sched_to_soft_us = 0xFFFFFFFFu;
    slot.length = static_cast<uint16_t>(std::min<size_t>(length, 0xFFFFu));
    slot.source_port = source_port;
    slot.dest_port = dest_port;
    slot.stage = stage;
    slot.ip_version = ip_version;
    slot.flags = flags;
    slot.commit_id.store(ordinal, std::memory_order_release);
    return ordinal;
}

std::string dump_udp_trace(BackendState *state) {
    if (state == nullptr || state->udp_trace_buffer == nullptr || state->udp_trace_capacity == 0) return {};
    std::lock_guard<std::mutex> dump_lock(state->udp_trace_dump_mutex);
    const uint64_t total_written = state->udp_trace_write_count.load(std::memory_order_acquire);
    if (total_written == 0) return {};
    const uint64_t captured = std::min<uint64_t>(total_written, state->udp_trace_capacity);
    const uint64_t start_ordinal = total_written > state->udp_trace_capacity ? (total_written - state->udp_trace_capacity + 1ULL) : 1ULL;

    struct PacketKey {
        uint32_t sender_id = 0;
        uint64_t seq = 0;

        bool operator==(const PacketKey &other) const {
            return sender_id == other.sender_id && seq == other.seq;
        }
    };

    struct PacketKeyHash {
        size_t operator()(const PacketKey &key) const {
            return std::hash<uint64_t>{}((static_cast<uint64_t>(key.sender_id) << 32ULL) ^ key.seq);
        }
    };

    struct PacketTruthRow {
        PacketKey key{};
        uint64_t first_local_ns = 0;
        uint64_t tun_read_ns = 0;
        uint64_t classified_ns = 0;
        uint64_t send_call_ns = 0;
        uint64_t recv_user_ns = 0;
        uint64_t inject_ns = 0;
        uint64_t tun_write_ns = 0;
        uint64_t sender_t0_ns = 0;
        uint64_t sender_s1_ns = 0;
        uint64_t sender_send_attempt_ns = 0;
        uint64_t kernel_rx_rt_ns = 0;
        uint32_t flow_hash = 0;
        uint16_t length = 0;
        uint16_t source_port = 0;
        uint16_t dest_port = 0;
        uint8_t ip_version = 0;
        uint16_t flags = 0;
        uint32_t kernel_to_user_us = 0xFFFFFFFFu;
        uint32_t tx_user_to_sched_us = 0xFFFFFFFFu;
        uint32_t tx_user_to_soft_us = 0xFFFFFFFFu;
        uint32_t tx_sched_to_soft_us = 0xFFFFFFFFu;
        bool has_tun_read = false;
        bool has_classified = false;
        bool has_send = false;
        bool has_recv = false;
        bool has_inject = false;
        bool has_tun_write = false;
        bool stable = false;
        bool from_peer = false;
    };

    std::unordered_map<PacketKey, PacketTruthRow, PacketKeyHash> rows;
    rows.reserve(static_cast<size_t>(captured));
    std::vector<PacketKey> order;
    order.reserve(static_cast<size_t>(captured));
    uint64_t snapshot_skipped = 0;

    for (uint64_t ordinal = start_ordinal; ordinal <= total_written; ++ordinal) {
        UdpTraceData slot{};
        {
            UdpTraceEvent &event = state->udp_trace_buffer[(ordinal - 1ULL) % state->udp_trace_capacity];
            TryTraceSlotGuard guard(event);
            if (!guard.acquired || event.commit_id.load(std::memory_order_relaxed) != ordinal) {
                ++snapshot_skipped;
                continue;
            }
            slot = static_cast<const UdpTraceData &>(event);
        }
        if ((slot.flags & kUdpTraceFlagTunnel) == 0) continue;
        if (slot.tunnel_seq == 0 || slot.sender_id == 0) continue;

        PacketKey key{slot.sender_id, slot.tunnel_seq};
        auto [it, inserted] = rows.emplace(key, PacketTruthRow{});
        PacketTruthRow &row = it->second;
        if (inserted) {
            row.key = key;
            row.first_local_ns = slot.ns;
            order.push_back(key);
        } else if (row.first_local_ns == 0 || slot.ns < row.first_local_ns) {
            row.first_local_ns = slot.ns;
        }

        if (slot.flow_hash != 0) row.flow_hash = slot.flow_hash;
        if (slot.length != 0) row.length = slot.length;
        if (slot.source_port != 0) row.source_port = slot.source_port;
        if (slot.dest_port != 0) row.dest_port = slot.dest_port;
        if (slot.ip_version != 0) row.ip_version = slot.ip_version;
        row.flags |= slot.flags;
        row.stable = row.stable || ((slot.flags & kUdpTraceFlagStableKnown) != 0);
        row.from_peer = row.from_peer || ((slot.flags & kUdpTraceFlagFromPeer) != 0);
        if (slot.sender_t0_ns != 0) row.sender_t0_ns = slot.sender_t0_ns;
        if (slot.sender_s1_ns != 0) row.sender_s1_ns = slot.sender_s1_ns;
        if (slot.sender_send_attempt_ns != 0) row.sender_send_attempt_ns = slot.sender_send_attempt_ns;
        if (slot.kernel_rx_rt_ns != 0) row.kernel_rx_rt_ns = slot.kernel_rx_rt_ns;
        if (slot.kernel_to_user_us != 0xFFFFFFFFu) row.kernel_to_user_us = slot.kernel_to_user_us;
        if (slot.tx_user_to_sched_us != 0xFFFFFFFFu) row.tx_user_to_sched_us = slot.tx_user_to_sched_us;
        if (slot.tx_user_to_soft_us != 0xFFFFFFFFu) row.tx_user_to_soft_us = slot.tx_user_to_soft_us;
        if (slot.tx_sched_to_soft_us != 0xFFFFFFFFu) row.tx_sched_to_soft_us = slot.tx_sched_to_soft_us;

        switch (slot.stage) {
            case kUdpTraceStageTunRead:
                row.tun_read_ns = slot.ns;
                row.has_tun_read = true;
                break;
            case kUdpTraceStageClassifiedTunnel:
                row.classified_ns = slot.ns;
                row.has_classified = true;
                break;
            case kUdpTraceStagePeerSend:
                row.send_call_ns = slot.ns;
                row.has_send = true;
                break;
            case kUdpTraceStagePeerRecv:
                row.recv_user_ns = slot.ns;
                row.has_recv = true;
                break;
            case kUdpTraceStageTunInject:
                row.inject_ns = slot.ns;
                row.has_inject = true;
                break;
            case kUdpTraceStageTunWrite:
                row.tun_write_ns = slot.ns;
                row.has_tun_write = true;
                break;
            default:
                break;
        }
    }

    if (order.empty()) return {};

    std::sort(order.begin(), order.end(), [&](const PacketKey &lhs, const PacketKey &rhs) {
        const PacketTruthRow &a = rows.at(lhs);
        const PacketTruthRow &b = rows.at(rhs);
        if (a.first_local_ns != b.first_local_ns) return a.first_local_ns < b.first_local_ns;
        if (a.key.sender_id != b.key.sender_id) return a.key.sender_id < b.key.sender_id;
        return a.key.seq < b.key.seq;
    });

    std::deque<uint64_t> gameplay_window_ns;
    uint64_t gameplay_start_ns = 0;
    for (const PacketKey &key : order) {
        const PacketTruthRow &row = rows.at(key);
        if (!row.stable) continue;
        if (!row.has_send && !row.has_recv) continue;
        const uint64_t sample_ns = row.has_send ? row.send_call_ns : row.recv_user_ns;
        if (sample_ns == 0) continue;
        gameplay_window_ns.push_back(sample_ns);
        while (!gameplay_window_ns.empty() && sample_ns > gameplay_window_ns.front() && (sample_ns - gameplay_window_ns.front()) > 1000000000ULL) {
            gameplay_window_ns.pop_front();
        }
        if (gameplay_window_ns.size() >= 20U) {
            gameplay_start_ns = gameplay_window_ns.front();
            break;
        }
    }

    std::string out;
    out.reserve(static_cast<size_t>(order.size()) * 256U + 1024U);
    char header[768];
    std::snprintf(header,
                  sizeof(header),
                  "# wifi_truth_trace version=2 packets=%zu capturedEvents=%llu overwritten=%llu localSenderId=%d kernelRxTs=%s txKernelTs=%s gameplayStartNs=%llu gameplayThresholdPps=20\n"
                  "# sender_id,seq,dir,ipver,flow_hash,flags,len,sport,dport,rel_ms,sender_delta_us,tx_tun_to_classify_us,tx_classify_to_send_us,tx_user_to_sched_us,tx_user_to_soft_us,tx_sched_to_soft_us,rx_kernel_delta_us,rx_kernel_to_user_us,rx_user_to_enqueue_us,rx_enqueue_to_write_us,rx_user_to_tun_write_us,tun_read_ns,classified_ns,send_call_ns,sender_t0_ns,sender_s1_ns,sender_send_attempt_ns,rx_kernel_rt_ns,rx_user_ns,enqueue_ns,tun_write_ns,status\n",
                  order.size(),
                  static_cast<unsigned long long>(captured),
                  static_cast<unsigned long long>(total_written > state->udp_trace_capacity ? (total_written - state->udp_trace_capacity) : 0ULL),
                  state->sender_id,
                  state->peer_rx_kernel_timestamp_enabled ? "enabled" : "disabled",
                  state->peer_tx_kernel_timestamp_enabled ? "enabled" : "disabled",
                  static_cast<unsigned long long>(gameplay_start_ns));
    out += header;
    out += "# liveSnapshot=true snapshotSkipped=" + std::to_string(snapshot_skipped) +
           " diagnosticWritesSkipped=" + std::to_string(state->udp_trace_skipped.load(std::memory_order_relaxed)) +
           " (diagnostic omissions are not packet drops)\n";

    if (gameplay_start_ns == 0) {
        out += "# no gameplay window reached 20 packets per second; no rows emitted\n";
        return out;
    }

    std::unordered_map<uint32_t, uint64_t> prev_sender_send_attempt_ns;
    std::unordered_map<uint32_t, uint64_t> prev_kernel_rx_rt_ns;
    const uint64_t base_ns = gameplay_start_ns;
    char line[1024];

    for (const PacketKey &key : order) {
        const PacketTruthRow &row = rows.at(key);
        if (!row.stable) continue;
        const uint64_t anchor_ns = row.has_send ? row.send_call_ns : (row.has_recv ? row.recv_user_ns : row.first_local_ns);
        if (anchor_ns == 0 || anchor_ns < gameplay_start_ns) continue;

        int64_t sender_delta_us = -1;
        if (row.sender_send_attempt_ns != 0) {
            auto it = prev_sender_send_attempt_ns.find(row.key.sender_id);
            if (it != prev_sender_send_attempt_ns.end() && row.sender_send_attempt_ns >= it->second) {
                sender_delta_us = static_cast<int64_t>((row.sender_send_attempt_ns - it->second) / 1000ULL);
            }
            prev_sender_send_attempt_ns[row.key.sender_id] = row.sender_send_attempt_ns;
        }

        int64_t rx_kernel_delta_us = -1;
        if (row.kernel_rx_rt_ns != 0) {
            auto it = prev_kernel_rx_rt_ns.find(row.key.sender_id);
            if (it != prev_kernel_rx_rt_ns.end() && row.kernel_rx_rt_ns >= it->second) {
                rx_kernel_delta_us = static_cast<int64_t>((row.kernel_rx_rt_ns - it->second) / 1000ULL);
            }
            prev_kernel_rx_rt_ns[row.key.sender_id] = row.kernel_rx_rt_ns;
        }

        int64_t tx_tun_to_classify_us = -1;
        if (row.has_tun_read && row.has_classified && row.classified_ns >= row.tun_read_ns) {
            tx_tun_to_classify_us = static_cast<int64_t>((row.classified_ns - row.tun_read_ns) / 1000ULL);
        }

        int64_t tx_classify_to_send_us = -1;
        if (row.has_classified && row.has_send && row.send_call_ns >= row.classified_ns) {
            tx_classify_to_send_us = static_cast<int64_t>((row.send_call_ns - row.classified_ns) / 1000ULL);
        }

        int64_t rx_kernel_to_user_us = row.kernel_to_user_us == 0xFFFFFFFFu ? -1 : static_cast<int64_t>(row.kernel_to_user_us);
        int64_t rx_user_to_enqueue_us = -1;
        if (row.has_recv && row.has_inject && row.inject_ns >= row.recv_user_ns) {
            rx_user_to_enqueue_us = static_cast<int64_t>((row.inject_ns - row.recv_user_ns) / 1000ULL);
        }
        int64_t rx_enqueue_to_write_us = -1;
        if (row.has_inject && row.has_tun_write && row.tun_write_ns >= row.inject_ns) {
            rx_enqueue_to_write_us = static_cast<int64_t>((row.tun_write_ns - row.inject_ns) / 1000ULL);
        }
        int64_t rx_user_to_tun_write_us = -1;
        if (row.has_recv && row.has_tun_write && row.tun_write_ns >= row.recv_user_ns) {
            rx_user_to_tun_write_us = static_cast<int64_t>((row.tun_write_ns - row.recv_user_ns) / 1000ULL);
        }

        int64_t tx_user_to_sched_us = row.tx_user_to_sched_us == 0xFFFFFFFFu ? -1 : static_cast<int64_t>(row.tx_user_to_sched_us);
        int64_t tx_user_to_soft_us = row.tx_user_to_soft_us == 0xFFFFFFFFu ? -1 : static_cast<int64_t>(row.tx_user_to_soft_us);
        int64_t tx_sched_to_soft_us = row.tx_sched_to_soft_us == 0xFFFFFFFFu ? -1 : static_cast<int64_t>(row.tx_sched_to_soft_us);

        const char *dir = row.key.sender_id == static_cast<uint32_t>(state->sender_id) ? "out" : "in";
        const char *status = "partial";
        if (row.has_tun_read && row.has_classified && row.has_send && row.has_recv && row.has_tun_write) {
            status = "complete";
        } else if (row.has_tun_read && row.has_classified && row.has_send && !row.has_recv) {
            status = "sent_no_rx";
        } else if (row.has_recv && !row.has_inject) {
            status = "rx_no_enqueue";
        } else if (row.has_recv && row.has_inject && !row.has_tun_write) {
            status = "rx_enqueued_no_write";
        } else if (row.has_recv && row.has_tun_write) {
            status = "rx_written";
        } else if (row.has_send && row.has_recv) {
            status = "send_rx";
        } else if (row.has_send) {
            status = "send_only";
        } else if (row.has_recv) {
            status = "rx_only";
        }

        const double rel_ms = static_cast<double>(anchor_ns - base_ns) / 1000000.0;
        std::snprintf(line,
                      sizeof(line),
                      "%u,%llu,%s,%u,%08x,%s,%u,%u,%u,%.3f,%lld,%lld,%lld,%lld,%lld,%lld,%lld,%lld,%lld,%lld,%lld,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%llu,%s\n",
                      row.key.sender_id,
                      static_cast<unsigned long long>(row.key.seq),
                      dir,
                      static_cast<unsigned>(row.ip_version),
                      row.flow_hash,
                      udp_trace_flags_to_string(row.flags).c_str(),
                      static_cast<unsigned>(row.length),
                      static_cast<unsigned>(row.source_port),
                      static_cast<unsigned>(row.dest_port),
                      rel_ms,
                      static_cast<long long>(sender_delta_us),
                      static_cast<long long>(tx_tun_to_classify_us),
                      static_cast<long long>(tx_classify_to_send_us),
                      static_cast<long long>(tx_user_to_sched_us),
                      static_cast<long long>(tx_user_to_soft_us),
                      static_cast<long long>(tx_sched_to_soft_us),
                      static_cast<long long>(rx_kernel_delta_us),
                      static_cast<long long>(rx_kernel_to_user_us),
                      static_cast<long long>(rx_user_to_enqueue_us),
                      static_cast<long long>(rx_enqueue_to_write_us),
                      static_cast<long long>(rx_user_to_tun_write_us),
                      static_cast<unsigned long long>(row.tun_read_ns),
                      static_cast<unsigned long long>(row.classified_ns),
                      static_cast<unsigned long long>(row.send_call_ns),
                      static_cast<unsigned long long>(row.sender_t0_ns),
                      static_cast<unsigned long long>(row.sender_s1_ns),
                      static_cast<unsigned long long>(row.sender_send_attempt_ns),
                      static_cast<unsigned long long>(row.kernel_rx_rt_ns),
                      static_cast<unsigned long long>(row.recv_user_ns),
                      static_cast<unsigned long long>(row.inject_ns),
                      static_cast<unsigned long long>(row.tun_write_ns),
                      status);
        out += line;
    }

    return out;
}

std::string ipv4_to_string(const std::array<uint8_t, 4> &ip) {
    char buf[INET_ADDRSTRLEN] = {0};
    if (inet_ntop(AF_INET, ip.data(), buf, sizeof(buf)) == nullptr) return "0.0.0.0";
    return std::string(buf);
}

std::string ipv6_to_string(const std::array<uint8_t, 16> &ip) {
    char buf[INET6_ADDRSTRLEN] = {0};
    if (inet_ntop(AF_INET6, ip.data(), buf, sizeof(buf)) == nullptr) return "::";
    return std::string(buf);
}

uint32_t pack_ipv4_be(const std::array<uint8_t, 4> &ip) {
    return (static_cast<uint32_t>(ip[0]) << 24u) |
           (static_cast<uint32_t>(ip[1]) << 16u) |
           (static_cast<uint32_t>(ip[2]) << 8u) |
           static_cast<uint32_t>(ip[3]);
}

std::array<uint8_t, 4> unpack_ipv4_be(uint32_t packed) {
    return {
            static_cast<uint8_t>((packed >> 24u) & 0xFFu),
            static_cast<uint8_t>((packed >> 16u) & 0xFFu),
            static_cast<uint8_t>((packed >> 8u) & 0xFFu),
            static_cast<uint8_t>(packed & 0xFFu),
    };
}

bool try_promote_thread_priority(JNIEnv *env,
                                 BackendState *state,
                                 const char *thread_name,
                                 bool register_hot_thread = true) {
    if (env == nullptr) return false;
    jclass process_class = env->FindClass("android/os/Process");
    if (process_class == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    jmethodID my_tid_mid = env->GetStaticMethodID(process_class, "myTid", "()I");
    jmethodID set_priority_mid = env->GetStaticMethodID(process_class, "setThreadPriority", "(II)V");
    // URGENT_DISPLAY (-8) gives the four small packet loops priority without
    // pretending they are audio callbacks. URGENT_AUDIO (-19) across six
    // threads increased priority-inversion/starvation risk under load.
    jfieldID priority_fid = env->GetStaticFieldID(process_class, "THREAD_PRIORITY_URGENT_DISPLAY", "I");
    if (my_tid_mid == nullptr || set_priority_mid == nullptr || priority_fid == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->DeleteLocalRef(process_class);
        return false;
    }
    const jint tid = env->CallStaticIntMethod(process_class, my_tid_mid);
    const jint priority = env->GetStaticIntField(process_class, priority_fid);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(process_class);
        return false;
    }
    if (register_hot_thread && state != nullptr) {
        std::lock_guard<std::mutex> lk(state->hot_tids_mutex);
        state->hot_tids.push_back(static_cast<int>(tid));
    }
    env->CallStaticVoidMethod(process_class, set_priority_mid, tid, priority);
    const bool ok = !env->ExceptionCheck();
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    env->DeleteLocalRef(process_class);
    if (!ok) {
        LOGW("Failed to promote thread priority for %s", thread_name != nullptr ? thread_name : "native");
    }
    return ok;
}

// ─── SoftAP-host egress fix (root-free) ──────────────────────────────────
// Find a local IPv4 on the same subnet as the peer. On the hotspot HOST,
// Android never publishes the ap0/wlan1 interface as a ConnectivityManager
// Network, so the JVM Network.bindSocket() path fails and the peer socket
// leaks out the default (cellular) route → "UDP-OUT Error: null". Binding the
// socket's source IP to the matching local address + pinning IP_UNICAST_IF
// forces egress out the hotspot interface via the main route table — no root.
bool find_local_ipv4_for_peer(const std::array<uint8_t, 4> &peer_ip,
                              std::array<uint8_t, 4> &out_src_ip,
                              unsigned int &out_if_index) {
    out_if_index = 0;
    struct ifaddrs *ifaddr = nullptr;
    if (getifaddrs(&ifaddr) != 0 || ifaddr == nullptr) return false;
    const uint32_t peer = (static_cast<uint32_t>(peer_ip[0]) << 24) |
                          (static_cast<uint32_t>(peer_ip[1]) << 16) |
                          (static_cast<uint32_t>(peer_ip[2]) << 8) |
                          static_cast<uint32_t>(peer_ip[3]);
    bool found = false;
    for (struct ifaddrs *ifa = ifaddr; ifa != nullptr; ifa = ifa->ifa_next) {
        if (ifa->ifa_addr == nullptr || ifa->ifa_netmask == nullptr) continue;
        if (ifa->ifa_addr->sa_family != AF_INET) continue;
        if ((ifa->ifa_flags & IFF_UP) == 0) continue;
        if ((ifa->ifa_flags & IFF_LOOPBACK) != 0) continue;
        const uint32_t addr = ntohl(reinterpret_cast<sockaddr_in *>(ifa->ifa_addr)->sin_addr.s_addr);
        const uint32_t mask = ntohl(reinterpret_cast<sockaddr_in *>(ifa->ifa_netmask)->sin_addr.s_addr);
        if (mask == 0) continue;
        if ((addr & mask) == (peer & mask)) {
            out_src_ip[0] = static_cast<uint8_t>((addr >> 24) & 0xFFu);
            out_src_ip[1] = static_cast<uint8_t>((addr >> 16) & 0xFFu);
            out_src_ip[2] = static_cast<uint8_t>((addr >> 8) & 0xFFu);
            out_src_ip[3] = static_cast<uint8_t>(addr & 0xFFu);
            out_if_index = if_nametoindex(ifa->ifa_name);
            found = true;
            break;
        }
    }
    freeifaddrs(ifaddr);
    return found;
}

// Pin a socket's egress interface without root using IP_UNICAST_IF. For IPv4
// the kernel applies ntohl() to the value, so the index is passed in network
// byte order. Best-effort: silent no-op if the option is unsupported.
void pin_socket_egress_interface(int fd, unsigned int if_index) {
#ifdef IP_UNICAST_IF
    if (if_index == 0) return;
    uint32_t idx_be = htonl(static_cast<uint32_t>(if_index));
    (void) setsockopt(fd, IPPROTO_IP, IP_UNICAST_IF, &idx_be, sizeof(idx_be));
#else
    (void) fd;
    (void) if_index;
#endif
}

void deliver_native_log(JNIEnv *env, BackendState *state, int level, bool file_only, const std::string &message) {
    if (message.empty()) return;
    if (!file_only || level >= kNativeLogWarn) {
        switch (level) {
            case kNativeLogError:
                LOGE("%s", message.c_str());
                break;
            case kNativeLogWarn:
                LOGW("%s", message.c_str());
                break;
            default:
                LOGI("%s", message.c_str());
                break;
        }
    }
    if (env == nullptr || state == nullptr || state->callbacks == nullptr || state->native_log_mid == nullptr) return;
    jstring message_j = env->NewStringUTF(message.c_str());
    if (message_j == nullptr) return;
    env->CallVoidMethod(state->callbacks, state->native_log_mid, static_cast<jint>(level), message_j, file_only ? JNI_TRUE : JNI_FALSE);
    env->DeleteLocalRef(message_j);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
}

bool enqueue_deferred_log(BackendState *state, int level, bool file_only, const std::string &message) {
    if (state == nullptr || message.empty()) return false;
    std::unique_lock<std::mutex> lock(state->file_log_mutex, std::try_to_lock);
    if (!lock.owns_lock() || state->deferred_file_logs.size() >= kMaxDeferredFileLogs) {
        state->deferred_file_logs_dropped.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    state->deferred_file_logs.push_back(DeferredNativeLog{level, file_only, message});
    state->file_log_cv.notify_one();
    return true;
}

void emit_native_log(JNIEnv *env, BackendState *state, int level, bool file_only, const std::string &message) {
    // During an active session every JNI/logcat callback is handled by the
    // dedicated logger. In particular, a severe RX-gap warning must not pause
    // the RX drain at exactly the moment packets are already queued in-kernel.
    if (state != nullptr && state->running.load(std::memory_order_acquire)) {
        (void) enqueue_deferred_log(state, level, file_only, message);
        return;
    }
    deliver_native_log(env, state, level, file_only, message);
}

void maybe_log_gap(JNIEnv *env,
                   BackendState *state,
                   const char *tag,
                   uint64_t now_ns,
                   uint64_t &last_event_ns,
                   uint64_t &last_log_ms,
                   uint64_t &last_sample_ms,
                   long long seq,
                   size_t length,
                   const char *extra = nullptr) {
    if (!kPacketDiagnosticsEnabled) {
        (void) env; (void) state; (void) tag; (void) now_ns; (void) last_event_ns;
        (void) last_log_ms; (void) last_sample_ms; (void) seq; (void) length; (void) extra;
        return;
    }
    if (last_event_ns != 0) {
        const uint64_t gap_ms = (now_ns - last_event_ns) / 1000000ULL;
        const uint64_t now_ms = now_ns / 1000000ULL;
        last_log_ms = now_ms;
        if (gap_ms >= 1) {
            const bool is_severe = (gap_ms >= kGapLogSevereMs);
            const bool is_notice = (gap_ms >= kGapLogNoticeMs);
            // Severe (>= kGapLogSevereMs): warn + UI. Notable (>= kGapLogNoticeMs):
            // always file-logged — these are the felt in-match micro-stutters we
            // want to keep capturing. Routine inter-packet spacing (the steady
            // ~37 ms cadence) is sampled at most once per tap per kGapLogSampleMs:
            // logging EVERY packet was itself adding jitter (per-packet JNI upcall +
            // file_log_mutex) and ballooned the log to 8 MB for a single match.
            bool should_log = is_notice;
            if (!should_log && (now_ms - last_sample_ms) >= kGapLogSampleMs) {
                last_sample_ms = now_ms;
                should_log = true;
            }
            if (should_log) {
                char msg[512];
                if (extra != nullptr && extra[0] != '\0') {
                    std::snprintf(msg, sizeof(msg), "[%s] gapMs=%llu seq=%lld len=%zu %s",
                                  tag,
                                  static_cast<unsigned long long>(gap_ms),
                                  seq,
                                  length,
                                  extra);
                } else {
                    std::snprintf(msg, sizeof(msg), "[%s] gapMs=%llu seq=%lld len=%zu",
                                  tag,
                                  static_cast<unsigned long long>(gap_ms),
                                  seq,
                                  length);
                }
                emit_native_log(env, state,
                                is_severe ? kNativeLogWarn : kNativeLogInfo,
                                !is_severe,   // file_only for non-severe gaps
                                msg);
            }
        }
    }
    last_event_ns = now_ns;
}

bool mark_first_seen_flow(BackendState *state, uint32_t flow_hash) {
    // This is only duplicate suppression for diagnostics, so a harmless hash
    // collision may suppress one flow log. It must never justify a datapath
    // mutex. The bitset is reset with each BackendState/session.
    uint32_t mixed = flow_hash ^ (flow_hash >> 16u) ^ (flow_hash * 0x9E3779B9u);
    const size_t bit = static_cast<size_t>(mixed) & (kFlowLogBitCount - 1u);
    const size_t word = bit / 64u;
    const uint64_t mask = 1ULL << (bit % 64u);
    const uint64_t previous = state->first_seen_flow_bits[word].fetch_or(mask, std::memory_order_relaxed);
    return (previous & mask) == 0;
}

void maybe_log_flow_ipv4(JNIEnv *env,
                         BackendState *state,
                         uint32_t flow_hash,
                         const std::array<uint8_t, 4> &src_ip,
                         uint16_t src_port,
                         const std::array<uint8_t, 4> &dst_ip,
                         uint16_t dst_port,
                         const char *action,
                         const char *reason,
                         bool file_only = true) {
    if (!kPacketDiagnosticsEnabled) return;
    if (!mark_first_seen_flow(state, flow_hash)) return;
    char msg[512];
    std::snprintf(msg, sizeof(msg), "[NATIVE/FLOW] src=%s:%u dst=%s:%u action=%s reason=%s",
                  ipv4_to_string(src_ip).c_str(),
                  static_cast<unsigned int>(src_port),
                  ipv4_to_string(dst_ip).c_str(),
                  static_cast<unsigned int>(dst_port),
                  action,
                  reason);
    emit_native_log(env, state, kNativeLogInfo, file_only, msg);
}

void maybe_log_flow_ipv6(JNIEnv *env,
                         BackendState *state,
                         uint32_t flow_hash,
                         const std::array<uint8_t, 16> &src_ip,
                         uint16_t src_port,
                         const std::array<uint8_t, 16> &dst_ip,
                         uint16_t dst_port,
                         const char *action,
                         const char *reason,
                         bool file_only = true) {
    if (!kPacketDiagnosticsEnabled) return;
    if (!mark_first_seen_flow(state, flow_hash)) return;
    char msg[640];
    std::snprintf(msg, sizeof(msg), "[NATIVE/FLOW] src=%s:%u dst=%s:%u action=%s reason=%s",
                  ipv6_to_string(src_ip).c_str(),
                  static_cast<unsigned int>(src_port),
                  ipv6_to_string(dst_ip).c_str(),
                  static_cast<unsigned int>(dst_port),
                  action,
                  reason);
    emit_native_log(env, state, kNativeLogInfo, file_only, msg);
}

void maybe_log_action_summary(JNIEnv *env, BackendState *state, bool force = false) {
    if (!kPacketDiagnosticsEnabled) return;
    const uint64_t now_ms = monotonic_ms();
    if (!force) {
        uint64_t due = state->next_summary_log_ms.load(std::memory_order_relaxed);
        if (due != 0 && now_ms < due) return;
        const uint64_t next = now_ms + kSummaryLogIntervalMs;
        if (!state->next_summary_log_ms.compare_exchange_strong(
                    due, next, std::memory_order_acq_rel, std::memory_order_relaxed)) {
            return;
        }
    }
    // Only one CAS winner reaches this lock every five seconds. Hot packet
    // paths return above without contending with each other.
    std::lock_guard<std::mutex> lock(state->summary_mutex);

    const long long tunnel_out = state->stats.tunnel_out_packets.load(std::memory_order_relaxed);
    const long long tunnel_in = state->stats.tunnel_in_packets.load(std::memory_order_relaxed);
    const long long stun_intercepted =
            state->stats.stun_intercepted_ipv4.load(std::memory_order_relaxed) +
            state->stats.stun_intercepted_ipv6.load(std::memory_order_relaxed);
    const long long bridged_to_jvm = state->stats.passthrough_to_jvm_packets.load(std::memory_order_relaxed);
    const long long injected_from_peer = state->stats.passthrough_to_tun_packets.load(std::memory_order_relaxed) + tunnel_in;
    const long long dropped = state->stats.dropped_packets.load(std::memory_order_relaxed);
    const long long drop_peer_tx = state->stats.dropped_peer_tx.load(std::memory_order_relaxed);
    const long long drop_bridge_tx = state->stats.dropped_bridge_tx.load(std::memory_order_relaxed);
    const long long drop_tun_inject = state->stats.dropped_tun_inject.load(std::memory_order_relaxed);
    const long long drop_parse = state->stats.dropped_parse_or_policy.load(std::memory_order_relaxed);
    const long long peer_events = state->stats.peer_socket_events.load(std::memory_order_relaxed);

    const long long delta_tunnel_out = tunnel_out - state->last_summary_tunnel_out_packets;
    const long long delta_tunnel_in = tunnel_in - state->last_summary_tunnel_in_packets;
    const long long delta_stun = stun_intercepted - state->last_summary_stun_intercepted;
    const long long delta_bridged = bridged_to_jvm - state->last_summary_bridged_to_jvm_packets;
    const long long delta_injected = injected_from_peer - state->last_summary_injected_from_peer_packets;
    const long long delta_dropped = dropped - state->last_summary_dropped_packets;

    state->last_summary_log_ms = now_ms;
    state->last_summary_tunnel_out_packets = tunnel_out;
    state->last_summary_tunnel_in_packets = tunnel_in;
    state->last_summary_stun_intercepted = stun_intercepted;
    state->last_summary_bridged_to_jvm_packets = bridged_to_jvm;
    state->last_summary_injected_from_peer_packets = injected_from_peer;
    state->last_summary_dropped_packets = dropped;

    if (!force && delta_tunnel_out == 0 && delta_tunnel_in == 0 && delta_stun == 0 && delta_bridged == 0 && delta_injected == 0 && delta_dropped == 0) {
        return;
    }

    char msg[1024];
    std::snprintf(msg,
                  sizeof(msg),
                  "[NATIVE/STATS] delta{tunOut=%lld injectPeer=%lld stun=%lld bridgedJvm=%lld dropped=%lld} total{tunOut=%lld injectPeer=%lld stun=%lld bridgedJvm=%lld dropped=%lld} detail{peerTx=%lld bridgeTx=%lld tunInject=%lld parse=%lld peerEvt=%lld} stablePort=%d remotePort=%d forceAllUdp=%s",
                  delta_tunnel_out,
                  delta_injected,
                  delta_stun,
                  delta_bridged,
                  delta_dropped,
                  tunnel_out,
                  injected_from_peer,
                  stun_intercepted,
                  bridged_to_jvm,
                  dropped,
                  drop_peer_tx,
                  drop_bridge_tx,
                  drop_tun_inject,
                  drop_parse,
                  peer_events,
                  stable_gameplay_known(state) ? load_stable_game_port(state) : 0,
                  stable_gameplay_known(state) ? load_stable_remote_port(state) : 0,
                  state->all_game_udp_tunnel_mode_active ? "true" : "false");
    emit_native_log(env, state, kNativeLogInfo, true, msg);
}

void close_fd(int &fd) {
    if (fd >= 0) {
        close(fd);
        fd = -1;
    }
}

bool set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags < 0) return false;
    if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) return false;
    return true;
}

uint16_t read_u16(const uint8_t *data, size_t off) {
    return static_cast<uint16_t>((static_cast<uint16_t>(data[off]) << 8) | static_cast<uint16_t>(data[off + 1]));
}

uint32_t read_u32(const uint8_t *data, size_t off) {
    return (static_cast<uint32_t>(data[off]) << 24) |
           (static_cast<uint32_t>(data[off + 1]) << 16) |
           (static_cast<uint32_t>(data[off + 2]) << 8) |
           static_cast<uint32_t>(data[off + 3]);
}

uint64_t read_u64(const uint8_t *data, size_t off) {
    return (static_cast<uint64_t>(read_u32(data, off)) << 32) | static_cast<uint64_t>(read_u32(data, off + 4));
}

void write_u16(uint8_t *data, size_t off, uint16_t value) {
    data[off] = static_cast<uint8_t>((value >> 8) & 0xFFu);
    data[off + 1] = static_cast<uint8_t>(value & 0xFFu);
}

void write_u32(uint8_t *data, size_t off, uint32_t value) {
    data[off] = static_cast<uint8_t>((value >> 24) & 0xFFu);
    data[off + 1] = static_cast<uint8_t>((value >> 16) & 0xFFu);
    data[off + 2] = static_cast<uint8_t>((value >> 8) & 0xFFu);
    data[off + 3] = static_cast<uint8_t>(value & 0xFFu);
}

void write_u64(uint8_t *data, size_t off, uint64_t value) {
    write_u32(data, off, static_cast<uint32_t>(value >> 32));
    write_u32(data, off + 4, static_cast<uint32_t>(value & 0xFFFFFFFFULL));
}

bool arrays_equal4(const std::array<uint8_t, 4> &a, const std::array<uint8_t, 4> &b) {
    return a[0] == b[0] && a[1] == b[1] && a[2] == b[2] && a[3] == b[3];
}

bool detect_stun_magic(const uint8_t *data, size_t length, size_t offset, size_t payload_length) {
    if (payload_length < 20 || offset + 8 > length) return false;
    return read_u32(data, offset + 4) == kStunMagicCookie;
}

bool is_private_ipv4(const std::array<uint8_t, 4> &ip) {
    const int b0 = ip[0];
    const int b1 = ip[1];
    const int b2 = ip[2];
    const int b3 = ip[3];
    if (b0 == 10) return true;
    if (b0 == 192 && b1 == 168) return true;
    if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
    if (b0 == 127) return true;
    if (b0 == 169 && b1 == 254) return true;
    if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
    if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 0) return true;
    if (b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255) return true;
    return false;
}

bool parse_ipv4(const uint8_t *data, size_t length, ParsedIpv4 &out) {
    out = ParsedIpv4{};
    if (length < 20) return false;
    const uint8_t version_and_ihl = data[0];
    const uint8_t version = version_and_ihl >> 4;
    const uint16_t ihl = static_cast<uint16_t>(version_and_ihl & 0x0F) * 4u;
    if (version != 4 || ihl < 20 || length < ihl) return false;

    out.total_length = read_u16(data, 2);
    if (out.total_length < ihl || out.total_length > length) return false;
    out.packet_length = out.total_length;
    out.header_length = ihl;
    out.protocol = data[9];
    std::memcpy(out.source_ip.data(), data + 12, 4);
    std::memcpy(out.dest_ip.data(), data + 16, 4);
    out.fragmented = (read_u16(data, 6) & 0x3FFFu) != 0;
    // Non-initial fragments do not contain UDP ports; initial fragments do
    // not contain the whole UDP datagram. Neither may seed a socket mapping.
    if (out.fragmented) { out.valid = true; return true; }

    if (out.protocol == kProtocolUdp && out.packet_length < static_cast<size_t>(ihl) + 8u) return false;

    if ((out.protocol == kProtocolUdp || out.protocol == kProtocolTcp) && out.packet_length >= static_cast<size_t>(ihl) + 4u) {
        out.source_port = read_u16(data, ihl);
        out.dest_port = read_u16(data, ihl + 2);
    }
    if (out.protocol == kProtocolUdp && out.packet_length >= static_cast<size_t>(ihl) + 8u) {
        const uint16_t udp_length = read_u16(data, ihl + 4);
        out.udp_payload_offset = ihl + 8;
        if (udp_length < 8 || static_cast<size_t>(ihl) + udp_length > out.packet_length) return false;
        out.udp_payload_length = udp_length - 8;
        out.is_stun = detect_stun_magic(data, out.packet_length, out.udp_payload_offset, out.udp_payload_length);
        out.is_stun_binding_request = out.is_stun && read_u16(data, out.udp_payload_offset) == 0x0001;
    }
    out.valid = true;
    return true;
}

bool parse_ipv6(const uint8_t *data, size_t length, ParsedIpv6 &out) {
    out = ParsedIpv6{};
    if (length < 40) return false;
    const uint8_t version = data[0] >> 4;
    if (version != 6) return false;

    const uint16_t payload_length = read_u16(data, 4);
    if (static_cast<size_t>(40u + payload_length) > length) return false;
    out.packet_length = 40u + payload_length;
    out.next_header = data[6];
    std::memcpy(out.source_ip.data(), data + 8, 16);
    std::memcpy(out.dest_ip.data(), data + 24, 16);

    if (out.next_header == kProtocolUdp && payload_length < 8) return false;

    if (out.next_header == kProtocolUdp && out.packet_length >= 48) {
        out.source_port = read_u16(data, 40);
        out.dest_port = read_u16(data, 42);
        out.udp_payload_offset = 48;
        const uint16_t udp_length = read_u16(data, 44);
        if (udp_length < 8 || 40u + udp_length > out.packet_length) return false;
        out.udp_payload_length = udp_length - 8;
        out.is_stun = detect_stun_magic(data, out.packet_length, out.udp_payload_offset, out.udp_payload_length);
        out.is_stun_binding_request = out.is_stun && read_u16(data, out.udp_payload_offset) == 0x0001;
    }
    out.valid = true;
    return true;
}

bool is_all_zero16(const std::array<uint8_t, 16> &addr) {
    for (uint8_t b : addr) {
        if (b != 0) return false;
    }
    return true;
}

bool is_loopback_ipv6(const std::array<uint8_t, 16> &addr) {
    for (size_t i = 0; i < 15; ++i) {
        if (addr[i] != 0) return false;
    }
    return addr[15] == 1;
}

bool is_link_local_ipv6(const std::array<uint8_t, 16> &addr) {
    return addr[0] == 0xFE && (addr[1] & 0xC0u) == 0x80u;
}

bool is_ipv4_mapped_equal(const std::array<uint8_t, 16> &ipv6, const std::array<uint8_t, 4> &ipv4) {
    for (size_t i = 0; i < 10; ++i) {
        if (ipv6[i] != 0) return false;
    }
    if (ipv6[10] != 0xFF || ipv6[11] != 0xFF) return false;
    return ipv6[12] == ipv4[0] && ipv6[13] == ipv4[1] && ipv6[14] == ipv4[2] && ipv6[15] == ipv4[3];
}

std::array<uint8_t, 16> ipv4_mapped_address(const std::array<uint8_t, 4> &ipv4) {
    std::array<uint8_t, 16> address{};
    address[10] = 0xFF;
    address[11] = 0xFF;
    std::copy(ipv4.begin(), ipv4.end(), address.begin() + 12);
    return address;
}

bool stable_gameplay_known(BackendState *state) {
    return state->has_stable_game_port.load(std::memory_order_acquire);
}

int load_stable_game_port(BackendState *state) {
    return state->stable_game_port.load(std::memory_order_acquire);
}

int load_stable_remote_port(BackendState *state) {
    return state->stable_remote_port.load(std::memory_order_acquire);
}

uint64_t gameplay_endpoint_key(size_t address_size, uint16_t local_port, uint16_t remote_port) {
    return (static_cast<uint64_t>(address_size == 4 ? 4 : 6) << 32u) |
           (static_cast<uint64_t>(local_port) << 16u) | remote_port;
}

bool endpoint_expired(const GameplayEndpoint &endpoint, uint64_t now_ms) {
    return now_ms >= endpoint.last_outbound_ms &&
           now_ms - endpoint.last_outbound_ms >= kGameplayEndpointTtlMs;
}

template <size_t N>
void remember_gameplay_endpoint(BackendState *state,
                                const std::array<uint8_t, N> &local_ip,
                                uint16_t local_port,
                                const std::array<uint8_t, N> &peer_ip,
                                uint16_t remote_port,
                                uint64_t now_ms) {
    if (local_port == 0) return;
    const uint64_t key = gameplay_endpoint_key(N, local_port, remote_port);
    std::lock_guard<std::mutex> lock(state->flow_mutex);
    auto &endpoints = state->gameplay_endpoints;
    if (remote_port == 0) {
        // A new local address on the same advertised socket invalidates old
        // exact-flow entries for that socket, not entries for other ports.
        const uint64_t socket_key = key >> 16u;
        for (auto it = endpoints.begin(); it != endpoints.end();) {
            if ((it->first >> 16u) == socket_key &&
                !std::equal(local_ip.begin(), local_ip.end(), it->second.local_ip.begin())) {
                it = endpoints.erase(it);
            } else ++it;
        }
    }
    if (now_ms - state->last_endpoint_cleanup_ms >= 1000 || endpoints.size() >= kMaxGameplayEndpoints) {
        for (auto it = endpoints.begin(); it != endpoints.end();) {
            if (endpoint_expired(it->second, now_ms)) it = endpoints.erase(it);
            else ++it;
        }
        state->last_endpoint_cleanup_ms = now_ms;
    }
    if (endpoints.find(key) == endpoints.end() && endpoints.size() >= kMaxGameplayEndpoints) {
        const auto oldest = std::min_element(endpoints.begin(), endpoints.end(),
                [](const auto &a, const auto &b) { return a.second.last_outbound_ms < b.second.last_outbound_ms; });
        endpoints.erase(oldest);
    }
    auto &endpoint = endpoints[key];
    std::copy(local_ip.begin(), local_ip.end(), endpoint.local_ip.begin());
    std::copy(peer_ip.begin(), peer_ip.end(), endpoint.peer_ip.begin());
    endpoint.last_outbound_ms = now_ms;
}

template <size_t N>
void resolve_gameplay_addresses(BackendState *state, uint16_t local_port, uint16_t remote_port,
                                uint64_t now_ms, std::array<uint8_t, N> &local_ip,
                                std::array<uint8_t, N> &peer_ip) {
    std::lock_guard<std::mutex> lock(state->flow_mutex);
    // Exact peer flow first, then that SAME local port's STUN binding. Never
    // borrow a port or address from another socket, IP family, or old session.
    for (const uint16_t candidate : {remote_port, uint16_t{0}}) {
        auto it = state->gameplay_endpoints.find(gameplay_endpoint_key(N, local_port, candidate));
        if (it == state->gameplay_endpoints.end()) continue;
        if (endpoint_expired(it->second, now_ms)) {
            state->gameplay_endpoints.erase(it);
            continue;
        }
        std::copy_n(it->second.local_ip.begin(), N, local_ip.begin());
        std::copy_n(it->second.peer_ip.begin(), N, peer_ip.begin());
        return; // Inbound packets must not keep an obsolete binding alive.
    }
}

bool should_intercept_ipv4_stun(const ParsedIpv4 &packet) {
    if (packet.protocol != kProtocolUdp) return false;
    if (is_private_ipv4(packet.dest_ip)) return false;
    if (packet.dest_port < kStunPortStart || packet.dest_port > kStunPortEnd) return false;
    return packet.is_stun_binding_request;
}

bool should_intercept_ipv6_stun(const ParsedIpv6 &packet) {
    if (packet.next_header != kProtocolUdp) return false;
    if (packet.dest_port < kStunPortStart || packet.dest_port > kStunPortEnd) return false;
    return packet.is_stun_binding_request;
}

bool should_tunnel_ipv6(BackendState *state, const std::array<uint8_t, 16> &dest_ip) {
    if (is_ipv4_mapped_equal(dest_ip, state->peer_fabricated_ip)) return true;
    return is_ipv4_mapped_equal(dest_ip, state->peer_lan_ip);
}

void observe_gameplay_flow(BackendState *state, int local_port, int remote_port, uint64_t now_ms) {
    if (local_port <= 0) return;
    // Diagnostic display only: routing uses the packet's addressed port and
    // the per-endpoint table, not these last-observed values.
    state->stable_game_port.store(local_port, std::memory_order_release);
    state->stable_remote_port.store(remote_port, std::memory_order_release);
    state->stable_game_port_last_seen_ms.store(now_ms, std::memory_order_release);
    state->has_stable_game_port.store(true, std::memory_order_release);
}


int resolve_inbound_gameplay_port(BackendState *, int peer_sent_port, int /*tunnel_flags*/, uint64_t /*now_ms*/) {
    // Fabricated STUN advertises the actual socket port. The peer's inner UDP
    // destination is therefore authoritative; rewriting it corrupts demux.
    return peer_sent_port;
}


bool should_exclude_from_force_tunnel(uint16_t source_port, uint16_t dest_port) {
    return source_port == 53 || dest_port == 53;
}

void note_gameplay_hint(BackendState *state, uint64_t now_ms) {
    (void)state;
    (void)now_ms;
}

void note_raw_gameplay_udp_for_force_mode(BackendState *state, uint64_t now_ms) {
    (void)state;
    (void)now_ms;
}

uint64_t current_keepalive_interval_ns(BackendState *state) {
    const uint64_t traffic_start_ms = state->game_traffic_start_ms.load(std::memory_order_acquire);
    if (traffic_start_ms == 0) return kKeepaliveIntervalNs;
    const uint64_t elapsed = monotonic_ms() - traffic_start_ms;
    if (elapsed < 30000ULL) return kKeepaliveFastNs;
    if (elapsed < 90000ULL) return kKeepaliveWarmNs;
    return kKeepaliveIntervalNs;
}

uint16_t checksum16(const uint8_t *data, size_t length) {
    uint32_t sum = 0;
    for (size_t i = 0; i + 1 < length; i += 2) {
        sum += static_cast<uint32_t>((data[i] << 8) | data[i + 1]);
    }
    if ((length & 1u) != 0u) {
        sum += static_cast<uint32_t>(data[length - 1] << 8);
    }
    while ((sum >> 16u) != 0u) {
        sum = (sum & 0xFFFFu) + (sum >> 16u);
    }
    const uint16_t result = static_cast<uint16_t>(~sum & 0xFFFFu);
    return result == 0 ? 0xFFFFu : result;
}

uint16_t udp_ipv4_checksum(const std::array<uint8_t, 4> &src_ip,
                           const std::array<uint8_t, 4> &dst_ip,
                           const uint8_t *packet,
                           size_t udp_offset,
                           size_t udp_length) {
    uint64_t sum = 0;
    sum += static_cast<uint16_t>((src_ip[0] << 8) | src_ip[1]);
    sum += static_cast<uint16_t>((src_ip[2] << 8) | src_ip[3]);
    sum += static_cast<uint16_t>((dst_ip[0] << 8) | dst_ip[1]);
    sum += static_cast<uint16_t>((dst_ip[2] << 8) | dst_ip[3]);
    sum += kProtocolUdp;
    sum += udp_length;
    for (size_t i = 0; i + 1 < udp_length; i += 2) {
        sum += static_cast<uint16_t>((packet[udp_offset + i] << 8) | packet[udp_offset + i + 1]);
    }
    if ((udp_length & 1u) != 0u) {
        sum += static_cast<uint16_t>(packet[udp_offset + udp_length - 1] << 8);
    }
    while ((sum >> 16u) != 0u) {
        sum = (sum & 0xFFFFu) + (sum >> 16u);
    }
    const uint16_t result = static_cast<uint16_t>(~sum & 0xFFFFu);
    return result == 0 ? 0xFFFFu : result;
}

uint16_t udp_ipv6_checksum(const uint8_t *packet, size_t udp_offset, size_t udp_length) {
    uint64_t sum = 0;
    for (size_t i = 8; i <= 22; i += 2) {
        sum += static_cast<uint16_t>((packet[i] << 8) | packet[i + 1]);
    }
    for (size_t i = 24; i <= 38; i += 2) {
        sum += static_cast<uint16_t>((packet[i] << 8) | packet[i + 1]);
    }
    sum += udp_length;
    sum += kProtocolUdp;
    for (size_t i = 0; i + 1 < udp_length; i += 2) {
        sum += static_cast<uint16_t>((packet[udp_offset + i] << 8) | packet[udp_offset + i + 1]);
    }
    if ((udp_length & 1u) != 0u) {
        sum += static_cast<uint16_t>(packet[udp_offset + udp_length - 1] << 8);
    }
    while ((sum >> 16u) != 0u) {
        sum = (sum & 0xFFFFu) + (sum >> 16u);
    }
    const uint16_t result = static_cast<uint16_t>(~sum & 0xFFFFu);
    return result == 0 ? 0xFFFFu : result;
}

std::vector<uint8_t> build_udp_ipv4_packet(const std::array<uint8_t, 4> &src_ip,
                                           const std::array<uint8_t, 4> &dst_ip,
                                           uint16_t src_port,
                                           uint16_t dst_port,
                                           const uint8_t *payload,
                                           size_t payload_length) {
    const size_t udp_length = 8 + payload_length;
    const size_t total_length = 20 + udp_length;
    std::vector<uint8_t> packet(total_length, 0);
    packet[0] = 0x45;
    packet[8] = 0x40;
    packet[9] = kProtocolUdp;
    write_u16(packet.data(), 2, static_cast<uint16_t>(total_length));
    std::memcpy(packet.data() + 12, src_ip.data(), 4);
    std::memcpy(packet.data() + 16, dst_ip.data(), 4);

    const size_t udp_offset = 20;
    write_u16(packet.data(), udp_offset, src_port);
    write_u16(packet.data(), udp_offset + 2, dst_port);
    write_u16(packet.data(), udp_offset + 4, static_cast<uint16_t>(udp_length));
    write_u16(packet.data(), udp_offset + 6, 0);
    if (payload_length > 0) {
        std::memcpy(packet.data() + udp_offset + 8, payload, payload_length);
    }

    const uint16_t ip_checksum = checksum16(packet.data(), 20);
    write_u16(packet.data(), 10, ip_checksum);
    const uint16_t udp_checksum = udp_ipv4_checksum(src_ip, dst_ip, packet.data(), udp_offset, udp_length);
    write_u16(packet.data(), udp_offset + 6, udp_checksum);
    return packet;
}

std::vector<uint8_t> build_udp_ipv6_packet(const std::array<uint8_t, 16> &src_ip,
                                           const std::array<uint8_t, 16> &dst_ip,
                                           uint16_t src_port,
                                           uint16_t dst_port,
                                           const uint8_t *payload,
                                           size_t payload_length) {
    const size_t udp_length = 8 + payload_length;
    const size_t total_length = 40 + udp_length;
    std::vector<uint8_t> packet(total_length, 0);
    packet[0] = 0x60;
    write_u16(packet.data(), 4, static_cast<uint16_t>(udp_length));
    packet[6] = kProtocolUdp;
    packet[7] = 64;
    std::memcpy(packet.data() + 8, src_ip.data(), 16);
    std::memcpy(packet.data() + 24, dst_ip.data(), 16);

    const size_t udp_offset = 40;
    write_u16(packet.data(), udp_offset, src_port);
    write_u16(packet.data(), udp_offset + 2, dst_port);
    write_u16(packet.data(), udp_offset + 4, static_cast<uint16_t>(udp_length));
    write_u16(packet.data(), udp_offset + 6, 0);
    if (payload_length > 0) {
        std::memcpy(packet.data() + udp_offset + 8, payload, payload_length);
    }

    const uint16_t udp_checksum = udp_ipv6_checksum(packet.data(), udp_offset, udp_length);
    write_u16(packet.data(), udp_offset + 6, udp_checksum);
    return packet;
}

uint32_t flow_hash_bytes(const uint8_t *src_ip,
                         size_t src_len,
                         const uint8_t *dst_ip,
                         size_t dst_len,
                         uint8_t protocol,
                         uint16_t src_port,
                         uint16_t dst_port) {
    uint32_t hash = 2166136261u;
    auto mix = [&hash](uint8_t b) {
        hash ^= b;
        hash *= 16777619u;
    };
    mix(protocol);
    for (size_t i = 0; i < src_len; ++i) mix(src_ip[i]);
    for (size_t i = 0; i < dst_len; ++i) mix(dst_ip[i]);
    mix(static_cast<uint8_t>((src_port >> 8) & 0xFFu));
    mix(static_cast<uint8_t>(src_port & 0xFFu));
    mix(static_cast<uint8_t>((dst_port >> 8) & 0xFFu));
    mix(static_cast<uint8_t>(dst_port & 0xFFu));
    return hash;
}

enum class SendPolicy {
    kImmediateDrop,
    kShortRetry,
};

bool wait_writable(int fd, int timeout_ms) {
    pollfd pfd{};
    pfd.fd = fd;
    pfd.events = POLLOUT;
    while (true) {
        const int poll_rc = poll(&pfd, 1, timeout_ms);
        if (poll_rc < 0 && errno == EINTR) continue;
        return poll_rc > 0 && (pfd.revents & POLLOUT) != 0;
    }
}

bool write_frame_bounded(int fd, const uint8_t *data, size_t length, int *error_out = nullptr) {
    constexpr int kWritePollTimeoutMs = 1;
    constexpr int kWriteRetries = 2;
    int attempts = 0;
    while (true) {
        const ssize_t rc = write(fd, data, length);
        if (rc < 0) {
            const int write_errno = errno;
            if (write_errno == EINTR) continue;
            if ((write_errno == EAGAIN || write_errno == EWOULDBLOCK) &&
                attempts++ < kWriteRetries && wait_writable(fd, kWritePollTimeoutMs)) continue;
            if (error_out != nullptr) *error_out = write_errno;
            errno = write_errno;
            return false;
        }
        if (static_cast<size_t>(rc) != length) {
            // TUN writes are packet-oriented. A second write would be a second,
            // invalid packet, rather than completing the original frame.
            if (error_out != nullptr) *error_out = EMSGSIZE;
            errno = EMSGSIZE;
            return false;
        }
        if (error_out != nullptr) *error_out = 0;
        return true;
    }
}

bool send_message(int fd,
                  const uint8_t *data,
                  size_t length,
                  const sockaddr_in *target = nullptr,
                  SendPolicy policy = SendPolicy::kShortRetry,
                  int *error_out = nullptr) {
    const int kSendPollTimeoutMs = (policy == SendPolicy::kImmediateDrop) ? 0 : 1;
    const int kSendRetries = (policy == SendPolicy::kImmediateDrop) ? 0 : 1;
    int attempts = 0;
    while (true) {
        ssize_t rc;
        if (target != nullptr) {
            rc = sendto(fd,
                        data,
                        length,
                        MSG_DONTWAIT | MSG_NOSIGNAL,
                        reinterpret_cast<const sockaddr *>(target),
                        sizeof(*target));
        } else {
            rc = send(fd, data, length, MSG_DONTWAIT | MSG_NOSIGNAL);
        }
        if (rc < 0) {
            const int send_errno = errno;
            if (send_errno == EINTR) {
                continue;
            }
            if ((send_errno == EAGAIN || send_errno == EWOULDBLOCK) && attempts < kSendRetries && wait_writable(fd, kSendPollTimeoutMs)) {
                ++attempts;
                continue;
            }
            if (error_out != nullptr) *error_out = send_errno;
            errno = send_errno;
            return false;
        }
        if (static_cast<size_t>(rc) == length) {
            if (error_out != nullptr) *error_out = 0;
            return true;
        }
        if (error_out != nullptr) *error_out = EMSGSIZE;
        errno = EMSGSIZE;
        return false;
    }
}

bool prepare_peer_socket_callback(JNIEnv *env, BackendState *state) {
    const int callback_fd = dup(state->peer_fd);
    if (callback_fd < 0) return false;
    const jboolean prepared = env->CallBooleanMethod(
            state->callbacks, state->prepare_peer_socket_mid, static_cast<jint>(callback_fd));
    const bool exception = env->ExceptionCheck();
    if (exception) env->ExceptionClear();
    close(callback_fd); // Kotlin borrows it via fromFd(), including on exceptions.
    return !exception && prepared == JNI_TRUE;
}

bool bind_peer_source(BackendState *state, int local_port) {
    if (!state->has_configured_local_lan_ip || local_port < 1 || local_port > 65535) {
        errno = EINVAL;
        return false;
    }
    sockaddr_in source{};
    source.sin_family = AF_INET;
    source.sin_port = htons(static_cast<uint16_t>(local_port));
    std::memcpy(&source.sin_addr, state->configured_local_lan_ip.data(), 4);
    // A vanished source address cannot silently become a wildcard/cellular bind.
    return bind(state->peer_fd, reinterpret_cast<const sockaddr *>(&source), sizeof(source)) == 0;
}

bool disconnect_udp_socket(int fd) {
    sockaddr sa{};
    sa.sa_family = AF_UNSPEC;
    return connect(fd, &sa, sizeof(sa)) == 0;
}

bool connect_udp_socket(int fd, const sockaddr_in &target) {
    return connect(fd, reinterpret_cast<const sockaddr *>(&target), sizeof(target)) == 0;
}

bool parse_tunnel_diag_meta(const uint8_t *data, size_t length, TunnelDiagMeta &meta) {
    if (length < kTunnelDiagHeaderSize) return false;
    const uint32_t magic = read_u32(data, 0);
    if (magic != kTunnelDiagMagic) return false;
    const uint8_t version = data[4];
    if (version != kTunnelDiagVersion) return false;
    const uint16_t header_size = read_u16(data, 6);
    if (header_size < kTunnelDiagHeaderSize || header_size > length) return false;
    const uint32_t payload_length = read_u32(data, 48);
    if (payload_length > length - header_size) return false;
    meta.valid = true;
    meta.flags = data[5] & 0xFF;
    meta.header_size = header_size;
    meta.sender_id = static_cast<int>(read_u32(data, 8));
    meta.flow_hash = read_u32(data, 12);
    meta.seq = read_u64(data, 16);
    meta.t0_ns = read_u64(data, 24);
    meta.s1_ns = read_u64(data, 32);
    meta.send_attempt_ns = read_u64(data, 40);
    meta.payload_length = payload_length;
    return true;
}

void append_tunnel_diag_header(std::vector<uint8_t> &frame,
                               BackendState *state,
                               uint64_t seq,
                               uint64_t t0_ns,
                               uint64_t s1_ns,
                               uint64_t send_attempt_ns,
                               uint32_t flow_hash,
                               size_t payload_length,
                               int flags) {
    frame.resize(kTunnelDiagHeaderSize + payload_length);
    write_u32(frame.data(), 0, kTunnelDiagMagic);
    frame[4] = kTunnelDiagVersion;
    frame[5] = static_cast<uint8_t>(flags & 0xFF);
    write_u16(frame.data(), 6, kTunnelDiagHeaderSize);
    write_u32(frame.data(), 8, static_cast<uint32_t>(state->sender_id));
    write_u32(frame.data(), 12, flow_hash);
    write_u64(frame.data(), 16, seq);
    write_u64(frame.data(), 24, t0_ns);
    write_u64(frame.data(), 32, s1_ns);
    write_u64(frame.data(), 40, send_attempt_ns);
    write_u32(frame.data(), 48, static_cast<uint32_t>(payload_length));
    write_u32(frame.data(), 52, 0);
}

bool call_fabricate_stun(JNIEnv *env,
                         BackendState *state,
                         const uint8_t *data,
                         size_t length,
                         std::vector<uint8_t> &response_out) {
    if (env == nullptr || state->callbacks == nullptr || state->fabricate_stun_mid == nullptr) return false;
    jbyteArray packet_array = env->NewByteArray(static_cast<jsize>(length));
    if (packet_array == nullptr) return false;
    env->SetByteArrayRegion(packet_array, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte *>(data));
    jobject response_obj = env->CallObjectMethod(state->callbacks, state->fabricate_stun_mid, packet_array, static_cast<jint>(length));
    env->DeleteLocalRef(packet_array);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    if (response_obj == nullptr) return false;
    auto *response_array = reinterpret_cast<jbyteArray>(response_obj);
    const jsize response_length = env->GetArrayLength(response_array);
    response_out.resize(static_cast<size_t>(response_length));
    if (response_length > 0) {
        env->GetByteArrayRegion(response_array, 0, response_length, reinterpret_cast<jbyte *>(response_out.data()));
    }
    env->DeleteLocalRef(response_array);
    return !response_out.empty();
}


uint32_t saturating_delta_us(uint64_t newer_ns, uint64_t older_ns) {
    if (newer_ns < older_ns) return 0xFFFFFFFFu;
    const uint64_t delta_us = (newer_ns - older_ns) / 1000ULL;
    return static_cast<uint32_t>(std::min<uint64_t>(delta_us, 0xFFFFFFFEu));
}

void update_tx_trace_slot(BackendState *state,
                          uint32_t tx_id,
                          uint32_t tx_kind,
                          uint64_t timestamp_rt_ns) {
    // This runs on the receive worker as well as the maintenance thread.
    // A preempted diagnostic producer must not hold up incoming game traffic.
    std::unique_lock<std::mutex> lock(state->timestamp_mutex, std::try_to_lock);
    if (!lock.owns_lock()) {
        state->udp_trace_skipped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    auto it = state->pending_tx_timestamps.find(tx_id);
    if (it == state->pending_tx_timestamps.end()) return;
    PendingTxTimestamp &pending = it->second;
    if (pending.ordinal == 0 || state->udp_trace_buffer == nullptr || state->udp_trace_capacity == 0) {
        state->pending_tx_timestamps.erase(it);
        return;
    }
    UdpTraceEvent &slot = state->udp_trace_buffer[(pending.ordinal - 1ULL) % state->udp_trace_capacity];
    TryTraceSlotGuard guard(slot);
    if (!guard.acquired) {
        state->pending_tx_timestamps.erase(it);
        state->udp_trace_skipped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    const uint64_t committed = slot.commit_id.load(std::memory_order_acquire);
    if (committed != pending.ordinal || slot.tunnel_seq != pending.seq || slot.stage != kUdpTraceStagePeerSend) {
        state->pending_tx_timestamps.erase(it);
        return;
    }

    if (tx_kind == SCM_TSTAMP_SCHED) {
        pending.tx_sched_rt_ns = timestamp_rt_ns;
        pending.have_sched = true;
        slot.tx_user_to_sched_us = saturating_delta_us(timestamp_rt_ns, pending.user_send_rt_ns);
    } else if (tx_kind == SCM_TSTAMP_SND) {
        pending.tx_soft_rt_ns = timestamp_rt_ns;
        pending.have_soft = true;
        slot.tx_user_to_soft_us = saturating_delta_us(timestamp_rt_ns, pending.user_send_rt_ns);
    }

    if (pending.have_sched && pending.have_soft) {
        // Error-queue notifications are not required to be delivered in stage
        // order. Compute the cross-stage delta after both have arrived.
        slot.tx_sched_to_soft_us = saturating_delta_us(
                pending.tx_soft_rt_ns, pending.tx_sched_rt_ns);
    }
    slot.commit_id.store(committed, std::memory_order_release);
    if (pending.have_sched && pending.have_soft) {
        state->pending_tx_timestamps.erase(it);
    }
}

void drain_peer_tx_timestamps(BackendState *state) {
    if (state == nullptr || state->peer_fd < 0) return;
    uint8_t data[2048];
    alignas(cmsghdr) char control[512];
    sockaddr_in addr{};
    for (int budget = 0; budget < 64; ++budget) {
        iovec iov{};
        iov.iov_base = data;
        iov.iov_len = sizeof(data);

        msghdr msg{};
        msg.msg_name = &addr;
        msg.msg_namelen = sizeof(addr);
        msg.msg_iov = &iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control;
        msg.msg_controllen = sizeof(control);

        const ssize_t rc = recvmsg(state->peer_fd, &msg, MSG_ERRQUEUE | MSG_DONTWAIT);
        if (rc < 0) {
            if (errno == EINTR) continue;
            break;
        }
        // OPT_TSONLY deliberately returns a zero-byte payload plus control messages.

        uint32_t tx_kind = 0;
        uint32_t tx_id = 0xFFFFFFFFu;
        uint64_t tx_rt_ns = 0;
        for (cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
            if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_TIMESTAMPING &&
                cmsg->cmsg_len >= CMSG_LEN(sizeof(timespec) * 3)) {
                timespec ts[3]{};
                std::memcpy(ts, CMSG_DATA(cmsg), sizeof(ts));
                for (const auto &entry : ts) {
                    if (entry.tv_sec != 0 || entry.tv_nsec != 0) {
                        tx_rt_ns = timespec_to_ns(entry);
                        break;
                    }
                }
            } else if ((cmsg->cmsg_level == SOL_IP || cmsg->cmsg_level == SOL_IPV6) && cmsg->cmsg_type == IP_RECVERR &&
                       cmsg->cmsg_len >= CMSG_LEN(sizeof(sock_extended_err))) {
                auto *serr = reinterpret_cast<sock_extended_err *>(CMSG_DATA(cmsg));
                if (serr != nullptr && serr->ee_origin == SO_EE_ORIGIN_TIMESTAMPING) {
                    tx_kind = serr->ee_info;
                    tx_id = serr->ee_data;
                }
            }
        }

        if (tx_rt_ns != 0 && tx_id != 0xFFFFFFFFu && (tx_kind == SCM_TSTAMP_SCHED || tx_kind == SCM_TSTAMP_SND)) {
            update_tx_trace_slot(state, tx_id, tx_kind, tx_rt_ns);
        }
    }
    // POLLERR also reports sk_err when IP_RECVERR is unavailable. An empty
    // MSG_ERRQUEUE alone does not clear it, causing the RX poller to spin.
    int socket_error = 0;
    socklen_t error_length = sizeof(socket_error);
    (void) getsockopt(state->peer_fd, SOL_SOCKET, SO_ERROR, &socket_error, &error_length);
}

bool send_peer_control_byte(BackendState *state, uint8_t value) {
    int send_error = 0;
    std::lock_guard<std::mutex> send_lock(state->peer_send_mutex);
    if (!state->running.load(std::memory_order_acquire)) return false;
    if (!send_message(state->peer_fd, &value, 1, nullptr, SendPolicy::kImmediateDrop, &send_error)) {
        return false;
    }
    // SOF_TIMESTAMPING_OPT_ID numbers every successful send, including probes
    // and acknowledgements. Skipping these increments shifted all later TX
    // diagnostic IDs away from the packet they described.
    if (state->peer_tx_kernel_timestamp_enabled) {
        state->next_peer_tx_timestamp_id += 1u;
    }
    return true;
}

bool send_keepalive(BackendState *state) {
    if (!send_peer_control_byte(state, 0xFFu)) return false;
    state->stats.keepalive_tx.fetch_add(1, std::memory_order_relaxed);
    state->last_keepalive_sent_ns.store(monotonic_ns(), std::memory_order_release);
    return true;
}

void update_game_traffic_start(BackendState *state) {
    uint64_t expected = 0;
    const uint64_t now_ms = monotonic_ms();
    (void) state->game_traffic_start_ms.compare_exchange_strong(
            expected,
            now_ms,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
}

void file_log_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for file_log_loop");
        return;
    }
    std::vector<DeferredNativeLog> batch;
    batch.reserve(256);
    while (state->running.load(std::memory_order_acquire)) {
        batch.clear();
        uint64_t dropped = 0;
        {
            std::unique_lock<std::mutex> lock(state->file_log_mutex);
            state->file_log_cv.wait_for(lock, std::chrono::milliseconds(20), [&]() {
                return !state->running.load(std::memory_order_acquire) || !state->deferred_file_logs.empty();
            });
            while (!state->deferred_file_logs.empty() && batch.size() < 256) {
                batch.push_back(std::move(state->deferred_file_logs.front()));
                state->deferred_file_logs.pop_front();
            }
            dropped = state->deferred_file_logs_dropped.exchange(0, std::memory_order_acq_rel);
        }
        for (const auto &entry : batch) {
            deliver_native_log(env, state, entry.level, entry.file_only, entry.message);
        }
        if (dropped > 0) {
            char msg[160];
            std::snprintf(msg, sizeof(msg), "[NATIVE/LOG] dropped %llu deferred logs",
                          static_cast<unsigned long long>(dropped));
            deliver_native_log(env, state, kNativeLogWarn, false, msg);
        }
        if (!state->running.load(std::memory_order_acquire) && batch.empty()) {
            break;
        }
    }
    while (true) {
        DeferredNativeLog entry{};
        {
            std::lock_guard<std::mutex> lock(state->file_log_mutex);
            if (state->deferred_file_logs.empty()) break;
            entry = std::move(state->deferred_file_logs.front());
            state->deferred_file_logs.pop_front();
        }
        deliver_native_log(env, state, entry.level, entry.file_only, entry.message);
    }
    const uint64_t dropped = state->deferred_file_logs_dropped.exchange(0, std::memory_order_acq_rel);
    if (dropped > 0) {
        char msg[160];
        std::snprintf(msg, sizeof(msg), "[NATIVE/LOG] dropped %llu deferred logs",
                      static_cast<unsigned long long>(dropped));
        deliver_native_log(env, state, kNativeLogWarn, false, msg);
    }
    state->jvm->DetachCurrentThread();
}

bool send_tunnel_frame(JNIEnv *env,
                       BackendState *state,
                       const uint8_t *packet,
                       size_t packet_length,
                       uint64_t seq,
                       uint64_t t0_ns,
                       uint64_t s1_ns,
                       uint32_t flow_hash,
                       int tunnel_flags,
                       uint8_t ip_version,
                       uint16_t source_port,
                       uint16_t dest_port) {
    PeerTxJob job{};
    job.payload.assign(packet, packet + packet_length);
    job.seq = seq;
    job.t0_ns = t0_ns;
    job.s1_ns = s1_ns;
    job.flow_hash = flow_hash;
    job.tunnel_flags = tunnel_flags;
    job.ip_version = ip_version;
    job.source_port = source_port;
    job.dest_port = dest_port;
    if (!enqueue_peer_tx(state, std::move(job))) {
        emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/TX] peer TX queue full; dropped tunnel frame");
        return false;
    }
    return true;
}

bool pass_packet_to_bridge(JNIEnv *env, BackendState *state, const uint8_t *packet, size_t packet_length) {
    std::vector<uint8_t> bytes(packet, packet + packet_length);
    if (enqueue_bridge_tx(state, std::move(bytes))) return true;
    emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/BRIDGE] bridge TX queue full; dropped packet");
    return false;
}

void inject_inner_ipv4_to_tun(JNIEnv *env,
                              BackendState *state,
                              const uint8_t *inner_packet,
                              size_t inner_length,
                              int tunnel_flags,
                              const TunnelDiagMeta *meta) {
    ParsedIpv4 parsed{};
    if (!parse_ipv4(inner_packet, inner_length, parsed) || parsed.protocol != kProtocolUdp || parsed.fragmented) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (parsed.packet_length < parsed.udp_payload_offset || parsed.udp_payload_offset + parsed.udp_payload_length > inner_length) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    auto dest_ip = state->vpn_ipv4;
    const uint64_t now_ms = monotonic_ms();

    auto src_ip = state->peer_fabricated_ip;
    resolve_gameplay_addresses(state, parsed.dest_port, parsed.source_port, now_ms, dest_ip, src_ip);
    const uint16_t src_port = parsed.source_port;
    const int target_port = resolve_inbound_gameplay_port(state, parsed.dest_port, tunnel_flags, now_ms);

    std::vector<uint8_t> packet = build_udp_ipv4_packet(
            src_ip,
            dest_ip,
            src_port,
            static_cast<uint16_t>(target_port),
            inner_packet + parsed.udp_payload_offset,
            parsed.udp_payload_length);
    const uint32_t flow_hash = flow_hash_bytes(src_ip.data(), 4, dest_ip.data(), 4,
                                               kProtocolUdp, src_port, static_cast<uint16_t>(target_port));
    record_udp_trace(state,
                     kUdpTraceStageTunInject,
                     monotonic_ns(),
                     4,
                     static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer | kUdpTraceFlagInject | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                     src_port,
                     static_cast<uint16_t>(target_port),
                     packet.size(),
                     meta != nullptr ? meta->seq : 0,
                     meta != nullptr ? meta->flow_hash : flow_hash,
                     0xFFFFFFFFu,
                     meta != nullptr ? static_cast<uint32_t>(meta->sender_id) : 0,
                     meta != nullptr ? meta->t0_ns : 0,
                     meta != nullptr ? meta->s1_ns : 0,
                     meta != nullptr ? meta->send_attempt_ns : 0,
                     0);
    if (enqueue_tun_inject(state, std::move(packet), TunInjectSource::kPeer, true, flow_hash,
                           meta != nullptr ? meta->seq : 0,
                           meta != nullptr ? static_cast<uint32_t>(meta->sender_id) : 0)) {
        record_match_packet_signal(state, parsed.udp_payload_length, now_ms);
        maybe_log_flow_ipv4(env, state, flow_hash, src_ip, src_port, dest_ip, static_cast<uint16_t>(target_port),
                           "INJECT_PEER", "peer-to-tun");
    } else {
        emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/INJECT] failed to inject IPv4 packet from peer");
    }
}

void inject_inner_ipv6_to_tun(JNIEnv *env,
                              BackendState *state,
                              const uint8_t *inner_packet,
                              size_t inner_length,
                              int tunnel_flags,
                              const TunnelDiagMeta *meta) {
    ParsedIpv6 parsed{};
    if (!parse_ipv6(inner_packet, inner_length, parsed)) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    if (parsed.next_header == kProtocolUdp) {
        if (parsed.packet_length < parsed.udp_payload_offset || parsed.udp_payload_offset + parsed.udp_payload_length > inner_length) {
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            return;
        }
        auto dest_ip = state->vpn_ipv6;
        const uint64_t now_ms = monotonic_ms();

        auto src_ip = ipv4_mapped_address(state->peer_fabricated_ip);
        resolve_gameplay_addresses(state, parsed.dest_port, parsed.source_port, now_ms, dest_ip, src_ip);
        const uint16_t src_port = parsed.source_port;
        const int target_port = resolve_inbound_gameplay_port(state, parsed.dest_port, tunnel_flags, now_ms);

        std::vector<uint8_t> packet = build_udp_ipv6_packet(
                src_ip,
                dest_ip,
                src_port,
                static_cast<uint16_t>(target_port),
                inner_packet + parsed.udp_payload_offset,
                parsed.udp_payload_length);
        const uint32_t flow_hash = flow_hash_bytes(src_ip.data(), 16, dest_ip.data(), 16,
                                                   kProtocolUdp, src_port, static_cast<uint16_t>(target_port));
        record_udp_trace(state,
                         kUdpTraceStageTunInject,
                         monotonic_ns(),
                         6,
                         static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer | kUdpTraceFlagInject | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                         src_port,
                         static_cast<uint16_t>(target_port),
                         packet.size(),
                         meta != nullptr ? meta->seq : 0,
                         meta != nullptr ? meta->flow_hash : flow_hash,
                         0xFFFFFFFFu,
                         meta != nullptr ? static_cast<uint32_t>(meta->sender_id) : 0,
                         meta != nullptr ? meta->t0_ns : 0,
                         meta != nullptr ? meta->s1_ns : 0,
                         meta != nullptr ? meta->send_attempt_ns : 0,
                         0);
        if (enqueue_tun_inject(state, std::move(packet), TunInjectSource::kPeer, true, flow_hash,
                               meta != nullptr ? meta->seq : 0,
                               meta != nullptr ? static_cast<uint32_t>(meta->sender_id) : 0)) {
            record_match_packet_signal(state, parsed.udp_payload_length, now_ms);
            maybe_log_flow_ipv6(env, state, flow_hash, src_ip, src_port, dest_ip, static_cast<uint16_t>(target_port),
                               "INJECT_PEER", "peer-to-tun");
        } else {
            emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/INJECT] failed to inject IPv6 UDP packet from peer");
        }
        return;
    }

    std::vector<uint8_t> packet(inner_packet, inner_packet + inner_length);
    if (enqueue_tun_inject(state, std::move(packet), TunInjectSource::kPeer, true)) {
    } else {
        emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/INJECT] failed to inject IPv6 packet from peer");
    }
}

ssize_t recv_peer_packet_with_timestamp(BackendState *state,
                                        uint8_t *buffer,
                                        size_t buffer_length,
                                        sockaddr_in *from_addr,
                                        socklen_t *from_len,
                                        uint64_t *userspace_rx_mono_ns_out,
                                        uint32_t *kernel_to_user_us_out,
                                        uint64_t *kernel_rx_rt_ns_out) {
    if (userspace_rx_mono_ns_out != nullptr) *userspace_rx_mono_ns_out = 0;
    if (kernel_to_user_us_out != nullptr) *kernel_to_user_us_out = 0xFFFFFFFFu;
    if (kernel_rx_rt_ns_out != nullptr) *kernel_rx_rt_ns_out = 0;

    alignas(cmsghdr) char control[CMSG_SPACE(sizeof(timespec)) + CMSG_SPACE(sizeof(timespec) * 3)];
    std::memset(control, 0, sizeof(control));
    std::memset(from_addr, 0, sizeof(*from_addr));

    iovec iov{};
    iov.iov_base = buffer;
    iov.iov_len = buffer_length;

    msghdr msg{};
    msg.msg_name = from_addr;
    msg.msg_namelen = sizeof(*from_addr);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = control;
    msg.msg_controllen = sizeof(control);

    const ssize_t rc = recvmsg(state->peer_fd, &msg, MSG_DONTWAIT);
    const int receive_error = errno;
    const uint64_t userspace_rx_rt_ns = realtime_ns();
    const uint64_t userspace_rx_mono_ns = monotonic_ns();
    if (userspace_rx_mono_ns_out != nullptr) *userspace_rx_mono_ns_out = userspace_rx_mono_ns;
    if (from_len != nullptr) *from_len = static_cast<socklen_t>(msg.msg_namelen);

    if (rc < 0) { errno = receive_error; return rc; }
    if ((msg.msg_flags & MSG_TRUNC) != 0) { errno = EMSGSIZE; return -1; }
    if (rc == 0) return rc;

    uint64_t kernel_rx_rt_ns = 0;
    for (cmsghdr *cmsg = CMSG_FIRSTHDR(&msg); cmsg != nullptr; cmsg = CMSG_NXTHDR(&msg, cmsg)) {
        if (cmsg->cmsg_level == SOL_SOCKET && cmsg->cmsg_type == SCM_TIMESTAMPNS &&
            cmsg->cmsg_len >= CMSG_LEN(sizeof(timespec))) {
            timespec ts{};
            std::memcpy(&ts, CMSG_DATA(cmsg), sizeof(ts));
            kernel_rx_rt_ns = timespec_to_ns(ts);
            break;
        }
    }

    if (kernel_rx_rt_ns_out != nullptr) *kernel_rx_rt_ns_out = kernel_rx_rt_ns;

    if (kernel_to_user_us_out != nullptr && kernel_rx_rt_ns != 0 && userspace_rx_rt_ns >= kernel_rx_rt_ns) {
        const uint64_t delta_us = (userspace_rx_rt_ns - kernel_rx_rt_ns) / 1000ULL;
        *kernel_to_user_us_out = static_cast<uint32_t>(std::min<uint64_t>(delta_us, 0xFFFFFFFEu));
    }

    return rc;
}

void handle_peer_packet(JNIEnv *env,
                        BackendState *state,
                        const uint8_t *packet,
                        size_t packet_length,
                        uint64_t userspace_rx_ns,
                        uint32_t kernel_to_user_us,
                        uint64_t kernel_rx_rt_ns) {
    if (packet_length == 1 && packet[0] == 0xFCu) {
        (void) send_peer_control_byte(state, 0xFDu);
        return;
    }
    if (packet_length == 1 && packet[0] == 0xFDu) {
        state->peer_probe_ack.store(true, std::memory_order_release);
        state->peer_probe_cv.notify_all();
        return;
    }
    if (packet_length == 1 && packet[0] == 0xFFu) {
        state->stats.keepalive_rx.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    TunnelDiagMeta meta{};
    size_t payload_offset = 0;
    size_t payload_length = packet_length;
    int tunnel_flags = 0;
    if (parse_tunnel_diag_meta(packet, packet_length, meta)) {
        payload_offset = meta.header_size;
        payload_length = meta.payload_length;
        tunnel_flags = meta.flags;
    }
    const uint64_t rx_now_ns = userspace_rx_ns != 0 ? userspace_rx_ns : monotonic_ns();
    maybe_log_gap(env, state, "NATIVE/RX-GAP", rx_now_ns, state->last_peer_rx_ns, state->last_peer_rx_gap_log_ms, state->last_rx_subsevere_log_ms, meta.valid ? static_cast<long long>(meta.seq) : -1LL, packet_length);
    if (payload_offset > packet_length || payload_length > packet_length - payload_offset || payload_length == 0) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    const uint8_t *inner_packet = packet + payload_offset;
    const uint8_t version = inner_packet[0] >> 4;
    if (version == 4) {
        ParsedIpv4 parsed{};
        if (meta.valid && parse_ipv4(inner_packet, payload_length, parsed) && parsed.protocol == kProtocolUdp) {
            record_udp_trace(state,
                             kUdpTraceStagePeerRecv,
                             rx_now_ns,
                             4,
                             static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                             parsed.source_port,
                             parsed.dest_port,
                             payload_length,
                             meta.valid ? meta.seq : 0,
                             meta.valid ? meta.flow_hash : flow_hash_bytes(parsed.source_ip.data(), 4, parsed.dest_ip.data(), 4,
                                                                          parsed.protocol, parsed.source_port, parsed.dest_port),
                             kernel_to_user_us,
                             meta.valid ? static_cast<uint32_t>(meta.sender_id) : 0,
                             meta.valid ? meta.t0_ns : 0,
                             meta.valid ? meta.s1_ns : 0,
                             meta.valid ? meta.send_attempt_ns : 0,
                             kernel_rx_rt_ns);
        }
        inject_inner_ipv4_to_tun(env, state, inner_packet, payload_length, tunnel_flags, meta.valid ? &meta : nullptr);
    } else if (version == 6) {
        ParsedIpv6 parsed{};
        if (meta.valid && parse_ipv6(inner_packet, payload_length, parsed) && parsed.next_header == kProtocolUdp) {
            record_udp_trace(state,
                             kUdpTraceStagePeerRecv,
                             rx_now_ns,
                             6,
                             static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                             parsed.source_port,
                             parsed.dest_port,
                             payload_length,
                             meta.valid ? meta.seq : 0,
                             meta.valid ? meta.flow_hash : flow_hash_bytes(parsed.source_ip.data(), 16, parsed.dest_ip.data(), 16,
                                                                          parsed.next_header, parsed.source_port, parsed.dest_port),
                             kernel_to_user_us,
                             meta.valid ? static_cast<uint32_t>(meta.sender_id) : 0,
                             meta.valid ? meta.t0_ns : 0,
                             meta.valid ? meta.s1_ns : 0,
                             meta.valid ? meta.send_attempt_ns : 0,
                             kernel_rx_rt_ns);
        }
        inject_inner_ipv6_to_tun(env, state, inner_packet, payload_length, tunnel_flags, meta.valid ? &meta : nullptr);
    } else {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
    }
}

void handle_tun_ipv4(JNIEnv *env, BackendState *state, const uint8_t *packet, size_t packet_length, uint64_t t0_ns) {
    ParsedIpv4 parsed{};
    if (!parse_ipv4(packet, packet_length, parsed)) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    if (parsed.fragmented) {
        if (arrays_equal4(parsed.dest_ip, state->peer_fabricated_ip)) {
            // Reassembly is not implemented in this backend. Do not pretend
            // fragment payload bytes are a UDP header and corrupt another flow.
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
            emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/PACKET] fragmented peer UDP unsupported; check path MTU");
        } else {
            pass_packet_to_bridge(env, state, packet, parsed.packet_length);
        }
        return;
    }

    enum class Action { Pass, Tunnel, Intercept, Drop };
    Action original_action = Action::Pass;
    const char *reason = "default-bridge";
    if (arrays_equal4(parsed.dest_ip, state->peer_fabricated_ip)) {
        if (parsed.protocol == kProtocolUdp) {
            original_action = Action::Tunnel;
            reason = "fabricated-peer";
        } else {
            original_action = Action::Drop;
            reason = "fabricated-peer-non-udp";
        }
    } else if (parsed.protocol == kProtocolUdp && should_intercept_ipv4_stun(parsed)) {
        original_action = Action::Intercept;
        reason = "stun-intercept";
    }

    const uint64_t now_ms = monotonic_ms();
    const bool forced_tunnel = false;
    const Action final_action = original_action;

    const uint32_t flow_hash = flow_hash_bytes(parsed.source_ip.data(), 4, parsed.dest_ip.data(), 4,
                                               parsed.protocol, parsed.source_port, parsed.dest_port);
    const char *action_name = "BRIDGE_JVM";
    switch (final_action) {
        case Action::Pass:
            action_name = "BRIDGE_JVM";
            maybe_log_flow_ipv4(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, action_name, reason);
            pass_packet_to_bridge(env, state, packet, parsed.packet_length);
            break;
        case Action::Tunnel: {
            action_name = "TUNNELED";
            const uint64_t seq = ++state->outbound_seq;
            if (parsed.protocol == kProtocolUdp) {
                record_udp_trace(state,
                                 kUdpTraceStageTunRead,
                                 t0_ns,
                                 4,
                                 static_cast<uint16_t>(kUdpTraceFlagPeerFabricated | kUdpTraceFlagTunnel | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                                 parsed.source_port,
                                 parsed.dest_port,
                                 parsed.packet_length,
                                 seq,
                                 flow_hash,
                                 0xFFFFFFFFu,
                                 static_cast<uint32_t>(state->sender_id),
                                 t0_ns,
                                 0,
                                 0,
                                 0);
            }
            maybe_log_flow_ipv4(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, action_name, reason);
            update_game_traffic_start(state);
            if (parsed.protocol == kProtocolUdp && original_action == Action::Tunnel) {
                const bool had_stable_port = stable_gameplay_known(state);
                const int old_stable_port = load_stable_game_port(state);
                const int old_remote_port = load_stable_remote_port(state);
                observe_gameplay_flow(state, parsed.source_port, parsed.dest_port, now_ms);
                remember_gameplay_endpoint(state, parsed.source_ip, parsed.source_port,
                                           parsed.dest_ip, parsed.dest_port, now_ms);
                const int new_stable_port = load_stable_game_port(state);
                const int new_remote_port = load_stable_remote_port(state);
                if (!had_stable_port || old_stable_port != new_stable_port || old_remote_port != new_remote_port) {
                    char msg[256];
                    std::snprintf(msg, sizeof(msg), "[NATIVE/EVENT] Gameplay flow observed: local=%d remote=%d source=IPv4-TUNNEL-OUT",
                                  new_stable_port,
                                  new_remote_port);
                    emit_native_log(env, state, kNativeLogInfo, false, msg);
                }
            }
            const uint64_t s1_ns = monotonic_ns();
            int tunnel_flags = 0;
            if (original_action == Action::Tunnel) tunnel_flags |= kTunnelFlagFabricatedFlow;
            if (forced_tunnel) tunnel_flags |= kTunnelFlagForcedUdp;
            if (parsed.protocol == kProtocolUdp) {
                record_udp_trace(state,
                                 kUdpTraceStageClassifiedTunnel,
                                 s1_ns,
                                 4,
                                 static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                                 parsed.source_port,
                                 parsed.dest_port,
                                 parsed.packet_length,
                                 seq,
                                 flow_hash,
                                 0xFFFFFFFFu,
                                 static_cast<uint32_t>(state->sender_id),
                                 t0_ns,
                                 s1_ns,
                                 0,
                                 0);
            }
            if (send_tunnel_frame(env, state, packet, parsed.packet_length, seq, t0_ns, s1_ns,
                                  flow_hash, tunnel_flags, 4, parsed.source_port, parsed.dest_port)) {
                record_match_packet_signal(state, parsed.udp_payload_length, now_ms);
            }
            break;
        }
        case Action::Intercept: {
            action_name = "STUN_INTERCEPT";
            maybe_log_flow_ipv4(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, action_name, reason);
            state->stats.stun_intercepted_ipv4.fetch_add(1, std::memory_order_relaxed);
            std::vector<uint8_t> response;
            if (call_fabricate_stun(env, state, packet, parsed.packet_length, response)) {
                remember_gameplay_endpoint(state, parsed.source_ip, parsed.source_port,
                                           state->peer_fabricated_ip, 0, now_ms);
                (void) enqueue_tun_inject(state, std::move(response), TunInjectSource::kStun, false);
            } else {
                // Unsupported requests must retain normal networking, not
                // disappear simply because they carry the STUN cookie.
                pass_packet_to_bridge(env, state, packet, parsed.packet_length);
            }
            break;
        }
        case Action::Drop:
            action_name = "DROP";
            maybe_log_flow_ipv4(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, action_name, reason);
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            break;
    }
}

void handle_tun_ipv6(JNIEnv *env, BackendState *state, const uint8_t *packet, size_t packet_length, uint64_t t0_ns) {
    ParsedIpv6 parsed{};
    if (!parse_ipv6(packet, packet_length, parsed)) {
        state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
        return;
    }

    const uint32_t flow_hash = flow_hash_bytes(parsed.source_ip.data(), 16, parsed.dest_ip.data(), 16,
                                               parsed.next_header, parsed.source_port, parsed.dest_port);

    if (should_tunnel_ipv6(state, parsed.dest_ip)) {
        const uint64_t seq = ++state->outbound_seq;
        if (parsed.next_header == kProtocolUdp) {
            record_udp_trace(state,
                             kUdpTraceStageTunRead,
                             t0_ns,
                             6,
                             static_cast<uint16_t>(kUdpTraceFlagPeerFabricated | kUdpTraceFlagTunnel | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                             parsed.source_port,
                             parsed.dest_port,
                             parsed.packet_length,
                             seq,
                             flow_hash,
                             0xFFFFFFFFu,
                             static_cast<uint32_t>(state->sender_id),
                             t0_ns,
                             0,
                             0,
                             0);
        }
        maybe_log_flow_ipv6(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, "TUNNELED", "ipv6-peer-route");
        update_game_traffic_start(state);
        int tunnel_flags = is_ipv4_mapped_equal(parsed.dest_ip, state->peer_fabricated_ip) ? kTunnelFlagFabricatedFlow : 0;
        if (parsed.next_header == kProtocolUdp) {
            const bool had_stable_port = stable_gameplay_known(state);
            const int old_stable_port = load_stable_game_port(state);
            const int old_remote_port = load_stable_remote_port(state);
            observe_gameplay_flow(state, parsed.source_port, parsed.dest_port, monotonic_ms());
            remember_gameplay_endpoint(state, parsed.source_ip, parsed.source_port,
                                       parsed.dest_ip, parsed.dest_port, monotonic_ms());
            const int new_stable_port = load_stable_game_port(state);
            const int new_remote_port = load_stable_remote_port(state);
            if (!had_stable_port || old_stable_port != new_stable_port || old_remote_port != new_remote_port) {
                char msg[256];
                std::snprintf(msg, sizeof(msg), "[NATIVE/EVENT] Gameplay flow observed: local=%d remote=%d source=IPv6-TUNNEL-OUT",
                              new_stable_port,
                              new_remote_port);
                emit_native_log(env, state, kNativeLogInfo, false, msg);
            }
        }
        const uint64_t s1_ns = monotonic_ns();
        if (parsed.next_header == kProtocolUdp) {
            record_udp_trace(state,
                             kUdpTraceStageClassifiedTunnel,
                             s1_ns,
                             6,
                             static_cast<uint16_t>(tunnel_flags | kUdpTraceFlagTunnel | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                             parsed.source_port,
                             parsed.dest_port,
                             parsed.packet_length,
                             seq,
                             flow_hash,
                             0xFFFFFFFFu,
                             static_cast<uint32_t>(state->sender_id),
                             t0_ns,
                             s1_ns,
                             0,
                             0);
        }
        if (send_tunnel_frame(env, state, packet, parsed.packet_length, seq, t0_ns, s1_ns,
                              flow_hash, tunnel_flags, 6, parsed.source_port, parsed.dest_port) &&
            parsed.next_header == kProtocolUdp) {
            record_match_packet_signal(state, parsed.udp_payload_length, monotonic_ms());
        }
        return;
    }

    if (should_intercept_ipv6_stun(parsed)) {
        maybe_log_flow_ipv6(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, "STUN_INTERCEPT", "ipv6-stun-intercept");
        state->stats.stun_intercepted_ipv6.fetch_add(1, std::memory_order_relaxed);
        std::vector<uint8_t> response;
        if (call_fabricate_stun(env, state, packet, parsed.packet_length, response)) {
            remember_gameplay_endpoint(state, parsed.source_ip, parsed.source_port,
                                       ipv4_mapped_address(state->peer_fabricated_ip), 0, monotonic_ms());
            (void) enqueue_tun_inject(state, std::move(response), TunInjectSource::kStun, false);
            return;
        }
        pass_packet_to_bridge(env, state, packet, parsed.packet_length);
        return;
    }

    maybe_log_flow_ipv6(env, state, flow_hash, parsed.source_ip, parsed.source_port, parsed.dest_ip, parsed.dest_port, "BRIDGE_JVM", "ipv6-default-bridge");
    pass_packet_to_bridge(env, state, packet, parsed.packet_length);
}


void peer_rx_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for peer_rx_loop");
        request_backend_stop(state);
        return;
    }
    (void) try_promote_thread_priority(env, state, "peer_rx_loop");
    std::vector<uint8_t> peer_buffer(kMaxPacketSize + kTunnelDiagHeaderSize + 32);
    while (state->running.load(std::memory_order_acquire)) {
        pollfd pfd{};
        pfd.fd = state->peer_fd;
        const int ready = wait_for_backend_read(state, pfd.fd, pfd.revents);
        if (ready < 0) {
            if (errno == EINTR) continue;
            request_backend_stop(state);
            break;
        }
        if (ready == 0) {
            continue;
        }
        const short peer_errors = static_cast<short>(pfd.revents & (POLLERR | POLLHUP | POLLNVAL));
        if ((peer_errors & POLLERR) != 0) {
            state->stats.peer_socket_events.fetch_add(1, std::memory_order_relaxed);
            // If gameplay is ready too, drain it first. TX diagnostic records
            // can wait until the end of this bounded receive batch.
            if ((pfd.revents & POLLIN) == 0) drain_peer_tx_timestamps(state);
        }
        if ((peer_errors & (POLLHUP | POLLNVAL)) != 0) {
            state->stats.peer_socket_events.fetch_add(1, std::memory_order_relaxed);
            request_backend_stop(state);
            break;
        }
        if ((pfd.revents & POLLIN) == 0) continue;
        int peer_budget = 64;
        uint64_t batch_packets = 0;
        uint64_t batch_max_kernel_to_user_us = 0;
        uint64_t first_kernel_rx_rt_ns = 0;
        uint64_t last_kernel_rx_rt_ns = 0;
        while (state->running.load(std::memory_order_acquire) && peer_budget-- > 0) {
            sockaddr_in from_addr{};
            socklen_t from_len = sizeof(from_addr);
            uint64_t userspace_rx_ns = 0;
            uint32_t kernel_to_user_us = 0xFFFFFFFFu;
            uint64_t kernel_rx_rt_ns = 0;
            const ssize_t rc = recv_peer_packet_with_timestamp(state,
                                                               peer_buffer.data(),
                                                               peer_buffer.size(),
                                                               &from_addr,
                                                               &from_len,
                                                               &userspace_rx_ns,
                                                               &kernel_to_user_us,
                                                               &kernel_rx_rt_ns);
            if (rc > 0) {
                batch_packets++;
                if (kernel_to_user_us != 0xFFFFFFFFu) {
                    batch_max_kernel_to_user_us = std::max<uint64_t>(
                            batch_max_kernel_to_user_us, kernel_to_user_us);
                }
                if (kernel_rx_rt_ns != 0) {
                    if (first_kernel_rx_rt_ns == 0) first_kernel_rx_rt_ns = kernel_rx_rt_ns;
                    last_kernel_rx_rt_ns = kernel_rx_rt_ns;
                }
                const bool connected_peer_recv =
                    state->peer_socket_connected.load(std::memory_order_acquire) &&
                    (from_len == 0 || from_addr.sin_family != AF_INET);
                const bool addressed_peer_recv =
                    from_len >= sizeof(sockaddr_in) &&
                    from_addr.sin_family == AF_INET &&
                    from_addr.sin_addr.s_addr == state->peer_addr.sin_addr.s_addr &&
                    from_addr.sin_port == state->peer_addr.sin_port;
                if (connected_peer_recv || addressed_peer_recv) {
                    handle_peer_packet(env, state, peer_buffer.data(), static_cast<size_t>(rc), userspace_rx_ns, kernel_to_user_us, kernel_rx_rt_ns);
                } else {
                    state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
                    state->stats.dropped_parse_or_policy.fetch_add(1, std::memory_order_relaxed);
                }
                continue;
            }
            if (rc == 0) {
                // UDP permits empty datagrams; zero is not stream EOF.
                // Consume it and continue receiving instead of killing all
                // tunnel threads. The receive budget still bounds this loop.
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) {
                break;
            }
            state->stats.peer_socket_events.fetch_add(1, std::memory_order_relaxed);
            break;
        }
        if (batch_packets > 0) {
            const uint64_t now_ms = monotonic_ms();
            if (state->rx_batch_window_start_ms == 0) state->rx_batch_window_start_ms = now_ms;
            state->rx_batch_wakes++;
            state->rx_batch_packets += batch_packets;
            state->rx_batch_max = std::max(state->rx_batch_max, batch_packets);
            state->rx_batch_max_kernel_to_user_us = std::max(
                    state->rx_batch_max_kernel_to_user_us, batch_max_kernel_to_user_us);
            if (batch_max_kernel_to_user_us >= 60000ULL) state->rx_batch_delayed_wakes++;
            if (last_kernel_rx_rt_ns >= first_kernel_rx_rt_ns && first_kernel_rx_rt_ns != 0) {
                state->rx_batch_max_kernel_span_us = std::max<uint64_t>(
                        state->rx_batch_max_kernel_span_us,
                        (last_kernel_rx_rt_ns - first_kernel_rx_rt_ns) / 1000ULL);
            }
            if (now_ms - state->rx_batch_window_start_ms >= kSummaryLogIntervalMs) {
                char msg[320];
                std::snprintf(msg, sizeof(msg),
                              "[NATIVE/LAT] rxWake wakes=%llu packets=%llu maxBatch=%llu delayedWakes=%llu maxKernelToUserMs=%llu maxKernelSpanMs=%llu interpretation=kernel-ts-vs-user-wake",
                              static_cast<unsigned long long>(state->rx_batch_wakes),
                              static_cast<unsigned long long>(state->rx_batch_packets),
                              static_cast<unsigned long long>(state->rx_batch_max),
                              static_cast<unsigned long long>(state->rx_batch_delayed_wakes),
                              static_cast<unsigned long long>(state->rx_batch_max_kernel_to_user_us / 1000ULL),
                              static_cast<unsigned long long>(state->rx_batch_max_kernel_span_us / 1000ULL));
                emit_native_log(env, state, kNativeLogInfo, true, msg);
                state->rx_batch_window_start_ms = now_ms;
                state->rx_batch_wakes = 0;
                state->rx_batch_packets = 0;
                state->rx_batch_max = 0;
                state->rx_batch_delayed_wakes = 0;
                state->rx_batch_max_kernel_to_user_us = 0;
                state->rx_batch_max_kernel_span_us = 0;
            }
        }
        drain_peer_tx_timestamps(state);
        maybe_log_action_summary(env, state, false);
    }
    maybe_log_action_summary(env, state, true);
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] peer RX loop stopped");
    state->jvm->DetachCurrentThread();
}

void tun_reader_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for tun_reader_loop");
        request_backend_stop(state);
        return;
    }
    (void) try_promote_thread_priority(env, state, "tun_reader_loop");
    std::vector<uint8_t> tun_buffer(kMaxPacketSize);
    while (state->running.load(std::memory_order_acquire)) {
        pollfd pfd{};
        pfd.fd = state->tun_fd;
        const int ready = wait_for_backend_read(state, pfd.fd, pfd.revents);
        if (ready < 0) {
            if (errno == EINTR) continue;
            request_backend_stop(state);
            break;
        }
        if (ready == 0) {
            continue;
        }
        if ((pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            request_backend_stop(state);
            break;
        }
        if ((pfd.revents & POLLIN) == 0) continue;
        const ssize_t rc = read(state->tun_fd, tun_buffer.data(), tun_buffer.size());
        if (rc > 0) {
            const size_t packet_length = static_cast<size_t>(rc);
            const uint64_t t0_ns = monotonic_ns();
            maybe_log_gap(env, state, "NATIVE/T0-GAP", t0_ns, state->last_tun_read_ns, state->last_tun_gap_log_ms, state->last_tun_subsevere_log_ms, static_cast<long long>(state->outbound_seq), packet_length);
            const uint8_t version = tun_buffer[0] >> 4;
            if (version == 4) {
                handle_tun_ipv4(env, state, tun_buffer.data(), packet_length, t0_ns);
            } else if (version == 6) {
                handle_tun_ipv6(env, state, tun_buffer.data(), packet_length, t0_ns);
            } else {
                state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
            }
            maybe_log_action_summary(env, state, false);
        } else if (rc == 0) {
            request_backend_stop(state);
            break;
        } else if (errno != EINTR && errno != EAGAIN) {
            request_backend_stop(state);
            break;
        }
    }
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] TUN reader loop stopped");
    state->jvm->DetachCurrentThread();
}

void bridge_rx_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for bridge_rx_loop");
        request_backend_stop(state);
        return;
    }
    // Bridge traffic is not latency-critical and must not compete at the same
    // elevated priority as gameplay RX/TX/TUN injection.
    std::vector<uint8_t> bridge_buffer(kMaxPacketSize);
    while (state->running.load(std::memory_order_acquire)) {
        pollfd pfd{};
        pfd.fd = state->bridge_fd;
        const int ready = wait_for_backend_read(state, pfd.fd, pfd.revents);
        if (ready < 0) {
            if (errno == EINTR) continue;
            request_backend_stop(state);
            break;
        }
        if (ready == 0) continue;
        if ((pfd.revents & (POLLERR | POLLHUP | POLLNVAL)) != 0) {
            request_backend_stop(state);
            break;
        }
        if ((pfd.revents & POLLIN) == 0) continue;
        const ssize_t rc = recv(state->bridge_fd, bridge_buffer.data(), bridge_buffer.size(), 0);
        if (rc > 0) {
            std::vector<uint8_t> packet(bridge_buffer.begin(), bridge_buffer.begin() + rc);
            if (!enqueue_tun_inject(state, std::move(packet), TunInjectSource::kBridge, false)) {
                emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/BRIDGE] failed to enqueue JVM bridge packet back into TUN");
            }
        } else if (rc == 0) {
            request_backend_stop(state);
            break;
        } else if (errno != EINTR && errno != EAGAIN) {
            state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
        }
    }
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] bridge RX loop stopped");
    state->jvm->DetachCurrentThread();
}

void keepalive_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for keepalive_loop");
        request_backend_stop(state);
        return;
    }
    while (state->running.load(std::memory_order_acquire)) {
        std::unique_lock<std::mutex> lock(state->keepalive_mutex);
        state->keepalive_cv.wait_for(lock, std::chrono::milliseconds(250), [&]() {
            return !state->running.load(std::memory_order_acquire);
        });
        if (!state->running.load(std::memory_order_acquire)) break;
        const uint64_t now_ns = monotonic_ns();
        const uint64_t keepalive_interval_ns = current_keepalive_interval_ns(state);
        const uint64_t last_keepalive_ns = state->last_keepalive_sent_ns.load(std::memory_order_acquire);
        if (last_keepalive_ns > 0 && now_ns - last_keepalive_ns > keepalive_interval_ns) {
            send_keepalive(state);
        }
        // Drain pending TX timestamps periodically (was previously done in peer_rx_loop idle)
        drain_peer_tx_timestamps(state);
        maybe_log_action_summary(env, state, false);
    }
    maybe_log_action_summary(env, state, true);
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] keepalive loop stopped");
    state->jvm->DetachCurrentThread();
}

void peer_tx_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for peer_tx_loop");
        request_backend_stop(state);
        return;
    }
    (void) try_promote_thread_priority(env, state, "peer_tx_loop");
    std::vector<uint8_t> frame;
    frame.reserve(kMaxPacketSize + kTunnelDiagHeaderSize + 32);
    while (state->running.load(std::memory_order_acquire)) {
        PeerTxJob job{};
        if (!wait_for_queue_pop(state->running, state->peer_tx_mutex, state->peer_tx_cv, state->peer_tx_queue, job)) {
            continue;
        }
        while (state->running.load(std::memory_order_acquire)) {
            const uint64_t send_attempt_ns = monotonic_ns();
            const uint64_t send_attempt_rt_ns = realtime_ns();
            append_tunnel_diag_header(frame, state, job.seq, job.t0_ns, job.s1_ns, send_attempt_ns, job.flow_hash, job.payload.size(), job.tunnel_flags);
            std::memcpy(frame.data() + kTunnelDiagHeaderSize, job.payload.data(), job.payload.size());
            const uint64_t trace_ordinal = record_udp_trace(state,
                                                            kUdpTraceStagePeerSend,
                                                            send_attempt_ns,
                                                            job.ip_version,
                                                            static_cast<uint16_t>(job.tunnel_flags | kUdpTraceFlagTunnel | (stable_gameplay_known(state) ? kUdpTraceFlagStableKnown : 0)),
                                                            job.source_port,
                                                            job.dest_port,
                                                            job.payload.size(),
                                                            job.seq,
                                                            job.flow_hash,
                                                            0xFFFFFFFFu,
                                                            static_cast<uint32_t>(state->sender_id),
                                                            job.t0_ns,
                                                            job.s1_ns,
                                                            send_attempt_ns,
                                                            0);
            int send_error = 0;
            bool send_ok = false;
            uint32_t tx_id = 0;
            {
                std::lock_guard<std::mutex> send_lock(state->peer_send_mutex);
                if (!state->running.load(std::memory_order_acquire)) break;
                std::unique_lock<std::mutex> timestamp_lock(state->timestamp_mutex, std::defer_lock);
                if (state->peer_tx_kernel_timestamp_enabled && trace_ordinal != 0 && timestamp_lock.try_lock()) {
                    tx_id = state->next_peer_tx_timestamp_id;
                    if (state->pending_tx_timestamps.size() >= 4096) {
                        // Bound cleanup work per send if a driver omits TX stages.
                        state->pending_tx_timestamps.erase(state->pending_tx_timestamps.begin());
                        state->udp_trace_skipped.fetch_add(1, std::memory_order_relaxed);
                    }
                    PendingTxTimestamp pending{};
                    pending.ordinal = trace_ordinal;
                    pending.seq = job.seq;
                    pending.user_send_rt_ns = send_attempt_rt_ns;
                    // Register before send(): a fast POLLERR/error-queue drain
                    // can otherwise consume the timestamp before this ID exists.
                    state->pending_tx_timestamps[tx_id] = pending;
                } else if (state->peer_tx_kernel_timestamp_enabled && trace_ordinal != 0) {
                    state->udp_trace_skipped.fetch_add(1, std::memory_order_relaxed);
                }
                // A diagnostic lock is never waited on. If acquired, retain it
                // over this nonblocking send so a failed send can remove its ID
                // without another wait or a stale entry matching a future send.
                send_ok = send_message(state->peer_fd, frame.data(), frame.size(), nullptr, SendPolicy::kImmediateDrop, &send_error);
                if (send_ok && state->peer_tx_kernel_timestamp_enabled) {
                    state->next_peer_tx_timestamp_id += 1u;
                } else if (!send_ok && timestamp_lock.owns_lock()) {
                    state->pending_tx_timestamps.erase(tx_id);
                }
            }
            if (send_ok) {
                state->stats.tunnel_out_packets.fetch_add(1, std::memory_order_relaxed);
                state->stats.tunnel_out_bytes.fetch_add(static_cast<long long>(job.payload.size()), std::memory_order_relaxed);
                state->last_keepalive_sent_ns.store(send_attempt_ns, std::memory_order_release);
                const uint64_t send_done_ns = monotonic_ns();
                maybe_log_gap(env, state, "NATIVE/TX-GAP",
                              send_done_ns,
                              state->last_tunnel_send_ns,
                              state->last_tunnel_send_gap_log_ms,
                              state->last_tx_subsevere_log_ms,
                              static_cast<long long>(job.seq),
                              job.payload.size());
                // ─── Local processing-latency probe (T0 read → wire send) ────
                // This is the metric that pins the felt jitter to local CPU
                // scheduling rather than the radio link. Owned solely by this
                // thread (no locks). Emitted as a compact summary, not per-packet.
                if (job.t0_ns != 0 && send_done_ns > job.t0_ns) {
                    const uint64_t lat_ns = send_done_ns - job.t0_ns;
                    if (lat_ns > state->tx_lat_max_ns) state->tx_lat_max_ns = lat_ns;
                    state->tx_lat_sum_ns += lat_ns;
                    state->tx_lat_count += 1;
                }
                const uint64_t lat_now_ms = send_done_ns / 1000000ULL;
                if (state->tx_lat_window_start_ms == 0) state->tx_lat_window_start_ms = lat_now_ms;
                if ((lat_now_ms - state->tx_lat_window_start_ms) >= kSummaryLogIntervalMs &&
                    state->tx_lat_count > 0) {
                    char lat_msg[192];
                    std::snprintf(lat_msg, sizeof(lat_msg),
                                  "[NATIVE/LAT] localProc maxMs=%llu avgMs=%llu n=%llu (T0->wire; lower=smoother)",
                                  static_cast<unsigned long long>(state->tx_lat_max_ns / 1000000ULL),
                                  static_cast<unsigned long long>((state->tx_lat_sum_ns / state->tx_lat_count) / 1000000ULL),
                                  static_cast<unsigned long long>(state->tx_lat_count));
                    emit_native_log(env, state, kNativeLogInfo, true, lat_msg);
                    state->tx_lat_window_start_ms = lat_now_ms;
                    state->tx_lat_max_ns = 0;
                    state->tx_lat_sum_ns = 0;
                    state->tx_lat_count = 0;
                }
            } else {
                state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
                state->stats.dropped_peer_tx.fetch_add(1, std::memory_order_relaxed);
                char msg[256];
                std::snprintf(msg, sizeof(msg), "[NATIVE/TX] send_message failed for tunnel frame errno=%d(%s)",
                              send_error,
                              send_error != 0 ? std::strerror(send_error) : "unknown");
                emit_native_log(env, state, kNativeLogWarn, false, msg);
            }
            std::lock_guard<std::mutex> lock(state->peer_tx_mutex);
            if (state->peer_tx_queue.empty()) {
                break;
            }
            job = std::move(state->peer_tx_queue.front());
            state->peer_tx_queue.pop_front();
        }
    }
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] peer TX loop stopped");
    state->jvm->DetachCurrentThread();
}

void bridge_tx_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for bridge_tx_loop");
        request_backend_stop(state);
        return;
    }
    // Bridge traffic remains at normal priority; gameplay queues are isolated.
    while (state->running.load(std::memory_order_acquire)) {
        ByteBufferJob job{};
        if (!wait_for_queue_pop(state->running, state->bridge_tx_mutex, state->bridge_tx_cv, state->bridge_tx_queue, job)) {
            continue;
        }
        while (state->running.load(std::memory_order_acquire)) {
            if (send_message(state->bridge_fd, job.bytes.data(), job.bytes.size(), nullptr, SendPolicy::kImmediateDrop)) {
                state->stats.passthrough_to_jvm_packets.fetch_add(1, std::memory_order_relaxed);
            } else {
                state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
                state->stats.dropped_bridge_tx.fetch_add(1, std::memory_order_relaxed);
                emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/BRIDGE] failed to forward packet to JVM bridge");
            }
            std::lock_guard<std::mutex> lock(state->bridge_tx_mutex);
            if (state->bridge_tx_queue.empty()) break;
            job = std::move(state->bridge_tx_queue.front());
            state->bridge_tx_queue.pop_front();
        }
    }
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] bridge TX loop stopped");
    state->jvm->DetachCurrentThread();
}

void tun_inject_loop(BackendState *state) {
    JNIEnv *env = nullptr;
    if (state->jvm->AttachCurrentThread(&env, nullptr) != JNI_OK || env == nullptr) {
        LOGE("AttachCurrentThread failed for tun_inject_loop");
        request_backend_stop(state);
        return;
    }
    (void) try_promote_thread_priority(env, state, "tun_inject_loop");
    while (state->running.load(std::memory_order_acquire)) {
        TunInjectJob job{};
        if (!pop_tun_inject_job(state, job, true)) {
            continue;
        }
        while (state->running.load(std::memory_order_acquire)) {
            const uint64_t write_start_ns = monotonic_ns();
            if (job.enqueue_ns != 0 && write_start_ns >= job.enqueue_ns) {
                const uint64_t queue_ns = write_start_ns - job.enqueue_ns;
                state->inject_lat_sum_ns += queue_ns;
                state->inject_lat_max_ns = std::max(state->inject_lat_max_ns, queue_ns);
                state->inject_lat_count++;
            }
            if (write_frame_bounded(state->tun_fd, job.bytes.data(), job.bytes.size())) {
                if (job.count_as_tunnel) {
                    state->stats.tunnel_in_packets.fetch_add(1, std::memory_order_relaxed);
                    state->stats.tunnel_in_bytes.fetch_add(static_cast<long long>(job.bytes.size()), std::memory_order_relaxed);
                } else {
                    state->stats.passthrough_to_tun_packets.fetch_add(1, std::memory_order_relaxed);
                }
                if (job.count_as_tunnel && !job.bytes.empty()) {
                    uint8_t ip_version = job.bytes[0] >> 4;
                    uint16_t source_port = 0;
                    uint16_t dest_port = 0;
                    if (ip_version == 4) {
                        ParsedIpv4 parsed{};
                        if (parse_ipv4(job.bytes.data(), job.bytes.size(), parsed)) {
                            source_port = parsed.source_port;
                            dest_port = parsed.dest_port;
                        }
                    } else if (ip_version == 6) {
                        ParsedIpv6 parsed{};
                        if (parse_ipv6(job.bytes.data(), job.bytes.size(), parsed)) {
                            source_port = parsed.source_port;
                            dest_port = parsed.dest_port;
                        }
                    }
                    record_udp_trace(state,
                                     kUdpTraceStageTunWrite,
                                     monotonic_ns(),
                                     ip_version,
                                     static_cast<uint16_t>(kUdpTraceFlagTunnel | kUdpTraceFlagFromPeer | kUdpTraceFlagInject),
                                     source_port,
                                     dest_port,
                                     job.bytes.size(),
                                     job.tunnel_seq,
                                     job.flow_hash,
                                     0xFFFFFFFFu,
                                     job.sender_id,
                                     0,
                                     0,
                                     0,
                                     0);
                }
            } else {
                state->stats.dropped_packets.fetch_add(1, std::memory_order_relaxed);
                state->stats.dropped_tun_inject.fetch_add(1, std::memory_order_relaxed);
                emit_native_log(env, state, kNativeLogWarn, false, "[NATIVE/INJECT] failed to inject queued packet into TUN");
            }
            const uint64_t now_ms = monotonic_ms();
            if (state->inject_lat_window_start_ms == 0) state->inject_lat_window_start_ms = now_ms;
            if (now_ms - state->inject_lat_window_start_ms >= kSummaryLogIntervalMs) {
                if (state->inject_lat_count > 0) {
                    char msg[256];
                    std::snprintf(msg, sizeof(msg),
                                  "[NATIVE/LAT] inboundQueue maxMs=%llu avgUs=%llu n=%llu peakGame=%llu peakControl=%llu policy=game-first",
                                  static_cast<unsigned long long>(state->inject_lat_max_ns / 1000000ULL),
                                  static_cast<unsigned long long>((state->inject_lat_sum_ns / state->inject_lat_count) / 1000ULL),
                                  static_cast<unsigned long long>(state->inject_lat_count),
                                  static_cast<unsigned long long>(state->inject_peak_game_depth),
                                  static_cast<unsigned long long>(state->inject_peak_control_depth));
                    emit_native_log(env, state, kNativeLogInfo, true, msg);
                }
                state->inject_lat_window_start_ms = now_ms;
                state->inject_lat_sum_ns = 0;
                state->inject_lat_max_ns = 0;
                state->inject_lat_count = 0;
                state->inject_peak_game_depth = 0;
                state->inject_peak_control_depth = 0;
            }
            if (!pop_tun_inject_job(state, job, false)) break;
        }
    }
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] TUN inject loop stopped");
    state->jvm->DetachCurrentThread();
}

std::string jstring_to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

BackendState *from_handle(jlong handle) {
    return reinterpret_cast<BackendState *>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeStart(
        JNIEnv *env,
        jobject /*thiz*/,
        jint tun_fd,
        jstring peer_lan_ip,
        jint peer_port,
        jint local_port,
        jstring my_fabricated_ip,
        jstring peer_fabricated_ip,
        jstring vpn_address,
        jstring vpn_address_ipv6,
        jstring local_lan_ip,
        jint local_interface_index,
        jint mtu,
        jstring raw_capture_path,
        jobject callbacks) {
    if (tun_fd < 0 || callbacks == nullptr || peer_port < 1 || peer_port > 65535 ||
        local_port < 1 || local_port > 65535) {
        if (tun_fd >= 0) close(tun_fd);
        return env->NewLongArray(0);
    }

    auto *state = new BackendState();
    env->GetJavaVM(&state->jvm);
    state->tun_fd = tun_fd;
    state->stop_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (state->stop_fd < 0) {
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }
    state->mtu = mtu > 0 ? mtu : 1400;
    state->sender_id = static_cast<int>(monotonic_ns() & 0x7FFFFFFF);
    state->last_keepalive_sent_ns.store(monotonic_ns(), std::memory_order_release);
    state->udp_trace_capacity = kUdpTraceCapacity;
    if (state->udp_trace_capacity > 0) {
        state->udp_trace_buffer = std::unique_ptr<UdpTraceEvent[]>(new UdpTraceEvent[state->udp_trace_capacity]());
    }

    const std::string peer_lan_ip_str = jstring_to_string(env, peer_lan_ip);
    const std::string my_fabricated_ip_str = jstring_to_string(env, my_fabricated_ip);
    const std::string peer_fabricated_ip_str = jstring_to_string(env, peer_fabricated_ip);
    const std::string vpn_address_str = jstring_to_string(env, vpn_address);
    const std::string vpn_address_ipv6_str = jstring_to_string(env, vpn_address_ipv6);
    const std::string local_lan_ip_str = jstring_to_string(env, local_lan_ip);
    const std::string raw_capture_path_str = jstring_to_string(env, raw_capture_path);

    if (inet_pton(AF_INET, peer_lan_ip_str.c_str(), state->peer_lan_ip.data()) != 1 ||
        inet_pton(AF_INET, my_fabricated_ip_str.c_str(), state->my_fabricated_ip.data()) != 1 ||
        inet_pton(AF_INET, peer_fabricated_ip_str.c_str(), state->peer_fabricated_ip.data()) != 1 ||
        inet_pton(AF_INET, vpn_address_str.c_str(), state->vpn_ipv4.data()) != 1 ||
        inet_pton(AF_INET6, vpn_address_ipv6_str.c_str(), state->vpn_ipv6.data()) != 1) {
        LOGE("Failed to parse backend IP configuration");
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }
    state->has_configured_local_lan_ip =
        inet_pton(AF_INET, local_lan_ip_str.c_str(), state->configured_local_lan_ip.data()) == 1;
    state->configured_local_if_index = local_interface_index > 0 ? static_cast<unsigned int>(local_interface_index) : 0u;
    if (!state->has_configured_local_lan_ip) {
        LOGE("Failed to parse configured local LAN IP");
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }

    state->callbacks = env->NewGlobalRef(callbacks);
    jclass callback_class = env->GetObjectClass(callbacks);
    state->prepare_peer_socket_mid = env->GetMethodID(callback_class, "preparePeerSocket", "(I)Z");
    state->fabricate_stun_mid = env->GetMethodID(callback_class, "fabricateStunResponse", "([BI)[B");
    state->native_log_mid = env->GetMethodID(callback_class, "onNativeLog", "(ILjava/lang/String;Z)V");
    env->DeleteLocalRef(callback_class);
    if (state->prepare_peer_socket_mid == nullptr || state->fabricate_stun_mid == nullptr || state->native_log_mid == nullptr) {
        LOGE("Required callback methods were not found");
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }

    int bridge_fds[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_SEQPACKET, 0, bridge_fds) != 0) {
        LOGE("socketpair failed: %d", errno);
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }
    state->bridge_fd = bridge_fds[0];
    state->bridge_fd_for_jvm = bridge_fds[1];
    if (!set_nonblocking(state->bridge_fd) || !set_nonblocking(state->tun_fd)) {
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }

    state->peer_fd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (state->peer_fd < 0) {
        LOGE("peer socket creation failed: %d", errno);
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }

    // A second session must not share this unicast endpoint and steal datagrams.
    int buffer_bytes = 65536;
    setsockopt(state->peer_fd, SOL_SOCKET, SO_RCVBUF, &buffer_bytes, sizeof(buffer_bytes));
    setsockopt(state->peer_fd, SOL_SOCKET, SO_SNDBUF, &buffer_bytes, sizeof(buffer_bytes));

    // ===== Low-latency socket tuning =====
    // These options operate at the kernel socket layer on the selected Wi-Fi LAN
    // interface. They reduce scheduling/queueing latency without changing the
    // PeerLink packet format or eFootball traffic semantics.

    // DSCP EF (46) is a best-effort latency hint. Android, the driver or the
    // access point may ignore/remark it, so success is logged but never treated
    // as proof of radio priority.
    int tos = 46 << 2;
    const bool tos4_ok = setsockopt(state->peer_fd, SOL_IP, IP_TOS, &tos, sizeof(tos)) == 0;
    const bool tos6_ok = setsockopt(state->peer_fd, IPPROTO_IPV6, IPV6_TCLASS, &tos, sizeof(tos)) == 0;

    // SO_PRIORITY = 6 (TC_PRIO_INTERACTIVE): kernel-side QoS priority. Affects
    // which qdisc band the packet goes into. Linux's default pfifo_fast qdisc has
    // three bands and drains higher-priority bands first when the device is ready
    // to transmit. Interactive priority sends our game packets to the front of the
    // line when the WiFi driver pulls packets from qdisc.
    int prio = 6;
    const bool priority_ok = setsockopt(state->peer_fd, SOL_SOCKET, SO_PRIORITY, &prio, sizeof(prio)) == 0;

    // SO_BUSY_POLL is optional and frequently unavailable to apps. It is a
    // 50-microsecond hint only; the timestamp diagnostics remain authoritative.
    int busy_poll_us = 50;
    const bool busy_poll_ok = setsockopt(state->peer_fd, SOL_SOCKET, SO_BUSY_POLL, &busy_poll_us, sizeof(busy_poll_us)) == 0;

    int timestamp_ns = 1;
    state->peer_rx_kernel_timestamp_enabled = (setsockopt(state->peer_fd, SOL_SOCKET, SO_TIMESTAMPNS, &timestamp_ns, sizeof(timestamp_ns)) == 0);
    int recv_err = 1;
    (void) setsockopt(state->peer_fd, SOL_IP, IP_RECVERR, &recv_err, sizeof(recv_err));
    int tx_timestamp_flags = SOF_TIMESTAMPING_SOFTWARE |
                             SOF_TIMESTAMPING_TX_SCHED |
                             SOF_TIMESTAMPING_TX_SOFTWARE |
                             SOF_TIMESTAMPING_OPT_ID |
                             SOF_TIMESTAMPING_OPT_TSONLY;
    state->peer_tx_kernel_timestamp_enabled = (setsockopt(state->peer_fd, SOL_SOCKET, SO_TIMESTAMPING, &tx_timestamp_flags, sizeof(tx_timestamp_flags)) == 0);
    state->next_peer_tx_timestamp_id = 0u;
    state->pending_tx_timestamps.clear();
    char qos_msg[256];
    std::snprintf(qos_msg, sizeof(qos_msg),
                  "[NATIVE/QOS] dscp4=%s dscp6=%s socketPriority=%s busyPoll=%s rxTimestamp=%s txTimestamp=%s",
                  tos4_ok ? "ok" : "unsupported",
                  tos6_ok ? "ok" : "unsupported",
                  priority_ok ? "ok" : "unsupported",
                  busy_poll_ok ? "ok" : "unsupported",
                  state->peer_rx_kernel_timestamp_enabled ? "ok" : "unsupported",
                  state->peer_tx_kernel_timestamp_enabled ? "ok" : "unsupported");
    emit_native_log(env, state, kNativeLogInfo, true, qos_msg);
    emit_native_log(env,
                    state,
                    kNativeLogInfo,
                    false,
                    std::string("[WIFI-TRUTH] tx_timestamping=") +
                        (state->peer_tx_kernel_timestamp_enabled ? "enabled" : "failed") +
                        " flags=SOFTWARE|TX_SCHED|TX_SOFTWARE|OPT_ID|OPT_TSONLY");

    // Source IPv4/interface were locked by the resolver before creating the VPN.
    // Our listener port is independent of the destination advertised by the peer.
    const unsigned int src_if_index = state->configured_local_if_index;
    const bool bind_ok = bind_peer_source(state, static_cast<int>(local_port));
    if (!bind_ok) {
        LOGE("peer socket bind failed: %d", errno);
        close_fd(state->peer_fd);
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }
    // Lock the socket to the exact LAN interface selected by the JVM resolver.
    // Source-address binding alone is not sufficient on some OEM hotspot hosts:
    // policy routing may still prefer cellular for a protected VPN socket.
    if (src_if_index > 0) {
        pin_socket_egress_interface(state->peer_fd, src_if_index);
    }

    std::memset(&state->peer_addr, 0, sizeof(state->peer_addr));
    state->peer_addr.sin_family = AF_INET;
    state->peer_addr.sin_port = htons(static_cast<uint16_t>(peer_port));
    std::memcpy(&state->peer_addr.sin_addr, state->peer_lan_ip.data(), 4);

    if (!prepare_peer_socket_callback(env, state)) {
        LOGE("Peer socket protection or exact network binding failed");
        close_fd(state->peer_fd);
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }

    if (!connect_udp_socket(state->peer_fd, state->peer_addr)) {
        LOGE("peer socket connect failed: %d", errno);
        close_fd(state->peer_fd);
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
        close_fd(state->tun_fd);
        delete state;
        return env->NewLongArray(0);
    }
    state->peer_socket_connected.store(true, std::memory_order_release);

    // F24: deliberately do not start the PCAPNG writer. Packet forwarding and
    // timing metadata continue, but raw IP/UDP bytes are never written to disk.
    const bool capture_ok = false;

    jlongArray result = env->NewLongArray(2);
    if (result == nullptr) {
        close_fd(state->tun_fd);
        close_fd(state->peer_fd);
        close_fd(state->bridge_fd);
        close_fd(state->bridge_fd_for_jvm);
        env->DeleteGlobalRef(state->callbacks);
        delete state;
        return nullptr;
    }
    state->running.store(true, std::memory_order_release);
    state->file_log_thread = std::thread(file_log_loop, state);
    if (capture_ok) state->raw_capture_thread = std::thread(raw_capture_writer_loop, state);
    state->peer_rx_thread = std::thread(peer_rx_loop, state);
    state->peer_tx_thread = std::thread(peer_tx_loop, state);
    state->tun_thread = std::thread(tun_reader_loop, state);
    state->bridge_rx_thread = std::thread(bridge_rx_loop, state);
    state->bridge_tx_thread = std::thread(bridge_tx_loop, state);
    state->tun_inject_thread = std::thread(tun_inject_loop, state);
    state->keepalive_thread = std::thread(keepalive_loop, state);

    const jlong values[2] = {
            static_cast<jlong>(reinterpret_cast<intptr_t>(state)),
            static_cast<jlong>(state->bridge_fd_for_jvm),
    };
    env->SetLongArrayRegion(result, 0, 2, values);
    state->bridge_fd_for_jvm = -1;
    char start_msg[256];
    std::snprintf(start_msg, sizeof(start_msg), "[NATIVE/EVENT] backend started peer=%s:%d mtu=%d", peer_lan_ip_str.c_str(), peer_port, state->mtu);
    emit_native_log(env, state, kNativeLogInfo, false, start_msg);
    char trace_msg[256];
    std::snprintf(trace_msg,
                  sizeof(trace_msg),
                  "[F24-TRACE] rawBytes=off udpTiming=on capacity=%zu textPacketDiagnostics=off",
                  state->udp_trace_capacity);
    emit_native_log(env, state, kNativeLogInfo, false, trace_msg);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRequestStop(
        JNIEnv *, jobject, jlong handle) {
    request_backend_stop(from_handle(handle));
}

extern "C" JNIEXPORT void JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeStop(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    BackendState *state = from_handle(handle);
    if (state == nullptr) return;

    request_backend_stop(state);
    if (state->keepalive_thread.joinable()) state->keepalive_thread.join();
    if (state->peer_rx_thread.joinable()) state->peer_rx_thread.join();
    if (state->peer_tx_thread.joinable()) state->peer_tx_thread.join();
    if (state->tun_thread.joinable()) state->tun_thread.join();
    if (state->bridge_rx_thread.joinable()) state->bridge_rx_thread.join();
    if (state->bridge_tx_thread.joinable()) state->bridge_tx_thread.join();
    if (state->tun_inject_thread.joinable()) state->tun_inject_thread.join();
    close_fd(state->tun_fd);
    close_fd(state->peer_fd);
    close_fd(state->bridge_fd);
    close_fd(state->bridge_fd_for_jvm);
    state->raw_capture_enabled.store(false, std::memory_order_release);
    state->raw_capture_stop.store(true, std::memory_order_release);
    state->raw_capture_cv.notify_all();
    if (state->raw_capture_thread.joinable()) state->raw_capture_thread.join();
    const auto cap = raw_capture_stats(state);
    char cap_msg[320];
    std::snprintf(cap_msg, sizeof(cap_msg),
                  "[WIRE-CAP ] stopped packets=%llu bytes=%llu queueDrops=%llu oversized=%llu writeErrors=%llu fileBytes=%llu complete=%s",
                  static_cast<unsigned long long>(cap[0]), static_cast<unsigned long long>(cap[1]),
                  static_cast<unsigned long long>(cap[2]), static_cast<unsigned long long>(cap[3]),
                  static_cast<unsigned long long>(cap[4]), static_cast<unsigned long long>(cap[5]),
                  (cap[2] == 0 && cap[3] == 0 && cap[4] == 0) ? "true" : "false");
    emit_native_log(env, state, (cap[2] == 0 && cap[3] == 0 && cap[4] == 0) ? kNativeLogInfo : kNativeLogWarn, false, cap_msg);
    emit_native_log(env, state, kNativeLogInfo, false, "[NATIVE/EVENT] backend stopping");
    if (state->file_log_thread.joinable()) state->file_log_thread.join();
    if (state->callbacks != nullptr) {
        env->DeleteGlobalRef(state->callbacks);
        state->callbacks = nullptr;
    }
    delete state;
}


extern "C" JNIEXPORT jlongArray JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeFlushRawCapture(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    BackendState *state = from_handle(handle);
    request_raw_capture_flush(state);
    const auto stats = raw_capture_stats(state);
    jlongArray result = env->NewLongArray(static_cast<jsize>(stats.size()));
    if (result == nullptr) return nullptr;
    jlong values[6];
    for (size_t i = 0; i < stats.size(); ++i) values[i] = static_cast<jlong>(stats[i]);
    env->SetLongArrayRegion(result, 0, 6, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeDumpUdpTrace(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    BackendState *state = from_handle(handle);
    const std::string dump = dump_udp_trace(state);
    return env->NewStringUTF(dump.c_str());
}


// Returns kernel thread IDs for diagnostics and an optional root-only chrt
// path. Normal stock devices already use Android URGENT_DISPLAY priority.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeGetHotThreadTids(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    BackendState *state = from_handle(handle);
    if (state == nullptr) return env->NewIntArray(0);
    std::vector<int> tids;
    {
        std::lock_guard<std::mutex> lk(state->hot_tids_mutex);
        tids = state->hot_tids;
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(tids.size()));
    if (arr == nullptr) return env->NewIntArray(0);
    if (!tids.empty()) {
        env->SetIntArrayRegion(arr, 0, static_cast<jsize>(tids.size()),
                               reinterpret_cast<const jint *>(tids.data()));
    }
    return arr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeRebindPeerSocket(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong handle) {
    BackendState *state = from_handle(handle);
    if (state == nullptr || state->peer_fd < 0 || state->callbacks == nullptr ||
        !state->running.load(std::memory_order_acquire)) return JNI_FALSE;
    // Android Network.bindSocket requires an unconnected socket. Serialize the
    // short disconnect/bind/reconnect transaction with every native send.
    std::lock_guard<std::mutex> send_lock(state->peer_send_mutex);
    if (!state->running.load(std::memory_order_acquire)) return JNI_FALSE;
    if (state->peer_socket_connected.load(std::memory_order_acquire)) {
        if (!disconnect_udp_socket(state->peer_fd)) return JNI_FALSE;
        state->peer_socket_connected.store(false, std::memory_order_release);
    }
    pin_socket_egress_interface(state->peer_fd, state->configured_local_if_index);
    const bool prepared = prepare_peer_socket_callback(env, state);
    if (!state->running.load(std::memory_order_acquire)) return JNI_FALSE;
    // Restore connected mode even if preparation failed. Report that failure
    // so the service retries instead of claiming its intended rebind worked.
    const bool connected = connect_udp_socket(state->peer_fd, state->peer_addr);
    state->peer_socket_connected.store(connected, std::memory_order_release);
    return prepared && connected ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeVerifyPeerPath(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle, jint timeout_ms) {
    BackendState *state = from_handle(handle);
    if (state == nullptr || state->peer_fd < 0 || !state->running.load(std::memory_order_acquire)) return JNI_FALSE;
    const int bounded = std::max(500, std::min(90000, static_cast<int>(timeout_ms)));
    state->peer_probe_ack.store(false, std::memory_order_release);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(bounded);
    while (state->running.load(std::memory_order_acquire) && std::chrono::steady_clock::now() < deadline) {
        (void) send_peer_control_byte(state, 0xFCu);
        std::unique_lock<std::mutex> lock(state->peer_probe_mutex);
        if (state->peer_probe_cv.wait_for(lock, std::chrono::milliseconds(250), [&]() {
                return state->peer_probe_ack.load(std::memory_order_acquire) || !state->running.load(std::memory_order_acquire);
            })) {
            if (state->peer_probe_ack.load(std::memory_order_acquire)) return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativePollStats(
        JNIEnv *env, jobject /*thiz*/, jlong handle) {
    BackendState *state = from_handle(handle);
    jlongArray result = env->NewLongArray(14);
    if (result == nullptr) return nullptr;
    const jlong values[14] = {
        state ? static_cast<jlong>(state->stats.tunnel_out_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.tunnel_out_bytes.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.tunnel_in_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.tunnel_in_bytes.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.stun_intercepted_ipv4.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.stun_intercepted_ipv6.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.passthrough_to_jvm_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.passthrough_to_tun_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.dropped_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.keepalive_tx.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.keepalive_rx.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.small_game_packets.load(std::memory_order_relaxed)) : 0,
        state ? static_cast<jlong>(state->stats.small_game_last_ms.load(std::memory_order_relaxed)) : 0,
        state && state->running.load(std::memory_order_acquire) ? 1 : 0,
    };
    env->SetLongArrayRegion(result, 0, 14, values);
    return result;
}
