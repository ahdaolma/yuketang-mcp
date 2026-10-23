"""Verify replacement preserves an encrypted preference on a disposable CI emulator."""
import pathlib
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.check_output(['adb', *args])


assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Upgrade check is emulator-only'
package = 'cn.studybridge.mobile'
apk = os.environ.get('RAIN_UPDATE_APK', 'app/build/outputs/apk/release/app-release.apk')
baseline_apk = os.environ.get('RAIN_UPDATE_BASELINE_APK', apk)
expected_version = os.environ.get('RAIN_UPDATE_EXPECTED_VERSION', '')
baseline_version = os.environ.get('RAIN_UPDATE_BASELINE_VERSION', '')
legacy_marker = 'rain_upgrade_fixture_v' + baseline_version.replace('.', '')
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
        items = nodes()
        # A setting label and its switch share a name. Prefer the actual
        # accessible control instead of tapping the non-interactive label.
        candidates = [n for n in items if n.get('content-desc') == label]
        if not candidates:
            candidates = [n for n in items if n.get('text') == label]
        for node in candidates:
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


def wait_text(text):
    for attempt in range(8):
        if any(text in node.get('text', '') for node in nodes()):
            return
        time.sleep(0.5)
    raise AssertionError('Missing persisted upgrade text: ' + text)


def version():
    result = adb('shell', 'dumpsys', 'package', package).decode()
    match = re.search(r'\bversionName=([^\s]+)', result)
    assert match, 'Installed version must be inspectable'
    return match.group(1)


def seed_legacy_note():
    tap('笔记')
    tap('新建笔记')
    tap('Markdown 笔记')
    adb('shell', 'input', 'text', legacy_marker)
    wait_text(legacy_marker)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    time.sleep(0.5)
    tap('保存修改')
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    wait_text(legacy_marker)
    tap('学习')


def verify_legacy_note():
    tap('笔记')
    wait_text(legacy_marker)
    (out / 'upgrade-note.png').write_bytes(adb('exec-out', 'screencap', '-p'))
    tap('学习')


def focus_mode():
    tap('设置')
    tap('笔记生成')
    for attempt in range(8):
        if any(node.get('content-desc') == '超重点模式' and node.get('checked') == 'true' for node in nodes()):
            tap('返回设置')
            tap('返回学习')
            return
        time.sleep(0.5)
    raise AssertionError('Encrypted super-focus preference did not survive replacement')


try:
    adb('logcat', '-c')
    adb('install', '-r', baseline_apk)
    start()
    before_version = version()
    if baseline_apk != apk:
        assert baseline_version and before_version == baseline_version, 'Pinned published upgrade baseline changed'
        seed_legacy_note()
    tap('设置')
    tap('笔记生成')
    switch = next(n for n in nodes() if n.get('content-desc') == '超重点模式')
    assert switch.get('checked') == 'false', 'Disposable installation must start with super-focus disabled'
    tap('超重点模式')
    tap('返回设置')
    tap('返回学习')
    focus_mode()
    # Restart first: prove the preference was persisted, rather than only kept in RAM.
    adb('shell', 'am', 'force-stop', package)
    start()
    focus_mode()
    if baseline_apk != apk:
        verify_legacy_note()
    result = adb('install', '-r', apk).decode()
    assert 'Success' in result, 'Fixed-key replacement failed'
    start()
    after_version = version()
    if expected_version:
        assert after_version == expected_version, 'Installed upgrade version differs from release candidate'
    focus_mode()
    if baseline_apk != apk:
        verify_legacy_note()
    (out / 'upgrade.txt').write_text(f'PASS: {before_version} -> {after_version}; adb install -r succeeded; encrypted super-focus preference survived restart and APK replacement.' + (f' A manual note created in published {before_version} also survived restart and upgrade.' if baseline_apk != apk else '') + '\n')
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

