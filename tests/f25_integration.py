#!/usr/bin/env python3
"""Cross-file source contracts. These checks are not a Kotlin compiler or UI test."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/peerlink/app'
source = {p: p.read_text() for p in JAVA.rglob('*.kt')}
checks = 0

def check(name, ok, detail=''):
    global checks
    checks += 1
    print(('PASS ' if ok else 'FAIL ') + name + (': ' + detail if detail else ''))
    if not ok:
        raise AssertionError(name)

app_state = (JAVA / 'core/AppState.kt').read_text()
state_members = set(re.findall(r'\b(?:fun|val|var)\s+(\w+)', app_state))
unknown = sorted({m for s in source.values() for m in re.findall(r'\bAppState\.(\w+)', s)} - state_members)
check('AppState references resolve across application sources', not unknown, ', '.join(unknown))

for enum_name, path in [('PeerSessionPhase', JAVA/'core/AppState.kt'), ('DiscoveryPhase', JAVA/'ui/DiscoveryUiState.kt')]:
    body = re.search(r'enum class ' + enum_name + r'\s*\{([^}]+)\}', path.read_text()).group(1)
    values = set(re.findall(r'\b[A-Z][A-Z_]+\b', body))
    used = {m for s in source.values() for m in re.findall(r'\b' + enum_name + r'\.([A-Z][A-Z_]+)\b', s)}
    check(enum_name + ' references resolve', used <= values, ', '.join(sorted(used - values)))

resources = set()
for p in (ROOT/'app/src/main/res').rglob('*'):
    if not p.is_file(): continue
    kind = p.parent.name.split('-')[0]
    if kind != 'values': resources.add((kind, p.stem))
    if p.suffix == '.xml':
        xml = ET.parse(p).getroot()
        if kind == 'values':
            for node in xml:
                if 'name' in node.attrib:
                    resources.add((node.attrib.get('type', node.tag), node.attrib['name']))
# resValue declarations in the app build also create resources.
resources.update(re.findall(r'resValue\("([^"]+)",\s*"([^"]+)"', (ROOT/'app/build.gradle.kts').read_text()))
used = {m for s in source.values() for m in re.findall(r'(?<!android\.)\bR\.([a-z_]+)\.(\w+)', s)}
check('local Android resource references resolve', used <= resources, str(sorted(used - resources)))

main = (JAVA/'ui/MainActivity.kt').read_text()
ui = (JAVA/'ui/PeerLinkScreen.kt').read_text()
check('call action reaches the permission-aware handler', 'setCallBlock = { on -> setCallBlockSafely(on) }' in main)
check('marker setting has an activity implementation', 'setMatchMarker = { on ->' in main and 'onMatchMarker = actions.setMatchMarker' in ui)
check('diagnostic export is reachable from Settings', 'onExportLogs = actions.exportMatchLogs' in ui and 'onExportLogs()' in ui)
check('UI retains all connection phases including cleanup', all('PeerSessionPhase.' + name in ui for name in ['IDLE','PAIRING','STARTING_TUNNEL','VERIFYING_PATH','ACTIVE','STOPPING','ERROR']))
check('UI flows use lifecycle-aware collection', '.collectAsState()' not in ui and '.collectAsStateWithLifecycle()' in ui)
workflow = (ROOT/'.github/workflows/build-apk.yml').read_text()
check('CI compiles actual source and runs Kotlin tests', ':app:testDebugUnitTest :app:assembleDebug' in workflow and 'patch --' not in workflow and 'unzip ' not in workflow)
print(f'SUMMARY checks={checks} failures=0')
