# day-meter - Development Tracker

## Scope
- Fix day reset behavior so mornings reset automatically and reliably.
- Fix manual start behavior so it works for the current day and can optionally persist safely.
- Support day end times that pass midnight.
- Audit notification behavior and report gaps.
- Keep implementation simple and robust.

## Fixed In This Pass
- [x] `manual_start_time` now affects the current logical day even when `is_manual_locked` is off.
- [x] Day reset now follows the app's logical day window instead of the raw calendar date.
- [x] Overnight schedules are now allowed when `day_end <= ignore_before`.
- [x] Progress/end calculations now support logical days that cross midnight.
- [x] Usage detection worker now uses logical day boundaries instead of assuming "today".
- [x] Automatic detection no longer depends only on the 15-minute WorkManager cadence; before the day starts, widget scheduling now polls every minute and widget refresh paths also run detection.
- [x] Progress now shows at least `1%` once the day has started and any time has elapsed, avoiding the misleading "detected but still looks like 0%" state.
- [x] Right after auto-detection, the widget keeps refreshing every minute until progress is visibly above zero.
- [x] Locked manual start times now roll forward safely to each new logical day.
- [x] Reset-to-defaults now also clears runtime day state instead of leaving stale start/reset values behind.
- [x] The missing "force update" settings action is now actually present in the preferences screen.
- [x] Stale notification-only manifest permission was removed because no notification feature exists.
- [x] Widget rendering now uses generated bitmaps for background/border and progress fill so the real widget better matches settings.
- [x] Settings preview now refreshes on activity resume and after updates, making it much less likely to look stale.
- [x] App chrome now follows the progress fill color.
- [x] App chrome now supports a left-to-right gradient using the progress fill start/end colors where the platform allows it.
- [x] Color picker dialogs now open on the current selected color, making small adjustments practical.
- [x] Widget layouts were compacted vertically so the bar-focused widget wastes less top/bottom space.
- [x] Progress fill now supports a two-color horizontal gradient.
- [x] The settings screen was visually refreshed with a nicer preview card, softer surfaces, and better spacing.
- [x] The launcher icon was redesigned to a fresher day-meter style and the app branding was renamed from `Day Progress` to `day-meter`.
- [x] Real widget text now uses the configured font family through spannable text where supported.
- [x] Dead `ScreenReceiver` source file was removed.
- [x] Room schema export warning was removed with `exportSchema = false`.
- [x] Battery-optimization exemption flow and permission were removed to avoid policy-risky behavior.
- [x] App icon metadata was added to the manifest.
- [x] Hardcoded widget placeholder text and several lint issues were cleaned up.
- [x] Added a proper Android `.gitignore`.
- [x] Added a simple repo `README.md`.
- [x] Added a `SECURITY_AUDIT.md` summary and basic automated dependency monitoring with Dependabot.
- [x] Local build verification was fixed by adding `local.properties` for this environment.

## Device / Runtime Findings
- [x] App installed successfully on connected device `XQ_DC54` via `adb install -r`.
- [x] Usage access is granted on the device (`GET_USAGE_STATS: allow`).
- [x] A widget instance is present on the launcher (`DayProgressWidgetProvider`, appWidget id 16).
- [x] Periodic `DayStateWorker` exists in WorkManager's database on-device.
- [x] Exact widget update alarms are scheduled on-device.
- [x] The connected device previously had a same-day manual start set (`11:00`), which would override automatic detection for that logical day.
- [x] Manual override was cleared on-device to allow automatic detection to take over again.
- [x] Automatic detection then fired successfully on-device and set `detected_start_time` (`2026-04-01 18:50:41` local device time).

## Automatic Detection Status
- [x] Code path audited and fixed.
- [x] Required device permission is granted.
- [x] Worker is enqueued on-device.
- [x] Runtime-confirmed on the connected device: after clearing the stale manual override, `DayStateWorker` detected usage and set `detected_start_time` successfully.
- [x] Follow-up issue found and fixed: the threshold affected *how much usage* was needed, but detection cadence could still lag because checks were tied to the 15-minute worker / previous widget schedule.
- [x] Before-start detection now re-polls every minute and threshold changes now immediately trigger a refresh/detection pass.
- [x] There is no remaining code-level blocker found for automatic detection.

