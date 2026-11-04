//! Shadows and highlights, like a camera's dynamic range optimiser: brightens or darkens by how bright each area is, using
//! an edge-aware blur of the brightness (a guided filter, worked out small and scaled up) so local contrast and
//! edges stay as they are and there are no halos.

use crate::util::{box_blur, sample, smooth};
use rayon::prelude::*;

pub struct LocalTone {
    /// shrink factor of the small maps
    f: usize,
    sw: usize,
    sh: usize,
    a: Vec<f32>,
    b: Vec<f32>,
    exposure: f32,
    shadows: f32,
    highlights: f32,
}

const SMALL: usize = 768;
/// how big a brightness change counts as an edge to keep (log2 units squared)
const EPS: f32 = 0.25;
/// stops at full strength
const STOPS: f32 = 1.5;

fn log_lum(c: [f32; 3], exposure: f32) -> f32 {
    ((0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) * exposure).max(1e-5).log2() - 0.18f32.log2()
}

impl LocalTone {
    /// None when there's nothing to do. `pixel(i)` gives linear RGB of pixel i (row-major, w x h); `exposure` is
    /// the gain applied afterwards, so "dark" and "bright" match what's seen.
    pub fn new(w: usize, h: usize, exposure: f32, shadows: f32, highlights: f32, pixel: &(dyn Fn(usize) -> [f32; 3] + Sync)) -> Option<LocalTone> {
        if shadows == 0.0 && highlights == 0.0 {
            return None;
        }
        let f = w.max(h).div_ceil(SMALL).max(1);
        let (sw, sh) = (w.div_ceil(f), h.div_ceil(f));
        // average brightness of each f x f block, in stops from mid-grey
        let mut i_map = vec![0f32; sw * sh];
        i_map.par_chunks_mut(sw).enumerate().for_each(|(by, row)| {
            for (bx, v) in row.iter_mut().enumerate() {
                let (mut sum, mut n) = (0.0, 0);
                for y in by * f..((by + 1) * f).min(h) {
                    for x in bx * f..((bx + 1) * f).min(w) {
                        sum += log_lum(pixel(y * w + x), exposure);
                        n += 1;
                    }
                }
                *v = sum / n.max(1) as f32;
            }
        });
        let r = (sw.max(sh) / 30).max(2);
        let mut mean_i = i_map.clone();
        box_blur(&mut mean_i, sw, sh, r);
        let mut mean_ii: Vec<f32> = i_map.iter().map(|v| v * v).collect();
        box_blur(&mut mean_ii, sw, sh, r);
        let mut a: Vec<f32> = mean_i.iter().zip(&mean_ii).map(|(m, mm)| {
            let var = (mm - m * m).max(0.0);
            var / (var + EPS)
        }).collect();
        let mut b: Vec<f32> = mean_i.iter().zip(&a).map(|(m, a)| m - a * m).collect();
        box_blur(&mut a, sw, sh, r);
        box_blur(&mut b, sw, sh, r);
        Some(LocalTone { f, sw, sh, a, b, exposure, shadows: shadows.clamp(-1.0, 1.0), highlights: highlights.clamp(-1.0, 1.0) })
    }

    /// The gain for pixel (x, y) with linear colour c.
    pub fn gain(&self, x: usize, y: usize, c: [f32; 3]) -> f32 {
        let (sx, sy) = ((x as f32 + 0.5) / self.f as f32 - 0.5, (y as f32 + 0.5) / self.f as f32 - 0.5);
        let i = log_lum(c, self.exposure);
        let base = sample(&self.a, self.sw, self.sh, sx, sy) * i + sample(&self.b, self.sw, self.sh, sx, sy);
        let dark = 1.0 - smooth(-5.0, -0.5, base);
        let bright = smooth(0.0, 2.5, base);
        (STOPS * (self.shadows * dark + self.highlights * bright)).exp2()
    }
}
