#!/usr/bin/env bash
set -eu
if ! command -v kotlinc >/dev/null 2>&1; then
  printf '%s\n' 'NOT RUN: kotlinc is not installed. Alternatively run Gradle testDebugUnitTest with JDK 17+ and Android SDK.'
  exit 2
fi
test_root="$(cd "$(dirname "$0")/.." && pwd)"
test_build="$(mktemp -d "${TMPDIR:-/tmp}/peerlink-stun-tests.XXXXXX")"
kotlinc "$test_root/app/src/main/java/com/peerlink/app/tunnel/StunFabricator.kt" \
  "$test_root/app/src/main/java/com/peerlink/app/tunnel/PacketParser.kt" \
  "$test_root/tests/kotlin/AppState.kt" \
  "$test_root/app/src/test/java/com/peerlink/app/tunnel/StunFabricatorChecks.kt" \
  -include-runtime -d "$test_build/stun-tests.jar"
java -cp "$test_build/stun-tests.jar" com.peerlink.app.tunnel.StunFabricatorChecks
