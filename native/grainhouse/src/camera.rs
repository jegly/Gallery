//! Camera looks: the colour and character of other cameras, designed by eye to be in the spirit of each (not
//! measured from them). Colour (matrix, saturation, tone, split toning) goes on before the film; character
//! (lens vignette and soft corners, colour fringing, sensor noise, sharpening, a lower-resolution sensor) after
//! the effect. Also the orange date stamp of disposables and 2000s compacts.

use crate::img::Image;
use crate::util::{gaussian, hash01, smooth};
use rayon::prelude::*;
use std::path::Path;

pub struct Camera {
    pub name: &'static str,
    sat: f32,
    /// rows: output R, G, B from input R, G, B (sRGB values)
    matrix: [[f32; 3]; 3],
    /// S-curve strength
    contrast: f32,
    /// raised blacks (faded)
    black: f32,
    /// input level that becomes white (below 1 clips highlights early, like small sensors)
    white: f32,
    /// opens up shadows (phones)
    lift: f32,
    shadow_tint: [f32; 3],
    highlight_tint: [f32; 3],
    vignette: f32,
    /// soft, smeared corners from a simple lens
    soft: f32,
    /// colour fringing, as a fraction of the long side at the corners
    fringe: f32,
    noise: f32,
    sharpen: f32,
    /// sensor size to emulate in megapixels (0 = keep)
    megapixels: f32,
    gameboy: bool,
}

const I: [[f32; 3]; 3] = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];

const fn cam(name: &'static str) -> Camera {
    Camera {
        name,
        sat: 1.0,
        matrix: I,
        contrast: 0.0,
        black: 0.0,
        white: 1.0,
        lift: 0.0,
        shadow_tint: [0.0; 3],
        highlight_tint: [0.0; 3],
        vignette: 0.0,
        soft: 0.0,
        fringe: 0.0,
        noise: 0.0,
        sharpen: 0.0,
        megapixels: 0.0,
        gameboy: false,
    }
}

pub static CAMERAS: [Camera; 13] = [
    cam("None"),
    Camera {
        sat: 0.72,
        matrix: [[0.92, 0.06, 0.02], [0.03, 0.95, 0.02], [0.0, 0.08, 0.92]],
        contrast: 0.35,
        black: 0.015,
        shadow_tint: [-0.012, 0.0, 0.015],
        highlight_tint: [0.015, 0.008, -0.01],
        ..cam("Fujifilm Classic Chrome-style")
    },
    Camera {
        sat: 0.85,
        matrix: [[1.04, -0.04, 0.0], [0.02, 0.94, 0.04], [0.0, 0.02, 0.98]],
        contrast: 0.5,
        shadow_tint: [-0.02, 0.01, 0.02],
        highlight_tint: [0.02, -0.004, -0.012],
        ..cam("Fujifilm Classic Neg-style")
    },
    Camera {
        sat: 1.15,
        matrix: [[1.08, -0.06, -0.02], [-0.03, 1.05, -0.02], [-0.02, -0.04, 1.06]],
        contrast: 0.4,
        white: 0.97,
        highlight_tint: [0.01, 0.005, -0.005],
        vignette: 0.25,
        noise: 0.12,
        ..cam("Leica M9 CCD-style")
    },
    Camera {
        sat: 1.25,
        matrix: [[1.05, -0.03, -0.02], [0.0, 1.0, 0.0], [-0.03, -0.02, 1.05]],
        contrast: 0.45,
        shadow_tint: [0.0, -0.005, 0.01],
        highlight_tint: [0.02, 0.012, -0.02],
        vignette: 0.15,
        ..cam("Ricoh GR Positive Film-style")
    },
    Camera {
        sat: 1.08,
        matrix: [[1.03, -0.04, 0.01], [0.0, 1.0, 0.0], [0.0, -0.01, 1.01]],
        contrast: 0.25,
        highlight_tint: [0.012, 0.004, -0.006],
        ..cam("Canon-style")
    },
    Camera {
        sat: 1.08,
        matrix: [[1.0, 0.0, 0.0], [-0.03, 1.06, -0.03], [0.0, 0.0, 1.0]],
        contrast: 0.32,
        shadow_tint: [-0.004, 0.0, 0.008],
        sharpen: 0.2,
        ..cam("Nikon-style")
    },
    Camera {
        contrast: 0.12,
        white: 1.0,
        highlight_tint: [0.006, 0.002, -0.004],
        ..cam("Hasselblad-style")
    },
    Camera {
        sat: 1.12,
        contrast: 0.1,
        lift: 0.35,
        highlight_tint: [0.008, 0.004, -0.004],
        sharpen: 0.6,
        ..cam("iPhone-style")
    },
    Camera {
        sat: 1.3,
        contrast: 0.45,
        white: 0.9,
        shadow_tint: [-0.01, 0.0, 0.02],
        highlight_tint: [-0.004, 0.0, 0.01],
        vignette: 0.1,
        soft: 0.2,
        fringe: 0.002,
        noise: 0.5,
        sharpen: 0.8,
        megapixels: 3.0,
        ..cam("2000s CCD compact")
    },
    Camera {
        sat: 1.1,
        contrast: 0.3,
        black: 0.06,
        white: 0.93,
        shadow_tint: [-0.01, 0.02, -0.005],
        highlight_tint: [0.03, 0.015, -0.02],
        vignette: 0.55,
        soft: 0.6,
        fringe: 0.0015,
        noise: 0.3,
        ..cam("Disposable camera")
    },
    Camera {
        sat: 1.35,
        contrast: 0.6,
        black: 0.03,
        shadow_tint: [-0.02, 0.01, 0.03],
        highlight_tint: [0.03, 0.03, -0.04],
        vignette: 0.85,
        soft: 0.9,
        fringe: 0.003,
        ..cam("Toy camera (Holga-style)")
    },
    Camera { gameboy: true, ..cam("Game Boy Camera") },
];

