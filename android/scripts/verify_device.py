"""Run release checks and device tests, collecting synthetic UI review screenshots."""
import pathlib
import subprocess
import sys

try:
    subprocess.run([sys.executable, 'scripts/smoke.py'], check=True)
    subprocess.run(['gradle', '--no-daemon', 'connectedDebugAndroidTest'], check=True)
finally:
    target = pathlib.Path('ui-review-artifacts')
    target.mkdir(exist_ok=True)
    subprocess.run(['adb', 'pull', '/sdcard/Android/data/cn.studybridge.mobile/files/ui-review/.', str(target)], check=False)
