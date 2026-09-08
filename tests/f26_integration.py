#!/usr/bin/env python3
"""Cross-language ABI/lifecycle contracts; complements executed native/JVM tests.
These checks do not compile Android Kotlin or execute real Network callbacks.
"""
from pathlib import Path
import re
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/peerlink/app'
backend = (JAVA / 'tunnel/NativePeerLinkBackend.kt').read_text()
service = (JAVA / 'service/PeerLinkVpnService.kt').read_text()
bridge = (JAVA / 'tunnel/TunnelEngine.kt').read_text()
cpp = (ROOT / 'app/src/main/jni/peerlink_backend.cpp').read_text()
header = (ROOT / 'app/src/main/jni/peerlink_backend.h').read_text()
checks = 0

def check(label, ok):
    global checks
    checks += 1
    if not ok: raise AssertionError(label)
    print('PASS ' + label)

def body(source, function):
    start = source.index(function)
    start = source.index('{', start)
    # These selected method bodies have balanced braces, including strings.
    depth = 1
    end = start + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]

kt_params = re.search(r'private external fun nativeStart\((.*?)\):', backend, re.S).group(1)
kt_types = re.findall(r'\w+\s*:\s*(Int|String|Any)\b', kt_params)
abi_map = {'Int':'jint', 'String':'jstring', 'Any':'jobject'}
expected = ['JNIEnv', 'jobject'] + [abi_map[t] for t in kt_types]
for label, source in [('header',header), ('implementation',cpp)]:
    args = re.search(r'Java_com_peerlink_app_tunnel_NativePeerLinkBackend_nativeStart\((.*?)\)', source, re.S).group(1)
    types = re.findall(r'\b(JNIEnv|jobject|jint|jstring)\b', args)
    check('nativeStart Kotlin argument types match C++ ' + label, types == expected)
check('local and peer ports have separate JNI arguments', 'peerPort = config.peerPort' in backend and 'localPort = config.localPort' in backend)
models = (JAVA / 'tunnel/NativeBackendModels.kt').read_text()
check('native liveness crosses the JNI array and triggers service cleanup',
      'NewLongArray(12)' in cpp and 'backendRunning = raw[11] != 0L' in models and
      '!stats.backendRunning' in body(service, 'override fun onStats(') and
      'stopVpn()' in body(service, 'override fun onStats('))
monitor = body(service, 'private fun startGameplayPathMonitor()')
check('monitor reset occurs before preserving verified socket identity', monitor.index('stopGameplayPathMonitor()') < monitor.index('rememberVerifiedGameplayPath()'))
prepare = body(service, 'private fun prepareNativePeerSocket(')
check('Kotlin borrows callback FD while native closes its duplicate', 'ParcelFileDescriptor.fromFd(fdForBinding)' in prepare and 'close(callback_fd)' in body(cpp, 'bool prepare_peer_socket_callback('))
check('network callback receives ordered immutable link-property snapshots',
      'data class GameplayLinkSnapshot(' in service and
      'ConcurrentHashMap<Network, GameplayLinkSnapshot>()' in service and
      'GameplayLinkSnapshot.from(linkProperties)' in body(service, 'override fun onLinkPropertiesChanged(') and
      'postGameplayNetworkEvent' in body(service, 'override fun onLinkPropertiesChanged('))
check('Wi-Fi loss is compared to the bound Network object', 'network == boundGameplayNetwork' in body(service, 'override fun onLost('))
kern = body(service, 'private fun isKernelGameplayPathAvailable(')
check('kernel fallback requires original role and live source interface', 'path.isHotspotOwnerStyle' in kern and 'LanPathResolver.isStillAvailable(path)' in kern)
refresh = body(service, 'private fun refreshGameplayPath(')
check('retry uses elapsed clock and is cancelled during stop', 'SystemClock.elapsedRealtime()' in refresh and 'scheduleGameplayRetry' in refresh and 'stopVpnGuard.get()' in refresh)
check('IPv6 proxy separates both endpoints in cache key', '${formatIpv6Address(srcAddr, 0)}:$srcPort->${formatIpv6Address(destAddr, 0)}:$destPort' in bridge)
for method in ['udpOutputLoop()', 'tcpOutputLoop()']:
    segment = body(bridge, 'private fun ' + method)
    check(method + ' returns its pooled packet after exceptions', re.search(r'finally\s*\{\s*(?://[^\n]*\n\s*)*udpTcpPacketPool.release\(qp\)', segment) is not None)
print(f'SUMMARY checks={checks} failures=0')
