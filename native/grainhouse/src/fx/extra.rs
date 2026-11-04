//! Effects made for the desktop app (the camera's are in the other modules). Some use the AI subject mask
//! (subject light, zoom burst, motion pan, neon edges, pixel sort, 3D, double exposure); the rest work on the
//! picture alone. Sizes are fractions of the picture, so a preview looks like the export.

use super::tint;
use crate::img::Image;
use crate::util::{box_blur, gaussian, hash01, sample, smooth};
use rayon::prelude::*;

pub const NAMES: [&str; 16] = [
    "Subject light",
    "Zoom burst",
    "Motion pan",
    "Neon edges",
    "Pixel sort",
    "3D anaglyph",
    "Infrared",
    "Anamorphic flare",
    "Light rays",
    "Tilt-shift",
    "Duotone",
    "Oil paint",
    "Sketch",
    "Mirror",
    "VHS",
    "Double exposure",
];
pub const SUBJECT_LIGHT: usize = 0;
pub const ZOOM: usize = 1;
pub const PAN: usize = 2;
pub const NEON: usize = 3;
pub const PIXEL_SORT: usize = 4;
pub const ANAGLYPH: usize = 5;
pub const INFRARED: usize = 6;
pub const ANAMORPHIC: usize = 7;
pub const RAYS: usize = 8;
pub const TILT: usize = 9;
pub const DUOTONE: usize = 10;
pub const OIL: usize = 11;
pub const SKETCH: usize = 12;
pub const MIRROR: usize = 13;
pub const VHS: usize = 14;
pub const DOUBLE: usize = 15;

/// The choices for each effect's Look row.
pub fn looks(i: usize) -> &'static [&'static str] {
    match i {
        SUBJECT_LIGHT => &["Both", "Brighter subject", "Darker background"],
        ZOOM => &["Zoom", "Spin"],
        PAN => &["Sideways", "Up and down", "Diagonal"],
        NEON => &["Subject", "Everything"],
        PIXEL_SORT => &["Background", "Subject", "Everything"],
        INFRARED => &["Aerochrome", "Black and white", "Golden"],
        ANAMORPHIC => &["Blue", "Warm", "Violet"],
        RAYS => &["From the brightest light", "From above"],
        TILT => &["Middle", "Lower third", "Upper third"],
        DUOTONE => &["Blue and pink", "Teal and orange", "Purple and yellow", "Green and red", "Navy and cream"],
        OIL => &["Oil", "Watercolour"],
        SKETCH => &["Graphite", "Colour pencil", "Charcoal"],
        MIRROR => &["Left onto right", "Right onto left", "Top onto bottom", "Kaleidoscope 4", "Kaleidoscope 6"],
        VHS => &["Clean", "Worn", "Chewed"],
        DOUBLE => &["Light background", "Dark background"],
        _ => &[],
    }
}

/// Effects that need the AI's subject mask.
pub fn uses_subjects(i: usize) -> bool {
    matches!(i, SUBJECT_LIGHT | ZOOM | PAN | NEON | PIXEL_SORT | ANAGLYPH | DOUBLE)
}

/// The subject's soft shape, readable at any picture position.
pub struct Mask {
    w: usize,
    h: usize,
    a: Vec<f32>,
    /// centre of the subject in fractions, if there is one
    pub centre: Option<(f32, f32)>,
}

impl Mask {
    pub fn new(mask: &[u8], w: usize, h: usize) -> Mask {
        let mut a = super::alpha_from_mask(mask);
        gaussian(&mut a, w, h, 1);
        let (mut sx, mut sy, mut n) = (0.0, 0.0, 0.0);
        for (i, v) in a.iter().enumerate() {
            sx += (i % w) as f32 * v;
            sy += (i / w) as f32 * v;
            n += v;
        }
        let centre = (n > 1.0).then(|| ((sx / n + 0.5) / w as f32, (sy / n + 0.5) / h as f32));
        Mask { w, h, a, centre }
    }

    /// Mask value at fractions (fx, fy) of the picture.
    pub fn at(&self, fx: f32, fy: f32) -> f32 {
        sample(&self.a, self.w, self.h, fx * self.w as f32 - 0.5, fy * self.h as f32 - 0.5)
    }
}

fn unpack(p: u32) -> [f32; 3] {
    [((p >> 16) & 255) as f32 / 255.0, ((p >> 8) & 255) as f32 / 255.0, (p & 255) as f32 / 255.0]
}

