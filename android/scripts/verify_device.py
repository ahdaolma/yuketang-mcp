"""Run release checks and device tests, collecting synthetic UI review screenshots."""
import pathlib
import subprocess
import sys

passed = False
try:
    subprocess.run([sys.executable, 'scripts/smoke.py'], check=True)
    subprocess.run(['gradle', '--no-daemon', 'connectedDebugAndroidTest'], check=True)
    passed = True
finally:
    target = pathlib.Path('ui-review-artifacts')
    target.mkdir(exist_ok=True)
    transfer = subprocess.run(['adb', 'pull', '/sdcard/Pictures/StudyUiReview', str(target)], check=False)
    if passed:
        transfer.check_returncode()
        required = {'sample-courses-light.png', 'sample-notes-light.png', 'sample-note-reading.png', 'sample-class-large-text.png', 'sample-settings-large-text.png', 'sample-notes-dark.png', 'sample-settings-dark.png', 'sample-note-reading-dark.png', 'sample-note-reading-large-text.png'}
        found = {p.name for p in target.rglob('*.png')}
        assert required <= found, f'Missing UI review screenshots: {required - found}'
