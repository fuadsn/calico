# Full design integration

Source: origin/main bc6014a, compared with upstream base 5deaf59. Local HEAD remains 39f0591; presentation was integrated into the local work rather than replacing it with a full merge.

Following clarification, the complete Home, Overview and Exercises layouts are included: six exercise split cards, activity heatmap, calorie progress gauge, daily stat charts, goal bar, level journey and floating navigation. The original Theme.kt palette is unchanged. Chart and goal-bar contrast are adapted to its brown/sand/lime colors.

Voice remains available through the Home microphone, offline coach link, hands-free control and existing wake listener. Journey voice commands route to Overview. AR previews, scan behavior, rep counting and mannequin data are unchanged.

The upstream Progress additions and workout completion statistics supply the dashboard with real completed-workout counts and duration. Calorie values use upstream estimates and are labeled estimated. New daily metrics start from this update; existing streak/level progress is retained. No historical metrics are invented.

Backups: build/before-design-sync-20260913-001938 and build/full-design-backup. SHA-256 checks confirm that all pre-existing files outside MainActivity.kt, WorkoutActivity.kt and Progress.kt match the original backup. Ui.kt is the shared visual helper.

Validation: debug builds and unit tests pass. Device tests verify the complete Overview and daily metric accumulation, plus voice navigation. Overview layout inspected on the iQOO; installed on the phone. Physical AR scanning was not retested.

The workout-orb device test failed twice at speech recognition startup. Its voice implementation is unchanged; this design update does not claim to fix that microphone issue. Home and Overview screenshots are saved in build/full-home.png and build/full-overview.png.
