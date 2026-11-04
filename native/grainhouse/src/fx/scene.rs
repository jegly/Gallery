//! Effects that treat the subjects and the rest of the scene differently:
//! Noir pop, Bokeh, Rim light, Glitch, Thermal and Comic.

use super::diffusion::{linear, srgb8};
use super::{alpha_from_mask, tint, Effect};
use crate::img::{pack_f, unpack};
use crate::util::{gaussian, hash01, lerp2, row_of, sample, smooth, Cols};

pub const NAMES: [&str; 6] = ["Noir pop", "Bokeh", "Rim light", "Glitch", "Thermal", "Comic"];
pub const NOIR: usize = 0;
pub const BOKEH: usize = 1;
pub const RIM: usize = 2;
pub const GLITCH: usize = 3;
pub const THERMAL: usize = 4;
pub const COMIC: usize = 5;

pub struct Scene {
    kind: usize,
    mw: usize,
    mh: usize,
    amount: f32,
    alpha: Vec<f32>,
    aux: Vec<f32>,
    aux2: Vec<f32>,
    c: [f32; 3],
    seed: i32,
    /// Bokeh: near and far blurs of the background at bw x bh, packed RGB
    blur: Option<(Vec<u32>, Vec<u32>, usize, usize)>,
}

impl Scene {
    /// `big` (bw x bh) is a copy of the photo for Bokeh's blur, larger than the mask for a cleaner result.
    pub fn new(kind: usize, mask: &[u8], mw: usize, mh: usize, big: &[u32], bw: usize, bh: usize, amount: f32, colour: usize, seed: i32) -> Scene {
        let n = mw * mh;
        let mut alpha = alpha_from_mask(mask);
        gaussian(&mut alpha, mw, mh, ((mw as f32 / 400.0).round() as usize).max(1));
        let r = |d: f32, min: usize| ((mw as f32 / d).round() as usize).max(min);
        let (mut aux, mut aux2) = (Vec::new(), Vec::new());
        match kind {
            RIM => {
                // the rim: the figure minus a shrunk copy of it; and a wide glow from the figure
                let mut inner = alpha.clone();
                gaussian(&mut inner, mw, mh, r(320.0, 1));
                aux = (0..n).map(|i| alpha[i] * (1.0 - smooth(0.8, 0.99, inner[i]))).collect();
                aux2 = alpha.clone();
                gaussian(&mut aux2, mw, mh, r(22.0, 2));
                aux2.iter_mut().for_each(|v| *v = (*v * 1.8).min(1.0));
            }
            THERMAL => {
                aux = alpha.clone();
                gaussian(&mut aux, mw, mh, r(70.0, 2));
                aux.iter_mut().for_each(|v| *v = (*v * *v * 1.6).min(1.0));
            }
            BOKEH => {
                // further from the subjects means further out of focus
                aux = alpha.clone();
                gaussian(&mut aux, mw, mh, r(12.0, 2));
                aux.iter_mut().for_each(|v| *v = 1.0 - (*v * 2.2).min(1.0));
            }
            _ => {}
        }
        let mut s = Scene { kind, mw, mh, amount, alpha, aux, aux2, c: tint(colour), seed, blur: None };
        if kind == BOKEH {
            s.blur = Some(s.bokeh_levels(big, bw, bh));
        }
        s
    }

    /// Two blurs of the background alone (subjects masked out and filled in), highlights boosted into discs.
    fn bokeh_levels(&self, big: &[u32], w: usize, h: usize) -> (Vec<u32>, Vec<u32>, usize, usize) {
        let n = w * h;
        let mut wgt = vec![0f32; n];
        for y in 0..h {
            let sy = (y as f32 + 0.5) * self.mh as f32 / h as f32 - 0.5;
            for x in 0..w {
                let sx = (x as f32 + 0.5) * self.mw as f32 / w as f32 - 0.5;
                wgt[y * w + x] = 1.0 - (sample(&self.alpha, self.mw, self.mh, sx, sy) * 1.6).min(1.0);
            }
        }
        let rn = ((w as f32 * (0.003 + 0.007 * self.amount)).round() as usize).max(1);
        let rf = ((w as f32 * (0.008 + 0.02 * self.amount)).round() as usize).max(2);
        let (mut wn, mut wf) = (wgt.clone(), wgt.clone());
        gaussian(&mut wn, w, h, rn);
        gaussian(&mut wf, w, h, rf);
        let (mut near, mut far) = (vec![0u32; n], vec![0u32; n]);
        for c in 0..3 {
            let shift = 16 - 8 * c;
            let mut ch: Vec<f32> = (0..n)
                .map(|i| {
                    let p = big[i];
                    let mx = linear(p >> 16).max(linear(p >> 8)).max(linear(p));
                    // bright spots get much more light, so they open up into discs like a real lens
                    linear(p >> shift) * (1.0 + 10.0 * smooth(0.55, 1.0, mx)) * wgt[i]
                })
                .collect();
            let mut c2 = ch.clone();
            gaussian(&mut ch, w, h, rn);
            gaussian(&mut c2, w, h, rf);
            for i in 0..n {
                let vn = if wn[i] > 1e-4 { ch[i] / wn[i] } else { 0.0 };
                let vf = if wf[i] > 1e-4 { c2[i] / wf[i] } else { 0.0 };
                near[i] |= srgb8(vn / (1.0 + vn * 0.2)) << shift;
                far[i] |= srgb8(vf / (1.0 + vf * 0.2)) << shift;
            }
        }
        (near, far, w, h)
    }
}

