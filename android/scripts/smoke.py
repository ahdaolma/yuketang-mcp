"""Launch the built APK and inspect actual Compose UI on a clean emulator."""
import pathlib
import re
import subprocess
import time
import xml.etree.ElementTree as ET

out = pathlib.Path('smoke-artifacts')
out.mkdir(exist_ok=True)
def adb(*args):
    return subprocess.check_output(['adb', *args])
def nodes():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    return ET.fromstring(adb('shell', 'cat', '/sdcard/ui.xml')).iter('node')
def tap(label):
    for n in nodes():
        if n.get('text') == label or n.get('content-desc') == label:
            x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
            adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
            time.sleep(1)
            return
    raise AssertionError(f'Missing navigation: {label}')
def shot(name, required):
    items = list(nodes())
    text = '\n'.join((n.get('text', '') + ' ' + n.get('content-desc', '')) for n in items)
    (out / f'{name}.txt').write_text(text)
    for label in required:
        assert label in text, f'{name}: missing {label}'
    (out / f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))

adb('install','-r','app/build/outputs/apk/release/app-release.apk')
adb('logcat','-c')
adb('shell','am','start','-W','-n','cn.studybridge.mobile/.MainActivity')
time.sleep(3)
shot('chat', ['雨课伴学', '把每一页'])
tap('雨课堂'); shot('rain', ['上课模式', '尚未连接账号'])
tap('笔记'); shot('notes', ['我的学习笔记'])
tap('模型'); shot('models', ['用你选择的 AI', '新增 OpenAI'])
tap('新增 OpenAI')
# Selected OpenAI provider must expose Responses, not silently keep DeepSeek.
shot('openai', ['OpenAI GPT', 'Responses'])
logs = adb('logcat','-d','-s','AndroidRuntime:E').decode(errors='replace')
(out / 'runtime.txt').write_text(logs)
assert 'FATAL EXCEPTION' not in logs, logs
print('APK launch and all four tabs passed.')
