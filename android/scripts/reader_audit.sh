#!/usr/bin/env bash
set -euo pipefail
# The emulator runner stops the device before subsequent workflow steps.
# Pull evidence inside its live session, on success and on test failure.
status=0
gradle --no-daemon connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=cn.studybridge.mobile.ReaderExperienceTest,cn.studybridge.mobile.ReaderNativeTapTest,cn.studybridge.mobile.ReaderMotionPreviewTest,cn.studybridge.mobile.ReaderSourceAnnotationTest,cn.studybridge.mobile.ReaderBackwardStabilityTest,cn.studybridge.mobile.ReaderLegacyAnnotationTest || status=$?
adb pull /sdcard/Pictures/StudyUiReview reader-audit-ui || true
exit "$status"
