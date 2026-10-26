#!/usr/bin/env bash
set -euo pipefail
# The emulator runner stops the device before subsequent workflow steps.
# Pull evidence inside its live session, on success and on test failure.
status=0
classes=cn.studybridge.mobile.ReaderExperienceTest,cn.studybridge.mobile.ReaderNativeTapTest,cn.studybridge.mobile.ReaderMotionPreviewTest,cn.studybridge.mobile.ReaderSourceAnnotationTest,cn.studybridge.mobile.ReaderBackwardStabilityTest,cn.studybridge.mobile.ReaderLegacyAnnotationTest,cn.studybridge.mobile.ReaderLayerCaptureTest,cn.studybridge.mobile.ReaderPaperVisualTest,cn.studybridge.mobile.ReaderMotionCompletionTest,cn.studybridge.mobile.ReaderBoundaryRegressionTest,cn.studybridge.mobile.ReaderPreviewSizeTest,cn.studybridge.mobile.ReaderMarkdownLeaseTest
if [ "${RAIN_READER_SUITE:-reader}" = paper ]; then
  classes=cn.studybridge.mobile.ReaderLayerCaptureTest,cn.studybridge.mobile.ReaderPaperVisualTest,cn.studybridge.mobile.ReaderMotionPreviewTest,cn.studybridge.mobile.ReaderMotionCompletionTest
fi
if [ "${RAIN_READER_SUITE:-reader}" = editor ]; then
  classes=cn.studybridge.mobile.NoteEditorHardwareInputTest,cn.studybridge.mobile.NoteEditorEchoRaceTest
fi
if [ "${RAIN_READER_SUITE:-reader}" = echo ]; then
  classes=cn.studybridge.mobile.NoteEditorEchoRaceTest
fi
if [ "${RAIN_READER_SUITE:-reader}" = boundary ]; then
  classes=cn.studybridge.mobile.TabletWindowEvidenceTest
fi
if [ "${RAIN_READER_SUITE:-reader}" = storage ]; then
  classes=cn.studybridge.mobile.PrivateStorageConcurrencyTest,cn.studybridge.mobile.ImageMarkupConcurrencyTest,cn.studybridge.mobile.AppDraftRecreationTest,cn.studybridge.mobile.ImageMarkupWorkflowTest,cn.studybridge.mobile.NotebookBackupDeviceTest,cn.studybridge.mobile.AppFlowSimplificationTest
fi
gradle --no-daemon connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  "-Pandroid.testInstrumentationRunnerArguments.class=$classes" || status=$?
adb pull /sdcard/Pictures/StudyUiReview reader-audit-ui || true
adb pull /sdcard/Android/data/cn.studybridge.mobile/files/StudyUiReview reader-audit-ui || true
if [ "${RAIN_READER_SUITE:-reader}" = reader ] || [ "${RAIN_READER_SUITE:-reader}" = paper ]; then
for expected in reader-layer-capture-bitmap.png reader-completion-curl-1.png reader-completion-cover-1.png; do
  if [ ! -d reader-audit-ui ] || [ -z "$(find reader-audit-ui -type f -name "$expected" -size +0c -print -quit)" ]; then
    echo "Missing actual paper evidence: $expected"
    status=1
  fi
done
fi
exit "$status"
