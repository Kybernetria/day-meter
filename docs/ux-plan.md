# Checkpoint habits and settings UX plan

## Goal
Make daily routines progress one checkpoint at a time, explain exactly how the day starts, and give reminders useful controls without making routine setup feel like a project-management tool.

## Evidence and design decisions
- [OmniFocus availability and sequential actions](https://support.omnigroup.com/documentation/omnifocus/universal/4.3.3/en/glossary/) distinguish a blocked action from an action waiting for a time. Adopt that separation: the existing checkpoint time becomes the earliest reminder time when a predecessor is selected. Keep blocked items visible with an explanation so users can repair their routines.
- [Todoist reminders](https://www.todoist.com/help/todoist/features/introduction-to-reminders-9PezfU) distinguish default reminder behavior and task reminder setup, and expose configurable snooze. Adopt a shared snooze duration with explicit action text; retain per-checkpoint alert style.
- [Structured notification customization](https://help.structured.app/en/articles/1870914) makes alert presets and system notification settings discoverable. Provide named sound/vibration presets and direct Android channel settings, with an explanation that Android controls final delivery.

These are patterns selected for Day Meter, not an assertion that another app's full workflow belongs here.

## Habit chains
1. Existing checkpoints remain independent by default. In a checkpoint editor, choose “Wait for checkpoint” and an optional delay after completion.
2. Earliest reminder = later of configured clock/day percentage and predecessor completion plus delay. A late completion can unlock a child even when its original reminder time was more than an hour ago.
3. Only Done unlocks. Snooze, Skip, missed reminders, dismissing a notification, and disabling a predecessor do not count as completing a habit.
4. Use the existing checkpoint occurrence's scheduled calendar date for dependency matching. A chain resets each calendar date, including when the day window extends past midnight. Explain this limit in the editor; do not imply overnight logical-day chaining.
5. Reject self-links, cycles, missing predecessors, and child weekdays not covered by the predecessor. Prevent deletion of a referenced checkpoint and explain how to detach it. Disabled predecessors remain visible and clearly block their children.
6. Keep rows visible with “Waiting for [name]”, delay, and today's status. Add a dedicated today action reachable from the editor so notifications are optional for completion. Completion records a timestamp, cancels the old notification, and reschedules successors.
7. Preserve existing stored records and defaults. Store dependency and completion metadata separately so legacy data continues to load.

## Manual start and settings
- Put day window, day start, and automatic detection before checkpoint and notification sections; widget appearance comes afterward.
- Rename “Lock Manual Start Time” to “Repeat this start time daily”; explain that it overrides automatic detection.
- Show current mode/status, “today only” versus “repeats daily”, and context-specific picker titles. Start now is explicitly for today and ends any repeating override.
- Do not claim automatic detection is enabled when Usage Access is missing. Describe permission and waiting state, and keep manual starts usable.
- Retain preference keys and current day calculation behavior.

## Notification controls
- Per-checkpoint Gentle (sound), Silent, Vibrate, and Prominent (sound/vibration, heads-up eligible) presets.
- Shared snooze choices: 5, 10, 15, 30, or 60 minutes; default stays 10.
- Option to keep reminders until action; explain Android can still dismiss them.
- Lock-screen detail opt-in; default stays hidden.
- Separate permission/channel health, reminder behavior, and system channel settings. Do not reset channels users already customized.

## Review sequence
1. Settings grouping and manual-start clarity: independently useful, no scheduler changes.
2. Habit chain model/engine and editor/completion UI: one coherent behavior, focused migration/edge-case tests.
3. Expanded notification controls: defaults preserved, channel and action tests.

Each feature is a short-lived branch with one focused PR. Validate each independently before stacking the next. The upstream trunk is unchanged until the maintainer merges. Each later PR targets the preceding fork branch to keep its diff small, and documents retargeting to main after its predecessor lands.

## Acceptance targets for the feature stack
- Test chains across late completion, delay, skip/snooze, midnight, cycles, disabled predecessors, persisted scheduled states, and serialization.
- Test completion without a delivered notification and stale action rejection.
- Test manual status, today/daily behavior, and missing permission messaging.
- Test notification presets, snooze, privacy, and persistence on modern and pre-channel Android APIs.
- Build APK, run full unit suite and lint, and render native Android views for screenshots at normal and enlarged font sizes. Label the screenshot method accurately; physical-device battery and OEM delivery remain outside local verification.


## Settings PR validation
The settings change has focused UI tests for behavior-first grouping, wrapping titles, today/daily picker context, daily future presets, permission-aware automatic-start feedback, retained detected starts, and stale manual state on resume. It retains existing preference keys. The remaining chain and notification acceptance targets above are work for their respective PRs; they are not claims that this settings PR implements those features.

Settings validation completed with `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: 55 tests passed, a debug APK was built, and lint completed successfully. Screenshot review is documented in the PR separately.