fn pack(c: [f32; 3]) -> u32 {
    let q = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
    (q(c[0]) << 16) | (q(c[1]) << 8) | q(c[2])
}

fn luma(c: [f32; 3]) -> f32 {
    0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
}

fn mix3(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [0, 1, 2].map(|i| a[i] + (b[i] - a[i]) * t)
}

fn screen(a: [f32; 3], b: [f32; 3]) -> [f32; 3] {
    [0, 1, 2].map(|i| 1.0 - (1.0 - a[i]) * (1.0 - b[i].clamp(0.0, 1.0)))
}

/// Bilinear colour at pixel coordinates.
fn at(img: &Image, x: f32, y: f32) -> [f32; 3] {
    let x = x.clamp(0.0, (img.w - 1) as f32);
    let y = y.clamp(0.0, (img.h - 1) as f32);
    let (x0, y0) = (x as usize, y as usize);
    let (x1, y1) = ((x0 + 1).min(img.w - 1), (y0 + 1).min(img.h - 1));
    let (fx, fy) = (x - x0 as f32, y - y0 as f32);
    let (a, b, c, d) = (unpack(img.px[y0 * img.w + x0]), unpack(img.px[y0 * img.w + x1]), unpack(img.px[y1 * img.w + x0]), unpack(img.px[y1 * img.w + x1]));
    mix3(mix3(a, b, fx), mix3(c, d, fx), fy)
}

/// A float map read at a full-size pixel position (the map covers the whole picture).
fn map_at(m: &[f32], mw: usize, mh: usize, x: usize, y: usize, w: usize, h: usize) -> f32 {
    sample(m, mw, mh, (x as f32 + 0.5) * mw as f32 / w as f32 - 0.5, (y as f32 + 0.5) * mh as f32 / h as f32 - 0.5)
}

fn planes(img: &Image) -> [Vec<f32>; 3] {
    [16, 8, 0].map(|s| img.px.iter().map(|p| ((p >> s) & 255) as f32 / 255.0).collect())
}

/// Horizontal-only blur (three box passes).
fn blur_x(m: &mut [f32], w: usize, r: usize) {
    if r == 0 {
        return;
    }
    m.par_chunks_mut(w).for_each(|row| {
        let mut tmp = vec![0f32; w];
        for _ in 0..3 {
            let norm = 1.0 / (2 * r + 1) as f32;
            let ci = |i: isize| row[i.clamp(0, w as isize - 1) as usize];
            let mut sum: f32 = (-(r as isize)..=r as isize).map(ci).sum();
            for x in 0..w {
                tmp[x] = sum * norm;
                let xi = x as isize;
                sum += ci(xi + r as isize + 1) - ci(xi - r as isize);
            }
            row.copy_from_slice(&tmp);
        }
    });
}

/// Runs a desktop effect. `mask` is the subject (None when not found or not used); `amount` 0..1.
pub fn apply(i: usize, img: &mut Image, mask: Option<&Mask>, look: usize, amount: f32, colour: usize, seed: u64, date: Option<(i32, u32, u32)>) {
    let a = amount.clamp(0.0, 1.0);
    match i {
        SUBJECT_LIGHT => {
            if let Some(m) = mask {
                subject_light(img, m, look, a)
            }
        }
        ZOOM | PAN => motion(img, mask, i, look, a),
        NEON => neon(img, mask, look, a, colour),
        PIXEL_SORT => pixel_sort(img, mask, look, a),
        ANAGLYPH => anaglyph(img, mask, a),
        INFRARED => infrared(img, look, a),
        ANAMORPHIC => anamorphic(img, look, a),
        RAYS => rays(img, look, a),
        TILT => tilt(img, look, a),
        DUOTONE => duotone(img, look, a, seed),
        OIL => oil(img, look, a, seed),
        SKETCH => sketch(img, look, a, seed),
        MIRROR => mirror(img, look),
        VHS => vhs(img, look, a, seed, date),
        _ => {}
    }
}

fn to_lin(v: f32) -> f32 {
    if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
}

fn to_srgb(v: f32) -> f32 {
    let v = v.max(0.0);
    if v <= 0.0031308 { v * 12.92 } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 }
}

