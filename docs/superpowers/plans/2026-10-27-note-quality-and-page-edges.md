# Note quality and page edges implementation plan

> Execute sequentially using superpowers:executing-plans. User requests no further questions; no delegated work.

**Goal:** Improve source-grounded note organization and eliminate settled half-line clipping without regressing reader navigation.

**Architecture:** Keep the existing Markdown/native reader and generation pipelines. Derive bounded source/topic context from saved Markdown. Reproduce clipping with native Layout geometry before choosing a minimal rendering/pagination change.

**Tech Stack:** Kotlin, Jetpack Compose, TextView Layout, Markwon 4.6.2, GitHub Actions Android emulators.

### 1. Reproduce the reported visual fault

- [x] Inspect the four attachments and current fixed-pixel paging implementation.
- [x] Add `ReaderExperienceTest#settledPagedScreensNeverStartInsideANativeTextLine`, using actual native line bounds after taps/swipes in a short viewport; record screenshots before assertion.
- [x] Add the `edges` dispatch profile to `.github/workflows/reader-audit.yml` and `android/scripts/reader_audit.sh`.
- [x] Run against unchanged reader code: `40493937859` failed actual glyph ink at screen1 (`91 < 95 < 160`). Artifact12730206810 was downloaded, digest checked and the original viewed. Earlier compilation failures and layout-spacing-only assertions are not this proof.

### 2. Improve existing generation context

Files: `LearningContext.kt`, `Prompts.kt`, `LearningContextTest.kt`, `NotePresentationTest.kt`.

- [x] Add fixtures with source page headings, nested concept headings, fenced code and more than 40 pages. Three old-code failures are retained in `40493230845`.
- [x] Build a bounded early/recent source/topic index outside fences; preserve literal heading hashes and long final paragraphs. Do not invent pages for personal notes.
- [x] Refine ordinary/concise instructions: new information, related concepts, source-specific evidence, short comparison cells, local uncertainty, units and conditions. No extra model calls or old-note rewrites.
- [x] Validate exact stock prompt migration against the shipped1.0.5 fixture and preserve edited/custom roles.202 cloud units passed on `302624a`.

### 3. Fix actual reader edge geometry

Files: `NotebookReader.kt`, `ReaderTextRegistry.kt`, native reader rendering helpers and reader device tests as required by the failing evidence.

- [x] Use measured native and public table-cell Layouts at real width; no predictive TextView layout or timer-based restoration.
- [x] Clip at the scrolling paper layer, preserving complete native display lists, source mapping and full-paper capture. Reject hidden-line selection while blank taps still turn pages. The first native-onDraw clipping approach was rejected after manual blank-screen review and independent actual-pixel failures.
- [x] API35 `40498200783` and API26 `40498204298`:202 unit +4 device each,0 failure/error/skip. All table/body lines actually painted on some settled screen, partial edge pixels absent, both styles retraced forward/back offsets in real landscape and short portrait.44 PNGs per run downloaded. Initial short sampling was replaced by waiting on real command readiness/generation, not fixed clock time; old API26 full-display screenshot null uses the already-checked actual paper capture.
- [ ] Recheck modes, continuous reverse scrolling, exact search reveal, selection, table contrast and paper capture.

### 4. Verify and deliver the final candidate

- [ ] Run cloud unit tests and API 26/35 targeted tests, then final full Android/lint/R8/signed APK and upgrade regression.
- [ ] Download immutable artifacts; verify digest, XML counts, screenshots and fixed certificate. Record failures separately.
- [ ] Publish only the tested final stable patch and verify the public APK digest. Leave the supplied user attachments untracked.

Final candidate preparation:1.0.6/code29; full workflow upgrades from the public1.0.5 APK, pinned SHA256 `c852ee1e436e962b91043bae7ac86c51bb9ccf96bbb93eb13d10407a47af3de1`. R8 smoke additionally enters a long table through the ordinary editor and records intra-source forward/back screens for both styles. Full suite timeout90 minutes preserves all205 device cases.
