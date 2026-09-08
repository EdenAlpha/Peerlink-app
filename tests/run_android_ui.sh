#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
collect_ui() {
  mkdir -p verification/ui
  adb pull /sdcard/Android/data/com.peerlink.app/files/ui-checks/. verification/ui/ || true
  python3 - <<'PY'
from pathlib import Path
import base64, textwrap
for path in sorted(Path('verification/ui').glob('*-preview.jpg')):
    print('UI_PREVIEW_BEGIN ' + path.name)
    print('\n'.join(textwrap.wrap(base64.b64encode(path.read_bytes()).decode(), 76)))
    print('UI_PREVIEW_END ' + path.name)
PY
}
trap collect_ui EXIT
bash gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.peerlink.app.ui.PeerLinkUiTest --stacktrace
