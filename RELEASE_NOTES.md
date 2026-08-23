# Gal Gallery — v4.1.0

A big quality-of-life release. Multi-select everywhere, a completely overhauled viewer,
a richer photo editor, a much better video player, a floating Material You navigation
pill, the whole UI moved to M3 Expressive's spring-based motion system, a 44-theme
Ptyxis palette pack, a font picker, a duplicate finder, and local folder backup.

## New

### Multi-select on every grid
Long-press a thumbnail on Timeline / Albums / Favourites / Search / Trash to enter
selection mode. Contextual top bar shows count, close, select-all and bulk actions:
**Share · Favorite · Copy to album · Move to album · Trash**. Trash screen offers
**Restore + Delete forever**.

### Favourites tab + Trash reachable
- **Favourites** is now its own tab (heart icon) — the DB column was wired up but the
  UI didn't exist before. Heart toggle in the viewer flips it.
- **Trash** is reachable from the Albums overflow menu (it was orphaned before — the
  screen existed but nothing navigated to it).

### Timeline date headers
Grouped by day — *Today / Yesterday / Mon, MMM d / MMM d, yyyy* — using `dateAdded` so
fresh drops land in *Today* instead of an unexpected older day.

### Viewer overhaul
The fixed bottom pill toolbar is gone. Tap to reveal:
- Back FAB at top-start
- **Title chip** centre-top (with middle-ellipsis so the extension stays visible —
  positioned below the display cutout)
- **3-dot menu** top-right: **Share, Favorite (toggle), Edit, Rotate, Set as wallpaper,
  Rename, Copy to album, Move to album, Open with…, Info, Delete**

**Info sheet** now reads EXIF on demand: date taken, camera make/model, lens, ISO,
aperture, shutter speed, focal length, and GPS coordinates (tap to open in your maps
app). Requires the location permission to be granted — the app now requests it
alongside the media permissions on first launch.

**Set as wallpaper** goes straight to an in-app Home / Lock / Both picker that calls
`WallpaperManager` directly — no external app involved. (An earlier build routed
through the OEM system crop+picker intent first, but that intent resolves
unpredictably across devices and could launch an unrelated app, e.g. Contacts'
photo-attach cropper, instead of a wallpaper picker. The OEM picker is still one tap
away via "System picker…" inside the dialog, for anyone who wants its crop UI.)

### Video player — Material You 3 + more controls

Bar redesign:
- Bottom bar is a floating M3 Surface (`surfaceContainerHigh`, `shapes.extraLarge`,
  tonalElevation, shadow) — picks up the wallpaper accent on dynamic themes.
- Slider uses `primary` thumb + active track, `surfaceContainerHighest` inactive track.
- Play/Pause is a prominent **`FilledIconButton`** at 56dp filled with `primary`. Icon
  cross-fades on state change.
- Position + duration labels flank the slider with typography from theme.

Right-side icon column (floating M3 Surface) with **active-state chip** highlighting
when a control is engaged (`secondaryContainer`):
- **Playback speed** (0.25x – 2x)
- **Aspect ratio** cycle (Fit / Zoom / Fill)
- **Repeat mode** cycle (Off / Once / All)
- **Picture-in-Picture** with safe 16:9 fallback when metadata hasn't loaded
- **Subtitle / audio track selection** (when the file has multiple tracks)
- **Frame capture** — saves the current frame as JPG to Pictures/Gal

Gestures:
- **Double-tap** left/right halves: skip ±10s
- **Vertical swipe** left half: brightness · right half: volume (with M3-styled
  centre indicator using `primary` for the progress bar)
- **Single tap** toggles controls — and bringing controls back after the auto-hide
  timeout now works (a gesture detector race was eating the single tap).

### Editor (image)
A full pass on the edit screen:
- **Filters** — 7 presets (None / B&W / Sepia / Vintage / Vivid / Cool / Warm)
  composited into the colour pipeline alongside brightness/contrast/saturation.
- **Flip H / Flip V** alongside the existing 90° rotate buttons.
- **Crop aspect presets** — Free / 1:1 / 4:3 / 16:9 / Original. Apply button always
  visible regardless of phone width (split row layout).
