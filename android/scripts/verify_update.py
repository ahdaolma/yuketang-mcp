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
    for attempt in range(6):
        try:
            adb('shell', 'rm', '-f', '/sdcard/update-ui.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/update-ui.xml')
            result = list(ET.fromstring(adb('shell', 'cat', '/sdcard/update-ui.xml')).iter('node'))
            # Some cold Google API emulators show an ANR from their own launcher
            # after boot. Dismiss only that exact system-owned dialog; an ANR
            # from our application must still fail and preserve diagnostics.
            launcher_dialog = any(n.get('package') == 'android' and n.get('text') == "Pixel Launcher isn't responding" for n in result)
            if launcher_dialog:
                activities = adb('shell', 'dumpsys', 'activity', 'activities')
                if b'Application Not Responding: com.google.android.apps.nexuslauncher' in activities:
                    close = next((n for n in result if n.get('package') == 'android' and n.get('resource-id') == 'android:id/aerr_close'), None)
                    if close is not None:
                        (out / 'launcher-anr.png').write_bytes(adb('exec-out', 'screencap', '-p'))
                        (out / 'launcher-anr.xml').write_bytes(adb('shell', 'cat', '/sdcard/update-ui.xml'))
                        (out / 'launcher-anr-activity.txt').write_bytes(activities)
                        x1, y1, x2, y2 = map(int, re.findall(r'\d+', close.get('bounds')))
                        assert x2 > x1 and y2 > y1
                        adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))
                        (out / 'launcher-recovery.txt').write_text('Dismissed the emulator Pixel Launcher ANR; application checks remain mandatory.\n')
                        time.sleep(1)
                        continue
            if result:
                return result
        except (subprocess.CalledProcessError, ET.ParseError):
            pass
        time.sleep(1)
    raise AssertionError('Emulator accessibility tree did not become ready')


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
    result = adb('shell', 'am', 'start', '-W', '-n', package + '/.MainActivity')
    with (out / 'upgrade-launch.txt').open('ab') as stream:
        stream.write(result + b'\n')
    time.sleep(1)


def native_mode():
    for attempt in range(8):
        if any(node.get('text') == '从一页课件开始' for node in nodes()):
            return
        time.sleep(0.5)
    raise AssertionError('Encrypted learning preference did not survive replacement')


try:
    adb('logcat', '-c')
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
except Exception:
    # Preserve the original failure before the disposable installation is removed.
    # A missing control must not hide a release-only crash or a system dialog.
    commands = {
        'upgrade-failure.png': ('exec-out', 'screencap', '-p'),
        'upgrade-runtime.txt': ('logcat', '-d'),
        'upgrade-activity.txt': ('shell', 'dumpsys', 'activity', 'activities'),
        'upgrade-window.txt': ('shell', 'dumpsys', 'window', 'windows'),
        'upgrade-ui.xml': ('shell', 'cat', '/sdcard/update-ui.xml'),
    }
    for name, command in commands.items():
        try:
            (out / name).write_bytes(adb(*command))
        except Exception as error:
            (out / (name + '.error')).write_text(type(error).__name__)
    raise
finally:
    # Only the disposable, emulator-guarded test installation is removed.
    adb('uninstall', package)
