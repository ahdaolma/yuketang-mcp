"""Verify replacement preserves an encrypted preference on a disposable CI emulator."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(['adb', *args])


assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Upgrade check is emulator-only'
package = 'cn.studybridge.mobile'
apk = 'app/build/outputs/apk/release/app-release.apk'
out = pathlib.Path('signing-artifacts')
out.mkdir(exist_ok=True)


def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/update-ui.xml')
    return list(ET.fromstring(adb('shell', 'cat', '/sdcard/update-ui.xml')).iter('node'))


def tap(label):
    for attempt in range(8):
        for node in nodes():
            if label in (node.get('text'), node.get('content-desc')):
                x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
                if x2 > x1 and y2 > y1:
                    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                    time.sleep(0.5)
                    return
        time.sleep(0.5)
    raise AssertionError('Missing upgrade control: ' + label)


def start():
    adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    time.sleep(1)


def native_mode():
    for attempt in range(8):
        if any(node.get('text') == '从一页课件开始' for node in nodes()):
            return
        time.sleep(0.5)
    raise AssertionError('Encrypted learning preference did not survive replacement')


try:
    adb('install', '-r', apk)
    start()
    tap('设置')
    tap('模型接口')
    tap('返回学习')
    native_mode()
    # Restart first: prove the preference was persisted, rather than only kept in RAM.
    adb('shell', 'am', 'force-stop', package)
    start()
    native_mode()
    result = adb('install', '-r', apk).decode()
    assert 'Success' in result, 'Fixed-key replacement failed'
    start()
    native_mode()
    (out / 'upgrade.txt').write_text('PASS: adb install -r succeeded; encrypted learning preference survived restart and APK replacement.\n')
    (out / 'upgrade.png').write_bytes(adb('exec-out', 'screencap', '-p'))
finally:
    # Only the disposable, emulator-guarded test installation is removed.
    adb('uninstall', package)
