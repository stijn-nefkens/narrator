# CLAUDE.md

Notes for Claude (or any AI assistant) working on this repo. Keep this current as
conventions evolve.

## The app in one paragraph

Narrator is a privacy-first audiobook player for Android. It imports EPUB and PDF
files, splits the text into sentence-/clause-shaped chunks, synthesises each chunk
to a WAV via the on-device `TextToSpeech` engine, and plays them through a single
reused `MediaPlayer`. No network access for any core feature. Targeted at /e/OS and
Fairphone but works on any Android 8.0+ device.

## Package layout

- `com.example.narrator` — Application class + `MainActivity` + container DI.
- `com.example.narrator.epub` — EPUB parser, sentence splitter (`Sentences`) used
  by both parsers, HTML→text helpers.
- `com.example.narrator.pdf` — PDFBox-Android based parser + `TextCleaner` for
  unicode / abbreviation / Roman-numeral / URL normalisation.
- `com.example.narrator.data` — SQLite (`NarratorDatabase`), entities, repository,
  importer, `BackupManager` + `BackupArchive` (the pure ZIP I/O lives here so it
  can be unit-tested without an Android context).
- `com.example.narrator.tts` — `Narrator` (state machine + StateFlow), `FilePipeline`
  (synth-to-WAV + MediaPlayer queue), `NarrationService` (foreground media session),
  `VoicePreferences`.
- `com.example.narrator.ui.{library,player,settings,about,voicesetup}` — fragments
  + helpers per tab.

## Build commands

```bash
./gradlew :app:assembleDebug          # debug APK at app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest      # all JVM tests (must pass before commit)
./gradlew :app:lintDebug              # Android Lint
./gradlew :app:assembleRelease        # release APK (needs app/keystore.properties)
```

JDK 21. JAVA_HOME points at Android Studio's bundled JBR on Windows:
`C:/Program Files/Android/Android Studio/jbr`. PowerShell users may need to prefix
with `JAVA_HOME=... ./gradlew ...`.

## Phone / device

Development happens on a Fairphone 6 connected over USB. The user has `stay_on_while_plugged_in=7`
so the screen never sleeps while debugging. adb path on Windows:
`$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`.

`adb install -r app\build\outputs\apk\debug\app-debug.apk` after each build.

For screenshots during UI work: `adb shell screencap -p /sdcard/x.png; adb pull /sdcard/x.png ...`

## Release versioning policy

Bump `versionCode` + `versionName` at meaningful release points — not every commit.
For each release:

1. Bump `versionCode` (+1) and `versionName` (semver) in `app/build.gradle.kts`.
2. Add a `Builds:` entry in `metadata/com.example.narrator.yml` and update
   `CurrentVersion` + `CurrentVersionCode`.
3. Add `metadata/en-US/changelogs/<versionCode>.txt` (concise, user-facing).
4. Add a section at the top of `CHANGELOG.md` (engineering-flavoured detail).
5. Commit with `Release X.Y.Z` (release-only commit).
6. Tag `vX.Y.Z` annotated; push commit + tag.

The user has explicitly authorized auto-bumping at session pauses. Always confirm
before pushing the tag (`git push origin vX.Y.Z`).

## Commit messages

The user expects engineering-quality commit bodies — explain the *why* and the
*what changed*, not just the symptom. Existing commit log demonstrates the style.

End every commit with:

```
Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

Git identity is set globally to `stijn <stijn.nefkens@aurorasgrid.com>`.

Commit messages with embedded `"` and multi-line text are fragile on Windows PowerShell
+ git. Use a HEREDOC-style temp file and `git commit -F .git/COMMIT_MSG_TMP`.

## Known gotchas (saved as memories too)

- **AGP 9 bundles Kotlin** — don't apply `org.jetbrains.kotlin.android` separately.
- **JAVA_HOME** — `C:/Program Files/Android/Android Studio/jbr`.
- **TTS engines need a `<queries>` filter** in the manifest on Android 11+.
- **sherpa-onnx speaker IDs** are hidden from `TextToSpeech.voices` — only its own
  settings activity exposes them.
- **MediaPlayer.setPlaybackParams auto-starts** a PREPARED MP on Android 15.
- **Stale PREPARED MediaPlayer** drops audio if held > 1s before start().
- **sherpa-onnx punctuation artifacts**: trailing quotes render as an audible "ktsh"
  click; FilePipeline strips them.
- **tts.stop() cancels queued synth** — skip/teardown must call it or stale work piles up.
- **Material 3 widgets need M3 theme** — MaterialSwitch is invisible under M2.
- **Sherpa APK installs disabled** — direct APK install can leave sherpa-onnx in
  `enabled=0` state; check with `adb shell dumpsys package com.k2fsa.sherpa.onnx.tts.engine | grep enabled`.

## Database schema versioning

Current version: 4. Migrations live inline in `NarratorDatabase.onUpgrade`. Bump
`DATABASE_VERSION` and add an `if (oldVersion < N)` block whenever adding columns.
Tests should round-trip a backup with the new schema to catch column-mismatch errors.

## When the user asks for "QoL"

They mean: standard Android patterns the app is missing (swipe gestures, snackbars,
empty states, undo, etc.). Lean toward Material 3 conventions over custom widgets.

## When the user asks for "a plan"

They want a structured proposal with options ranked by impact-vs-effort, plus a
clear "my recommendation" at the end. Use `AskUserQuestion` to surface concrete
forks rather than blocking on every detail.

## Testing conventions

- Pure JVM unit tests live under `app/src/test/`.
- Android-only types (Bitmap, Uri, SQLiteDatabase) keep us out of full integration
  testing in unit tests — extract pure helpers (see `BackupArchive` vs
  `BackupManager`) and test those.
- Mark helpers needed by tests with `@androidx.annotation.VisibleForTesting` and
  `internal` visibility.
- Don't add Robolectric / instrumented tests without explicit user buy-in — they
  slow CI and bloat dependencies.

## CI

`.github/workflows/android.yml` runs on every push, every PR, and every `v*` tag.

- gradlew must have the executable bit (use `git update-index --chmod=+x gradlew`
  on Windows).
- Tagged builds produce signed release APKs IF the `RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` secrets
  are set. Otherwise the build runs unsigned and skips the attach step.

## What not to do

- Don't grow the abbreviation list in TextCleaner without test cases — each new
  entry risks mis-expanding international content.
- Don't add a network permission for any feature. The one outbound action (opening
  the Kokoro APK URL in the browser via Voice setup) does not need a permission.
- Don't switch to a third-party EPUB library; the hand-rolled parser is intentional
  for trust + footprint.
- Don't add analytics. Don't add Firebase. Don't add Play Services.