fn subject_light(img: &mut Image, m: &Mask, look: usize, a: f32) {
    let up = if look != 2 { a * 0.7 } else { 0.0 };
    let down = if look != 1 { a * 1.2 } else { 0.0 };
    let desat = if look != 1 { a * 0.4 } else { 0.0 };
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let t = m.at((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32);
            let gain = (-down + (up + down) * t).exp2();
            let c = unpack(*p).map(|v| to_srgb(to_lin(v) * gain));
            let l = luma(c);
            let k = 1.0 - desat * (1.0 - t);
            *p = pack(c.map(|v| l + (v - l) * k));
        }
    });
}

/// Zoom burst / spin, or a panning blur, behind a sharp subject.
fn motion(img: &mut Image, mask: Option<&Mask>, kind: usize, look: usize, a: f32) {
    let work = img.fit(1000);
    let (ww, wh) = (work.w, work.h);
    let centre = mask.and_then(|m| m.centre).unwrap_or((0.5, 0.5));
    let (cx, cy) = (centre.0 * ww as f32, centre.1 * wh as f32);
    const N: usize = 40;
    let dir = match look {
        1 => (0.0, 1.0),
        2 => (0.7071, 0.7071),
        _ => (1.0, 0.0),
    };
    let len = a * 0.09 * ww.max(wh) as f32;
    let mut blurred = Image::new(ww, wh);
    blurred.px.par_chunks_mut(ww).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (px, py) = (x as f32, y as f32);
            let mut s = [0f32; 3];
            for k in 0..N {
                let t = k as f32 / (N - 1) as f32;
                let (sx, sy) = if kind == PAN {
                    let d = (t - 0.5) * len;
                    (px + dir.0 * d, py + dir.1 * d)
                } else if look == 1 {
                    let ang = (t - 0.5) * a * 0.3;
                    let (sn, cs) = ang.sin_cos();
                    let (dx, dy) = (px - cx, py - cy);
                    (cx + dx * cs - dy * sn, cy + dx * sn + dy * cs)
                } else {
                    let z = t * a * 0.3;
                    (px + (cx - px) * z, py + (cy - py) * z)
                };
                let c = at(&work, sx, sy);
                for i in 0..3 {
                    s[i] += to_lin(c[i]);
                }
            }
            *p = pack(s.map(|v| to_srgb(v / N as f32)));
        }
    });
    let (w, h) = (img.w, img.h);
    let src = img.clone();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (fx, fy) = ((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32);
            // keep the subject sharp; with no subject, the middle of a zoom
            let keep = match mask {
                Some(m) if m.centre.is_some() => m.at(fx, fy),
                _ if kind == ZOOM => 1.0 - smooth(0.04, 0.3, ((fx - 0.5).powi(2) + (fy - 0.5).powi(2)).sqrt()),
                _ => 0.0,
            };
            let b = at(&blurred, fx * ww as f32 - 0.5, fy * wh as f32 - 0.5);
            *p = pack(mix3(b, unpack(src.px[y * w + x]), keep));
        }
    });
}

fn neon(img: &mut Image, mask: Option<&Mask>, look: usize, a: f32, colour: usize) {
    let work = img.fit(1600);
    let (ww, wh) = (work.w, work.h);
    let l: Vec<f32> = work.px.iter().map(|&p| luma(unpack(p))).collect();
    let mut e = vec![0f32; ww * wh];
    for y in 1..wh - 1 {
        for x in 1..ww - 1 {
            let g = |dx: isize, dy: isize| l[(y as isize + dy) as usize * ww + (x as isize + dx) as usize];
            let gx = g(1, -1) + 2.0 * g(1, 0) + g(1, 1) - g(-1, -1) - 2.0 * g(-1, 0) - g(-1, 1);
            let gy = g(-1, 1) + 2.0 * g(0, 1) + g(1, 1) - g(-1, -1) - 2.0 * g(0, -1) - g(1, -1);
            let mut v = smooth(0.25, 0.7, (gx * gx + gy * gy).sqrt());
            if look == 0 {
                if let Some(m) = mask {
                    v *= smooth(0.05, 0.4, m.at((x as f32 + 0.5) / ww as f32, (y as f32 + 0.5) / wh as f32));
                }
            }
            e[y * ww + x] = v;
        }
    }
    let mut g1 = e.clone();
    gaussian(&mut g1, ww, wh, 2);
    let mut g2 = e.clone();
    gaussian(&mut g2, ww, wh, ((0.006 * ww.max(wh) as f32) as usize).max(2));
    let base = tint(colour);
    // saturate the light colour so it reads as neon (white stays white)
    let lc = luma(base);
    let neon = base.map(|v| (lc + (v - lc) * 1.8).clamp(0.0, 1.0));
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let k = map_at(&e, ww, wh, x, y, w, h) * 0.9 + 0.5 * map_at(&g1, ww, wh, x, y, w, h) + 0.8 * map_at(&g2, ww, wh, x, y, w, h);
            let c = unpack(*p).map(|v| v * (1.0 - a * 0.8));
            *p = pack(screen(c, neon.map(|v| v * k.min(1.0))));
        }
    });
}