- **Markup / Draw mode** — freehand strokes in 6 colours and 3 brush sizes. Strokes
  are clipped to the actual image area (no stray pixels in the letterbox). Apply
  bakes the strokes into the bitmap.
- **Undo / Redo** — now snapshots bitmaps on Crop and Draw so undo actually reverts
  those operations (previously only sliders/filter could be undone). 20-step history,
  snapshots recycled on close.
- **Save in place** (overwrites the source via `createWriteRequest`) alongside the
  existing **Save copy**. In-app confirmation dialog before the system permission
  flow + proper toast when the user denies the permission.
- **HEIC guard**: source files refuse Save in-place with a clear toast (Android's
  `Bitmap.compress` can't encode HEIC, so silently overwriting with JPEG bytes used
  to rot the file).

### Floating Material You navigation pill
Replaced the stock 80dp NavigationBar with a compact pill that genuinely floats over
content. Selected tab fills with `secondaryContainer`. Last row of thumbnails extends
behind the pill, with bottom contentPadding so nothing important is hidden.

### M3 Expressive motion sweep
Every animation now uses spring-based specs following M3 Expressive's motion-physics
system — six tokens (Fast / Default / Slow × Spatial / Effects). Spatial springs
overshoot for movement / scale / position; effects springs are critically damped for
opacity / colour. Replaces all hand-tuned `tween(180)` / `tween(220)` durations across
the codebase.

### Splash
Suppressed the visible launcher-icon flash on Android 12+ cold start — splash is now
just a brief black frame so the app appears to load straight into the first screen.

### 44 new themes (Ptyxis palette pack)
Settings → Display → **Ptyxis**: a full terminal-palette pack ported in — Nord,
Kanagawa, Monokai Pro, Solarized, GitHub, Everforest, Synthwave, Dracula-adjacent
sets, and more, 44 in total. Pick from a scrollable named-swatch row exactly like the
existing Catppuccin/Dracula accent pickers. Every palette keeps its real, authored
colours (background/foreground/full ANSI table) — nothing invented.

### Font picker
Settings → Display → **Font**: 13 selectable typefaces (Nunito, Cormorant Garamond,
IBM Plex Mono/Serif, Playfair Display, Space Grotesk, Quicksand, Press Start 2P, and
more), each shown as a live "Aa" preview in its own face. Applies app-wide; DotGothic16
remains the default.

### Find duplicates
Settings → Library → **Find duplicates**: scans the library (size, then a streamed
SHA-256 content hash — never hashes the whole library, only same-size candidates),
groups exact-copy photos/videos, and shows each set as a thumbnail row with the oldest
copy marked **KEEP**. Everything but the kept copy is pre-selected; review and send to
Trash in one tap. Nothing is ever permanently deleted from this screen — duplicates go
to Trash like any other delete, so a mis-scan is always recoverable.

### Local folder backup
Settings → Library → **Backup to folder**: pick any folder via the system folder
picker (Storage Access Framework) and copy the whole library into it — a progress
dialog tracks it live. Fully offline, no cloud, no account; re-running a backup into
the same folder skips files already there instead of duplicating them.

### Android 13 support
Lowered `minSdk` from 36 to 33 — the app now installs on Android 13 and up instead of
requiring the very latest OS release.

## Fixes

### Critical
- **Trash returned empty on Android 11+** — MediaStore filters trashed rows out of
  any default query, so the SQL `IS_TRASHED=1` clause matched nothing. Now uses the
  Bundle-based query overload with `QUERY_ARG_MATCH_TRASHED = MATCH_INCLUDE`.

### UI
- Viewer no longer has a black bar at the top (outer Scaffold used to reserve
  status-bar inset on every screen even when the viewer is immersive).
- Viewer title chip clears the display cutout / punch-hole on Android 12+.
- Sort and grouping now both key on `dateAdded`, so newly dropped photos land in
  *Today* instead of a random older day.
- Floating nav pill no longer reserves a "rectangle" of empty space above it; it
  truly overlays content.