pub fn names() -> Vec<&'static str> {
    CAMERAS.iter().map(|c| c.name).collect()
}

fn lerp(a: f32, b: f32, t: f32) -> f32 {
    a + (b - a) * t
}

/// The camera's colour, mixed in by `strength` (0..1).
pub fn colour(img: &mut Image, cam: usize, strength: f32) {
    let Some(c) = CAMERAS.get(cam) else { return };
    let k = strength.clamp(0.0, 1.0);
    if cam == 0 || k == 0.0 || c.gameboy {
        return;
    }
    // tone per channel, as a table
    let tone: [Vec<f32>; 3] = [0, 1, 2].map(|ch| {
        (0..256)
            .map(|v| {
                let x = (v as f32 / 255.0 / c.white).min(1.0);
                let mut y = x + c.contrast * 0.55 * (x * x * (3.0 - 2.0 * x) - x);
                y = y.max(0.0).powf(1.0 - 0.35 * c.lift);
                y += 1.5 * (c.shadow_tint[ch] * (1.0 - y) * (1.0 - y) + c.highlight_tint[ch] * y * y);
                y = c.black + (1.0 - c.black) * y;
                lerp(v as f32 / 255.0, y, k).clamp(0.0, 1.0)
            })
            .collect()
    });
    let m: [[f32; 3]; 3] = [0, 1, 2].map(|i| [0, 1, 2].map(|j| lerp(I[i][j], c.matrix[i][j], k)));
    let sat = lerp(1.0, c.sat, k);
    img.px.par_chunks_mut(4096).for_each(|chunk| {
        for p in chunk.iter_mut() {
            let v = [((*p >> 16) & 255) as f32, ((*p >> 8) & 255) as f32, (*p & 255) as f32];
            let mut o = [0, 1, 2].map(|i| m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2]);
            let l = 0.299 * o[0] + 0.587 * o[1] + 0.114 * o[2];
            o = o.map(|x| (l + (x - l) * sat).clamp(0.0, 255.0));
            let out = [0, 1, 2].map(|i| {
                let x = o[i];
                let i0 = (x as usize).min(254);
                let t = x - i0 as f32;
                ((tone[i][i0] + (tone[i][i0 + 1] - tone[i][i0]) * t) * 255.0 + 0.5) as u32
            });
            *p = (out[0].min(255) << 16) | (out[1].min(255) << 8) | out[2].min(255);
        }
    });
}

fn planes(img: &Image) -> [Vec<f32>; 3] {
    [16, 8, 0].map(|s| img.px.iter().map(|p| ((p >> s) & 255) as f32).collect())
}