fn pixel_sort(img: &mut Image, mask: Option<&Mask>, look: usize, a: f32) {
    let (w, h) = (img.w, img.h);
    let lo = 0.55 - a * 0.45;
    let hi = 0.97;
    let cols: Vec<Vec<u32>> = (0..w)
        .into_par_iter()
        .map(|x| {
            let mut col: Vec<u32> = (0..h).map(|y| img.px[y * w + x]).collect();
            let ok = |y: usize, p: u32| {
                let l = luma(unpack(p));
                let inside = match (look, mask) {
                    (0, Some(m)) => m.at((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32) < 0.5,
                    (1, Some(m)) => m.at((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32) >= 0.5,
                    (1, None) => false,
                    _ => true,
                };
                inside && l >= lo && l <= hi
            };
            let mut y = 0;
            while y < h {
                if !ok(y, col[y]) {
                    y += 1;
                    continue;
                }
                let start = y;
                while y < h && ok(y, col[y]) {
                    y += 1;
                }
                col[start..y].sort_by(|p, q| luma(unpack(*q)).total_cmp(&luma(unpack(*p))));
            }
            col
        })
        .collect();
    for (x, col) in cols.iter().enumerate() {
        for (y, p) in col.iter().enumerate() {
            img.px[y * w + x] = *p;
        }
    }
}

fn anaglyph(img: &mut Image, mask: Option<&Mask>, a: f32) {
    let (w, h) = (img.w, img.h);
    let shift = a * 0.012 * w as f32;
    let src = img.clone();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (fx, fy) = ((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32);
            // depth: the subject comes forward; without one, lower in the frame is nearer
            let depth = match mask {
                Some(m) if m.centre.is_some() => m.at(fx, fy) - 0.35,
                _ => fy - 0.5,
            };
            let d = shift * depth;
            let left = at(&src, x as f32 + d, y as f32);
            let right = at(&src, x as f32 - d, y as f32);
            *p = pack([0.3 * luma(left) + 0.7 * left[0], right[1], right[2]]);
        }
    });
}

fn infrared(img: &mut Image, look: usize, a: f32) {
    let (w, h) = (img.w, img.h);
    let ir_of = |c: [f32; 3]| {
        let l = luma(c);
        // foliage reflects infrared strongly, blue sky hardly at all
        let veg = smooth(0.0, 0.12, c[1] - c[0].max(c[2]) * 0.92);
        let sky = smooth(0.05, 0.25, c[2] - c[0]);
        (l * 0.75 + veg * 0.8 - sky * 0.35 * l).clamp(0.0, 1.0)
    };
    let src = img.clone();
    let glow = if look == 1 {
        let work = src.fit(900);
        let mut g: Vec<f32> = work.px.iter().map(|&p| smooth(0.55, 1.0, ir_of(unpack(p)))).collect();
        gaussian(&mut g, work.w, work.h, ((0.012 * work.w.max(work.h) as f32) as usize).max(1));
        Some((g, work.w, work.h))
    } else {
        None
    };
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let c = unpack(src.px[y * w + x]);
            let ir = ir_of(c);
            let out = match look {
                0 => {
                    // Aerochrome's layers: infrared -> red, red -> green, green -> blue
                    let o = [ir, c[0], c[1]];
                    let l = luma(o);
                    o.map(|v| (l + (v - l) * 1.35).clamp(0.0, 1.0))
                }
                1 => {
                    let gl = glow.as_ref().map_or(0.0, |(g, gw, gh)| map_at(g, *gw, *gh, x, y, w, h));
                    let v = (ir + gl * 0.35).min(1.0);
                    let v = v + 0.4 * (v * v * (3.0 - 2.0 * v) - v);
                    [v; 3]
                }
                _ => [ir, ir * 0.82, (c[2] * 0.6 + luma(c) * 0.2).min(1.0)],
            };
            *p = pack(mix3(c, out, a));
        }
    });
}

