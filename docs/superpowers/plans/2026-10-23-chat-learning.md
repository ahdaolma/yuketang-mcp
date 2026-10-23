# AI 学习对话 Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. Read the matching design before editing. Android validation is cloud-only per user instruction.

**Goal:** 修复对话命名和状态串用，清晰呈现资料层级及整份讲解。

**Architecture:** ViewModel owns chat drafts and reversible material state. Shared Compose controls implement whole-deck/range selection on both entry surfaces. Keep existing storage, streaming and per-page checkpoints.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit, Android instrumentation, GitHub Actions.

### 1. 状态及命名
- [ ] In `StudyViewModel.kt` and a focused chat helper, name default-titled chats in both send and explain paths using `source` or first user text; retain nondefault titles. Repair legacy populated defaults on load.
- [ ] Add `chatDrafts: Map<String,String>` to UiState, `chatDraft(text)` setter; clear only accepted sends/deleted chat. Capture attachment source before IO; use navigation revision for async material opening.
- [ ] Store selected lesson and parent activity list; `backRainMaterials()` unwinds deck, lesson, course. Add `backMaterials()` for local/file/home navigation.
- [ ] Add `batchAllNotes()` and preflight validation shared with `batchNotes(from,to)`; full deck uses sorted distinct available pages, no 50-page truncation; keep existing custom range limit. Close only after successful validation.
- [ ] Add focused unit and device tests for naming, state ownership, hierarchy, invalid/preflight and full-deck checkpoint behavior. No local execution.

### 2. 可见交互与一致入口
- [ ] Update `LearningMaterials.kt`, `CourseLibraryUi.kt`, `MainActivity.kt`: visible labelled material entry, clear source/model context, direct material empty state, per-chat draft binding.
- [ ] Extract `MaterialStudyControls.kt`: default whole deck, optional custom range, inline validation, clear count/result, obvious main CTA; reuse on course screen and material sheet.
- [ ] Hierarchy labels and staged back at both surfaces; batch controls only course activity layer. Flexible actions and readable content widths.
- [ ] Add actual Compose click-flow tests and viewport screenshots, retain existing semantics where practical.

### 3. 验证与交付
- [ ] Spec review then quality review; resolve findings with source/diff evidence.
- [ ] Push branch, inspect full Android/Python GitHub Actions results; retrieve XML, screenshots, inspect UI and correct failures. `git diff --check` is allowed locally; no local Gradle/emulator/ADB.
- [ ] Only after all batch requirements pass, prepare next stable patch, verify fixed signing and published-baseline upgrade on Actions, publish with exact reports/artifact hashes. Record tested scope and real-device limitations.

### 4. 追加：可交互笔记及修改上下文
- [ ] `Models.kt` backwards-compatible Message note references with ID, hash and bounded original snapshot; optional app result kind. Retain existing fields, roundtrip backup tests.
- [ ] `ChatNotebookContext.kt` local query/source/history retrieval, at most three bounded note texts, clear excerpt markers, current saved annotations, source IDs, change summaries vs last provided version. Treat stored note commands as quoted data, never system instructions. Never include trash or drafts.
- [ ] `StudyViewModel.kt`: attach existing note without generating, send uses actual retrieved context and records references on answer, note saving distinguishes raw dialogue (no AI) and AI organization (returns real saved note card). Latest saved edits supplied on next question, no background sends.
- [ ] `ChatMessageCard.kt` + `MainActivity.kt`: render live note cards with view/edit/updated/deleted states, notebook action sheet, existing-note attach action. Existing reader owns all editing and annotations; preserve unsaved draft handling.
- [ ] Unit and cloud instrumentation for retrieval/update/raw save/result cards/backward serialization; review again before final CI and publication.

### 5. 追加：选区菜单定位
- [ ] `NoteSelection.kt` replaces fixed bottom popup with selection bounds from TextView and window-aware PopupPositionProvider; preserve native actions and dynamic scroll/reflow positioning. Dedicated cloud selection test verifies placement near selection and screen-edge avoidance.