/// "Iron" heat palette for t in 0..1.
fn iron(t: f32) -> (f32, f32, f32) {
    const S: [[f32; 4]; 7] = [
        [0.0, 0.0, 0.0, 0.0],
        [0.2, 40.0, 0.0, 90.0],
        [0.42, 150.0, 0.0, 140.0],
        [0.6, 225.0, 40.0, 40.0],
        [0.78, 255.0, 140.0, 0.0],
        [0.92, 255.0, 230.0, 60.0],
        [1.0, 255.0, 255.0, 235.0],
    ];
    if t <= 0.0 {
        return (0.0, 0.0, 0.0);
    }
    for i in 1..S.len() {
        if t <= S[i][0] {
            let (p, q) = (S[i - 1], S[i]);
            let k = (t - p[0]) / (q[0] - p[0]);
            return (p[1] + (q[1] - p[1]) * k, p[2] + (q[2] - p[2]) * k, p[3] + (q[3] - p[3]) * k);
        }
    }
    (255.0, 255.0, 235.0)
}

fn mix_rgb(m: &[u32], q: [usize; 4], wx: f32, wy: f32, shift: u32) -> f32 {
    let v = |i: usize| ((m[i] >> shift) & 255) as f32;
    let top = v(q[0]) + (v(q[1]) - v(q[0])) * wx;
    let bot = v(q[2]) + (v(q[3]) - v(q[2])) * wx;
    top + (bot - top) * wy
}

