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
- [x] Recheck modes, continuous reverse scrolling, exact search reveal, selection, table contrast and paper capture: final45e7384 full206/API26reader81 and strengthened72377b2 window18 passed; downloaded counts/digests and images verified.
- [x] Reject the `5868f4f` candidate after API26 full-reader `40500148169` failed five cases. Per-test logcat exposed the legacy status-bar area changing viewport height after a dialog; clear only a read-only collapsed caret on focus return and preserve active ranges. Stable legacy status-bar layout passed both API26/35 lifecycle profiles `40506148613` / `40506152664` (202 unit +6 device each).
- [x] Independently reproduce a footer reading-order hole: stronger actual-pixel test `40507760137` failed because a short continuation painted below a tool-hidden line. This failure supersedes the earlier edge-test acceptance.
- [x] Verify contiguous footer crop and matching hidden-image hit testing. Earlier d025304 profile failures and their fixture corrections are recorded below; final45e7384 full206/API26reader81 passed actual native/cell pixels, ordered footer and hidden-image touch checks. API26 screenshot persistence uses the existing compatible helper without requesting storage permission.

Candidate follow-up `72a92f2` changes tests/documentation only; application source remains `d025304`. Both d025 edge runs passed the three actual geometry/pixel/complete-line cases; the fourth failed its fixture precondition because short text did not reach the tool region. The window profile passed17/18, including actual landscape keyboard/typing, and stopped at the legacy compat status-inset approximation. The new fixture uses wrapped text, waits the actual resized height and saves its paper before asserting. Legacy status-bar checks use the real fullscreen flag as in the existing viewer regression; modern systems still use visibility insets. No failed profile is accepted as a final pass.

Running stronger source72 checks: API35 edges `40620865916`, API26 window18 `40620869322`, and API26 complete reader81 `40620872732`. Full signed-candidate validation can run in parallel; publication remains blocked until every final result, artifact and image review is complete.

Source72 reader API26 passed202 unit/81 device with no failures/errors/skips; edge35 passed202/4 and window26 passed202/18. Downloaded/digest checked. Manual window screenshots nevertheless showed a status bar after IME dismissal. Stronger test-only source996c316 queries actual Android8 `dumpsys window policy`: `40622276867` failed after6 seconds, focused app and actual `WINDOW_STATE_SHOWING`, flags `0xb718` instead of hidden `0xb71c`. Restore the lost legacy fullscreen bit through its actual visibility-change callback; retain transient swipe bars and the existing stable layout/navigation/IME policies. Recheck window18 and full reader81 with this application change.

Full source84f21f2 run40621193298 stopped in R8 smoke before complete device regression: `整本开头` was missed. The actual failure screenshot/XML show an open working bounded menu at its bottom; the driver flung using whole-screen coordinates, overshot the target row and searched only downward. Make smoke navigation inspect the actual scrollable bounds, use overlapping low-velocity steps and search back when reaching an unchanged end. Missing controls still fail; no app menu change or longer product wait is introduced. This failed candidate is not publishable.

### 4. Verify and deliver the final candidate

- [x] Run cloud unit tests and API26/35 validation: full40623381515, Python40623381366, API26reader40623433524, strengthened window40625798349, all successful.
- [x] Download immutable artifacts; verify202 unit/206 full device,81 reader,18 window and0 failures/errors/skips,44 existing Lint warnings/no errors, v2 fixed certificate,1.0.5 upgrade, all final PNG overviews and16-page actual PDF. Record failures separately in the1.0.6 verification record.
- [ ] Publish only the tested final stable patch and verify the public APK digest. Leave the supplied user attachments untracked.

Final candidate preparation:1.0.6/code29; full workflow upgrades from the public1.0.5 APK, pinned SHA256 `c852ee1e436e962b91043bae7ac86c51bb9ccf96bbb93eb13d10407a47af3de1`. R8 smoke additionally enters a long table through the ordinary editor and records intra-source forward/back screens for both styles. Full suite timeout90 minutes preserves all206 device cases, including the added caret-versus-active-selection regression. No1.0.6 release has been published; `5868f4f` full-run success is historical and cannot validate the newer window/footer changes.
