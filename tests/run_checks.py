#!/usr/bin/env python3
"""Current, read-only host checks. Android compilation is a separate CI step."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'verification'
OUTPUT.mkdir(exist_ok=True)

def source_hashes():
    return {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted((ROOT / 'app').rglob('*')) if p.is_file()}

before = source_hashes()
results = []

def run(name, command, env=None):
    result = subprocess.run(command, cwd=ROOT, text=True, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, env=env, timeout=180)
    (OUTPUT / (name + '.log')).write_text(result.stdout)
    results.append({'suite': name, 'exit_code': result.returncode})
    print(('PASS ' if result.returncode == 0 else 'FAIL ') + name, flush=True)
    if result.returncode:
        print(result.stdout[-3000:], flush=True)

for name in [
    'kotlin_structural', 'f11_prime_discovery', 'f16_ui_discovery_regression',
    'f20_capability_regression', 'f21_runtime_ui_regression',
    'f22_runtime_performance_regression',
    'f24_light_trace_marker_regression', 'f31_match_automation_regression', 'f25_integration', 'f26_integration', 'trace_analyzer_test',
]:
    run(name, [sys.executable, 'tests/' + name + '.py'])

with tempfile.TemporaryDirectory(prefix='peerlink-checks-') as temp:
    for name, production in [('GameplayPathPolicyRegression', 'GameplayPathPolicy'), ('UdpProxySocketsRegression', 'UdpProxySockets')]:
        run(name + '_compile', [
            'java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '17', '-d', temp,
            'app/src/main/java/com/peerlink/app/network/' + production + '.java', 'tests/' + name + '.java',
        ])
        if results[-1]['exit_code'] == 0:
            run(name, ['java', '-cp', temp, name])
    for name in ['native_regression', 'f25_runtime_regression', 'f26_wifi_socket_regression', 'f27_latency_regression']:
        binary = str(Path(temp) / name)
        run(name + '_compile', [
            'g++', '-std=c++17', '-O1', '-g', '-fsanitize=address,undefined',
            '-fno-omit-frame-pointer', '-pthread', '-Itests/shim', 'tests/' + name + '.cpp', '-o', binary,
        ])
        if results[-1]['exit_code'] == 0:
            env = os.environ.copy()
            # LeakSanitizer cannot enumerate threads in the supplied container.
            # ASan bounds/lifetime checks and UBSan remain enabled.
            env['ASAN_OPTIONS'] = 'detect_leaks=0'
            run(name, [binary], env)

unchanged = before == source_hashes()
results.append({'suite': 'tests_do_not_modify_app_source', 'exit_code': 0 if unchanged else 1})
print(('PASS ' if unchanged else 'FAIL ') + 'tests_do_not_modify_app_source')
(OUTPUT / 'results.json').write_text(json.dumps({
    'results': results,
    'android_build_verified': False,
    'on_device_gameplay_verified': False,
    'note': 'Host checks do not compile Compose or verify radio/gameplay performance. See the Android CI build separately.',
}, indent=2) + '\n')
sys.exit(1 if any(r['exit_code'] != 0 for r in results) else 0)
