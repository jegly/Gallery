//! Grainhouse: a photo editor with film looks, AI effects, Lightroom-style editing and Real-ESRGAN upscaling.

pub mod adjust;
pub mod ai;
pub mod camera;
pub mod creative;
pub mod develop;
pub mod engine;
pub mod export;
pub mod facemesh;
pub mod film;
pub mod font;
pub mod frame;
pub mod fx;
pub mod geometry;
pub mod img;
pub mod io;
pub mod local;
pub mod localtone;
pub mod lut;
pub mod pipeline;
pub mod presets;
/// Gallery's copy has no Real-ESRGAN upscaling: the same names, and asking for it returns an error.
#[path = "upscale_off.rs"]
pub mod upscale;
pub mod util;
pub mod whitebalance;
