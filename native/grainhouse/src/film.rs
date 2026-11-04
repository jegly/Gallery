//! The 153 film looks: a 1024-point tone curve on each channel, then a 3x3 colour matrix, exactly
//! what the A7 II's image processor does with them in the camera app.

use crate::img::{pack_f, unpack};

const LIST: &str = include_str!("../assets/films/films.txt");

pub struct Film {
    pub file: &'static str,
    pub name: &'static str,
    pub brand: &'static str,
    pub kind: &'static str,
    /// Mean error against the original LUT (0..255); negative for looks designed for the camera directly.
    pub error: f32,
}

pub fn films() -> Vec<Film> {
    LIST.lines()
        .filter_map(|l| {
            let f: Vec<&str> = l.split('|').collect();
            if f.len() < 5 {
                return None;
            }
            Some(Film { file: f[0], name: f[1], brand: f[2], kind: f[3], error: f[4].trim().parse().unwrap_or(0.0) })
        })
        .collect()
}

/// Brands in library order.
pub fn brands() -> Vec<&'static str> {
    let mut out: Vec<&str> = Vec::new();
    for f in films() {
        if !out.contains(&f.brand) {
            out.push(f.brand);
        }
    }
    out
}

/// The look as stored: curve (0..1023) and matrix (x1024, row-major, out = M . in).
pub struct Look {
    pub gamma: [i32; 1024],
    pub matrix: [i32; 9],
}

pub fn load(file: &str) -> Option<Look> {
    let data = FILM_DATA.iter().find(|(n, _)| *n == file)?.1;
    let rd = |i: usize| i32::from_be_bytes([data[i * 4], data[i * 4 + 1], data[i * 4 + 2], data[i * 4 + 3]]);
    if data.len() < (1 + 1024 + 9) * 4 || rd(0) != 1 {
        return None;
    }
    let mut look = Look { gamma: [0; 1024], matrix: [0; 9] };
    for i in 0..1024 {
        look.gamma[i] = rd(1 + i);
    }
    for i in 0..9 {
        look.matrix[i] = rd(1025 + i);
    }
    Some(look)
}

/// A look at a given strength, ready to apply: a 256-entry curve per input value and the matrix.
pub struct Applied {
    curve: [f32; 256],
    m: [f32; 9],
}

impl Applied {
    pub fn new(look: &Look, strength: f32) -> Applied {
        let a = strength.clamp(0.0, 1.0);
        let mut curve = [0f32; 256];
        for (v, c) in curve.iter_mut().enumerate() {
            let i = (v as f32 / 255.0 * 1023.0).round() as usize;
            let g = look.gamma[i] as f32;
            *c = ((1.0 - a) * i as f32 + a * g) / 1023.0;
        }
        let ident = [1024, 0, 0, 0, 1024, 0, 0, 0, 1024];
        let mut m = [0f32; 9];
        for i in 0..9 {
            m[i] = ((1.0 - a) * ident[i] as f32 + a * look.matrix[i] as f32) / 1024.0;
        }
        Applied { curve, m }
    }

    pub fn apply(&self, px: &mut [u32]) {
        for p in px.iter_mut() {
            let (r, g, b) = unpack(*p);
            let u = [self.curve[r as usize], self.curve[g as usize], self.curve[b as usize]];
            let m = &self.m;
            let o = |j: usize| (m[j * 3] * u[0] + m[j * 3 + 1] * u[1] + m[j * 3 + 2] * u[2]).clamp(0.0, 1.0) * 255.0;
            *p = pack_f(o(0), o(1), o(2));
        }
    }
}

// every assets/films/*.bin, listed by build.rs
static FILM_DATA: &[(&str, &[u8])] = include!(concat!(env!("OUT_DIR"), "/films.rs"));