fn anamorphic(img: &mut Image, look: usize, a: f32) {
    let work = img.fit(960);
    let (ww, wh) = (work.w, work.h);
    let bright: Vec<f32> = work
        .px
        .iter()
        .map(|&p| {
            let c = unpack(p);
            let m = c[0].max(c[1]).max(c[2]);
            smooth(0.72, 1.0, m).powi(2) * m
        })
        .collect();
    let mut streak = bright.clone();
    blur_x(&mut streak, ww, ((0.1 * ww as f32) as usize).max(2));
    let mut glow = bright;
    gaussian(&mut glow, ww, wh, ((0.01 * ww.max(wh) as f32) as usize).max(1));
    let colour = match look {
        1 => [1.0, 0.62, 0.3],
        2 => [0.72, 0.45, 1.0],
        _ => [0.35, 0.58, 1.0],
    };
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let s = map_at(&streak, ww, wh, x, y, w, h) * 14.0 * a;
            let g = map_at(&glow, ww, wh, x, y, w, h) * 2.0 * a;
            let light = [0, 1, 2].map(|i| colour[i] * s + g);
            *p = pack(screen(unpack(*p), light));
        }
    });
}

fn rays(img: &mut Image, look: usize, a: f32) {
    let work = img.fit(640);
    let (ww, wh) = (work.w, work.h);
    let bright: Vec<f32> = work.px.iter().map(|&p| smooth(0.6, 1.0, luma(unpack(p))).powf(1.5)).collect();
    let (sx, sy) = if look == 1 {
        (ww as f32 * 0.5, -(wh as f32) * 0.15)
    } else {
        let mut b = bright.clone();
        gaussian(&mut b, ww, wh, 12);
        let i = b.iter().enumerate().max_by(|p, q| p.1.total_cmp(q.1)).map(|(i, _)| i).unwrap_or(0);
        ((i % ww) as f32, (i / ww) as f32)
    };
    const N: usize = 64;
    let mut rays = vec![0f32; ww * wh];
    rays.par_chunks_mut(ww).enumerate().for_each(|(y, row)| {
        for (x, r) in row.iter_mut().enumerate() {
            let (mut s, mut wsum, mut decay) = (0.0, 0.0, 1.0);
            for k in 0..N {
                let t = k as f32 / N as f32 * 0.9;
                let (px, py) = (x as f32 + (sx - x as f32) * t, y as f32 + (sy - y as f32) * t);
                if px >= 0.0 && py >= 0.0 && px < ww as f32 && py < wh as f32 {
                    s += sample(&bright, ww, wh, px, py) * decay;
                }
                wsum += decay;
                decay *= 0.97;
            }
            *r = s / wsum;
        }
    });
    let warm = [1.0, 0.9, 0.72];
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let k = map_at(&rays, ww, wh, x, y, w, h) * 2.6 * a;
            *p = pack(screen(unpack(*p), warm.map(|v| v * k)));
        }
    });
}

fn tilt(img: &mut Image, look: usize, a: f32) {
    let work = img.fit(1400);
    let (ww, wh) = (work.w, work.h);
    let long = ww.max(wh) as f32;
    let blur = |r: f32| {
        let mut p = planes(&work);
        for c in p.iter_mut() {
            gaussian(c, ww, wh, (r as usize).max(1));
        }
        p
    };
    let (b1, b2) = (blur(0.003 * long), blur(0.009 * long));
    let y0 = [0.5, 0.67, 0.33][look.min(2)];
    let (w, h) = (img.w, img.h);
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let d = ((y as f32 + 0.5) / h as f32 - y0).abs();
        let level = smooth(0.07, 0.35, d) * 2.0 * (0.3 + 0.7 * a);
        for (x, p) in row.iter_mut().enumerate() {
            let c = unpack(*p);
            let get = |b: &[Vec<f32>; 3]| [0, 1, 2].map(|i| map_at(&b[i], ww, wh, x, y, w, h));
            let c = if level <= 0.0 {
                c
            } else if level <= 1.0 {
                mix3(c, get(&b1), level)
            } else {
                mix3(get(&b1), get(&b2), level - 1.0)
            };
            // toy-model colours: brighter, punchier
            let l = luma(c);
            let c = c.map(|v| (l + (v - l) * (1.0 + 0.35 * a)).clamp(0.0, 1.0));
            *p = pack(c.map(|v| v + 0.25 * a * (v * v * (3.0 - 2.0 * v) - v)));
        }
    });
}

