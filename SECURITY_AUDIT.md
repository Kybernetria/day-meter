# Security Audit

Date: 2026-04-01

## Checks performed

### 1) Dependency advisory check
A direct dependency check was run against the OSV API for the app's declared Gradle dependencies.

Checked dependencies:
- `androidx.core:core-ktx:1.12.0`
- `androidx.appcompat:appcompat:1.6.1`
- `com.google.android.material:material:1.10.0`
- `androidx.constraintlayout:constraintlayout:2.1.4`
- `androidx.preference:preference-ktx:1.2.1`
- `androidx.work:work-runtime-ktx:2.8.1`
- `androidx.room:room-runtime:2.6.1`
- `androidx.room:room-ktx:2.6.1`
- `com.github.skydoves:colorpickerview:2.2.4`
- `junit:junit:4.13.2`
- `androidx.test.ext:junit:1.1.5`
- `androidx.test.espresso:espresso-core:3.5.1`

Result:
- **No known OSV advisories were returned for the direct dependencies above at the time of checking.**

### 2) Secret scan
A repository grep scan was run for common exposed-secret patterns such as:
- GitHub tokens
- API keys
- private keys
- obvious hardcoded password/secret/token assignments

Result:
- **No matching hardcoded secrets were found in the tracked source files scanned.**

## Follow-up protections added
- Added `.github/dependabot.yml` for ongoing Gradle dependency monitoring.
- Added a repo `.gitignore` that excludes build outputs, local SDK config, signing files, and other generated/local artifacts.

## Notes
- This was a best-effort direct dependency and secret scan, not a full SAST/DAST penetration test.
- Transitive dependency advisories can still change over time, so GitHub Dependabot should remain enabled.
