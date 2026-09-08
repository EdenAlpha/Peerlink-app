// Compile against either the original or fixed production source. This test
// intentionally uses only APIs present in both versions.
#ifndef PEERLINK_BACKEND_SOURCE
#define PEERLINK_BACKEND_SOURCE "../app/src/main/jni/peerlink_backend.cpp"
#endif
#include PEERLINK_BACKEND_SOURCE
#include <iostream>

int main() {
    unsigned wrong = 0;
    for (int n = 0; n < 1000; ++n) {
        auto state = std::make_unique<BackendState>();
        state->running = true;
        state->vpn_ipv4 = {10,0,0,2}; state->peer_fabricated_ip = {203,0,113,2};
        const uint16_t active = 20000 + n, discovery = 40000 + n;
        const uint8_t body[] = {1,2,3,4};
        auto tx = build_udp_ipv4_packet(state->vpn_ipv4,state->peer_fabricated_ip,active,20769,body,sizeof(body));
        handle_tun_ipv4(nullptr,state.get(),tx.data(),tx.size(),monotonic_ns());
        uint8_t request[20]{}; write_u16(request,0,1); write_u32(request,4,kStunMagicCookie);
        const std::array<uint8_t,4> server{18,176,255,15};
        auto st = build_udp_ipv4_packet(state->vpn_ipv4,server,discovery,3478,request,sizeof(request));
        handle_tun_ipv4(nullptr,state.get(),st.data(),st.size(),monotonic_ns());
        auto rx = build_udp_ipv4_packet(state->peer_fabricated_ip,state->vpn_ipv4,20769,active,body,sizeof(body));
        inject_inner_ipv4_to_tun(nullptr,state.get(),rx.data(),rx.size(),kTunnelFlagFabricatedFlow,nullptr);
        ParsedIpv4 parsed{};
        if (state->tun_inject_queue.empty()) { ++wrong; continue; }
        const auto &injected = state->tun_inject_queue.back().bytes;
        if (!parse_ipv4(injected.data(),injected.size(),parsed) || parsed.dest_port != active) ++wrong;
    }
    std::cout << "Synthetic port-interference cases: 1000; wrong destination ports: " << wrong << '\n';
    return wrong == 0 ? 0 : 1;
}
