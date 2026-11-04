//! The Ghosts effects, ported from the camera app. Each works on row strips of the image, from small maps
//! (the subject mask and things made from it) stretched to the image's size.

pub mod contours;
pub mod delaunay;
pub mod diffusion;
pub mod extra;
pub mod ghost;
pub mod mesh;
pub mod multishot;
pub mod scene;
pub mod skeleton;

use crate::img::Image;
use rayon::prelude::*;

/// Colours of the effects' light.
pub const COLOURS: [&str; 8] = ["White", "Moonlight", "Warm", "Gold", "Rose", "Violet", "Cyan", "Green"];
const TINTS: [[f32; 3]; 8] = [
    [1.0, 1.0, 1.0],
    [0.82, 0.9, 1.0],
    [1.0, 0.9, 0.78],
    [1.0, 0.83, 0.5],
    [1.0, 0.78, 0.86],
    [0.85, 0.76, 1.0],
    [0.45, 0.95, 1.0],
    [0.45, 1.0, 0.55],
];

pub fn tint(colour: usize) -> [f32; 3] {
    TINTS[colour.min(TINTS.len() - 1)]
}

/// Glow levels: off, soft, strong.
pub const GLOW_NAMES: [&str; 3] = ["Off", "Soft", "Strong"];
pub const GLOW: [f32; 3] = [0.0, 0.6, 1.0];

pub fn glow(level: usize) -> f32 {
    GLOW[level.min(2)]
}

pub trait Effect: Sync {
    /// Applies the effect to whole rows `rows` (rows.len() / w of them) starting at row y0 of a w x h image.
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize);
}

const STRIP: usize = 64;

/// Runs an effect over a whole image, a strip per thread.
pub fn run(effect: &dyn Effect, img: &mut Image) {
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w * STRIP).enumerate().for_each(|(i, rows)| effect.apply(rows, i * STRIP, w, h));
}

/// The subject's soft shape from the engine's mask: a firm edge around the model's 50% line.
pub fn alpha_from_mask(mask: &[u8]) -> Vec<f32> {
    mask.iter()
        .map(|&m| {
            let a = (m as f32 / 255.0 - 0.35) / 0.3;
            if a <= 0.0 {
                0.0
            } else if a >= 1.0 {
                1.0
            } else {
                a * a * (3.0 - 2.0 * a)
            }
        })
        .collect()
}
