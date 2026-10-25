"""Launch the built APK and inspect actual Compose UI on a clean emulator."""
import pathlib
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import sys
import shlex
import json

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

def type_ascii(value):
    # input text stamps a complete KeyCharacterMap batch before dispatching.
    # Long batches can outlive InputDispatcher's stale-event deadline on a
    # software-rendered emulator. New batches refresh timestamps; assertions
    # below still require every original character, without retrying lost text.
    for start in range(0, len(value), 64):
        adb('shell', 'input text ' + shlex.quote(value[start:start + 64].replace(' ', '%s')))
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
shot('study-native', ['从一页课件开始', '选择学习资料'])
tap('课程'); shot('courses', ['尚未连接账号'])
tap('笔记'); shot('notes', ['0 份笔记', '生成笔记'])
tap('设置'); shot('settings-overview', ['模型与账号', '笔记生成', '学习偏好', '文件与下载'])
tap('模型与账号'); tap('ChatGPT 账号授权（可选）'); reveal('Continue with ChatGPT'); shot('models-advanced', ['使用 ChatGPT 账号', 'Continue with ChatGPT'])
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
tap('返回设置'); tap('返回学习')
tap('学习'); shot('study-native', ['从一页课件开始', '选择学习资料'])

# Exercise the actual minified, fixed-signature APK through public UI only.
# No debug fixture component or private-data injection is used.
assert adb('shell', 'getprop', 'ro.kernel.qemu').decode().strip() == '1', 'Reader fixture is emulator-only'
tap('笔记'); tap('新建笔记'); tap('Markdown 笔记')
reader_source_lines = ('## R8_FIRST', '', 'release_first_body', '', '## R8_SECOND', '', 'release_second_body')
for index, line in enumerate(reader_source_lines):
    if index:
        adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
    if line:
        type_ascii(line)
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
# Do not misdiagnose a malformed hardware-input fixture as a paging failure.
# The actual editor must retain the complete source before it can be saved.
fixture_nodes = nodes()
fixture_editors = [n.get('text', '') for n in fixture_nodes if n.get('class') == 'android.widget.EditText']
(out / 'release-editor-source.txt').write_text('\n--- editor ---\n'.join(fixture_editors))
assert '\n'.join(reader_source_lines) in fixture_editors, 'Hardware-entered Markdown source changed before saving'
shot('release-editor-source', ['R8_FIRST', 'R8_SECOND', 'release_first_body', 'release_second_body'])
tap('保存修改')
size = adb('shell', 'wm', 'size').decode()
reader_width, reader_height = map(int, re.findall(r'(\d+)x(\d+)', size)[-1])

def reader_settings():
    tap('悬浮阅读工具'); tap('阅读方式与字号')

def reader_page(name, present, absent):
    shot(name, [present])
    text = '\n'.join(n.get('text', '') for n in nodes())
    assert absent not in text, f'{name}: stale native source page remains attached'

for style, label in (('cover', '覆盖翻页'), ('curl', '仿真翻页')):
    reader_settings()
    tap('点击翻页'); tap(label); tap('关闭阅读方式与字号')
    reader_page(f'release-reader-{style}-first', 'R8_FIRST', 'R8_SECOND')
    adb('shell', 'input', 'tap', str(round(reader_width * .9)), str(round(reader_height * .55)))
    reader_page(f'release-reader-{style}-forward', 'R8_SECOND', 'R8_FIRST')
    adb('shell', 'input', 'tap', str(round(reader_width * .1)), str(round(reader_height * .55)))
    reader_page(f'release-reader-{style}-backward', 'R8_FIRST', 'R8_SECOND')
tap('悬浮阅读工具'); tap('返回笔记列表'); tap('新建笔记'); tap('Markdown 笔记')
continuous_lines = ['## R8_CONT_FIRST', '', 'release_cont_first_start', '']
for row in range(7):
    continuous_lines.extend([f'first_row_{row}_' + 'origin_body_' * 6, ''])
