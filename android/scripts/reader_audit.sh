#!/usr/bin/env bash
set -euo pipefail
# The emulator runner stops the device before subsequent workflow steps.
# Pull evidence inside its live session, on success and on test failure.
status=0
gradle --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.studybridge.mobile.ReaderPaperVisualTest,cn.studybridge.mobile.ReaderMotionPreviewTest || status=$?
adb pull /sdcard/Pictures/StudyUiReview reader-audit-ui || true
exit "$status"