fn pack3(r: f32, g: f32, b: f32) -> u32 {
    let c = |v: f32| (v.clamp(0.0, 255.0) + 0.5) as u32;
    (c(r) << 16) | (c(g) << 8) | c(b)
}

/// The camera's lens and sensor character, mixed in by `strength`.
pub fn character(img: &mut Image, cam: usize, strength: f32, seed: u64) {
    let Some(c) = CAMERAS.get(cam) else { return };
    let k = strength.clamp(0.0, 1.0);
    if cam == 0 || k == 0.0 {
        return;
    }
    if c.gameboy {
        gameboy(img);
        return;
    }
    let (w, h) = (img.w, img.h);
    let long = w.max(h) as f32;
    let (cx, cy) = (w as f32 / 2.0, h as f32 / 2.0);
    let norm = (cx * cx + cy * cy).sqrt();
    // lens: soft corners, fringing and vignette, all growing towards the corners
    if c.soft > 0.0 || c.fringe > 0.0 || c.vignette > 0.0 {
        let src = img.clone();
        let blurred = if c.soft > 0.0 {
            let mut p = planes(&src);
            let r = ((0.004 * long) as usize).max(1);
            for ch in p.iter_mut() {
                gaussian(ch, w, h, r);
            }
            Some(p)
        } else {
            None
        };
        let fringe = c.fringe * k * long;
        img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
            for (x, p) in row.iter_mut().enumerate() {
                let (dx, dy) = (x as f32 + 0.5 - cx, y as f32 + 0.5 - cy);
                let r = (dx * dx + dy * dy).sqrt() / norm;
                let get = |ch: usize, s: f32| -> f32 {
                    // channel ch sampled with its image scaled by s about the centre
                    let (sx, sy) = (cx + dx * s - 0.5, cy + dy * s - 0.5);
                    let (x0, y0) = (sx.clamp(0.0, (w - 1) as f32) as usize, sy.clamp(0.0, (h - 1) as f32) as usize);
                    let shift = [16, 8, 0][ch];
                    ((src.px[y0 * w + x0] >> shift) & 255) as f32
                };
                let s = fringe * r / (norm.max(1.0));
                let mut v = if fringe > 0.0 { [get(0, 1.0 + s), get(1, 1.0), get(2, 1.0 - s)] } else { [0, 1, 2].map(|ch| get(ch, 1.0)) };
                if let Some(b) = &blurred {
                    let t = c.soft * k * smooth(0.3, 1.0, r);
                    let i = y * w + x;
                    v = [0, 1, 2].map(|ch| lerp(v[ch], b[ch][i], t));
                }
                let vig = 1.0 - c.vignette * k * smooth(0.25, 1.15, r).powf(1.3);
                *p = pack3(v[0] * vig, v[1] * vig, v[2] * vig);
            }
        });
    }
    // sensor: work at the emulated size if it's smaller, then scale back up (soft, as those cameras were)
    if c.noise > 0.0 || c.sharpen > 0.0 || c.megapixels > 0.0 {
        let target = (c.megapixels * 1e6) as usize;
        let small = c.megapixels > 0.0 && w * h > target * 5 / 4;
        let mut s = if small {
            let f = (target as f32 / (w * h) as f32).sqrt();
            img.resized(((w as f32 * f) as usize).max(2), ((h as f32 * f) as usize).max(2))
        } else {
            img.clone()
        };
        sensor(&mut s, c.noise * k, c.sharpen * k, seed);
        *img = if small { s.resized(w, h) } else { s };
    }
}

fn sensor(img: &mut Image, noise: f32, sharpen: f32, seed: u64) {
    let (w, h) = (img.w, img.h);
    if sharpen > 0.0 {
        let p = planes(img);
        let mut b = p.clone();
        for ch in b.iter_mut() {
            gaussian(ch, w, h, 1);
        }
        img.px.par_iter_mut().enumerate().for_each(|(i, px)| {
            let v = [0, 1, 2].map(|ch| p[ch][i] + sharpen * 1.6 * (p[ch][i] - b[ch][i]));
            *px = pack3(v[0], v[1], v[2]);
        });
    }
    if noise > 0.0 {
        let seed = seed as i32;
        img.px.par_iter_mut().enumerate().for_each(|(i, px)| {
            let (r, g, b) = (((*px >> 16) & 255) as f32, ((*px >> 8) & 255) as f32, (*px & 255) as f32);
            let l = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
            // more noise in the shadows, like a small sensor pushed
            let amp = noise * 22.0 * (0.25 + 0.75 * (1.0 - l).powf(1.5));
            let n = |o: i32| hash01(i as i32, seed.wrapping_add(o)) - 0.5;
            let luma = n(0) * amp;
            *px = pack3(r + luma + n(1) * amp * 0.6, g + luma + n(2) * amp * 0.4, b + luma + n(3) * amp * 0.8);
        });
    }
}

