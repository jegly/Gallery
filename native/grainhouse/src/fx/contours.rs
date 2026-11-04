//! Contours: a glowing outline, contour lines that follow the body's shape inwards, and horizontal scan
//! lines, over the subject dimmed behind them. Lines come from a smoothed signed distance to the edge.

use super::{alpha_from_mask, glow, tint, Effect};
use crate::img::{pack_f, unpack};
use crate::util::{gaussian, lerp2, row_of, Cols};

/// Distance (pixels) from each pixel to the nearest pixel on the other side, 3-4 chamfer; 0 on the side itself.
pub fn chamfer(inside: &[bool], w: usize, h: usize, measure_inside: bool) -> Vec<f32> {
    let big = 1e6f32;
    let mut d: Vec<f32> = inside.iter().map(|&b| if b == measure_inside { big } else { 0.0 }).collect();
    const D: f32 = 1.4142;
    for y in 0..h {
        for x in 0..w {
            let i = y * w + x;
            if d[i] == 0.0 {
                continue;
            }
            let mut v = d[i];
            if x > 0 {
                v = v.min(d[i - 1] + 1.0);
            }
            if y > 0 {
                v = v.min(d[i - w] + 1.0);
                if x > 0 {
                    v = v.min(d[i - w - 1] + D);
                }
                if x < w - 1 {
                    v = v.min(d[i - w + 1] + D);
                }
            }
            d[i] = v;
        }
    }
    for y in (0..h).rev() {
        for x in (0..w).rev() {
            let i = y * w + x;
            if d[i] == 0.0 {
                continue;
            }
            let mut v = d[i];
            if x < w - 1 {
                v = v.min(d[i + 1] + 1.0);
            }
            if y < h - 1 {
                v = v.min(d[i + w] + 1.0);
                if x < w - 1 {
                    v = v.min(d[i + w + 1] + D);
                }
                if x > 0 {
                    v = v.min(d[i + w - 1] + D);
                }
            }
            d[i] = v;
        }
    }
    d
}

/// Smoothed signed distance from the figure's edge in mask pixels, positive inside.
pub fn signed_distance(mask: &[u8], mw: usize, mh: usize) -> (Vec<bool>, Vec<f32>) {
    let inside: Vec<bool> = mask.iter().map(|&m| m >= 128).collect();
    let din = chamfer(&inside, mw, mh, true);
    let dout = chamfer(&inside, mw, mh, false);
    let mut dist: Vec<f32> = (0..inside.len()).map(|i| if inside[i] { din[i] - 0.5 } else { 0.5 - dout[i] }).collect();
    gaussian(&mut dist, mw, mh, ((mw as f32 / 320.0).round() as usize).max(1));
    (inside, dist)
}

/// An anti-aliased line: 1 on the line, fading to 0 one pixel past its half width.
#[inline]
pub fn line(d: f32, half: f32) -> f32 {
    (half + 0.5 - d).clamp(0.0, 1.0)
}

#[inline]
pub fn soft(d: f32, r: f32) -> f32 {
    if d >= r {
        0.0
    } else {
        let t = 1.0 - d / r;
        t * t
    }
}

pub struct Contours {
    mw: usize,
    mh: usize,
    dist: Vec<f32>,
    alpha: Vec<f32>,
    spacing: f32,
    dim: f32,
    glow: f32,
    c: [f32; 3],
}

impl Contours {
    pub fn new(mask: &[u8], mw: usize, mh: usize, dim: f32, glow_level: usize, colour: usize) -> Contours {
        let (_, dist) = signed_distance(mask, mw, mh);
        Contours { mw, mh, dist, alpha: alpha_from_mask(mask), spacing: (mw as f32 / 60.0).max(4.0), dim, glow: glow(glow_level), c: tint(colour) }
    }
}

impl Effect for Contours {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let cols = Cols::new(w, self.mw);
        let scale = w as f32 / self.mw as f32;
        let half = (w as f32 / 1500.0).max(0.7);
        let outline_half = half * 1.6;
        let gap = self.spacing * scale;
        for (r, row) in rows.chunks_mut(w).enumerate() {
            let yy = y0 + r;
            let (iy, iy2, wy) = row_of(yy, h, self.mh);
            let (r0, r1) = (iy * self.mw, iy2 * self.mw);
            let sl = yy as f32 % gap;
            let scan = line(sl.min(gap - sl), half * 0.8) * 0.6;
            for x in 0..w {
                let (a0, a1, b0, b1) = (r0 + cols.x0[x], r0 + cols.x1[x], r1 + cols.x0[x], r1 + cols.x1[x]);
                let wx = cols.fx[x];
                let d = lerp2(&self.dist, a0, a1, b0, b1, wx, wy);
                let dpx = d * scale;
                if dpx < -outline_half * 8.0 {
                    continue;
                }
                let a = lerp2(&self.alpha, a0, a1, b0, b1, wx, wy);
                let outline = line(dpx.abs(), outline_half);
                let halo = self.glow * 0.45 * soft(dpx.abs(), outline_half * 7.0);
                let mut lines = 0.0;
                if d > 0.0 {
                    let t = d / self.spacing;
                    let k = (t - t.round()).abs() * self.spacing * scale;
                    lines = line(k, half).max(scan) * a * 0.85;
                }
                let light = (outline.max(lines) + halo * (1.0 - a * 0.7)).min(1.0);
                if light < 0.002 && a < 0.002 {
                    continue;
                }
                let (mut cr, mut cg, mut cb) = unpack(row[x]);
                let keep = 1.0 - self.dim * a;
                cr *= keep;
                cg *= keep;
                cb *= keep;
                cr = 255.0 - (255.0 - cr) * (1.0 - light * self.c[0]);
                cg = 255.0 - (255.0 - cg) * (1.0 - light * self.c[1]);
                cb = 255.0 - (255.0 - cb) * (1.0 - light * self.c[2]);
                row[x] = pack_f(cr, cg, cb);
            }
        }
    }
}
