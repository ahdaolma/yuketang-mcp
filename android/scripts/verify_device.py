"""Run release checks and device tests, collecting synthetic UI review screenshots."""
import pathlib
import subprocess
import sys
import os
from ui_review import verify_screenshots

passed = False
try:
    release_apk = pathlib.Path('app/build/outputs/apk/release/app-release.apk')
    smoke_environment = dict(os.environ)
    if os.environ.get('RAIN_VERIFY_RELEASE_UPDATE') == '1' and release_apk.exists():
        subprocess.run([sys.executable, 'scripts/verify_update.py'], check=True)
        smoke_environment['RAIN_SMOKE_APK'] = str(release_apk)
    subprocess.run([sys.executable, 'scripts/smoke.py'], check=True, env=smoke_environment)
    if smoke_environment.get('RAIN_SMOKE_APK') == str(release_apk):
        assert subprocess.check_output(['adb', 'shell', 'getprop', 'ro.kernel.qemu']).decode().strip() == '1', 'Release smoke cleanup is emulator-only'
        # The instrumentation target uses the debug certificate. Remove only
        # this disposable release installation after its UI checks have passed.
        subprocess.run(['adb', 'uninstall', 'cn.studybridge.mobile'], check=True)
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
        required.update({'sample-reader-time-cutout.png', 'sample-reader-continuous-end.png', 'sample-reader-click-pages.png', 'sample-reader-search-highlight.png', 'sample-reader-font-settings.png', 'sample-reader-sticky-title.png', 'sample-reader-swipe-pages.png'})
        required.update({'sample-expanded-dropdown.png', 'sample-expanded-directory.png', 'sample-expanded-classes.png', 'sample-expanded-landscape.png'})
        required.add('sample-settings-about.png')
        required.update({'sample-reader-title-below-camera.png', 'sample-settings-risk-statement.png'})
        required.add('sample-chat-landscape-busy.png')
        required.update({'sample-notebook-hierarchy.png', 'sample-notebook-tablet-landscape.png', 'sample-notebook-tablet-reader.png', 'sample-notebook-tablet-portrait-large-text.png', 'sample-notebook-split-window.png', 'sample-courseware-tablet-materials.png'})
        required.update({'chat-learning-auto-title.png', 'chat-learning-whole-deck.png', 'chat-learning-large-font.png', 'chat-learning-note-card.png', 'chat-learning-note-edited.png', 'sample-selection-menu-near-text.png', 'sample-selection-menu-top-large-font.png', 'sample-create-move-destination.png'})
        required.update({'sample-model-draft-restored.png', 'sample-image-draft-rotated.png'})
        required.add('sample-model-save-feedback.png')
        verify_screenshots(target, required)
