//! Stand-in for `upscale` when the crate is built without the `upscale` feature (no ONNX Runtime and no
//! Real-ESRGAN models): the names stay so callers compile, and asking for upscaling returns an error.

use crate::img::Image;

pub const MODELS: [&str; 3] = ["Fast (general x4v3)", "Fast, denoised (general wdn x4v3)", "Max quality (x4plus)"];
pub const FAST: usize = 0;
pub const MAX: usize = 2;
pub const SECONDS_PER_MP: [f32; 3] = [14.0, 14.0, 230.0];

pub fn estimate(w: usize, h: usize, model: usize) -> f32 {
    (w * h) as f32 / 1e6 * SECONDS_PER_MP[model.min(2)]
}

pub fn upscale4(_img: &Image, _model: usize, _progress: &dyn Fn(f32) -> bool) -> Result<Image, String> {
    Err("upscaling isn't included in this build".into())
}
