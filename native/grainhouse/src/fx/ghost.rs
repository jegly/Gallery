//! Ghosts: subjects turned into glowing white figures. Solid (opaque white with a soft glow), Ethereal
//! (feathered, see-through by opacity, a tight glow and a wide halo spilling outward), or Dreamy (Ethereal in a
//! hazy scene: lifted blacks, a soft bloom, gentler colour).

use super::{alpha_from_mask, glow, tint, Effect};
use crate::img::{pack_f, unpack};
use crate::util::{gaussian, lerp2, row_of, screen, Cols};

pub const LOOKS: [&str; 3] = ["Solid", "Ethereal", "Dreamy"];
pub const SOLID: usize = 0;
pub const DREAMY: usize = 2;

const LIFT: f32 = 22.0;
const BLOOM: f32 = 0.5;
const SATURATION: f32 = 0.78;

pub struct Ghost {
    mw: usize,
    mh: usize,
    figure: Vec<f32>,
    opacity: f32,
    near: Vec<f32>,
    far: Option<Vec<f32>>,
    bloom: Option<[Vec<f32>; 3]>,
    near_amount: f32,
    far_amount: f32,
    fig: [f32; 3],
    far_tint: [f32; 3],
}

impl Ghost {
    /// `preview` is the photo at mw x mh (used by Dreamy's bloom).
    pub fn new(mask: &[u8], mw: usize, mh: usize, glow_level: usize, look: usize, opacity: f32, colour: usize, preview: &[u32]) -> Ghost {
        let g = glow(glow_level);
        let t = tint(colour);
        let far_tint = if colour == 0 { [0.93, 0.96, 1.0] } else { t };
        let alpha = alpha_from_mask(mask);
        let r = |d: f32, min: usize| ((mw as f32 / d).round() as usize).max(min);
        let (figure, near, far, near_amount, far_amount);
        if look == SOLID {
            near = blurred(&alpha, mw, mh, r(110.0, 1));
            figure = alpha;
            far = None;
            near_amount = 0.85 * g;
            far_amount = 0.0;
        } else {
            figure = blurred(&alpha, mw, mh, r(240.0, 1));
            near = blurred(&alpha, mw, mh, r(80.0, 1));
            far = Some(blurred(&alpha, mw, mh, r(26.0, 2)));
            near_amount = 0.75 * g;
            far_amount = 0.7 * g;
        }
        let bloom = if look == DREAMY {
            let n = mw * mh;
            let (mut br, mut bg, mut bb) = (vec![0f32; n], vec![0f32; n], vec![0f32; n]);
            for i in 0..n.min(preview.len()) {
                let (r, g, b) = unpack(preview[i]);
                let (r, g, b) = (r / 255.0, g / 255.0, b / 255.0);
                let lum = 0.299 * r + 0.587 * g + 0.114 * b;
                let k = ((lum - 0.35) / 0.65).max(0.0).powi(2);
                br[i] = r * k;
                bg[i] = g * k;
                bb[i] = b * k;
            }
            let rad = r(40.0, 2);
            for m in [&mut br, &mut bg, &mut bb] {
                gaussian(m, mw, mh, rad);
            }
            Some([br, bg, bb])
        } else {
            None
        };
        Ghost { mw, mh, figure, opacity: opacity.clamp(0.0, 1.0), near, far, bloom, near_amount, far_amount, fig: t, far_tint }
    }
}

fn blurred(m: &[f32], w: usize, h: usize, r: usize) -> Vec<f32> {
    let mut v = m.to_vec();
    gaussian(&mut v, w, h, r);
    v
}

impl Effect for Ghost {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let cols = Cols::new(w, self.mw);
        for (r, row) in rows.chunks_mut(w).enumerate() {
            let (iy, iy2, wy) = row_of(y0 + r, h, self.mh);
            let (r0, r1) = (iy * self.mw, iy2 * self.mw);
            for x in 0..w {
                let (a0, a1, b0, b1) = (r0 + cols.x0[x], r0 + cols.x1[x], r1 + cols.x0[x], r1 + cols.x1[x]);
                let wx = cols.fx[x];
                let shape = lerp2(&self.figure, a0, a1, b0, b1, wx, wy);
                let s = shape * self.opacity;
                // the glow spills outward from the figures; inside them, opacity decides how white they are
                let out = 1.0 - shape;
                let g = if self.near_amount == 0.0 { 0.0 } else { lerp2(&self.near, a0, a1, b0, b1, wx, wy) * self.near_amount * out };
                let f = match &self.far {
                    Some(m) if self.far_amount > 0.0 => lerp2(m, a0, a1, b0, b1, wx, wy) * self.far_amount * out,
                    _ => 0.0,
                };
                if self.bloom.is_none() && g < 0.002 && s < 0.002 && f < 0.002 {
                    continue;
                }
                let (mut cr, mut cg, mut cb) = unpack(row[x]);
                if let Some([br, bg, bb]) = &self.bloom {
                    // softer colour, lifted blacks, then the scene's own light blooming over it
                    let lum = 0.299 * cr + 0.587 * cg + 0.114 * cb;
                    cr = lum + (cr - lum) * SATURATION;
                    cg = lum + (cg - lum) * SATURATION;
                    cb = lum + (cb - lum) * SATURATION;
                    let lift = |v: f32| LIFT + v * (1.0 - LIFT / 255.0);
                    cr = screen(lift(cr), lerp2(br, a0, a1, b0, b1, wx, wy) * BLOOM);
                    cg = screen(lift(cg), lerp2(bg, a0, a1, b0, b1, wx, wy) * BLOOM);
                    cb = screen(lift(cb), lerp2(bb, a0, a1, b0, b1, wx, wy) * BLOOM);
                }
                if f > 0.0 {
                    cr = screen(cr, f * self.far_tint[0]);
                    cg = screen(cg, f * self.far_tint[1]);
                    cb = screen(cb, f * self.far_tint[2]);
                }
                if g > 0.0 {
                    cr = screen(cr, g * self.fig[0]);
                    cg = screen(cg, g * self.fig[1]);
                    cb = screen(cb, g * self.fig[2]);
                }
                cr = screen(cr, s * self.fig[0]);
                cg = screen(cg, s * self.fig[1]);
                cb = screen(cb, s * self.fig[2]);
                row[x] = pack_f(cr, cg, cb);
            }
        }
    }
}
