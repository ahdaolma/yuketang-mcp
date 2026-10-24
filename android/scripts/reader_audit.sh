#!/usr/bin/env bash
set -euo pipefail
# The emulator runner stops the device before subsequent workflow steps.
# Pull evidence inside its live session, on success and on test failure.
status=0
classes=cn.studybridge.mobile.ReaderExperienceTest,cn.studybridge.mobile.ReaderNativeTapTest,cn.studybridge.mobile.ReaderMotionPreviewTest,cn.studybridge.mobile.ReaderSourceAnnotationTest,cn.studybridge.mobile.ReaderBackwardStabilityTest,cn.studybridge.mobile.ReaderLegacyAnnotationTest,cn.studybridge.mobile.ReaderLayerCaptureTest,cn.studybridge.mobile.ReaderPaperVisualTest,cn.studybridge.mobile.ReaderMotionCompletionTest
if [ "${RAIN_READER_SUITE:-reader}" = paper ]; then
  classes=cn.studybridge.mobile.ReaderLayerCaptureTest,cn.studybridge.mobile.ReaderPaperVisualTest,cn.studybridge.mobile.ReaderMotionPreviewTest,cn.studybridge.mobile.ReaderMotionCompletionTest
fi
gradle --no-daemon connectedDebugAndroidTest \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" || status=$?
adb pull /sdcard/Pictures/StudyUiReview reader-audit-ui || true
adb pull /sdcard/Android/data/cn.studybridge.mobile/files/StudyUiReview reader-audit-ui || true
exit "$status"