/// 128 x 112-style four-shade green picture with ordered dithering, scaled back up with square pixels.
fn gameboy(img: &mut Image) {
    const PALETTE: [u32; 4] = [0x0f380f, 0x306230, 0x8bac0f, 0x9bbc0f];
    const BAYER: [[f32; 4]; 4] = [[0.0, 8.0, 2.0, 10.0], [12.0, 4.0, 14.0, 6.0], [3.0, 11.0, 1.0, 9.0], [15.0, 7.0, 13.0, 5.0]];
    let (w, h) = (img.w, img.h);
    let f = 128.0 / w.max(h) as f32 * 1.25;
    let (sw, sh) = (((w as f32 * f) as usize).max(4), ((h as f32 * f) as usize).max(4));
    let small = img.resized(sw, sh);
    // stretch the contrast like the camera did
    let lum: Vec<f32> = small.px.iter().map(|p| (0.299 * ((p >> 16) & 255) as f32 + 0.587 * ((p >> 8) & 255) as f32 + 0.114 * (p & 255) as f32) / 255.0).collect();
    let mut sorted = lum.clone();
    sorted.sort_by(|a, b| a.total_cmp(b));
    let (lo, hi) = (sorted[sorted.len() / 50], sorted[sorted.len() * 49 / 50].max(sorted[sorted.len() / 50] + 0.01));
    let idx: Vec<usize> = lum
        .iter()
        .enumerate()
        .map(|(i, l)| {
            let v = ((l - lo) / (hi - lo)).clamp(0.0, 1.0) * 3.0;
            let d = (BAYER[(i / sw) % 4][(i % sw) % 4] + 0.5) / 16.0;
            ((v + d - 0.5).round() as usize).min(3)
        })
        .collect();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let sy = (y * sh / h).min(sh - 1);
        for (x, p) in row.iter_mut().enumerate() {
            *p = PALETTE[idx[sy * sw + (x * sw / w).min(sw - 1)]];
        }
    });
}

/// The date a photo was taken: the first EXIF-style date in the file, else the file's date. (year, month, day)
pub fn photo_date(path: &Path) -> Option<(i32, u32, u32)> {
    use std::io::Read;
    let mut head = vec![0u8; 1 << 18];
    let n = std::fs::File::open(path).ok()?.read(&mut head).ok()?;
    let d = &head[..n];
    let digit = |b: u8| b.is_ascii_digit();
    for i in 0..d.len().saturating_sub(19) {
        let s = &d[i..i + 19];
        if s[4] == b':' && s[7] == b':' && s[10] == b' ' && s[13] == b':' && s[16] == b':' && [0, 1, 2, 3, 5, 6, 8, 9, 11, 12].iter().all(|&j| digit(s[j])) {
            let num = |a: usize, b: usize| std::str::from_utf8(&s[a..b]).ok()?.parse::<u32>().ok();
            let (y, m, dd) = (num(0, 4)?, num(5, 7)?, num(8, 10)?);
            if (1990..2100).contains(&y) && (1..=12).contains(&m) && (1..=31).contains(&dd) {
                return Some((y as i32, m, dd));
            }
        }
    }
    let secs = std::fs::metadata(path).ok()?.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_secs() as i64;
    Some(civil(secs.div_euclid(86400)))
}

/// Days since 1970-01-01 to (year, month, day) (Howard Hinnant's algorithm).
fn civil(z: i64) -> (i32, u32, u32) {
    let z = z + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    ((yoe + era * 400 + (m <= 2) as i64) as i32, m, d)
}