fn duotone(img: &mut Image, look: usize, a: f32, seed: u64) {
    let (dark, light) = match look {
        1 => ([0.0, 0.25, 0.3], [1.0, 0.68, 0.32]),
        2 => ([0.28, 0.08, 0.42], [1.0, 0.92, 0.38]),
        3 => ([0.03, 0.3, 0.18], [1.0, 0.4, 0.33]),
        4 => ([0.07, 0.1, 0.24], [0.98, 0.94, 0.84]),
        _ => ([0.1, 0.13, 0.5], [1.0, 0.58, 0.72]),
    };
    let (w, h) = (img.w, img.h);
    // risograph: the two inks don't quite line up, and the paper shows through as grain
    let off = a * 0.003 * w.max(h) as f32;
    let src = img.clone();
    let seed = seed as i32;
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let l0 = luma(unpack(src.px[y * w + x]));
            let l1 = luma(at(&src, x as f32 + off, y as f32 + off * 0.6));
            let mut l = 0.5 * (l0 + l1);
            l = l + 0.3 * (l * l * (3.0 - 2.0 * l) - l);
            l += (hash01((y * w + x) as i32, seed) - 0.5) * 0.12 * a;
            *p = pack(mix3(dark, light, l.clamp(0.0, 1.0)));
        }
    });
}

/// Kuwahara filter: each pixel takes the calmest of the four squares around it, so areas flatten into
/// brush-like patches while edges stay.
fn kuwahara(img: &Image, q: usize) -> Image {
    let (w, h) = (img.w, img.h);
    let p = planes(img);
    let l: Vec<f32> = (0..w * h).map(|i| 0.299 * p[0][i] + 0.587 * p[1][i] + 0.114 * p[2][i]).collect();
    let l2: Vec<f32> = l.iter().map(|v| v * v).collect();
    let mean = |m: &[f32]| {
        let mut v = m.to_vec();
        box_blur(&mut v, w, h, q);
        v
    };
    let (mr, mg, mb, ml, ml2) = (mean(&p[0]), mean(&p[1]), mean(&p[2]), mean(&l), mean(&l2));
    let mut out = Image::new(w, h);
    let qi = q as isize;
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, o) in row.iter_mut().enumerate() {
            let mut best = (f32::MAX, 0usize);
            for (dx, dy) in [(-qi, -qi), (qi, -qi), (-qi, qi), (qi, qi)] {
                let xx = (x as isize + dx).clamp(0, w as isize - 1) as usize;
                let yy = (y as isize + dy).clamp(0, h as isize - 1) as usize;
                let i = yy * w + xx;
                let var = ml2[i] - ml[i] * ml[i];
                if var < best.0 {
                    best = (var, i);
                }
            }
            *o = pack([mr[best.1], mg[best.1], mb[best.1]]);
        }
    });
    out
}

fn oil(img: &mut Image, look: usize, a: f32, seed: u64) {
    let water = look == 1;
    let work = img.fit(if water { 1400 } else { 2000 });
    let q = ((if water { 0.004 } else { 0.0028 } * work.w.max(work.h) as f32) as usize).max(2);
    let mut painted = kuwahara(&work, q);
    if water {
        painted = kuwahara(&painted, (q / 2).max(1));
    }
    let (pw, ph) = (painted.w, painted.h);
    // watercolour pools pigment at the edges of each wash
    let edges = water.then(|| {
        let l: Vec<f32> = painted.px.iter().map(|&p| luma(unpack(p))).collect();
        let mut e = vec![0f32; pw * ph];
        for y in 1..ph - 1 {
            for x in 1..pw - 1 {
                let gx = l[y * pw + x + 1] - l[y * pw + x - 1];
                let gy = l[(y + 1) * pw + x] - l[(y - 1) * pw + x];
                e[y * pw + x] = (gx * gx + gy * gy).sqrt();
            }
        }
        gaussian(&mut e, pw, ph, 1);
        e
    });
    let (w, h) = (img.w, img.h);
    let seed = seed as i32;
    let src = img.clone();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let mut c = at(&painted, (x as f32 + 0.5) * pw as f32 / w as f32 - 0.5, (y as f32 + 0.5) * ph as f32 / h as f32 - 0.5);
            // canvas or paper texture, at a fixed size relative to the picture
            let (tx, ty) = ((x * 900 / w) as i32, (y * 900 / w) as i32);
            let tex = hash01(tx * 7919 + ty, seed) - 0.5;
            if let Some(e) = &edges {
                let edge = map_at(e, pw, ph, x, y, w, h);
                c = c.map(|v| (v.powf(0.85) * (1.0 - (edge * 2.5).min(0.35)) + tex * 0.05).clamp(0.0, 1.0));
                let l = luma(c);
                c = c.map(|v| l + (v - l) * 1.1);
            } else {
                let weave = ((x as f32 / w as f32 * 700.0).sin() * (y as f32 / w as f32 * 700.0).sin()) * 0.02;
                c = c.map(|v| v * (1.0 + weave + tex * 0.03));
            }
            *p = pack(mix3(unpack(src.px[y * w + x]), c, a.max(0.0001).sqrt()));
        }
    });
}

