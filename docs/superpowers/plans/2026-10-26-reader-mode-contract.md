# Reader mode contract implementation plan

**Goal:** Enforce the user's three distinct input modes and animate the entire paged reading surface without stretching text.

**Architecture:** Retain native frames and ScrollState positioning. Gate horizontal recognition by mode, disable paged touch scrolling, move only paged outer gutters into captured frames. Keep continuous/editor geometry unchanged.

**Tech Stack:** Kotlin, Compose, native selectable TextViews, cloud instrumentation.

- [ ] Add ReaderModeContractTest on the unchanged release implementation. Use real NotesScreen and actual native coordinates: tap-mode horizontal drag must not advance, vertical drags must not move either paged mode, tap/slide still advances long content, continuous control still scrolls, and phone/tablet paper canvases reach both edges while text remains bounded.
- [ ] Add a `modes` cloud investigation profile in reader-audit.yml / reader_audit.sh; run the unchanged-implementation failures and inspect their actual assertion messages.
- [ ] In NotebookReader.noteScreenSwipe, require `mode == "swipe"` for drag ownership. For buttons, cancel a tap candidate permanently after displacement exceeds touch slop. Add a defensive mode guard to beginDrag.
- [ ] Keep frame.scroll for program navigation but use `verticalScroll(frame.scroll, enabled = false)` in paged content. Keep continuous LazyColumn unchanged and retain position diagnostics.
- [ ] Make only the paged NotesScreen outer padding zero and only the paged NotebookReader paper container full-width. Render centered content with `padding(horizontal = 20.dp).widthIn(max = 840.dp).fillMaxWidth()` inside every captured frame; supply that actual text width to Markdown. Include existing top/bottom8dp spacing in the captured content, excluding72dp tool padding from content height as before.
- [ ] Verify green cloud modes plus existing reader/native selection/animation, long-page/restore/search/image/resize/append regressions; inspect middle-frame screenshots. Update only tests whose former expectations explicitly contradict the new user requirement.
- [ ] Prepare1.0.5 with fixed-signature1.0.4 upgrade baseline, complete cloud regression and actual APK/report review before stable publication. Do not claim the existing1.0.4 results validate changed gesture/layout code.

No local Android execution. Every fix review asks whether it introduces new bugs and whether a smaller correct approach exists. Record red/green run IDs below.

## Cloud baseline

40306418256 / 1c6fb3b:194unit pass;7device,5failures exactly matching the reported contracts,0errors/skips. Tap horizontal drag moves native first text fromY275 to-1238; both vertical-drag cases moveY275 to-1357. Paper starts20.19dp inside the phone edge and220dp inside the tablet edge. Continuous control and drag-return control pass. Artifact12643541077 SHA043f12a8e5451c796108987c4b3d45a8f8aed21f8710d7fe0cc6dc2df49a5e9e, downloaded digest verified. No production changes in this baseline.

Fix uses Compose's disabled-touch scrolling with the same programmatic ScrollState, rather than a new pagination engine: https://android.googlesource.com/platform/frameworks/support/+/79353bfe455684ea90e2973087df508359dcecb3/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/Scroll.kt . Keep two viewport-sized captures only; full-width blank gutters modestly increase bitmap size. Existing OOM/native fallback is retained. Text width and native first text position are preserved by moving the same20dp horizontal/8dp vertical spacing inside frames, not scaling a narrow snapshot.

Risk review found a physical-pixel rounding detail before finalization:20dp is rounded independently on each side, so subtracting40dp in floating point can give a different native requiredWidth at2.625x density. Derive the width from actual constraints minus two rounded gutters, and assert the real native view width against the actual paper bounds. This keeps preformatted table/text geometry consistent with its parent and prevents silent one-pixel overflow; no new paging engine or thresholds.

858b4ba / API35 run40313097438:194unit+7mode tests pass,0fail/error/skip. Artifact12645567861 SHA2d06eacdacf77a6023128f725ba220b950a047ca4ca760201e6995907470ce76 downloaded/verified. However the first screenshots preceded actual Canvas drawing: composed node presence is not pixel evidence. Updated middle-frame verification to capture the body until >50sampled paper pixels change and save that exact checked image, rather than an immediate UIAutomation frame.

858b4ba / API26 run40313101741:6mode tests pass; tablet fixture times out expecting resource.widthPixels==physical2560. Artifact12646570107 SHAc3657b6b44a902bea74bfc7aa8cb918429751bacc99636c2e56b94128ce1eac8 downloaded/verified. Recheck physical display resize separately from usable app width (navigation decor can reduce it), log both metrics, and independently assert actual root is wider than880dp plus full paper bounds and840dp text. Do not change production window policy based on this fixture failure. Final API26 run must confirm actual metrics and all7test outcomes.
