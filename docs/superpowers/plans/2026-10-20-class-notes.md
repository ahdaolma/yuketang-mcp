# Classroom notebook continuity and reading annotations

Goal: edit saved notes while following class, resume the same notebook on re-entry, and add application-owned reading selection, personal remarks and full-text navigation.

Architecture: keep generated pages append-only and rebase a user edit against its recorded original. Persist classroom identity (region, verified server user, classroom and lesson) and completed page keys on each notebook. Keep personal marks and remarks separate from Markdown, anchored to the exact rendered source block and selection; changed anchors stay visible in the remarks list as requiring review.

Tech stack: existing Kotlin, Compose, Markwon, encrypted Vault and JUnit/device tests.

- [x] Add pure regression tests for append-safe edits, conflicts, classroom reuse after ending/restarting/interleaving, account isolation, JSON compatibility, search and annotation anchoring.
- [x] Implement ClassNotebooks.kt and connect startClass, native generation. Authorize lesson first to obtain stable server user identity; never match notebooks by title. Preserve provider configuration when resuming an unfinished run.
- [x] Persist the editor base in encrypted drafts. Use noteEditable for text/marks while leaving deletion guarded until generation is stopped. Save edited text plus any AI append; refuse other conflicts and keep the draft.
- [x] Add NoteSelection.kt: source-anchored personal marks/remarks, application Compose selection toolbar, native selection handles, copy/highlight/underline/remove/add-remark; remarks management and export. Suppress the stock action menu only in note reading.
- [x] Add NoteSearch.kt: background literal search across full note, contextual snippets, jump to matching reading segment, compact menu entry.
- [x] Add durable super-focus mode to every note-generation request and grouped beginner settings.
- [x] Remove Android web mode, its JavaScript adapter and obsolete tests; migrate local originals without opening any website.
- [ ] Build unit and Android test APKs. Run device regression on an isolated emulator; inspect screenshots of class editing, custom selection/remarks, search and landscape layout. Preserve fixed release signing and increment version.
- [ ] Publish changes through the existing repository workflow, inspect its reports, and deliver the signed APK if the workflow succeeds. State real-account/device limitations.

Validation: tests must prove no AI appended page is lost, repeated page callbacks are idempotent, different accounts/classes do not merge, original Markdown remains intact under marks, changed anchors are never silently moved, and edited drafts survive restoration.
