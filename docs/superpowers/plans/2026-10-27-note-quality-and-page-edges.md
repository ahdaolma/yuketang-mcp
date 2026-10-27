# Note quality and page edges implementation plan

> Execute sequentially using superpowers:executing-plans. User requests no further questions; no delegated work.

**Goal:** Improve source-grounded note organization and eliminate settled half-line clipping without regressing reader navigation.

**Architecture:** Keep the existing Markdown/native reader and generation pipelines. Derive bounded source/topic context from saved Markdown. Reproduce clipping with native Layout geometry before choosing a minimal rendering/pagination change.

**Tech Stack:** Kotlin, Jetpack Compose, TextView Layout, Markwon 4.6.2, GitHub Actions Android emulators.

### 1. Reproduce the reported visual fault

- [x] Inspect the four attachments and current fixed-pixel paging implementation.
- [x] Add `ReaderExperienceTest#settledPagedScreensNeverStartInsideANativeTextLine`, using actual native line bounds after taps/swipes in a short viewport; record screenshots before assertion.
- [x] Add the `edges` dispatch profile to `.github/workflows/reader-audit.yml` and `android/scripts/reader_audit.sh`.
- [ ] Run the test against unchanged application code and retain the failing assertion and screenshot. Compilation failures are not a reproduction.

### 2. Improve existing generation context

Files: `LearningContext.kt`, `Prompts.kt`, `LearningContextTest.kt`, `NotePresentationTest.kt`.

- [ ] Add fixtures with source page headings, nested concept headings, fenced code and more than 40 pages. Assert topic/source pairing survives bounded truncation, recent text remains, image payloads and fenced headings are excluded.
- [ ] Build the index from real headings outside fences, pairing nested topics with the current source heading. Retain a bounded early/recent sample and a bounded recent excerpt, explicitly marking omissions and distinguishing prior AI notes from current source.
- [ ] Refine the ordinary/concise format instructions: related concepts, new information, source-specific evidence, short comparison cells, uncertainty without repeated boilerplate. Preserve custom roles and the concise mode override.
- [ ] Validate the exact prior stock prompt migration by digest and prove custom prompts are unchanged.

### 3. Fix actual reader edge geometry

Files: `NotebookReader.kt`, `ReaderTextRegistry.kt`, native reader rendering helpers and reader device tests as required by the failing evidence.

- [ ] Use measured native line geometry at real width; no predictive TextView layout or timer-based restoration.
- [ ] Cover table inner lines, marked text and font/viewport changes; retain source mappings and full paper capture.
- [ ] Validate forward/backward reachability and settled edges independently. Test both animation styles and actual landscape dimensions.
- [ ] Recheck modes, continuous reverse scrolling, exact search reveal, selection, table contrast and paper capture.

### 4. Verify and deliver the final candidate

- [ ] Run cloud unit tests and API 26/35 targeted tests, then final full Android/lint/R8/signed APK and upgrade regression.
- [ ] Download immutable artifacts; verify digest, XML counts, screenshots and fixed certificate. Record failures separately.
- [ ] Publish only the tested final stable patch and verify the public APK digest. Leave the supplied user attachments untracked.
