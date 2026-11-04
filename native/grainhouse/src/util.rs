//! Small helpers shared by the effects: blurs, bilinear map sampling, smoothstep, hashing.

/// Box blur of radius r, horizontally then vertically, edges clamped.
pub fn box_blur(m: &mut [f32], w: usize, h: usize, r: usize) {
    if r == 0 || w == 0 || h == 0 {
        return;
    }
    let ri = r as isize;
    let norm = 1.0 / (2 * r + 1) as f32;
    let mut tmp = vec![0f32; w.max(h)];
    let ci = |i: isize, n: usize| i.clamp(0, n as isize - 1) as usize;
    for y in 0..h {
        let row = &mut m[y * w..(y + 1) * w];
        let mut sum = 0.0;
        for i in -ri..=ri {
            sum += row[ci(i, w)];
        }
        for x in 0..w {
            tmp[x] = sum * norm;
            let xi = x as isize;
            sum += row[ci(xi + ri + 1, w)] - row[ci(xi - ri, w)];
        }
        row.copy_from_slice(&tmp[..w]);
    }
    for x in 0..w {
        let mut sum = 0.0;
        for i in -ri..=ri {
            sum += m[ci(i, h) * w + x];
        }
        for y in 0..h {
            tmp[y] = sum * norm;
            let yi = y as isize;
            sum += m[ci(yi + ri + 1, h) * w + x] - m[ci(yi - ri, h) * w + x];
        }
        for y in 0..h {
            m[y * w + x] = tmp[y];
        }
    }
}

/// Three box blurs of radius r, close to a Gaussian.
pub fn gaussian(m: &mut [f32], w: usize, h: usize, r: usize) {
    for _ in 0..3 {
        box_blur(m, w, h, r);
    }
}

#[inline]
pub fn smooth(e0: f32, e1: f32, v: f32) -> f32 {
    let t = (v - e0) / (e1 - e0);
    if t <= 0.0 {
        0.0
    } else if t >= 1.0 {
        1.0
    } else {
        t * t * (3.0 - 2.0 * t)
    }
}

/// Screen blend of white light at strength k (0..1) over a 0..255 value.
#[inline]
pub fn screen(v: f32, k: f32) -> f32 {
    let k = k.min(1.0);
    255.0 - (255.0 - v) * (1.0 - k)
}

/// Where each output column samples a map that is `mw` wide (bilinear, pixel centres aligned).
pub struct Cols {
    pub x0: Vec<usize>,
    pub x1: Vec<usize>,
    pub fx: Vec<f32>,
}

impl Cols {
    pub fn new(w: usize, mw: usize) -> Cols {
        let mut c = Cols { x0: vec![0; w], x1: vec![0; w], fx: vec![0.0; w] };
        for x in 0..w {
            let sx = ((x as f32 + 0.5) * mw as f32 / w as f32 - 0.5).max(0.0);
            let i = (sx as usize).min(mw - 1);
            c.x0[x] = i;
            c.x1[x] = (i + 1).min(mw - 1);
            c.fx[x] = sx - i as f32;
        }
        c
    }
}

/// The two map rows and weight for output row y of an image `h` tall over a map `mh` tall.
#[inline]
pub fn row_of(y: usize, h: usize, mh: usize) -> (usize, usize, f32) {
    let sy = ((y as f32 + 0.5) * mh as f32 / h as f32 - 0.5).max(0.0);
    let iy = (sy as usize).min(mh - 1);
    (iy, (iy + 1).min(mh - 1), sy - iy as f32)
}

#[inline]
pub fn lerp2(m: &[f32], a0: usize, a1: usize, b0: usize, b1: usize, wx: f32, wy: f32) -> f32 {
    let top = m[a0] + (m[a1] - m[a0]) * wx;
    let bot = m[b0] + (m[b1] - m[b0]) * wx;
    top + (bot - top) * wy
}

/// Bilinear sample of a map at continuous map coordinates.
pub fn sample(m: &[f32], w: usize, h: usize, sx: f32, sy: f32) -> f32 {
    let sx = sx.max(0.0);
    let sy = sy.max(0.0);
    let x0 = (sx as usize).min(w - 1);
    let y0 = (sy as usize).min(h - 1);
    let x1 = (x0 + 1).min(w - 1);
    let y1 = (y0 + 1).min(h - 1);
    lerp2(m, y0 * w + x0, y0 * w + x1, y1 * w + x0, y1 * w + x1, sx - x0 as f32, sy - y0 as f32)
}

/// A repeatable hash of (i, seed) in 0..1.
#[inline]
pub fn hash01(i: i32, seed: i32) -> f32 {
    let mut h = i.wrapping_mul(374761393).wrapping_add(seed.wrapping_mul(668265263));
    h = (h ^ ((h as u32) >> 13) as i32).wrapping_mul(1274126177);
    ((h ^ ((h as u32) >> 16) as i32) & 0xFFFFFF) as f32 / 0x1000000 as f32
}

/// A small deterministic random generator (xorshift64*).
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng(seed ^ 0x9E3779B97F4A7C15 | 1)
    }

    pub fn next_u32(&mut self) -> u32 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        (x.wrapping_mul(0x2545F4914F6CDD1D) >> 32) as u32
    }

    pub fn float(&mut self) -> f32 {
        (self.next_u32() >> 8) as f32 / (1u32 << 24) as f32
    }

    pub fn below(&mut self, n: u32) -> u32 {
        self.next_u32() % n.max(1)
    }
}