## Security Status
- [x] Direct dependency check against OSV found no known advisories for the declared direct dependencies at the time of checking.
- [x] Basic hardcoded-secret grep scan found no obvious credentials in tracked source files.
- [x] Added `.github/dependabot.yml` for ongoing dependency monitoring.

## Remaining / Reported Issues
- [x] Notifications are intentionally out of scope for now. There is no notification channel, scheduling, or permission flow because the current product does not need notifications unless you specifically want reminder-style features later.
- [ ] Remaining lint warnings are non-blocking and mostly tooling/assets related:
  - targetSdk is not the latest available version in this build setup
  - dependency update suggestions require newer AGP compatibility work
  - Room could be migrated from kapt to KSP for faster builds
  - launcher icon lint guidance may still want further polishing depending on launcher/icon masks
- [x] `main` branch protection was enabled with pull-request-based protection and force-push/deletion disabled.
- [x] Old Dependabot branches were cleaned up; Dependabot remains enabled for future updates.
- [ ] GitHub publish + latest release creation is the final publishing step for the current local changes.

## Files Changed
- `.github/dependabot.yml`
- `.gitignore`
- `DEVELOPMENT_TRACKER.md`
- `LICENSE`
- `README.md`
- `SECURITY_AUDIT.md`
- `local.properties`
- `settings.gradle`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/example/dayprogress/data/AppPreferences.kt`
- `app/src/main/java/com/example/dayprogress/data/DayHistory.kt`
- `app/src/main/java/com/example/dayprogress/data/DayRepository.kt`
- `app/src/main/java/com/example/dayprogress/data/UsageDetector.kt`
- `app/src/main/java/com/example/dayprogress/data/WidgetStyleHelper.kt`
- `app/src/main/java/com/example/dayprogress/ui/SettingsActivity.kt`
- `app/src/main/java/com/example/dayprogress/ui/SettingsFragment.kt`
- `app/src/main/java/com/example/dayprogress/widget/DayProgressWidgetProvider.kt`
- `app/src/main/java/com/example/dayprogress/worker/AlarmScheduler.kt`
- `app/src/main/java/com/example/dayprogress/worker/DayStateWorker.kt`
- `app/src/main/res/drawable/widget_background.xml`
- `app/src/main/res/layout/activity_settings.xml`
- `app/src/main/res/layout/settings_widget_preview.xml`
- `app/src/main/res/layout/widget_combined.xml`
- `app/src/main/res/layout/widget_progress_bar.xml`
- `app/src/main/res/layout/widget_text_only.xml`
- `app/src/main/res/mipmap-hdpi/ic_launcher.png`
- `app/src/main/res/mipmap-hdpi/ic_launcher_round.png`
- `app/src/main/res/mipmap-mdpi/ic_launcher.png`
- `app/src/main/res/mipmap-mdpi/ic_launcher_round.png`
- `app/src/main/res/mipmap-xhdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xhdpi/ic_launcher_round.png`
- `app/src/main/res/mipmap-xxhdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- `app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png`
- `app/src/main/res/values/plurals.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values/themes.xml`
- `app/src/main/res/xml/preferences.xml`
- `tools/generate_icons.sh`
- Removed: `app/src/main/java/com/example/dayprogress/worker/ScreenReceiver.kt`
- Removed: `app/src/main/res/drawable/custom_progress_bar.xml`

## Verification Notes
- [x] `./gradlew assembleDebug`
- [x] `./gradlew clean assembleDebug`
- [x] `./gradlew lintDebug`
- [x] `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- Lint reports: `app/build/reports/lint-results-debug.html`, `.txt`, `.xml`

## Behavior Notes
- Overnight schedules are treated as a single logical day that ends on the next calendar day.
- `day_end` now shows `(next day)` in settings when it crosses midnight.
- Manual start now works as a same-day override by default.
- When manual lock is enabled, the chosen clock time repeats safely across logical days.
- On the connected device, auto-detection should now be the active path again because the stale manual override was cleared.
