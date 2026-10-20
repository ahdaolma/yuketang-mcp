"""Run release checks and device tests, collecting synthetic UI review screenshots."""
import pathlib
import hashlib
import subprocess
import sys
import os

passed = False
try:
    if os.environ.get('RAIN_VERIFY_RELEASE_UPDATE') == '1' and pathlib.Path('app/build/outputs/apk/release/app-release.apk').exists():
        subprocess.run([sys.executable, 'scripts/verify_update.py'], check=True)
    subprocess.run([sys.executable, 'scripts/smoke.py'], check=True)
    subprocess.run(['gradle', '--no-daemon', 'connectedDebugAndroidTest'], check=True)
    passed = True
finally:
    target = pathlib.Path('ui-review-artifacts')
    target.mkdir(exist_ok=True)
    transfer = subprocess.run(['adb', 'pull', '/sdcard/Pictures/StudyUiReview', str(target)], check=False)
    if passed:
        transfer.check_returncode()
        required = {'sample-courses-light.png', 'sample-notes-light.png', 'sample-note-reading.png', 'sample-class-large-text.png', 'sample-settings-large-text.png', 'sample-notes-dark.png', 'sample-settings-dark.png', 'sample-note-reading-dark.png', 'sample-note-reading-large-text.png', 'sample-web-restored.png', 'sample-learner-settings.png', 'sample-learner-dark.png', 'sample-model-picker.png', 'sample-materials.png', 'sample-slide-preview.png', 'sample-note-with-slide.png', 'sample-note-reading-compact.png'}
        found = {p.name for p in target.rglob('*.png')}
        assert required <= found, f'Missing UI review screenshots: {required - found}'
        screenshots = [p for p in target.rglob('*.png') if p.name in required]
        digests = {hashlib.sha256(p.read_bytes()).hexdigest() for p in screenshots}
        assert len(digests) == len(required), 'UI screenshots captured a stale or duplicate frame'