continuous_lines.extend(['[short_link](https://example.com/' + 'long_source_path_' * 24 + ')', '',
                         'release_cont_first_end', '', '## R8_CONT_SECOND', '', 'release_cont_second_start', ''])
for row in range(8):
    continuous_lines.extend([f'second_row_{row}_' + 'reader_second_body_' * 10, ''])
for index, line in enumerate(continuous_lines):
    if index:
        adb('shell', 'input', 'keyevent', 'KEYCODE_ENTER')
    if line:
        type_ascii(line)
adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
continuous_editors = [n.get('text', '') for n in nodes() if n.get('class') == 'android.widget.EditText']
(out / 'release-continuous-source-expected.txt').write_text('\n'.join(continuous_lines))
(out / 'release-continuous-source-actual.txt').write_text('\n--- editor ---\n'.join(continuous_editors))
assert '\n'.join(continuous_lines) in continuous_editors, 'Continuous reader source changed before saving'
tap('保存修改')
reader_settings(); tap('连续滚动'); tap('关闭阅读方式与字号')
tap('悬浮阅读工具'); tap('下一段')

def continuous_second_bounds():
    matches = [n for n in nodes() if 'release_cont_second_start' in n.get('text', '')]
    assert len(matches) == 1, 'Expected one actual second-page native body'
    return tuple(map(int, re.findall(r'-?\d+', matches[0].get('bounds', ''))))

continuous_motion = []
for attempt in range(4):
    left, top, right, bottom = continuous_second_bounds()
    bottom = min(bottom, round(reader_height * .94))
    delta = max(60, round((bottom - top) * .05))
    x = (left + right) // 2
    y = top + round((bottom - top) * .4)
    adb('shell', 'input', 'swipe', str(x), str(y), str(x), str(y + delta), '800')
    after = continuous_second_bounds()[1]
    assert top + delta * .25 <= after <= top + delta * 2, f'Small reverse jumped: before={top} after={after} requested={delta}'
    continuous_motion.append({'before': top, 'after': after, 'requested': delta})
(out / 'release-continuous-motion.json').write_text(json.dumps(continuous_motion, indent=2))
shot('release-reader-continuous-small-reverse', ['release_cont_second_start'])

tap('悬浮阅读工具'); tap('返回笔记列表'); tap('学习')
shot('study-after-reader', ['从一页课件开始', '选择学习资料'])
print('APK native study, grouped settings and authorization controls passed.')
print('Actual APK reader: curl/cover forward and backward native source pages passed.')
print('Actual APK reader: four small continuous reverse gestures retained visible progress.')
# Review actual release UI in dark mode and a narrow viewport with larger text.
tap('设置')
try:
    adb('shell', 'cmd', 'uimode', 'night', 'yes')
    time.sleep(2)
    shot('settings-dark', ['设置', '模型与账号', '笔记生成'])
    density_text = adb('shell', 'wm', 'density').decode()
    density = int(re.findall(r'\d+', density_text)[-1])
    adb('shell', 'wm', 'size', f'{round(320*density/160)}x{round(700*density/160)}')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    time.sleep(2)
    shot('settings-narrow-large', ['设置', '模型与账号', '笔记生成', '学习偏好'])
    tap('学习偏好'); tap('学习助手提示词'); reveal('学习助教提示词')
    shot('settings-narrow-bottom', ['学习助手提示词'])
    tap('返回设置'); tap('返回学习')
    tap('学习')
    shot('study-native-narrow-large', ['从一页课件开始', '选择学习资料'])
finally:
    adb('shell', 'wm', 'size', 'reset')
    adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    adb('shell', 'cmd', 'uimode', 'night', 'no')
    time.sleep(1)
logs = adb('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace')
(out / 'runtime-final.txt').write_text(logs)
assert 'FATAL EXCEPTION' not in logs, logs
print('Dark theme and 320dp / 1.3x text release screens passed.')
