"""Reject every release APK whose actual signer differs from the pinned key."""
import os
import pathlib
import re
import subprocess
import zipfile

root = pathlib.Path(__file__).parent.parent
expected = (root / 'signing-certificate.sha256').read_text().strip()
apksigner = pathlib.Path(os.environ['ANDROID_HOME']) / 'build-tools/35.0.0/apksigner'
apk = root / 'app/build/outputs/apk/release/app-release.apk'
result = subprocess.run([str(apksigner), 'verify', '--verbose', '--print-certs', str(apk)], capture_output=True, text=True)
assert result.returncode == 0, 'Release APK signature verification failed'
signers = re.findall(r'Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)', result.stdout)
assert [s.lower() for s in signers] == [expected], 'Release signer changed; publication stopped'
assert re.search(r'Verified using v2 scheme[^\n]*: true', result.stdout), 'APK v2 signature required'
with zipfile.ZipFile(apk) as archive:
    bundled_license = archive.read('assets/LICENSE.txt').decode('utf-8').replace('\r\n', '\n')
expected_license = (root.parent / 'LICENSE').read_text().replace('\r\n', '\n')
assert bundled_license == expected_license, 'Release APK must include the project MIT copyright and license'
report = root / 'signing-artifacts'
report.mkdir(exist_ok=True)
(report / 'release-signature.txt').write_text(result.stdout)
(report / 'release-license.txt').write_text('PASS: bundled MIT copyright and license match the project LICENSE.\n')
print('Release APK uses pinned certificate: ' + expected)
