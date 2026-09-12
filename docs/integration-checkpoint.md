# Coach and design integration checkpoint

Find this combined commit with the local tag `calico-coach-design-2026-09-12`.
The tag is created after validation and the merge commit, so it points at the
complete result. Nothing is pushed automatically.

- Pre-pull HEAD: `69fc362` (shared JSON motion pipeline).
- Pulled design head: `5deaf59353d63f3f4258861048eb6eb26cd6fffb`.
- Recovery snapshot of tracked and untracked local work:
  `refs/calico/backups/pre-design-pull`, currently
  `625a6009d57c12cb486da44f781794e3b53f89bd`.
- The pull used `--no-commit --no-ff`, followed by local-work restoration and
  conflict resolution. The combined work is committed only after the pull.

## Preservation rules

The entire `roomscan/` module, `tools/`, `docs/AR.md`, `PoseAngles.kt`,
`RepCounter.kt`, and `RepCounterTest.kt` remain identical to pre-pull HEAD.
Workout recording/export and JSON metadata logic remain as in the recovery
snapshot; the incoming workout changes retained are presentation changes.
`OverlayView.kt` receives the incoming palette only.

The main app receives the stone/brown, sand, and lime cream palette. The Voice
screen keeps the separately requested white reference design with centered text,
waveform, black microphone, orange cancel, and blue check controls. Voice remains
in navigation alongside the pulled design.

## Checks

The combined debug app builds. All 88 app and roomscan unit tests pass. Device
benchmarks compare the historical and current coach prompts, inspect generated
answers, and exercise native cancellation. See `offline-coach.md` for runtime
limits and benchmark details.