fn sketch(img: &mut Image, look: usize, a: f32, seed: u64) {
    let (w, h) = (img.w, img.h);
    let long = w.max(h) as f32;
    let l: Vec<f32> = img.px.par_iter().map(|&p| luma(unpack(p))).collect();
    let mut b = l.clone();
    let r = if look == 2 { 0.006 } else { 0.0028 } * long;
    gaussian(&mut b, w, h, (r as usize).max(1));
    let power = match look {
        2 => 4.0 + 6.0 * a,
        _ => 1.0 + 2.2 * a,
    };
    let seed = seed as i32;
    let src = img.clone();
    img.px.par_iter_mut().enumerate().for_each(|(i, p)| {
        // colour dodge of the picture by its blurred self: flat areas go white, edges stay as lines
        let mut s = (l[i] / b[i].max(1e-3)).min(1.0).powf(power);
        if look == 2 {
            // charcoal smudges into the dark areas
            s = s.min(0.25 + l[i] * 1.2);
        }
        let grain = (hash01(i as i32, seed) - 0.5) * 0.06;
        let paper = [0.97, 0.955, 0.93];
        let c = match look {
            1 => {
                let c = unpack(src.px[i]);
                let lc = luma(c);
                let pastel = c.map(|v| ((lc + (v - lc) * 1.4).clamp(0.0, 1.0)).powf(0.55));
                [0, 1, 2].map(|k| s * (paper[k] * 0.35 + pastel[k] * 0.65) + grain)
            }
            _ => paper.map(|v| v * s + grain),
        };
        *p = pack(c);
    });
}

fn mirror(img: &mut Image, look: usize) {
    let (w, h) = (img.w, img.h);
    let src = img.clone();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (sx, sy) = match look {
                0 => (if x < w / 2 { x } else { w - 1 - x }, y),
                1 => (if x >= w / 2 { x } else { w - 1 - x }, y),
                2 => (x, if y < h / 2 { y } else { h - 1 - y }),
                3 => (if x < w / 2 { x } else { w - 1 - x }, if y < h / 2 { y } else { h - 1 - y }),
                _ => {
                    // six mirrored wedges around the centre
                    let (cx, cy) = (w as f32 / 2.0, h as f32 / 2.0);
                    let (dx, dy) = (x as f32 + 0.5 - cx, y as f32 + 0.5 - cy);
                    let r = (dx * dx + dy * dy).sqrt();
                    let wedge = std::f32::consts::PI / 3.0;
                    let mut ang = dy.atan2(dx).rem_euclid(wedge);
                    if ang > wedge / 2.0 {
                        ang = wedge - ang;
                    }
                    let ang = ang - std::f32::consts::FRAC_PI_2;
                    let sx = (cx + r * ang.cos()).clamp(0.0, (w - 1) as f32) as usize;
                    let sy = (cy + r * ang.sin()).clamp(0.0, (h - 1) as f32) as usize;
                    (sx, sy)
                }
            };
            *p = src.px[sy * w + sx];
        }
    });
}

const MONTHS: [&str; 12] = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"];

