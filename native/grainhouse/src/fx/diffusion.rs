//! A diffusion filter like a Black Pro-Mist or Glimmerglass. Works on light: each pixel gives away the share it
//! scatters (black mist scatters little from dark areas), and the glow comes back from a blurred copy of the
//! photo (a tight and a wide one). Bright clipped spots get extra light, as they were brighter than the file.

use super::Effect;
use crate::util::{gaussian, lerp2, row_of, smooth, Cols};
use std::sync::OnceLock;

pub const NAMES: [&str; 8] = ["Off", "Black Mist 1/8", "Black Mist 1/4", "Black Mist 1/2", "Black Mist 1", "White Mist 1/4", "White Mist 1/2", "Glimmer 1/4"];

// per setting: tight glow share, its radius (fraction of width), wide glow share, its radius, black mist?
const SETTINGS: [[f32; 5]; 8] = [
    [0.0, 0.0, 0.0, 0.0, 0.0],
    [0.14, 0.006, 0.07, 0.04, 1.0],
    [0.22, 0.007, 0.11, 0.045, 1.0],
    [0.32, 0.008, 0.16, 0.05, 1.0],
    [0.44, 0.009, 0.22, 0.055, 1.0],
    [0.14, 0.008, 0.24, 0.06, 0.0],
    [0.20, 0.009, 0.36, 0.07, 0.0],
    [0.28, 0.003, 0.05, 0.03, 1.0],
];

fn to_linear() -> &'static [f32; 256] {
    static T: OnceLock<[f32; 256]> = OnceLock::new();
    T.get_or_init(|| {
        let mut t = [0f32; 256];
        for (i, v) in t.iter_mut().enumerate() {
            let x = i as f64 / 255.0;
            *v = (if x <= 0.04045 { x / 12.92 } else { ((x + 0.055) / 1.055).powf(2.4) }) as f32;
        }
        t
    })
}

fn to_srgb() -> &'static Vec<u8> {
    static T: OnceLock<Vec<u8>> = OnceLock::new();
    T.get_or_init(|| {
        (0..=4096)
            .map(|i| {
                let v = i as f64 / 4096.0;
                let s = if v <= 0.0031308 { v * 12.92 } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 };
                (s * 255.0).round().clamp(0.0, 255.0) as u8
            })
            .collect()
    })
}

pub fn srgb8(v: f32) -> u32 {
    if v <= 0.0 {
        0
    } else if v >= 1.0 {
        255
    } else {
        to_srgb()[(v * 4096.0 + 0.5) as usize] as u32
    }
}

pub fn linear(v: u32) -> f32 {
    to_linear()[(v & 255) as usize]
}

fn weight(black_mist: bool, linear_max: f32) -> f32 {
    if black_mist {
        0.25 + 0.75 * smooth(0.05, 0.6, linear_max)
    } else {
        1.0
    }
}

/// Fine control on top of the chosen filter. All 1.0 (warmth 0) is the filter as designed.
#[derive(Clone, Copy, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct DiffusionOptions {
    /// how much light is scattered, 0..2
    pub strength: f32,
    /// how far the glow spreads, 0.25..3
    pub size: f32,
    /// extra glow from clipped highlights (lamps, sun on water), 0..3
    pub bloom: f32,
    /// glow colour, -1 cool .. 1 warm
    pub warmth: f32,
}

impl Default for DiffusionOptions {
    fn default() -> Self {
        DiffusionOptions { strength: 1.0, size: 1.0, bloom: 1.0, warmth: 0.0 }
    }
}

pub struct Diffusion {
    mw: usize,
    mh: usize,
    keep_for: [f32; 256],
    glow: [Vec<f32>; 3],
}

impl Diffusion {
    /// `preview` is the photo at mw x mh, packed RGB.
    pub fn new(preview: &[u32], mw: usize, mh: usize, setting: usize, opt: &DiffusionOptions) -> Diffusion {
        let p = SETTINGS[setting.min(SETTINGS.len() - 1)];
        let black = p[4] > 0.0;
        // past about 60% scattered the picture falls apart, so stronger settings stop there
        let base = (p[0] + p[2]).max(1e-6);
        let k = opt.strength.max(0.0).min(base.max(0.6) / base);
        let (near, far) = (p[0] * k, p[2] * k);
        let share = near + far;
        let lin = to_linear();
        let mut keep_for = [0f32; 256];
        for v in 0..256 {
            keep_for[v] = 1.0 - share * weight(black, lin[v]);
        }
        let n = mw * mh;
        let mut ch = [vec![0f32; n], vec![0f32; n], vec![0f32; n]];
        for i in 0..n {
            let c = preview[i];
            let l = [lin[((c >> 16) & 255) as usize], lin[((c >> 8) & 255) as usize], lin[(c & 255) as usize]];
            let mx = l[0].max(l[1]).max(l[2]);
            // clipped highlights were brighter than the file can say: give them more light to scatter
            let k = (1.0 + 3.0 * opt.bloom.max(0.0) * smooth(0.75, 1.0, mx)) * weight(black, mx);
            for c in 0..3 {
                ch[c][i] = l[c] * k;
            }
        }
        let size = opt.size.clamp(0.1, 4.0);
        let rn = ((p[1] * size * mw as f32).round() as usize).max(1);
        let rf = ((p[3] * size * mw as f32).round() as usize).max(1);
        // warm glow: more red, less blue (and the reverse), keeping its brightness about the same
        let w = opt.warmth.clamp(-1.0, 1.0) * 0.35;
        let tint = [1.0 + w, 1.0 - 0.1 * w.abs(), 1.0 - w];
        let mut c = 0;
        let glow = ch.map(|src| {
            let mut a = src.clone();
            let mut b = src;
            gaussian(&mut a, mw, mh, rn);
            gaussian(&mut b, mw, mh, rf);
            let t = tint[c];
            c += 1;
            a.iter().zip(&b).map(|(x, y)| (x * near + y * far) * t).collect()
        });
        Diffusion { mw, mh, keep_for, glow }
    }
}

impl Effect for Diffusion {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let cols = Cols::new(w, self.mw);
        for (r, row) in rows.chunks_mut(w).enumerate() {
            let (iy, iy2, wy) = row_of(y0 + r, h, self.mh);
            let (r0, r1) = (iy * self.mw, iy2 * self.mw);
            for x in 0..w {
                let (a0, a1, b0, b1) = (r0 + cols.x0[x], r0 + cols.x1[x], r1 + cols.x0[x], r1 + cols.x1[x]);
                let wx = cols.fx[x];
                let c = row[x];
                let (cr, cg, cb) = ((c >> 16) & 255, (c >> 8) & 255, c & 255);
                let keep = self.keep_for[cr.max(cg).max(cb) as usize];
                let lr = linear(cr) * keep + lerp2(&self.glow[0], a0, a1, b0, b1, wx, wy);
                let lg = linear(cg) * keep + lerp2(&self.glow[1], a0, a1, b0, b1, wx, wy);
                let lb = linear(cb) * keep + lerp2(&self.glow[2], a0, a1, b0, b1, wx, wy);
                row[x] = (srgb8(lr) << 16) | (srgb8(lg) << 8) | srgb8(lb);
            }
        }
    }
}
