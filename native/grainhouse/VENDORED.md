# Grainhouse (copy)

The editing library of [Grainhouse](https://github.com/jegly/grainhouse), copied from upstream commit
`23a24c6f7afb5cd9ef6dbff55b079d067b2b3347` (v1.0.2) so Gallery builds without fetching anything.
`native/editor` wraps it for Android.

Left out of this copy:

- the GTK window (`src/main.rs`) and the command-line tool (`src/bin/`)
- Real-ESRGAN upscaling and ONNX Runtime (`src/upscale.rs`, `assets/upscale/`): `src/upscale_off.rs` keeps
  the same names and returns an error instead
- the AI models (`assets/models/`): `src/engine.rs` has empty model data, so the AI effects can't load and
  report an error. Gallery's editor never asks for them.

Everything else under `src/`, `engine/`, `build.rs` and `assets/films/` is unchanged from upstream, apart
from those two files, `src/lib.rs` (which points `upscale` at `upscale_off.rs`) and `src/io.rs`, whose
`is_raw` also accepts crw, rwl, fff, mef, kdc and dcr (the RAW types Gallery lists).

To update from Grainhouse: copy `src/` (minus `main.rs`, `bin/` and `upscale.rs`), `engine/`, `build.rs` and
`assets/films/` again, then redo the edits above.

Licensed AGPL-3.0-only, as upstream; see `LICENSE` and `NOTICE`.