fn vhs(img: &mut Image, look: usize, a: f32, seed: u64, date: Option<(i32, u32, u32)>) {
    let (w, h) = (img.w, img.h);
    let damage = [0.35, 0.65, 1.0][look.min(2)] * (0.4 + 0.6 * a);
    // a tape holds about 330 x 480 of picture
    let (vw, vh) = (((480.0 * w as f32 / h as f32) as usize).max(16), 480usize.min(h).max(16));
    let tape = img.resized(vw, vh);
    let mut y_pl = vec![0f32; vw * vh];
    let mut i_pl = vec![0f32; vw * vh];
    let mut q_pl = vec![0f32; vw * vh];
    for (k, &p) in tape.px.iter().enumerate() {
        let c = unpack(p);
        y_pl[k] = 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2];
        i_pl[k] = 0.596 * c[0] - 0.274 * c[1] - 0.322 * c[2];
        q_pl[k] = 0.211 * c[0] - 0.523 * c[1] + 0.312 * c[2];
    }
    // colour smears sideways and lags behind the picture
    let smear = (3.0 + 5.0 * damage) as usize;
    blur_x(&mut i_pl, vw, smear);
    blur_x(&mut q_pl, vw, smear);
    let lag = (2.0 + 4.0 * damage) as usize;
    let seed = seed as i32;
    let mut out = Image::new(vw, vh);
    out.px.par_chunks_mut(vw).enumerate().for_each(|(y, row)| {
        // tracking: rows near the bottom jitter sideways, with snow
        let band = smooth(0.9, 0.97, y as f32 / vh as f32);
        let jitter = (hash01(y as i32, seed) - 0.5) * 2.0 * (1.5 + 18.0 * band) * damage;
        for (x, p) in row.iter_mut().enumerate() {
            let sx = (x as f32 + jitter).clamp(0.0, (vw - 1) as f32);
            let yv = sample(&y_pl, vw, vh, sx, y as f32);
            let cx = (sx - lag as f32).max(0.0);
            let iv = sample(&i_pl, vw, vh, cx, y as f32) * 1.15;
            let qv = sample(&q_pl, vw, vh, cx, y as f32) * 1.15;
            let snow = if hash01((y * vw + x) as i32, seed ^ 77) < band * 0.25 * damage { 0.6 } else { 0.0 };
            let noise = (hash01((y * vw + x) as i32, seed) - 0.5) * 0.08 * damage;
            let yv = (yv + noise + snow).clamp(0.0, 1.0);
            let c = [yv + 0.956 * iv + 0.621 * qv, yv - 0.272 * iv - 0.647 * qv, yv - 1.106 * iv + 1.703 * qv];
            // scan lines
            let scan = if y % 2 == 1 { 1.0 - 0.12 * damage } else { 1.0 };
            *p = pack(c.map(|v| v * scan));
        }
    });
    *img = out.resized(w, h);
    // on-screen text, like a camcorder playing back
    let dot = (h as f32 / 110.0).max(1.0);
    let white = [0.95, 0.95, 0.95];
    font_draw(img, "PLAY >", dot * 5.0, dot * 5.0, dot, white);
    if let Some((yy, m, d)) = date {
        let text = format!("{} {:2} {}", MONTHS[(m as usize).clamp(1, 12) - 1], d, yy);
        font_draw(img, &text, dot * 5.0, h as f32 - dot * 12.0, dot, white);
    }
}

fn font_draw(img: &mut Image, text: &str, x: f32, y: f32, dot: f32, colour: [f32; 3]) {
    crate::font::draw(img, text, x, y, dot, colour, 0.92, 0.35);
}

/// Double exposure: the subject of the first photo filled with the second.
pub fn double_exposure(portrait: &Image, texture: &Image, mask: &Mask, look: usize, a: f32) -> Image {
    let (w, h) = (portrait.w, portrait.h);
    // cover-fit the second photo
    let k = (w as f32 / texture.w as f32).max(h as f32 / texture.h as f32);
    let (tw, th) = (texture.w as f32 * k, texture.h as f32 * k);
    let (ox, oy) = ((tw - w as f32) / 2.0, (th - h as f32) / 2.0);
    let tex = texture.resized(tw.round() as usize, th.round() as usize);
    let dark = look == 1;
    let mut out = Image::new(w, h);
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let m = mask.at((x as f32 + 0.5) / w as f32, (y as f32 + 0.5) / h as f32);
            let pc = unpack(portrait.px[y * w + x]);
            let tc = at(&tex, x as f32 + ox, y as f32 + oy);
            let pl = luma(pc);
            let inside = if dark {
                screen([pl * 0.9; 3], tc.map(|v| v * a))
            } else {
                // light: the portrait's shadows hold the texture, its highlights go to paper
                let base = pl.powf(0.8);
                [0, 1, 2].map(|i| (base * (1.0 - a) + a * (1.0 - (1.0 - tc[i]) * (1.0 - base))).min(1.0) * (0.55 + 0.45 * base))
            };
            let outside = if dark { tc.map(|v| v * 0.12 * a) } else { [0.95, 0.94, 0.92].map(|v: f32| v - (1.0 - luma(tc)) * 0.08 * a) };
            *p = pack(mix3(outside, inside, m));
        }
    });
    out
}
