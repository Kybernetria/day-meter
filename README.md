# day-meter

A small Android widget app that tracks how much of your configured day has passed.

## What it does
- Detects your day start automatically from on-device usage, or works entirely with manual starts
- Handles day end times that go past midnight and daylight-saving transitions
- Shows progress, time remaining, and clear waiting/completed states
- Supports bar, text, and combined widgets with customizable colors, borders, fonts, and gradients
- Adds arbitrary daily checkpoints at a clock time or percentage of the day
- Shows checkpoint markers on the progress bar and sends gentle or silent reminders
- Supports Done, Snooze 10 min, and Skip today notification actions
- Uses battery-friendly passive widget refreshes rather than frequent exact wake-up alarms

## Notes
- Usage Access is optional and is only used for automatic day-start detection
- Android 13 and newer ask for notification permission when an enabled checkpoint is saved
- Checkpoint reminders are best-effort and can be delayed by Android battery restrictions
- Tap the widget once to open settings

## Build
```bash
./gradlew assembleDebug
```

APK output:
- `app/build/outputs/apk/debug/app-debug.apk`

## Releases

Pushing a tag matching the Android version (for example, `v1.0.35`) runs the
release workflow. It builds an APK and Android App Bundle, signs both with the
release key, publishes them as a GitHub Release, and creates a GitHub artifact
attestation for each release file.

Before the first release, create a GitHub environment named `release` and add
these environment secrets:

- `ANDROID_KEYSTORE_BASE64` — the Base64-encoded release `.jks`/`.keystore` file
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

For example, encode the keystore without line wrapping:

```bash
base64 < release-keystore.jks | tr -d '\n'
```

The release Gradle tasks intentionally fail unless all four signing values are
provided. Debug builds remain unsigned by the release key.

Anyone can verify a downloaded release asset's provenance with GitHub CLI:

```bash
gh attestation verify day-meter-v1.0.35.apk --repo OWNER/REPOSITORY
```

GitHub's **Verified** badge on commits and tags is separate from Android APK
signing and artifact attestations. To receive that badge, create the release tag
with a GPG, SSH, or S/MIME key added to the GitHub account that creates it.

## Project status
This repo is focused on keeping the app simple and robust.
