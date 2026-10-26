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
