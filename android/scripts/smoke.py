"""Launch the built APK and inspect actual Compose UI on a clean emulator."""
import pathlib
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import sys

out = pathlib.Path('smoke-artifacts')
out.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb', *args], timeout=25)

def failure_diagnostics(kind, value, traceback):
    for name, args in {
        'failure.png': ('exec-out', 'screencap', '-p'),
        'failure-runtime.txt': ('logcat', '-d'),
        'failure-activity.txt': ('shell', 'dumpsys', 'activity', 'activities'),
        'failure-ui.xml': ('shell', 'cat', '/sdcard/ui.xml'),
    }.items():
        try:
            (out / name).write_bytes(adb(*args))
        except Exception:
            pass
    sys.__excepthook__(kind, value, traceback)

sys.excepthook = failure_diagnostics

def nodes():
    # Chromium/activity handoff can temporarily return a null accessibility root.
    # Remove stale dumps and retry acquisition only; required app controls still
    # must exist in a newly obtained tree for the test to pass.
    for attempt in range(6):
        try:
            adb('shell', 'rm', '-f', '/sdcard/ui.xml')
            adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
            result = list(ET.fromstring(adb('shell', 'cat', '/sdcard/ui.xml')).iter('node'))
            if result:
                return result
        except (subprocess.SubprocessError, ET.ParseError):
            pass
        time.sleep(0.5)
    raise AssertionError('Emulator accessibility root did not become available')
def reveal(label):
    size = adb('shell', 'wm', 'size').decode()
    width, height = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])
    for attempt in range(6):
        for n in nodes():
            if n.get('text') == label or n.get('content-desc') == label:
                x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
                if x2 > x1 and y2 > y1 and y1 < height and y2 > 0:
                    return ((x1+x2)//2, (y1+y2)//2)
        adb('shell','input','swipe',str(width//2),str(height*3//4),str(width//2),str(height//3),'250')
        time.sleep(0.5)
    raise AssertionError(f'Missing control: {label}')
def tap(label):
    x, y = reveal(label)
    adb('shell','input','tap',str(x),str(y))
    time.sleep(1)
def shot(name, required):
    # A successful launch command is not a Compose frame/readiness fence.
    for attempt in range(8):
        items = nodes()
        text = '\n'.join((n.get('text', '') + ' ' + n.get('content-desc', '')) for n in items)
        if all(label in text for label in required):
            break
        time.sleep(0.5)
    (out / f'{name}.txt').write_text(text)
    for label in required:
        assert label in text, f'{name}: missing {label}'
    (out / f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))

adb('install','-r',os.environ.get('RAIN_SMOKE_APK', 'app/build/outputs/apk/release/app-release.apk'))
adb('logcat','-c')
adb('shell','am','start','-W','-n','cn.studybridge.mobile/.MainActivity')
time.sleep(3)
shot('study-web', ['学习', 'ChatGPT 网页', '保存回答'])
tap('课程'); shot('courses', ['尚未连接账号'])
tap('笔记'); shot('notes', ['0 份笔记', '保存网页回答'])
tap('设置'); shot('settings-web', ['学习方式', '网页跟课适配', '模型接口'])
# API details are hidden by default; switching is explicit.
tap('模型接口')
tap('配置模型与账号'); reveal('Continue with ChatGPT'); shot('models-advanced', ['使用 ChatGPT 账号', 'Continue with ChatGPT'])
# No credential or account is supplied. Exercise the authorization service and cancellation only.
tap('Continue with ChatGPT')
time.sleep(3)
adb('shell','am','start','-W','-n','cn.studybridge.mobile/.MainActivity')
time.sleep(1)
for attempt in range(4):
    items = list(nodes())
    auth_text = '\n'.join(n.get('text','') for n in items)
    if '取消本次授权' in auth_text or 'ChatGPT 登录未完成' in auth_text:
        break
    adb('shell','input','swipe','220','500','220','250','250')
    time.sleep(1)
if '取消本次授权' in auth_text:
    shot('authorization', ['取消本次授权'])
    service = adb('shell','dumpsys','activity','services','cn.studybridge.mobile').decode(errors='replace')
    (out / 'authorization-service.txt').write_text(service)
    assert 'AuthorizationService' in service and 'isForeground=true' in service, service
    tap('取消本次授权')
    shot('authorization-canceled', ['已取消本次授权'])
else:
    # CI may be unable to reach OpenAI; a network failure must be explicit and nonfatal.
    shot('authorization-network-failure', ['ChatGPT 登录未完成'])
logs = adb('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace')
(out / 'runtime.txt').write_text(logs)
assert 'FATAL EXCEPTION' not in logs, logs
tap('返回学习')
tap('学习'); shot('study-native', ['从一页课件开始', '选择课程'])
print('APK web default, three study tabs, settings and authorization controls passed.')
# Review actual release UI in dark mode and a narrow viewport with larger text.
tap('设置')
tap('ChatGPT 网页')
try:
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    shot('settings-dark', ['设置', '学习方式', '模型接口'])
    density_text = adb('shell', 'wm', 'density').decode()
    density = int(re.findall(r'\d+', density_text)[-1])
    adb('shell', 'wm', 'size', f'{round(320*density/160)}x{round(700*density/160)}')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(2)
    shot('settings-narrow-large', ['设置', '学习方式', 'ChatGPT 网页', '模型接口'])
    reveal('学习助手提示词')
    shot('settings-narrow-bottom', ['学习助手提示词'])
    tap('返回学习')
    tap('学习')
    shot('study-web-narrow-large', ['ChatGPT 网页', '保存回答'])
finally:
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    time.sleep(1)
logs = adb('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace')
(out / 'runtime-final.txt').write_text(logs)
assert 'FATAL EXCEPTION' not in logs, logs
print('Dark theme and 320dp / 1.3x text release screens passed.')
