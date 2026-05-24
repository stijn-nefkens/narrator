# Requirements & Specification: On-device EPUB narration app for /e/OS

## 1. Overview and goals

A privacy-first audiobook player for /e/OS (deGoogled Android). It lets a user import EPUB books they own and listen to them via on-device text-to-speech. No book content ever leaves the device.

The app must function fully offline for its core feature (listening). It is distributed through the Murena App Lounge.

Design principles, in priority order:

1. **Privacy** — book content stays local; no network is used for any core feature.
2. **Simplicity** — minimal, focused UI.
3. **Offline-first** — listening to an imported book never requires a network connection.

## 2. Platform and distribution

The target device is the Fairphone 6 (Murena Fairphone Gen. 6) running /e/OS (currently /e/OS 3.x, Android 15-based). Because /e/OS is Android-compatible, the app is a standard Android app and the recommended implementation is native Kotlin.

Constraints:

- Must not depend on Google Play Services or any proprietary Google library, so it runs on a deGoogled device and is acceptable to the App Lounge / F-Droid ecosystem.
- Dependencies should be FOSS-compatible.
- Minimum supported Android API level: **API 26 (Android 8.0)**. The Fairphone 6 runs Android 15, but this lower minimum widens support across the refurbished phones common in this audience.

Distribution channel: Murena App Lounge.

## 3. Navigation structure

The app uses a bottom navigation bar with three top-level destinations:

1. Player
2. Library
3. Settings

The currently selected destination is visually highlighted. Switching destinations preserves state — returning to Player continues showing the currently loaded book and its playback state without resetting.

## 4. Views

### 4.1 Player view

Controls playback of the currently loaded book. It displays:

- Cover image of the current book (the EPUB's embedded cover; a neutral placeholder when none exists).
- Book title and author.
- Current chapter name or number.
- A progress bar showing position through the **whole book** (not just the current chapter). Tapping or dragging the bar scrubs to a position.
- A primary play / pause toggle button.
- Skip controls: skip-by-chapter (previous / next chapter) and a smaller skip-by-sentence (previous / next sentence).
- A playback speed control (range 0.8x–2.0x).

If no book is loaded, the Player shows a neutral empty state directing the user to the Library to import or select a book.

### 4.2 Library view

Displays the user's imported books as a list. Each row shows:

- Cover image (the EPUB's embedded cover; a neutral placeholder is shown when none exists).
- Title and author.
- A reading-progress indicator (percentage or small progress bar) so the user can see what they have started.

Controls and behaviour:

- A "+" button imports a new book via the system file picker (EPUB only in this version).
- Tapping a book row loads it into the Player and switches to the Player view.
- A book can be deleted from the library (e.g. long-press or swipe). Deleting removes the copied file from app-private storage and its metadata/bookmark.
- **Empty state:** when the library has no books, the view shows the "+" button with placeholder text such as "Please import your books here."
- **Duplicate import:** when the user imports a file that matches an existing book (matched on title + author from metadata), the app asks the user which copy to keep — the newly imported one or the existing one — rather than silently overwriting or duplicating.

### 4.3 Settings view

The settings included in this version:

- **Default playback speed** — applied to newly opened books.
- **Voice selection** — choose which on-device TTS voice / engine to use (relevant on /e/OS, where several engines may be installed or the default may be limited).
- **Default skip increment** — defines the smaller skip control (e.g. sentence vs. paragraph).
- **Pitch adjustment** — adjusts TTS voice pitch.
- **Theme toggle** — light / dark / follow system.
- **Continue through chapter boundaries** — whether playback automatically continues into the next chapter or stops at the end of each chapter.
- **Voice setup** — re-runs the first-run voice setup flow (§5.3): sample the current voice, switch engine/voice, or get guidance on installing a better one.
- **About** — app version and licence information.

## 5. Functional requirements

### 5.1 Book import and parsing (EPUB only)

- The app accepts EPUB files chosen via the system file picker.
- EPUB files are parsed into structured chapters and clean text suitable for narration.
- The parser strips non-narratable artefacts (page numbers, repeated headers/footers) and reflows text into sentences so narration reads naturally.
- **Title and author** are taken from EPUB metadata. When metadata is missing, fall back in this order: (1) embedded metadata, (2) the filename, (3) "Unknown title" / "Unknown author" as a last resort. (Content-based guessing is intentionally not used, as it is unreliable.)
- **Cover image** is extracted from the EPUB and stored for display in the Library and Player. When the EPUB has no embedded cover, a neutral placeholder is used instead.
- PDF support is explicitly **out of scope for this version** (see §8 phasing).

### 5.2 Text-to-speech playback

- Narration is produced by an on-device TTS engine via Android's `TextToSpeech` API.
- Text is fed to the engine segment by segment (sentence by sentence). The app tracks the currently playing segment to support pause / resume and position tracking.
- **No book text is sent over the network** for narration.
- **No-engine handling:** if no suitable TTS engine or voice is installed, the app detects this and guides the user to install a better engine (a neural sherpa-onnx voice first, RHVoice as a lighter fallback). This is handled primarily by the first-run voice setup (§5.3) and re-checked whenever playback is attempted with no usable voice. Required behaviour given the deGoogled target platform.
- **Background and screen-off playback (required):** playback continues when the screen is off and when the app is backgrounded. The app exposes media controls (play / pause / skip) on the lock screen and in the notification shade, via an Android foreground media service.

### 5.3 First-run voice setup

Because /e/OS ships without Google's TTS engine, the device's default voice may sound robotic or, in some cases, no usable voice may be installed at all. Since voice quality is the single biggest factor in whether a listening app feels good, the app guides voice setup on first launch rather than leaving the user to discover a poor default mid-book.

Recommended engines, in priority order (validated by testing on a Fairphone 6 / /e/OS):

1. **A neural sherpa-onnx engine (e.g. Kokoro)** — primary recommendation. Confirmed to sound clearly natural on the target device. Heavier than the alternatives: larger storage footprint (models can be a few hundred MB) and some model-load latency on first use, which is acceptable for long-form listening.
2. **RHVoice** — lighter fallback. Better than the stock /e/OS voice but noticeably synthetic; suitable for older or storage-constrained devices.
3. The stock /e/OS engine (eSpeak-class) is treated as a last resort only, as it is too robotic for comfortable long-form listening.

On first launch (and reachable later from Settings), the app:

- Detects which on-device TTS engines and voices are available.
- Plays a short sample sentence so the user can hear the current default voice before committing to it.
- If voice quality is poor or no suitable engine is found, recommends installing a higher-quality engine — a neural sherpa-onnx voice first, RHVoice as a lighter alternative — with a direct link or clear instructions. This recommendation is friendly guidance, not a hard block — the user can proceed with the default if they prefer.
- Lets the user pick the engine and voice they want, writing the choice to the voice-selection setting (§4.3).

This flow is skippable, does not require a network connection to proceed (only the optional engine install does), and never blocks the user from reaching their library. It is shown once on first run; afterwards it lives in Settings as "Voice setup."

### 5.4 Persistence

- The library of imported books persists across app restarts.
- Each book's last playback position (bookmark) is persisted, so listening resumes where the user left off.
- **File storage:** on import, the app copies the EPUB file into its own app-private storage (rather than referencing the original location). This keeps the book available even if the original is moved or deleted, and keeps content within the app's sandbox, consistent with the privacy model.
- Metadata (title, author, progress, bookmark, file reference) is stored in a small local database (e.g. Room / SQLite).

## 6. Non-functional requirements

- **Privacy:** core features (import, parse, narrate, persist) must work with no network access. The app requests the minimum Android permissions necessary (file access for import) and includes no analytics or tracking SDKs.
- **Offline:** listening to an already-imported book must work in airplane mode.
- **Performance:** importing and parsing a typical book completes in a few seconds and does not freeze the UI (parsing runs off the main thread).
- **Licensing:** all dependencies are FOSS-compatible, suitable for distribution in the App Lounge / F-Droid ecosystem.

## 7. Out of scope

- Cloud sync of library or progress across devices.
- Built-in bookstore or catalogue (users bring their own files).
- DRM-protected book support (DRM EPUBs cannot be opened).
- Any account system.
- PDF import (deferred to a later phase — see below).
- Any cloud/AI text features.

## 8. Suggested phasing
    
- **Phase 1 (MVP):** first-run voice setup, EPUB import, on-device narration with play / pause, library list with persistence, resume-from-bookmark.
- **Phase 2:** speed control, skip controls (chapter + sentence), chapter display, scrub bar, delete, duplicate-import handling, background playback with lock-screen / notification controls, full Settings.
- **Phase 3:** PDF support.

## 9. Confirmed decisions

The previously open points are now resolved and reflected in the spec above:

1. **Duplicate-match rule** — same book = same title + author.
2. **Skip granularity** — both chapter skip and sentence skip are included.
3. **Minimum Android API level** — API 26 (Android 8.0) or higher.
4. **Cover images** — the EPUB cover image is displayed in the Library list (and on the Player). When a book has no embedded cover, a neutral placeholder is shown.