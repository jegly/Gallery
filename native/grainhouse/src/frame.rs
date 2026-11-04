//! Frames and borders put round the finished picture: white and black borders, Polaroid, Instax, a 35 mm strip
//! with sprocket holes and edge printing, a 6x6 negative carrier, and cinema bars.

use crate::img::Image;
use crate::util::{hash01, smooth};
use rayon::prelude::*;

pub const NAMES: [&str; 9] = [
    "None",
    "Thin white border",
    "Gallery white",
    "Black border",
    "Polaroid",
    "Instax",
    "35mm film strip",
    "6x6 negative",
    "Cinema bars 2.39:1",
];

fn pack(c: [f32; 3]) -> u32 {
    let q = |v: f32| (v.clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
    (q(c[0]) << 16) | (q(c[1]) << 8) | q(c[2])
}

/// Centre crop to a width / height ratio.
fn crop_to(img: &Image, ratio: f32) -> Image {
    let (w, h) = (img.w as f32, img.h as f32);
    let (cw, ch) = if w / h > ratio { (h * ratio, h) } else { (w, w / ratio) };
    let (cw, ch) = ((cw.round() as usize).max(1), (ch.round() as usize).max(1));
    let (x0, y0) = ((img.w - cw) / 2, (img.h - ch) / 2);
    let mut out = Image::new(cw, ch);
    for y in 0..ch {
        out.px[y * cw..(y + 1) * cw].copy_from_slice(&img.px[(y0 + y) * img.w + x0..(y0 + y) * img.w + x0 + cw]);
    }
    out
}

/// The picture on a background, with margins (left, top, right, bottom) in pixels. `paper` gives the colour at
/// each background pixel.
fn mount(img: &Image, m: [usize; 4], paper: impl Fn(usize, usize) -> [f32; 3] + Sync) -> Image {
    let (w, h) = (img.w + m[0] + m[2], img.h + m[1] + m[3]);
    let mut out = Image::new(w, h);
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let inside = x >= m[0] && x < m[0] + img.w && y >= m[1] && y < m[1] + img.h;
            *p = if inside { img.px[(y - m[1]) * img.w + x - m[0]] } else { pack(paper(x, y)) };
        }
    });
    out
}

/// Instant-film paper: off-white with a faint texture, and a soft shadow where it meets the picture.
fn instant(img: Image, sides: f32, top: f32, bottom: f32, seed: i32) -> Image {
    let unit = img.w as f32;
    let m = [(sides * unit) as usize, (top * unit) as usize, (sides * unit) as usize, (bottom * unit) as usize];
    let (iw, ih) = (img.w, img.h);
    let mut out = mount(&img, m, |x, y| {
        let t = (hash01((y * 7919 + x) as i32, seed) - 0.5) * 0.012;
        [0.955 + t, 0.948 + t, 0.925 + t]
    });
    // the picture sits slightly below the paper surface
    let shade = (unit * 0.006).max(1.0);
    let w = out.w;
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (dx, dy) = (x as f32 - m[0] as f32, y as f32 - m[1] as f32);
            if dx < 0.0 || dy < 0.0 || dx >= iw as f32 || dy >= ih as f32 {
                continue;
            }
            let d = dx.min(dy).min(iw as f32 - dx).min(ih as f32 - dy);
            let k = 1.0 - 0.25 * (1.0 - smooth(0.0, shade, d));
            let c = [((*p >> 16) & 255) as f32, ((*p >> 8) & 255) as f32, (*p & 255) as f32].map(|v| v / 255.0 * k);
            *p = pack(c);
        }
    });
    out
}

