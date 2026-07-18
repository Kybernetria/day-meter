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

## Releases

Pushing a tag matching the Android version (for example, `v1.0.33`) runs the
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
gh attestation verify day-meter-v1.0.33.apk --repo OWNER/REPOSITORY
```

GitHub's **Verified** badge on commits and tags is separate from Android APK
signing and artifact attestations. To receive that badge, create the release tag
with a GPG, SSH, or S/MIME key added to the GitHub account that creates it.

## Project status
This repo is focused on keeping the app simple and robust.
