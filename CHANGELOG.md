# Changelog

All notable changes per release. Newest at top. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.18.0 — 2026-05-31

Two batches shipped together — the 0.17.0 work below was never tagged
(its release attempts kept getting interrupted), so it ships here under
one tag. `versionCode` 22 is intentionally skipped. Schema migrates
v4 → v5.

Progress, chapter titles, import flow (the 0.18.0 batch):

- **Finished = 100%** (was stuck at 99%): the playhead only reaches the
  last sentence index (~99%), so a fully-read book never hit 100.
  `Narrator.onChunkComplete` auto-sets `isFinished` at end-of-book;
  `progressPercent` returns 100 when finished, caps in-progress at 99. The
  book dims like a manually-finished one.
- **Stale progress fixed** for pre-0.16 books: imported when parsers
  sub-chunked (many chunks), now whole sentences (fewer), so an old-unit
  `globalChunk` against the recomputed `totalChunks` gave a wrong % (e.g.
  90% at the halfway mark) and wrong resume point. No precise conversion
  exists → migration v4→v5 clears resume bookmarks once (named bookmarks
  kept); affected books restart from 0 with correct counts.
- **Chapter title no longer glued to the first sentence**: a heading
  carries no period, so it fused with the first body sentence into one
  chunk (read without a break, too long to trigger the title pause).
  `Sentences.splitHeadingFromBody` splits a glued heading into its own
  *paragraph* (terminated) so it becomes its own chunk — escaping the
  dialogue-merge — and the title pause fires. EpubParser + PdfParser;
  unit-tested.
- **Loading spinner when opening a book**: `onBookClicked` switches to the
  Player tab *before* `loadBook`, so the spinner shows during the parse.
- **Loading spinner when importing**: a `library_importing` ProgressBar
  over the copy+parse step.
- **Import preview removed**: importing is one tap. Duplicate prompt +
  failure toast remain; PDF page-range dialog unaffected.
- **Separate bookmark export removed**: the library backup (Settings)
  already includes the saved-bookmarks table.

Title sync, trailing-bracket audio, chopping tune (the 0.17.0 batch):

- **Library title/author edits now reach the Player** (+ mini-player +
  notification), live, without a re-parse. Root cause was a duplicated
  source of truth: `LoadedBook.title/author` came from the *parsed file*
  while the Library showed the *DB* row. Merged onto the DB via the new
  pure `LoadedBook.from(...)` factory; `Narrator.refreshLoadedMetadata`
  updates the loaded book in place on edit. Unit-tested.
- **Trailing-bracket click fixed**: `)` `]` `}` added to
  `FilePipeline.TRAILING_NOISE_CHARS` so ".)" / ".]" no longer renders the
  "ktsh" artifact. `stripTrailingNoise` extracted + unit-tested.
- **Dynamic chopping tuned**: `budgetForDepth` depth 1 130/90 → 110/75,
  depth 2 220/160 → 170/120 (cold start and whole-at-depth-3 unchanged) —
  banks audio a touch faster to avoid mid-playback stalls.

## 0.16.2 — 2026-05-31

Caption shows the whole sentence; highlight is segment-offset-aware.

0.16.1 fixed the highlight desync by making the caption follow the spoken
*segment* — but that leaked the synthesis chopping to the reader: while a
skipped-to sentence synthesised, the caption showed the whole sentence,
then snapped to a short first segment when audio started. This makes
segmentation invisible: the caption is always the whole sentence (stable),
and the highlight is positioned absolutely within it.

- `Sentences.subChunkWithOffsets` returns each segment with its char
  offset in the sentence (reconstructed by a whitespace-skipping walk —
  exact, no duplicate-word ambiguity a plain `indexOf` would hit).
- `FilePipeline` carries `segmentOffset` per segment and exposes
  `activeSegmentOffset()` / `activeSegmentLength()`.
