# day-meter

A small Android widget app that tracks how much of your configured day has passed.

## What it does
- Detects your day start automatically from device usage
- Supports manual start overrides
- Handles day end times that go past midnight
- Shows progress as a bar, text, or both
- Lets you customize colors, borders, text, and update frequency
- Supports solid or two-color gradient progress fills

## Notes
- Usage Access permission is required for automatic day-start detection
- Exact alarm permission helps with more frequent widget refreshes
- The widget can be opened into settings by double-tapping it

## Build
```bash
./gradlew assembleDebug
```

APK output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Project status
This repo is focused on keeping the app simple and robust.
