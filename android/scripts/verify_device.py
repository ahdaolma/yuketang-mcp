"""Run release checks and device tests, collecting synthetic UI review screenshots."""
import pathlib
import hashlib
import subprocess
import sys
import os

passed = False
try:
    if os.environ.get('RAIN_VERIFY_RELEASE_UPDATE') == '1' and pathlib.Path('app/build/outputs/apk/release/app-release.apk').exists():
        subprocess.run([sys.executable, 'scripts/verify_update.py'], check=True)
    subprocess.run([sys.executable, 'scripts/smoke.py'], check=True)
    subprocess.run(['gradle', '--no-daemon', 'connectedDebugAndroidTest'], check=True)
    passed = True
finally:
    target = pathlib.Path('ui-review-artifacts')
    target.mkdir(exist_ok=True)
    transfer = subprocess.run(['adb', 'pull', '/sdcard/Pictures/StudyUiReview', str(target)], check=False)
    pdf_transfer = subprocess.run(['adb', 'pull', '/sdcard/Download/StudyUiReview', str(target / 'pdf')], check=False)
    if passed:
        transfer.check_returncode()
        pdf_transfer.check_returncode()
        assert any(p.stat().st_size > 0 for p in target.rglob('sample-note-export.pdf')), 'Missing exported note PDF'
        required = {'sample-courses-light.png', 'sample-notes-light.png', 'sample-note-reading.png', 'sample-class-large-text.png', 'sample-settings-large-text.png', 'sample-notes-dark.png', 'sample-settings-dark.png', 'sample-note-reading-dark.png', 'sample-note-reading-large-text.png', 'sample-learner-settings.png', 'sample-learner-dark.png', 'sample-model-picker.png', 'sample-materials.png', 'sample-slide-preview.png', 'sample-note-with-slide.png', 'sample-note-reading-compact.png', 'sample-slide-full-preview.png', 'sample-chat-reading.png', 'sample-chat-reading-narrow-bars.png', 'sample-chat-reference-large-text.png', 'sample-chat-landscape.png', 'sample-note-landscape.png', 'sample-chat-landscape-typing.png', 'sample-note-tools.png', 'sample-chat-floating-tools.png', 'sample-chat-landscape-restored.png', 'sample-note-longpress.png', 'sample-note-categories.png', 'sample-note-delete-confirmation.png', 'sample-note-floating-controls.png', 'sample-note-autosave.png', 'sample-note-delete-pages.png', 'sample-note-image-editor.png', 'sample-note-memory-restored.png', 'sample-note-marks-light.png', 'sample-note-marks-dark.png', 'sample-class-floating-control.png', 'sample-class-edit-saved.png', 'sample-personal-note-remark.png', 'sample-note-search-result.png', 'sample-note-selection-tools.png', 'sample-settings-overview.png', 'sample-settings-super-focus.png', 'sample-settings-model-setup.png', 'sample-courses-with-paused-class.png', 'sample-notes-with-paused-class.png', 'sample-class-management.png', 'sample-page-switch-mid.png', 'sample-page-switch-complete.png'}
        required.update({'sample-note-image-full-width.png', 'sample-image-markup-saved.png', 'sample-class-system-circle-over-home.png', 'sample-note-pdf-first.png', 'sample-note-pdf-last.png', 'sample-notebook-details.png', 'sample-notebook-history.png', 'sample-notebook-trash.png', 'sample-notebook-tools.png'})
        found = {p.name for p in target.rglob('*.png')}
        assert required <= found, f'Missing UI review screenshots: {required - found}'
        screenshots = [p for p in target.rglob('*.png') if p.name in required]
        digests = {hashlib.sha256(p.read_bytes()).hexdigest() for p in screenshots}
        assert len(digests) == len(required), 'UI screenshots captured a stale or duplicate frame'
