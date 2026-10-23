# Notebook and Course Library Implementation Plan

> **For agentic workers:** use superpowers:subagent-driven-development for independent audit/implementation tasks; review specification compliance before code quality. Only one implementation subagent at a time. No local Android build/test/emulator/ADB.

**Goal:** Implement the approved user scope in the adjacent design: course notebooks/sections, reliable background QR login, and truthful teacher/time courseware organization, without publishing a release.

**Architecture:** Keep existing Note.folderId values and extend NoteFolder with a root parent and private classroom association. Pure helpers handle hierarchy, sorting and metadata; shared bounded Compose panels render them. Application ownership and the existing foreground service remain the QR-login boundary unless a cloud reproduction proves a change is required.

**Tech Stack:** Kotlin, Compose, JSON, OkHttp, GitHub Actions API 35 device tests.

## Task 1: Notebook model and migration

Files: NoteOrganization.kt, Models.kt, NotebookBackup.kt, ClassNotebooks.kt, StudyViewModel.kt; tests NoteOrganizationTest.kt, NotebookBackupTest.kt and class notebook tests.

- [ ] Add NoteFolder(parentId="", classroomKey="") and JSON compatibility, preserving existing constructor call sites.
- [ ] Add pure folder-parent validation, subtree membership/counts/path, sibling-unique naming, root/section deletion and atomic Saved.moveNotes helpers. Reject missing parents, a section beneath another section, cycles and invalid targets without altering existing data.
- [ ] Preserve parent IDs in backup manifests; omit classroomKey, validate references and map root IDs before child IDs when merging. Test old manifests, duplicate names under different roots, colliding imports and nested roundtrip.
- [ ] For new classroom notes, use a hash of authorized server/account/classroom identity to create/reuse a root; retain existing manual folder membership. Unit cases cover another lesson in the same classroom, another account, title rename and legacy unbound notes.
- [ ] Add ViewModel entry points for root/section creation and batch move with existing locked/storage checks.

## Task 2: Notebook navigation and actions

Files: new NotebookNavigation.kt, NoteOrganization.kt, MainActivity.kt NotesScreen only; tests NoteOrganizationWorkflowTest.kt and a new notebook hierarchy UI test.

- [ ] Replace the horizontal folder chip inventory with a current-notebook card opening a searchable bounded picker. Show All, Unorganized, roots and indented sections with counts; root selection includes sections. Keep a fixed close entry.
- [ ] Provide compact smart views for all, pinned, pending review and classroom notes. Retain tag/review filtering and search; show/reset active filters when no matches exist.
- [ ] Use the same hierarchy picker for move actions. Add multi-select with explicit selected count, select loaded results, cancel and one atomic move; note content and drafts stay untouched.
- [ ] Modernize folder management with explicit new notebook, new section, rename and delete confirmations. Preserve the classroom card and existing note long-press actions.
- [ ] Update physical UI tests for create notebook/section, root descendant counts, search, multi-move, rename and safe deletion, plus narrow/landscape/large-font bounded picker.

## Task 3: QR lifecycle audit

Files: RainApi.kt login section only, RainLoginService.kt, StudyViewModel.kt login section only, RainLoginBackgroundTest.kt and additional focused tests if needed.

- [ ] Inspect live cloud baseline, reproduce HOME/return and Activity recreation without replacing the ticket. Verify application-owned model, service lifetime, timeout and late-callback guards.
- [ ] Add a real UI return/recreation test to the existing synthetic TLS/WebSocket fixture; cover explicit cancel and service teardown. Do not contact a real account or launch local tools.
- [ ] Fix only demonstrated lifecycle gaps, keeping credential persistence and stale-attempt ownership. Report exact verification limits.

## Task 4: Courseware metadata and ordering

Files: new CourseLibrary.kt, Models.kt Course/Presentation declarations only, RainApi.kt course/activity parsing only, MaterialStore.kt metadata helpers only, LearningMaterials.kt, MainActivity.kt CoursesScreen only, StudyViewModel.kt course load methods only.

- [ ] Parse teacher/term as structured values rather than splitting captions. Retain existing constructors via default fields.
- [ ] Introduce pure metadata/time parsing that distinguishes nested presentation creation, activity publication and lesson start time. Test seconds/milliseconds, timezone ISO, common dates, invalid/missing dates, duplicate IDs and nested creation fields.
- [ ] Add shared stable ordering and teacher grouping functions. Unknown teacher/time values stay visibly unknown and sort last; ascending/descending must represent actual instants, not localized strings.
- [ ] Add shared controls and metadata rows to both course browsing surfaces. Show full date labels and source, keep pagination and clearly scope ordering to loaded records.
- [ ] Add device UI checks for sort/group changes and visible date/unknown labels; cover the learning-material picker as well as the course tab.

## Task 5: Cloud verification and review

- [ ] Commit focused source changes and push the working branch; use GitHub Actions only. Inspect compile/unit/lint/device results and retrieve XML/logs/screenshots.
- [ ] Perform specification review followed by code-quality review for delegated changes; fix identified issues, rerun relevant cloud checks and inspect all failures.
- [ ] Check existing reader, MIT/risk settings, class overlay and backup coverage remains green. Record tested commit and exact boundaries in requirements/docs.
- [ ] Do not publish a GitHub release or version tag. Leave delivery on hold until the user explicitly resumes publishing.
