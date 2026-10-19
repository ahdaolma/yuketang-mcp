"""Restore only the existing pinned release identity; never generate a CI key."""
import base64
import hashlib
import json
import os
import pathlib
import subprocess
import tempfile


def restore(bundle_text, directory, expected):
    data = json.loads(bundle_text)
    assert data.get('format') == 1 and data.get('alias') == 'rain-study'
    assert data.get('certificate_sha256') == expected
    passwords = [data['store_password'], data['key_password']]
    assert all(isinstance(p, str) and 16 <= len(p) <= 256 and '\n' not in p and '\r' not in p for p in passwords)
    key = base64.b64decode(data['keystore_base64'], validate=True)
    assert 512 <= len(key) <= 32768
    directory = pathlib.Path(directory)
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / 'rain-study-release.p12'
    with path.open('wb') as output:
        os.chmod(path, 0o600)
        output.write(key)
    env = dict(os.environ, RAIN_CHECK_PASSWORD=passwords[0])
    result = subprocess.run(['keytool', '-exportcert', '-keystore', str(path), '-alias', data['alias'], '-storepass:env', 'RAIN_CHECK_PASSWORD'], env=env, capture_output=True)
    assert result.returncode == 0 and hashlib.sha256(result.stdout).hexdigest() == expected
    return {'RAIN_SIGNING_KEYSTORE': str(path), 'RAIN_SIGNING_STORE_PASSWORD': passwords[0], 'RAIN_SIGNING_KEY_PASSWORD': passwords[1], 'RAIN_SIGNING_KEY_ALIAS': data['alias']}


if __name__ == '__main__':
    expected = (pathlib.Path(__file__).parent.parent / 'signing-certificate.sha256').read_text().strip()
    try:
        values = restore(os.environ['ANDROID_RELEASE_SIGNING'], pathlib.Path(os.environ.get('RUNNER_TEMP', tempfile.gettempdir())) / 'rain-release-signing', expected)
    except Exception:
        raise SystemExit('Fixed signing identity is missing, invalid or different from the pinned certificate. Release stopped.') from None
    for key in ('RAIN_SIGNING_STORE_PASSWORD', 'RAIN_SIGNING_KEY_PASSWORD'):
        print('::add-mask::' + values[key])
    with open(os.environ['GITHUB_ENV'], 'a') as output:
        for key, value in values.items():
            output.write(key + '=' + value + '\n')
        output.write('RAIN_SIGNING_CONFIGURED=true\n')
    print('Fixed signing certificate verified: ' + expected)