/// The orange seven-segment date of a film compact, bottom right: 'YY MM DD.
pub fn date_stamp(img: &mut Image, date: (i32, u32, u32)) {
    const SEGS: [u8; 10] = [0b0111111, 0b0000110, 0b1011011, 0b1001111, 0b1100110, 0b1101101, 0b1111101, 0b0000111, 0b1111111, 0b1101111];
    let text = format!("'{:02} {:02} {:02}", date.0.rem_euclid(100), date.1, date.2);
    let (w, h) = (img.w, img.h);
    let dh = (w.min(h) as f32 * 0.035).max(8.0); // digit height
    let dw = dh * 0.55;
    let t = dh * 0.13; // segment thickness
    let gap = dh * 0.28;
    let advance = |ch: char| match ch {
        '\'' => dw * 0.45,
        ' ' => gap,
        _ => dw + gap * 0.6,
    };
    let total: f32 = text.chars().map(advance).sum();
    let (x0, y0) = (w as f32 - w.min(h) as f32 * 0.06 - total, h as f32 - w.min(h) as f32 * 0.06 - dh);
    // the shapes, as rectangles (x, y, w, h) in pixels before the slant
    let mut rects: Vec<[f32; 4]> = Vec::new();
    let mut x = x0;
    for ch in text.chars() {
        if ch == '\'' {
            rects.push([x + dw * 0.1, y0, t, dh * 0.3]);
        } else if let Some(d) = ch.to_digit(10) {
            let s = SEGS[d as usize];
            let half = dh / 2.0;
            let r = [
                [x + t * 0.5, y0, dw - t, t],                      // a
                [x + dw - t, y0 + t * 0.5, t, half - t * 0.5],     // b
                [x + dw - t, y0 + half, t, half - t * 0.5],        // c
                [x + t * 0.5, y0 + dh - t, dw - t, t],             // d
                [x, y0 + half, t, half - t * 0.5],                 // e
                [x, y0 + t * 0.5, t, half - t * 0.5],              // f
                [x + t * 0.5, y0 + half - t * 0.5, dw - t, t],     // g
            ];
            for (i, rr) in r.iter().enumerate() {
                if s >> i & 1 == 1 {
                    rects.push(*rr);
                }
            }
        }
        x += advance(ch);
    }
    let slant = 0.12;
    let pad = (dh * 0.6) as usize;
    let bx0 = (x0 as usize).saturating_sub(pad);
    let by0 = (y0 as usize).saturating_sub(pad);
    let bx1 = ((x0 + total + dh * slant) as usize + pad).min(w);
    let by1 = ((y0 + dh) as usize + pad).min(h);
    if bx1 <= bx0 || by1 <= by0 {
        return;
    }
    let (bw, bh) = (bx1 - bx0, by1 - by0);
    // light from the stamp's LEDs, 2x2 samples per pixel
    let mut a = vec![0f32; bw * bh];
    for yy in 0..bh {
        for xx in 0..bw {
            let mut hit = 0.0;
            for (ox, oy) in [(0.25, 0.25), (0.75, 0.25), (0.25, 0.75), (0.75, 0.75)] {
                let py = (by0 + yy) as f32 + oy;
                let px = (bx0 + xx) as f32 + ox + (py - (y0 + dh)) * slant;
                if rects.iter().any(|r| px >= r[0] && px < r[0] + r[2] && py >= r[1] && py < r[1] + r[3]) {
                    hit += 0.25;
                }
            }
            a[yy * bw + xx] = hit;
        }
    }
    let mut glow = a.clone();
    gaussian(&mut glow, bw, bh, ((dh * 0.12) as usize).max(1));
    let orange = [255.0, 138.0, 36.0];
    for yy in 0..bh {
        for xx in 0..bw {
            let i = (by0 + yy) * w + bx0 + xx;
            let l = (a[yy * bw + xx] * 0.85 + glow[yy * bw + xx] * 0.7).min(1.0);
            if l <= 0.0 {
                continue;
            }
            let p = img.px[i];
            let v = [((p >> 16) & 255) as f32, ((p >> 8) & 255) as f32, (p & 255) as f32];
            // light added onto the film: a screen blend
            let o = [0, 1, 2].map(|c| 255.0 - (255.0 - v[c]) * (1.0 - orange[c] / 255.0 * l));
            img.px[i] = pack3(o[0], o[1], o[2]);
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn civil_dates() {
        assert_eq!(super::civil(0), (1970, 1, 1));
        assert_eq!(super::civil(20385), (2025, 10, 24));
    }
}