### Real-time updates
- New `MediaStoreRefresher` bumps on every `onResume` and kicks
  `MediaScannerConnection` on DCIM / Pictures / Movies / Downloads so files dropped
  via a file manager show up as soon as you switch back to the app.

### Search
- Was filtering the already-loaded timeline list, which could miss results in large
  libraries. Now runs a real `LIKE ?` query against MediaStore (DISPLAY_NAME +
  BUCKET_DISPLAY_NAME), parameter-bound so SQL injection isn't possible.

### Video player
- PiP no longer locks to a 1:1 square when entered before video metadata loads.
- Tap-to-show-controls after the timeout works again.

## Security / privacy hardening

- **FileProvider path** was `cache-path "."` — the entire cache directory was reachable
  via URI grants. Now scoped to `share/` subdirectory; share temp files write there.
- **IntentViewer** validates the incoming URI scheme via `UriValidator` before
  rendering anything (defence in depth — the manifest already restricts to
  content/file, but a misrouted intent would have skipped that).
- **Filename sanitiser** for rename + new-album-name was only stripping `/`. Now
  strips `\/:*?"<>|` plus control chars and leading dots (path-traversal guard).
- **FLAG_SECURE regression caught before release** — an in-progress refactor of the
  viewer had made screenshot/screen-recording protection an opt-in setting (default
  off) instead of always-on while viewing media. Restored as unconditional in the
  viewer, and added it to the photo editor as well (which never had it). The app-wide
  "Block screenshots" toggle in Settings still exists as an *additional* opt-in for
  the rest of the app.
- All these layer on top of the existing posture: EXIF stripping on share, secure
  delete (random + zero overwrite), signature verification on release builds, no
  network permission, empty trust anchors, backup excluded.

## Notes

- A handful of M3 Expressive components are still gated behind `internal` in the
  public AAR (HorizontalFloatingToolbar, LoadingIndicator, SplitButton, ButtonGroup,
  FabMenu, wavy progress indicator). The motion physics that `MaterialExpressiveTheme`
  would have provided IS shipped — by instantiating springs directly with the
  published Expressive damping/stiffness values. When the components are promoted to
  public API, swapping in the official wrappers is a small edit.
- Real-time updates depend on the file manager notifying MediaStore. Most modern
  file managers do; if yours doesn't, the app will catch new files on next resume
  via the manual scanner kick.

## Known issues (not fixed in this release)

A code-review pass this session found these; flagging so they're not lost. Roughly
ranked by severity:

- **Move to album can delete originals it never copied.** If moving several items and
  only some copy successfully, the move still deletes *all* selected source files —
  the ones that failed to copy are lost. (`MediaRepository.moveToAlbum`)
- **Timeline sort order is partially ignored.** Every sort mode still renders through
  day-grouped sections ordered newest-day-first, so "Oldest first" / "Name A-Z" /
  "Largest first" don't produce a single continuously-ordered list. (`TimelineViewModel`)
- **Rotating a mirrored photo drops the mirror.** EXIF orientations outside the plain
  0°/90°/180°/270° cycle (e.g. a horizontally-flipped capture) silently reset to
  unflipped before rotating. (`MediaRepository.rotateOrientation`)
- **Video player volume swipe barely responds.** The gesture converts each small
  per-frame drag delta straight to an integer volume step with no accumulator, so it
  takes an unusually fast swipe to register. (`VideoPlayer.kt`)
- **Cold-start hitch risk.** Coil's image loader factory does a blocking DataStore
  read via `runBlocking` on whatever thread requests the first image (typically main).
  (`GalApplication.newImageLoader`)
- **Bulk trash/restore is unbatched.** "Empty trash" / "Restore all" build one
  MediaStore request covering every item at once; a very large trash (several
  thousand+ items) could theoretically hit the Binder transaction limit.
- **Search treats `%`/`_` as SQL wildcards.** Not a security issue (query is
  parameter-bound), but a literal underscore or percent sign in a search term matches
  more broadly than expected.
- **Bulk import is now capped.** Switching the import picker to the Android Photo
  Picker caps a single import at the platform limit (commonly 100 files); the old
  document picker had no such cap, and there's no in-app message when the cap is hit.

— versionCode 5 · versionName 4.1.0
