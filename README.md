# Narrator

Privacy-first audiobook player for Android, built for /e/OS Fairphone but works on
any Android 8.0+ device.

Everything runs on the device. No network access, no analytics, no cloud sync.
Your books, bookmarks, and progress never leave the phone.

## Features

### Content
- **EPUB and PDF import** via the system file picker, share menu, or by tapping
  the file in any file manager.
- **On-device narration** via Android's `TextToSpeech`. Sherpa-onnx with Kokoro is
  recommended for natural neural narration; eSpeak and RHVoice also work. A
  one-tap "Install natural voice" button downloads the Kokoro APK directly from
  the official mirror.
- **Format chip** on each book row shows whether it's an EPUB or PDF source.

### Playback
- **Background playback** with lock-screen and notification controls (foreground
  media service).
- **Auto-pause on phone call / loss of audio focus**.
- **Speed control** — tap the chip to reset to 1.0x, drag left/right to step in
  0.1x increments, long-press for a slider dialog. Per-book speed memory plus a
  global default.
- **Skip controls** — previous/next chapter, previous/next step (sentence or
  paragraph, configurable).
- **Sleep timer** — end-of-chapter, 15 / 30 / 60 minutes, or any custom duration.
  Freezes when you pause, resumes when you play.
- **Scrub bar** with chapter-boundary dots so you can see chapter splits at a
  glance.
- **Bookmarks** at named positions; long-press to delete; export the whole list
  as plain text.
- **Follow-along text** with word-by-word highlighting when the engine reports
  `onRangeStart` events (falls back to time-based estimation).
- **Mini-player** above the bottom nav on the Library and Settings tabs so you
  can pause / skip / jump to Player without changing tabs.
- **Time-remaining estimate** based on observed playback rate.
- **Graceful TTS-engine failure** — if the selected engine becomes unbound or
  disabled, the pipeline halts instead of racing through chunks, and the Player
  shows a Snackbar with a shortcut to Voice setup.

### Library
- Search, four sort modes (recently played / added / title / author).
- **Long-press** enters multi-select; tap to add or remove rows.
- **Swipe left** on a row to delete with a Snackbar undo.
- Per-book rename via the pen icon in the action toolbar.
- "Last opened" timestamp per book.

### PDF handling
The PDF parser does substantial cleanup before chunking, so most PDFs read as
naturally as EPUBs:

- Chapters from the PDF's table-of-contents when present; falls back to a
  font-size heading heuristic.
- Front-matter / back-matter dropped automatically (copyright, imprint, TOC,
  references, bibliography, index, glossary).
- Running headers, footers, and page numbers stripped per page.
- End-of-line hyphen rejoin and soft-hyphen removal.
- Ligatures (ﬁ ﬂ ﬃ etc.) normalised to plain letters.
- Bullet glyphs and weird unicode whitespace cleaned up.
- URLs and email addresses replaced with spoken placeholders so they don't get
  spelled out character-by-character.
- Common abbreviations expanded (Dr. → Doctor, e.g. → for example, etc.).
- Roman numerals in chapter headings converted to Arabic.
- Footnote suppression (font-size based), image / table caption strip,
  table-region detection and drop.

User escape hatches for PDFs the heuristics miss:

- **Page range** dialog at import time.
- **Import preview** showing the parsed chapter list before the book lands in
  the library.
- **Per-book skip patterns** — regex list editable from the rename dialog;
  matching chunks are dropped at load.

### Themes & accessibility
- Light, Dark, Follow system, and Black (AMOLED true-black for OLED).
- Material 3 throughout.

### Library backup
- **Back up** the whole library (DB + source files + covers) to a single ZIP via
  `ACTION_CREATE_DOCUMENT`.
- **Restore** from a previous ZIP (confirms before wiping the current state).

## Build

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # unit tests
./gradlew :app:installDebug       # install to a connected device
```

JDK 21 (Android Studio's bundled JBR works). Gradle wrapper takes care of the
rest; no Google libraries, no Firebase, no Play Services. PDFBox-Android is the
only third-party dependency that adds significant APK size (~12 MB).

To build a signed release APK locally, drop `app/keystore.properties` (gitignored)
containing `storeFile=...`, `storePassword=...`, `keyAlias=...`, `keyPassword=...`,
then `./gradlew :app:assembleRelease`.

The GitHub Actions workflow at `.github/workflows/android.yml` builds debug + tests
on every push and a signed release APK on `v*` tags (uses
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` secrets).

## Architecture

- **EPUB parser** (`com.example.narrator.epub`) — hand-rolled, no third-party
  EPUB library. Uses `java.util.zip` + `jsoup` for HTML→text.
- **PDF parser** (`com.example.narrator.pdf`) — built on PDFBox-Android. A custom
  `PDFTextStripper` captures per-line font sizes so chapter detection works
  without a TOC; `TextCleaner` runs the unicode / abbreviation / link
  normalisation pass.
- **Sentence splitter** (`com.example.narrator.epub.Sentences`) — shared by both
  parsers. Restores missing post-period spaces, splits with `BreakIterator`,
  merges short consecutive sentences so dialogue + tag read as one utterance,
  sub-chunks long sentences at clause boundaries with weighted scoring (em-dash
  > semicolon/colon > comma).
- **Playback pipeline** (`com.example.narrator.tts.FilePipeline`) — synthesises
  each chunk to a WAV via `synthesizeToFile`, plays through a single reused
  `MediaPlayer`. Synthesis runs in parallel with playback; the next chunk is
  prepared while the current is spoken. A short linear fade masks engine
  artefacts at chunk boundaries. Cascading synth failures (3 in a row) halt the
  pipeline and surface to the UI instead of advancing through the book silently.
- **Persistence** — plain `SQLiteOpenHelper`. Three tables: `books` + resume
  `bookmarks` + `saved_bookmarks` for named positions. Schema v3 with inline
  migrations.
- **Backup** (`com.example.narrator.data.BackupManager`) — single ZIP of
  `narrator.db` + the `epubs/` and `covers/` directories. Restore stages to a
  temp dir first so a corrupt zip can't trash the existing library.
- **DI** — hand-wired `AppContainer` singleton on the `Application`. No Hilt,
  no annotation processors.

## Distribution

The repo contains F-Droid / Murena App Lounge metadata under `metadata/`. To
publish on F-Droid, submit a build recipe pointing at this repository to the
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) repo.

## Privacy

The app declares no `INTERNET` permission, uses no analytics SDK, and does not
talk to any server. The only Android permissions it requests are:

- File picker for EPUB / PDF import (no broad storage access)
- Foreground service + media playback for background listening
- Notifications (Android 13+) so the lock-screen player works
- Visibility into installed TTS engines (Android 11+ package visibility)

The "Install natural voice" button opens the Kokoro APK download URL in the
system browser — the only network activity the app ever triggers, and only when
the user explicitly taps that button.

## License

Apache 2.0 — see [LICENSE](LICENSE).
