#!/usr/bin/env bash
set -eu
test_root="$(cd "$(dirname "$0")/.." && pwd)"
test_build="$(mktemp -d "${TMPDIR:-/tmp}/peerlink-native-tests.XXXXXX")"
g++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer -pthread \
  -I"$test_root/tests/shim" "$test_root/tests/native_regression.cpp" \
  -o "$test_build/native_regression"
ASAN_OPTIONS=detect_leaks=0 "$test_build/native_regression"