fn film_strip(img: Image, seed: i32, label: &str) -> Image {
    let (iw, ih) = (img.w as f32, img.h as f32);
    // 35 mm: the frame is 36 x 24 mm on 35 mm film, holes in a 4.5 mm band each side
    let band = (ih * 0.2) as usize;
    let side = (iw * 0.025) as usize;
    let base = [0.07, 0.055, 0.04];
    let (w, h) = (img.w + 2 * side, img.h + 2 * band);
    let pitch = w as f32 / 8.0;
    let (hw, hh) = (pitch * 0.5, ih * 0.08);
    let r = hh * 0.25;
    let hole_y = [band as f32 * 0.42 - hh / 2.0, h as f32 - band as f32 * 0.42 - hh / 2.0];
    let mut out = mount(&img, [side, band, side, band], |x, y| {
        let (fx, fy) = (x as f32, y as f32);
        for &hy in &hole_y {
            let k = ((fx - pitch * 0.25) / pitch).floor();
            let hx = pitch * 0.25 + k * pitch;
            // rounded-rectangle sprocket hole, lit from behind
            let (dx, dy) = ((fx - hx - hw / 2.0).abs() - (hw / 2.0 - r), (fy - hy - hh / 2.0).abs() - (hh / 2.0 - r));
            let d = (dx.max(0.0).powi(2) + dy.max(0.0).powi(2)).sqrt() - r + dx.max(dy).min(0.0);
            if d < 0.0 {
                return [0.92, 0.9, 0.84];
            }
        }
        let t = (hash01((y * 7919 + x) as i32, seed) - 0.5) * 0.02;
        base.map(|v| v + t)
    });
    // edge printing in the rebate, orange like exposed edge marks
    let dot = (band as f32 * 0.045).max(1.0);
    let ink = [0.98, 0.62, 0.18];
    let n = 1 + (hash01(seed, 3) * 35.0) as u32;
    let y_text = band as f32 * 0.66;
    crate::font::draw(&mut out, label, pitch * 0.6, y_text, dot, ink, 0.85, 0.3);
    let num = format!("{n}  >{n}A");
    crate::font::draw(&mut out, &num, w as f32 - pitch * 0.6 - crate::font::width(&num, dot), y_text, dot, ink, 0.85, 0.3);
    out
}

fn negative_carrier(img: Image, seed: i32) -> Image {
    let sq = crop_to(&img, 1.0);
    let b = (sq.w as f32 * 0.07) as usize;
    let s = sq.w as f32;
    let (bf, total) = (b as f32, sq.w + 2 * b);
    let mut out = mount(&sq, [b, b, b, b], |_, _| [0.02, 0.02, 0.02]);
    // the film's own clear edge shows as a rough, slightly lighter line just outside the picture
    let w = out.w;
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for (x, p) in row.iter_mut().enumerate() {
            let (dx, dy) = (x as f32 - bf, y as f32 - bf);
            let edge = dx.min(dy).min(s - dx).min(s - dy);
            let rough = (hash01((x / 3 + y / 3 * 4099) as i32, seed) - 0.5) * s * 0.004;
            if edge < 0.0 && edge > -s * 0.012 + rough {
                *p = pack([0.13, 0.12, 0.1]);
            } else if edge >= 0.0 && edge < s * 0.004 + rough.abs() {
                *p = pack([0.03, 0.03, 0.03]);
            }
        }
    });
    let _ = total;
    out
}

fn cinema(mut img: Image) -> Image {
    let ratio = 2.39;
    let (w, h) = (img.w, img.h);
    let keep = (w as f32 / ratio).round() as usize;
    if keep >= h {
        return img;
    }
    let bar = (h - keep) / 2;
    for y in (0..bar).chain(h - bar..h) {
        img.px[y * w..(y + 1) * w].fill(0);
    }
    img
}

/// Puts the frame round the picture. `label` is the 35 mm strip's edge printing.
pub fn apply(img: Image, frame: usize, seed: u64, label: &str) -> Image {
    let seed = seed as i32;
    let short = img.w.min(img.h) as f32;
    match frame {
        1 => {
            let m = (short * 0.025) as usize;
            mount(&img, [m; 4], |_, _| [0.97, 0.97, 0.96])
        }
        2 => {
            let m = (short * 0.09) as usize;
            mount(&img, [m, m, m, (m as f32 * 1.25) as usize], |_, _| [0.96, 0.955, 0.94])
        }
        3 => {
            let m = (short * 0.03) as usize;
            mount(&img, [m; 4], |_, _| [0.02, 0.02, 0.02])
        }
        4 => instant(crop_to(&img, 1.0), 0.057, 0.066, 0.29, seed),
        5 => {
            if img.w >= img.h {
                // Instax Wide: 99 x 62 mm picture on 108 x 86 mm
                instant(crop_to(&img, 99.0 / 62.0), 0.045, 0.06, 0.18, seed)
            } else {
                // Instax Mini: 46 x 62 mm picture on 54 x 86 mm
                instant(crop_to(&img, 46.0 / 62.0), 0.087, 0.11, 0.41, seed)
            }
        }
        6 if img.w >= img.h => film_strip(crop_to(&img, 1.5), seed, label),
        // a portrait frame: build the strip sideways and turn it upright
        6 => crate::io::orient(film_strip(crate::io::orient(crop_to(&img, 2.0 / 3.0), 6), seed, label), 8),
        7 => negative_carrier(img, seed),
        8 => cinema(img),
        _ => img,
    }
}
