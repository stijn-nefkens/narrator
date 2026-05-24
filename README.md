# Narrator

Privacy-first audiobook player for Android, built for /e/OS Fairphone but works on
any Android 8.0+ device.

Everything runs on the device. No network access, no analytics, no cloud sync.
Your books, bookmarks, and progress never leave the phone.

## Features

- **EPUB import** via the system file picker, share menu, or by tapping an `.epub`
  in any file manager.
- **On-device narration** via Android's `TextToSpeech`. Sherpa-onnx with Kokoro is
  recommended for natural neural narration; RHVoice and eSpeak also work.
- **Background playback** with lock-screen and notification controls (foreground
  media service).
- **Auto-pause on phone call** via Android's audio-focus API.
- **Sleep timer** — end-of-chapter, 15 / 30 / 60 minutes, or any custom duration.
- **Bookmarks** at named positions; long-press to delete.
- **Per-book speed memory** plus a global default; +/- buttons step 0.05x.
- **Follow-along text** with word-by-word highlighting when the engine supports
  `onRangeStart` (falls back to time-based estimation).
- **Time-remaining estimate** based on observed playback rate.
- **Library** with search, four sort modes (recently played / added / title /
  author), multi-select delete, per-book rename, "Last opened" timestamp.
- **Themes:** Light, Dark, Follow system, and Black (AMOLED true-black for OLED).

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # unit tests (19)
./gradlew :app:installDebug       # install to a connected device
```

JDK 21 (Android Studio's bundled JBR works). Gradle wrapper takes care of the
rest; no Google libraries, no Firebase, no Play Services.

To build a signed release APK locally, drop `app/keystore.properties` (gitignored)
containing `storeFile=...`, `storePassword=...`, `keyAlias=...`, `keyPassword=...`,
then `./gradlew :app:assembleRelease`.

The GitHub Actions workflow at `.github/workflows/android.yml` builds debug + tests
on every push and a signed release APK on `v*` tags (uses
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` secrets).

## Architecture

- **EPUB parser** (`com.example.narrator.epub`) — hand-rolled, no third-party
  EPUB library. Uses `java.util.zip` + `jsoup` for HTML→text. Splits sentences
  with `BreakIterator`, merges short consecutive sentences (so dialogue + tag
  read as one utterance), and sub-chunks long sentences at clause boundaries
  with weighted scoring (em-dash > semicolon/colon > comma).
- **Playback pipeline** (`com.example.narrator.tts.FilePipeline`) — synthesises
  each chunk to a WAV via `synthesizeToFile`, plays it through a single reused
  `MediaPlayer`. Synthesis runs in parallel with playback; the next chunk is
  always being prepared while the current is being spoken. Trailing/leading
  WAV samples get a short linear fade to mask engine artefacts.
- **Persistence** — plain `SQLiteOpenHelper`. Two tables: `books` + resume
  `bookmarks`, plus `saved_bookmarks` for named positions. Schema migrations are
  handled inline in `onUpgrade`.
- **DI** — hand-wired `AppContainer` singleton on the `Application`. No Hilt,
  no annotation processors.

## Distribution

The repo contains F-Droid / Murena App Lounge metadata under `metadata/`. To
publish on F-Droid, submit a build recipe pointing at this repository to the
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repo.

## Privacy

The app declares no `INTERNET` permission, uses no analytics SDK, and does not
talk to any server. The only Android permissions it requests are:

- File picker for EPUB import (no broad storage access)
- Foreground service + media playback for background listening
- Notifications (Android 13+) so the lock-screen player works
- Visibility into installed TTS engines (Android 11+ package visibility)

## License

Apache 2.0 — see [LICENSE](LICENSE).