impl Effect for Scene {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let cols = Cols::new(w, self.mw);
        let bcols = self.blur.as_ref().map(|b| Cols::new(w, b.2));
        let src: Vec<u32> = if self.kind == GLITCH { rows.to_vec() } else { Vec::new() };
        let cell = (w as f32 / 85.0).max(5.0);
        let split = ((w as f32 * 0.009 * (0.4 + self.amount)).round() as isize).max(2);
        for (r, row) in rows.chunks_mut(w).enumerate() {
            let yy = y0 + r;
            let (iy, iy2, wy) = row_of(yy, h, self.mh);
            let (r0, r1) = (iy * self.mw, iy2 * self.mw);
            let brow = self.blur.as_ref().map(|b| row_of(yy, h, b.3));
            let (mut shift, mut scan, mut swap) = (0isize, false, false);
            if self.kind == GLITCH {
                // bands of uneven height, about half knocked sideways, a few with colours swapped
                let jitter = (hash01((yy / (h / 40).max(2)) as i32, self.seed + 3) * (h / 60) as f32) as usize;
                let band = (yy + jitter) / (h / 70).max(2);
                let hb = hash01(band as i32, self.seed);
                if hb > 0.5 {
                    shift = ((hash01(band as i32, self.seed + 7) - 0.5) * w as f32 * 0.12 * (0.3 + self.amount)).round() as isize;
                }
                swap = hb > 0.92;
                scan = yy % 3 == 0;
            }
            for x in 0..w {
                let (a0, a1, b0, b1) = (r0 + cols.x0[x], r0 + cols.x1[x], r1 + cols.x0[x], r1 + cols.x1[x]);
                let wx = cols.fx[x];
                let a = lerp2(&self.alpha, a0, a1, b0, b1, wx, wy);
                let (mut cr, mut cg, mut cb) = unpack(row[x]);
                let lum = 0.299 * cr + 0.587 * cg + 0.114 * cb;
                match self.kind {
                    NOIR => {
                        // the background as black and white film (S-curve), the subjects a touch more colourful
                        let v = lum / 255.0;
                        let gray = (v * v * (3.0 - 2.0 * v) * 0.85 + v * 0.15) * 245.0;
                        let sat = 1.25;
                        let (pr, pg, pb) = (lum + (cr - lum) * sat, lum + (cg - lum) * sat, lum + (cb - lum) * sat);
                        let k = (1.0 - a) * self.amount;
                        cr = pr + (gray - pr) * k;
                        cg = pg + (gray - pg) * k;
                        cb = pb + (gray - pb) * k;
                    }
                    BOKEH => {
                        let (Some((near, far, bw, _)), Some(bc), Some((by0, by1, bwy))) = (&self.blur, &bcols, brow) else { continue };
                        if a > 0.999 {
                            continue;
                        }
                        let f = lerp2(&self.aux, a0, a1, b0, b1, wx, wy);
                        let q = [by0 * bw + bc.x0[x], by0 * bw + bc.x1[x], by1 * bw + bc.x0[x], by1 * bw + bc.x1[x]];
                        let u = bc.fx[x];
                        let mut out = [0f32; 3];
                        for (k, sh) in [16u32, 8, 0].iter().enumerate() {
                            let n = mix_rgb(near, q, u, bwy, *sh);
                            out[k] = n + (mix_rgb(far, q, u, bwy, *sh) - n) * f;
                        }
                        cr = cr * a + out[0] * (1.0 - a);
                        cg = cg * a + out[1] * (1.0 - a);
                        cb = cb * a + out[2] * (1.0 - a);
                    }
                    RIM => {
                        let rim = lerp2(&self.aux, a0, a1, b0, b1, wx, wy);
                        let halo = lerp2(&self.aux2, a0, a1, b0, b1, wx, wy);
                        // the scene falls into darkness except the light spilling from behind the figures
                        let dark = 1.0 - self.amount * 0.9 * (1.0 - halo * 0.45);
                        let k = dark + (0.32 - dark) * a;
                        let light = (rim * 1.15 + halo * 0.5 * (1.0 - a)).min(1.0);
                        cr = 255.0 - (255.0 - cr * k) * (1.0 - light * self.c[0]);
                        cg = 255.0 - (255.0 - cg * k) * (1.0 - light * self.c[1]);
                        cb = 255.0 - (255.0 - cb * k) * (1.0 - light * self.c[2]);
                    }
                    GLITCH => {
                        if a < 0.01 {
                            continue;
                        }
                        let at = |dx: isize| src[r * w + (x as isize + shift + dx).clamp(0, w as isize - 1) as usize];
                        let (mut nr, ng, mut nb) = (((at(split) >> 16) & 255) as f32, ((at(0) >> 8) & 255) as f32, (at(-split) & 255) as f32);
                        if swap {
                            let t = nr;
                            nr = nb;
                            nb = 255.0 - t;
                        }
                        let k = if scan { 0.82 } else { 1.0 };
                        cr += (nr * k - cr) * a;
                        cg += (ng * k - cg) * a;
                        cb += (nb * k - cb) * a;
                    }
                    THERMAL => {
                        let v = lum / 255.0;
                        let core = lerp2(&self.aux, a0, a1, b0, b1, wx, wy);
                        let t = (0.08 + 0.32 * v) * (1.0 - a) + (0.46 + 0.26 * core + 0.28 * v) * a;
                        let (hr, hg, hb) = iron(t);
                        cr += (hr - cr) * self.amount;
                        cg += (hg - cg) * self.amount;
                        cb += (hb - cb) * self.amount;
                    }
                    COMIC => {
                        if a < 0.01 {
                            continue;
                        }
                        // flat punchy colour in three tones, Ben-Day dots in the middle tone, an ink outline
                        let v = lum / 255.0;
                        let sat = 1.35;
                        let (mut pr, mut pg, mut pb) = (lum + (cr - lum) * sat, lum + (cg - lum) * sat, lum + (cb - lum) * sat);
                        let mx = pr.max(pg).max(pb).max(1.0);
                        let tone = if v < 0.22 { 0.35 } else if v < 0.55 { 0.75 } else { 1.0 };
                        let s = 235.0 * tone / mx;
                        pr *= s;
                        pg *= s;
                        pb *= s;
                        if (0.22..0.55).contains(&v) {
                            let d45 = cell * 1.414;
                            let (u, t) = ((x + yy) as f32 / d45, (x as f32 - yy as f32) / d45);
                            let (du, dt) = (u - u.floor() - 0.5, t - t.floor() - 0.5);
                            let d = (du * du + dt * dt).sqrt() * d45;
                            let dot = (cell * 0.28 + 0.5 - d).clamp(0.0, 1.0);
                            let k = 1.0 - 0.55 * dot;
                            pr *= k;
                            pg *= k;
                            pb *= k;
                        }
                        let edge = 1.0 - (a - 0.5).abs() * 2.0;
                        let ink = 1.0 - ((edge - 0.35) * 2.5).clamp(0.0, 1.0);
                        let k = (a * 1.6).min(1.0) * self.amount;
                        cr += (pr * ink - cr) * k;
                        cg += (pg * ink - cg) * k;
                        cb += (pb * ink - cb) * k;
                    }
                    _ => {}
                }
                row[x] = pack_f(cr, cg, cb);
            }
        }
    }
}
