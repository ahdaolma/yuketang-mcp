# Class entry and page transition fixes

Goal: remove repeated classroom cards from course/note lists, retain class controls through one shared manager, and prevent outgoing pages surviving navigation transitions.

Architecture: a single ClassManagerHost owns a dismissible sheet and provides a toolbar entry to the current screen. Keep the draggable pause/resume circle visible for a persisted unfinished class, including after app restart. Only the current page is composed during navigation; animate its opacity rather than retaining an outgoing page under a newly changed app frame.

Scope: Kotlin/Compose Android only. Preserve stored notebooks, classroom identity and the existing circle gestures. Build and execute tests exclusively in GitHub Actions.

- [ ] Add UI regressions for a persisted paused class on both course/note pages, manager actions and rapid navigation sampled during animation.
- [ ] Remove embedded ClassModePanel calls; host it in one shared sheet with toolbar access on normal and reading pages. Preserve access to starting, pausing, continuing, viewing notes and ending classes.
- [ ] Show the existing circle for an unfinished saved class and remove it when the class ends.
- [ ] Replace overlapping outgoing/incoming page animation with current-page-only fade; verify outgoing controls disappear immediately even under rapid reversals.
- [ ] Adapt the large-text class screenshot to the shared manager; require new screenshots from actual navigation and paused-class screens.
- [ ] Increment to 0.9.1-preview (20), submit the complete change once, and inspect the signed cloud build, tests and screenshots before delivery.