- `Narrator.currentSpokenCharEnd()` is now sentence-absolute:
  `segmentOffset + within-segment progress` (engine char-ranges when
  present, else MediaPlayer position ÷ duration × segment length — the
  FP6's sherpa emits no ranges, so the time fallback is the live path).
- `PlayerFragment` always displays `currentText` (whole sentence) and
  highlights up to the absolute char end. Subsumes the 0.16.1 per-segment
  caption (removed) without the chopping side-effect.
- Highlight anti-jitter (fixes "highlight jumps back when pausing to
  synthesise", worst after skipping since a skipped-to sentence is cut
  most aggressively): two guards in `currentSpokenCharEnd` —
  (a) **position match**: ignore the bound segment's coordinates when it
  belongs to a different sentence than the one on screen (the gap between
  sentences); (b) **monotonic floor** (`highlightFloorChars`, reset per
  sentence in `updateCurrentTexts`): never move the highlight backward
  within a sentence, so the inter-segment synth gap — MediaPlayer idle at
  position 0 — can't snap it back to the start of the segment that just
  finished.

Tests: SentencesAdaptiveTest gains offset coverage (segments locatable,
offsets monotonic, whole-sentence offset 0); suite 94, 0 failures; Lint
green.

## 0.16.1 — 2026-05-31

Fix: follow-along caption out of sync with audio after 0.16.0.

- With buffer-adaptive length a sentence may be synthesised as several
  segments (cold start / after skipping back), but the caption still
  showed the whole sentence — so the word highlight, which maps the
  current segment's MediaPlayer progress onto the displayed text,
  restarted over the full sentence once per segment and outran the voice.
- `FilePipeline` now exposes `activeSegmentText()` (the segment bound to
  the MediaPlayer, held across the inter-segment reset gap);
  `Narrator.currentSpokenSegment()` surfaces it; `PlayerFragment`
  displays it for the highlight, falling back to the whole sentence
  (`currentText`) only when nothing is playing (e.g. paused before first
  play). Text, highlight, and audio now refer to the same segment.

## 0.16.0 — 2026-05-31

Buffer-adaptive sentence length — read whole sentences when buffered.

Mid-sentence cutting flattens intonation and sounds unnatural. Cutting
now scales inversely with how much audio is banked ahead of the playhead,
so the engine reads whole sentences whenever it can and only cuts when
there's latency pressure.

- **Phase 1 — `Sentences` primitives** (commit b523645):
  `CutBudget` + `budgetForDepth(depth)` (0→70/45, 1→130/90, 2→220/160,
  3+→whole, capped at `MAX_CHUNK_CHARS`); `splitSentences()` (merge-only
  position units); `subChunk(sentence, budget)` (on-demand). The
  clause/word cut helpers are parameterised by threshold+target; the
  parse-time `split()` (shared `rawSentences` source) is byte-identical,
  guarded by the existing `SentencesTest`.
- **Phase 2 — playback wiring**: the position unit is now a whole
  sentence (EPUB/PDF parsers emit `splitSentences`). `Narrator`
  sub-chunks each sentence at queue time by its distance from the
  playhead — head (depth 0) cut for fast first audio, sentences deeper in
  the buffer read whole. `FilePipeline` expands a sentence into 1..N
  audio segments while keeping the MediaPlayer state machine intact:
  `onChunkStarted` fires on the first segment, `onChunkCompleted` on the
  last, so a sentence is exactly one Narrator position (bookmarks,
  scrubbing, progress, chapter nav stay sentence-granular).
- Verified on the FP6: head sentence arrives as small segments
  (len 53/58, first audio ~0.1s), steady-state sentences synthesise and
  play whole (len 212/147/212/99), inter-segment gaps 8–22ms (no stall),
  positions advance once per sentence.
- Note: existing resume bookmarks shift slightly (position unit went from
  sub-chunk to sentence); they snap to the nearest sentence on next open.
  `totalChunks` recomputes on load.

## 0.15.0 — 2026-05-31

Natural pause after a spoken chapter title.

- The chapter heading is already read aloud (EPUB `<h1>` and PDF
  large-font line both become a chapter's first chunk). A short beat
  (`TITLE_PAUSE_MS` = 900ms) is now inserted after it so the title reads
  as its own line instead of running into the body. `FilePipeline` holds
  the MediaPlayer idle after a chunk flagged `isChapterTitle`; the
  existing inter-chapter pause and this post-title pause share one
  scheduled-start slot (`pendingDelayedStart`) and never overlap.
- The title flag is set by `Narrator.isChapterTitlePosition`: chunk 0,
  the chapter has a following body chunk, the chunk is short
  (≤ `TITLE_MAX_CHARS`), and its text matches the chapter title
  (`titleLike` — normalised compare with containment to absorb a
  "Chapter N" prefix / punctuation divergence). The title-match guard
  stops the pause firing after an ordinary short opening sentence.
- `titleLike` is pure + `@VisibleForTesting`; `NarratorTitleTest` covers
  exact / case / punctuation / containment matches plus non-title and
  blank cases. Added a `TIMING pause` log line for diagnosing gaps.

## 0.14.0 — 2026-05-31

PDF text-handling: body Roman numerals + stronger table detection.

- **Roman numerals in body text**: `TextCleaner.romanNumeralsInBody`
  converts standalone multi-letter romans outside the existing
  Chapter/Part marker path — "Louis XIV" → "Louis 14", "World War II" →
  "World War 2", "the XIX century" → "the 19 century". Three guards
  against prose damage: `{2,}` (never touches the pronoun "I" or single
  letters); canonical validation (rejects "IIII"/"VV"/"IL" and most
  all-caps roman-letter words like "DIM"); and a blocklist for the few
  short tokens that are canonical romans but usually abbreviations
  (`MM, MC, MD, MI, CD, DC, MIX, DIV, CIV`). Title conversion is
  unchanged.
- **Stronger table detection**: `stripTableRuns` now drops runs of 3+
  table-shaped lines (was 4+) and recognises *numeric rows* (3+ tokens,
  ≥half numbers) in addition to the existing wide-gap rule — so
  tightly-set numeric tables (years/figures with narrow columns) and
  shorter tables are caught. Prose with the odd number per line stays
  (numeric ratio under half), and single-gap verse/dialogue is untouched.
  `isTabularLine` extracted and unit-tested.

## 0.13.0 — 2026-05-31

First-audio latency fix for unpunctuated long sentences.

- **Word-boundary fallback cut**: `subChunkByClauses` now tries, in
  order, a clause delimiter → a word boundary near `SUB_CHUNK_TARGET`
  → (only for a space-less mega-token) the `MAX_CHUNK_CHARS` hard cut.
  Previously a long sentence with no comma/dash/bracket fell straight to
  the hard cut, which (being 500 chars) left it whole — so it synthesised
  in one ~7s pass before any audio (observed on the FP6 via FilePipeline
  timing logs). It now splits at a word boundary, halving first-audio for
  that case. `findWordCut` never breaks a word mid-character and returns
  -1 only when there is genuinely no space, preserving the hard-cut path
  for mega-tokens.

## 0.12.0 — 2026-05-31

Cross-book switching speed + the deferred glued-word fix.

- **Cross-book parse cache**: `Narrator` holds an LRU cache (max 5) of
  parsed books keyed by `bookId`, invalidated by a signature over
  `epubPath` + page range + skip patterns. Re-opening a recent book is a
  cache hit — no re-parse, no spinner. Finished books are never cached;
  cover bytes are dropped before caching (the Player reads the cover from
  disk). `warmRecentBooks()` pre-parses the most-recently-played
  non-finished books in the background at startup, never touching the TTS
  engine so it can't disturb playback.
- **Glued-word splitting**: `TextNormalize.splitGluedWords` breaks
  camelCase run-together words ("morningCame" → "morning Came"), a common
  PDF extraction artifact. Guarded against proper-name / brand mangling:
  requires two lowercase letters before the boundary (spares iPhone,
  eBook, McDonald, DeForest, LaSalle), an explicit `Mac` guard (spares
  MacArthur), and uppercase-then-lowercase after (spares acronym runs).
  Digit↔letter boundaries are intentionally left alone. Now part of
  `TextNormalize.normalize`, so it covers EPUB and PDF.

## 0.11.0 — 2026-05-31

Narration smoothness + text-cleanup pass, driven by real-world testing.

- **Loading indicator**: `NarratorState.loading` is set while a book is
  parsed; `PlayerFragment` shows a centered spinner. Opening a large PDF
  no longer looks like a dead tap.
- **Shorter sentence cutting**: `SUB_CHUNK_THRESHOLD` 100→70 and
  `SUB_CHUNK_TARGET` 70→45 so chunks the engine can synthesise in real
  time. `PREFETCH_DEPTH` 2→4 to keep more audio ready ahead of the
  playhead and absorb the occasional slow chunk.
- **Bracket-aware clause cutting**: `findClauseCut` now supports
  cut-before / cut-after delimiters. A long sentence with a mid-sentence
  parenthetical ("happening (an aside) and more") breaks around the
  brackets instead of mid-clause.
- **Numeric grouping commas stripped**: "200,000" → "200000" so the TTS
  reads it as one number. Guarded so list commas, enumerations and
  European decimals are untouched.
- **Broader glued-sentence repair**: the missing-space-after-period
  heuristic now also fires after digits ("1990.The") and preserves a
  closing quote on the left of the inserted space ("done.\"Then" →
  "done.\" Then").
- **Refactor**: shared `epub.TextNormalize` holds the dot-glue +
  number-comma transforms, called from both `Sentences.split` (covers
  EPUB and PDF) and `pdf.TextCleaner` (per-line). Removes the regex that
  was duplicated across the two files. Fully unit-tested.

## 0.10.1 — 2026-05-25

Stabilization pass. No user-visible behavior changes.

- Tests added for `Sentences` (missing-space restoration, sub-chunker
  no-cap), `TextCleaner` (every transform), and `PdfParser` heuristics
  (TOC / references / index / copyright / image-caption detection).
- `BackupArchive` extracted from `BackupManager` so the ZIP read/write
  is unit-testable without Android `Context`. Round-trip, empty-library,
  missing-DB, and zip-slip cases covered.
- Bug fix uncovered by tests: abbreviation expansion at end of sentence
  ("et al.") was consuming the period, breaking sentence splitting on
  the next chunk. Replacement now preserves a trailing period when
  followed by end-of-string.
- Repo hygiene: this CHANGELOG, `CLAUDE.md`, F-Droid screenshots and
  submission instructions, release-signing docs, and Lint in CI.

## 0.10.0 — 2026-05-25

Ten small QoL improvements bundled together. Schema migrates v3 → v4.

- **Library**: mark a book as finished (Edit details toggle, "FINISHED"
  chip on the row, slight dim). Reset progress button. Empty-state
  shows a prominent Import button. Long-press a second row to extend
  the selection range (shift-click style).
- **Player**: one-shot tooltip explains the speed-chip gestures on
  first open. Sleep timer fades audio over the last 15s instead of
  cutting mid-sentence. Long-press a chapter in the chapter navigator
  to bookmark its start.
- **Audio**: notification chimes now duck the narrator to ~30% instead
  of pausing. Phone calls / longer interruptions still pause + auto-
  resume.
- **Settings**: Snackbar with Share action after backup. Backup
  filename includes `_HH-mm` to avoid same-day collisions.

## 0.9.2 — 2026-05-25

Sentence chunking + backup fixes.

- `Sentences.split` restores missing post-period spaces ("scale.Quite")
  before BreakIterator runs, so long paragraphs no longer fuse and get
  hard-cut mid-word.
- Sub-chunker drops the 150-char upper search bound; cost function
  biases naturally toward target without losing long sentences whose
  first clause break sits past the cap.
- `BackupManager` runs `PRAGMA wal_checkpoint(FULL)` via `rawQuery`
  (cursor-consumed) instead of `execSQL` which rejects pragmas that
  return rows.

## 0.9.1 — 2026-05-25

Tightening pass on the 0.9.0 batch.

- Settings: backup + restore merged into a single "Library" section
  with side-by-side buttons.
- Library: Continue card removed; the mini-player above the bottom nav
  covers the same affordance.
- Player: "Synthesising…" message replaces the remaining-time line in
  place instead of reserving its own row; caption top margin trimmed.
  Player no longer scrolls on a typical 6.x phone screen.

## 0.9.0 — 2026-05-25

QoL pass.

- Swipe-to-delete now removes the row immediately with a Snackbar
  Undo. Bulk delete still confirms upfront.
- Mini-player above the bottom nav on Library / Settings tabs.
- Chapter-boundary dots on the player scrub bar.
- Library backup / restore as a single ZIP.
- Bookmark export from the bookmarks dialog as plain text.
- EPUB / PDF chip switched to a filled badge after the outlined version
  kept clipping the bottom stroke.

## 0.8.0 — 2026-05-25

Library QoL.

- Swipe a row to the left to delete it.
- Long-press enters selection mode and ticks the row in one motion.
- Selected rows show a clear colored background.
- Pen icon in the action toolbar opens Edit details when exactly one
  row is selected.
- "Select all" in the action mode overflow.
- Search clear (×) button.
- EPUB / PDF chip on each book row.

## 0.7.0 — 2026-05-25

PDF import escape hatches.

- Page-range dialog at PDF import (PDFs with > 1 page).
- Import preview lists chapters with first ~160 characters each
  before the book lands in the library.
- Per-book skip patterns (one regex per line) editable from Edit
  details; matching chunks are dropped at load.

## 0.6.0 — 2026-05-25

PDF support.

New parser backed by PDFBox-Android. Imports route by mime type; the
existing chunker + TTS pipeline consume the same `epub.Book` structure.

Cleanup work:
- Chapter detection from PDF outline; font-size heading heuristic
  fallback.
- Front-matter / back-matter skip (copyright, TOC, references,
  bibliography, index, glossary).
- Running header / footer / page-number strip.
- End-of-line hyphen rejoin; soft hyphen removal.
- Ligature normalisation (ﬁ → fi, ﬂ → fl, ...).
- Bullet glyph and weird-whitespace cleanup.
- URL / email replacement with spoken placeholders.
- Common abbreviation expansion; Roman numerals in chapter headings.
- Footnote suppression (font-size based).
- Image / table caption strip.
- Table-region detection and drop.
- Handles "encrypted" PDFs that are actually readable with an empty
  password (typical publisher-PDF pattern).

## 0.5.0 — 2026-05-25

Cascading TTS-synth failure handling.

When the selected engine becomes disabled or unbound, every chunk's
synth fails. Before, this caused position to race forward silently
("10x playback"). Now:

- FilePipeline halts after 3 consecutive synth_error results.
- Pause + drop the queue + fire `onSynthCascadeFailure` callback.
- Narrator surfaces an `engineError` in state; Player shows a Snackbar
  with a Voice setup shortcut.

## 0.4.0 — 2026-05-25

Voice setup nudge.

- Voice setup row moved to the top of Settings.
- "Get a better voice" replaced with "Install natural voice (Kokoro)"
  which one-tap downloads the sherpa-onnx Kokoro APK directly from the
  k2-fsa HuggingFace mirror.
- RHVoice suggestion dropped.

## 0.3.0 — 2026-05-25

Player + Settings polish.

- Speed control consolidated into one chip: tap = reset to 1.0×, drag
  = step by 0.1×, long-press = slider dialog.
- Sleep timer freezes when paused, resumes on play.
- Sleep / 1.00× / Mark chips share a uniform width.
- Cover-tap to play/pause; tap the chapter line to jump.
- Bookmark dialog: long-press a row to delete.
- Settings: theme moved to a segmented button row. "Continue through
  chapter boundaries" removed (narrator always continues). Voice setup
  rows no longer light up the background.

## 0.2.0 — 2026-05-25

Phase 2: foreground service, full controls, settings polish.

- Foreground media service with lock-screen / notification controls.
- Speed control with per-book memory + global default.
- Skip controls (sentence / paragraph / chapter).
- Scrub bar.
- Sleep timer (end-of-chapter, 15/30/60 minutes, custom).
- Bookmarks (resume + named).
- Audio focus on phone calls.
- AMOLED true-black theme; theme selector.
- File-manager intent integration.
- F-Droid metadata layout.
- GitHub Actions tag-triggered release workflow.

## 0.1.0 — 2026-05-23

Phase 1 MVP.

- EPUB import + parsing (chapters, cover, sentence/clause chunking).
- On-device narration via TextToSpeech with sherpa-onnx Kokoro support.
- Library list with progress + rename / delete.
- Single bookmark per book (resume position).
- Settings: speed default, pitch, skip increment, theme.
- Voice setup flow on first run.
