//! After-shot film effects: CineStill-style halation, film grain and light leaks.
//! Kept subtle on purpose (strong versions looked bad).

use crate::img::{pack_f, unpack, Image};
use crate::util::{box_blur, screen, Rng};
use rayon::prelude::*;

pub const GRAIN_NAMES: [&str; 5] = ["Off", "Fine", "Medium", "Heavy", "Coarse"];

#[derive(Clone, Copy, PartialEq, Debug, Default, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct DevelopOptions {
    pub halation: bool,
    /// 0 off, 1 fine, 2 medium, 3 heavy, 4 coarse
    pub grain: usize,
    pub light_leak: bool,
    /// Black and white film: grain without colour.
    pub monochrome: bool,
    pub seed: u64,
}

impl DevelopOptions {
    pub fn any(&self) -> bool {
        self.halation || self.grain > 0 || self.light_leak
    }
}

const THRESHOLD: i32 = 215;
const CORE_STRENGTH: f32 = 1.4;
const HALO_STRENGTH: f32 = 2.2;
const CORE_TINT: [f32; 3] = [1.0, 0.5, 0.22];
const HALO_TINT: [f32; 3] = [1.0, 0.18, 0.06];
const GRAIN_STRENGTH: [f32; 5] = [0.0, 7.0, 11.0, 17.0, 18.0];
const GRAIN_CLUMP: [f32; 5] = [1.0, 1.1, 1.6, 2.3, 3.6];
const LEAK_GRID: usize = 8;
const GRAIN_TILE: usize = 512;
const LEAK_COLOURS: [u32; 4] = [0xFF3B1F, 0xFF7A1A, 0xFFB347, 0xFF2E6B];

/// A small map (w x h) of glow strength.
struct Map {
    w: usize,
    h: usize,
    v: Vec<f32>,
}

impl Map {
    fn at(&self, x: f32, y: f32) -> f32 {
        crate::util::sample(&self.v, self.w, self.h, x, y)
    }
}

pub fn develop(img: &mut Image, opt: &DevelopOptions) {
    if !opt.any() {
        return;
    }
    let (w, h) = (img.w, img.h);
    // halation: the core glow from a quarter-size copy, the wide halo from a sixteenth
    let (mut core, mut halo) = (None, None);
    if opt.halation {
        let small = img.resized((w / 4).max(2), (h / 4).max(2));
        let tiny = small.resized((small.w / 4).max(2), (small.h / 4).max(2));
        let mut c = highlights(&small);
        let c0 = c.clone();
        for _ in 0..3 {
            box_blur(&mut c, small.w, small.h, ((small.w as f32 * 0.004).round() as usize).max(1));
        }
        let mut g = highlights(&tiny);
        let g0 = g.clone();
        for _ in 0..3 {
            box_blur(&mut g, tiny.w, tiny.h, ((tiny.w as f32 * 0.025).round() as usize).max(1));
        }
        // the glow only spills outward: a big bright window gets a red edge, not a wash over everything
        for i in 0..c.len() {
            c[i] = (c[i] - c0[i] * 0.9).max(0.0);
        }
        for i in 0..g.len() {
            g[i] = (g[i] - g0[i] * 0.9).max(0.0);
        }
        core = Some(Map { w: small.w, h: small.h, v: c });
        halo = Some(Map { w: tiny.w, h: tiny.h, v: g });
    }

    let mut rnd = Rng::new(opt.seed);
    let noise_seed = (opt.seed ^ (opt.seed >> 32)) as i32;
    let cell = GRAIN_CLUMP[opt.grain] * w as f32 / 3000.0;
    let strength = GRAIN_STRENGTH[opt.grain];

    // light leak: 1-3 soft blobs just outside one edge, stretched along it, worked out on a coarse grid
    let leak = if opt.light_leak { Some(leak_map(w, h, &mut rnd, noise_seed)) } else { None };

    // grain from a tile (luma plus two colour channels), shifted per photo
    let tile_x = rnd.below(GRAIN_TILE as u32) as usize;
    let tile_y = rnd.below(GRAIN_TILE as u32) as usize;
    let tiles = if opt.grain > 0 {
        let mut tl = vec![0f32; GRAIN_TILE * GRAIN_TILE];
        let mut tr = tl.clone();
        let mut tb = tl.clone();
        for ty in 0..GRAIN_TILE {
            for tx in 0..GRAIN_TILE {
                let k = ty * GRAIN_TILE + tx;
                tl[k] = (0.65 * tile_noise(tx, ty, cell, noise_seed) + 0.35 * tile_noise(tx, ty, cell * 2.3, noise_seed + 101)) * strength * 1.6;
                tr[k] = tile_noise(tx, ty, cell, noise_seed + 211) * strength * 0.45;
                tb[k] = tile_noise(tx, ty, cell, noise_seed + 307) * strength * 0.45;
            }
        }
        Some((tl, tr, tb))
    } else {
        None
    };
    let grain_weight: Vec<f32> = (0..256)
        .map(|i| {
            let l = i as f32 / 255.0;
            0.15 + 0.85 * (2.6 * l.sqrt() * (1.0 - l)).min(1.0)
        })
        .collect();

    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        for x in 0..w {
            let (mut r, mut g, mut b) = unpack(row[x]);
            if let (Some(core), Some(halo)) = (&core, &halo) {
                let c = core.at((x as f32 + 0.5) * core.w as f32 / w as f32 - 0.5, (y as f32 + 0.5) * core.h as f32 / h as f32 - 0.5)
                    * CORE_STRENGTH;
                let gl = halo.at((x as f32 + 0.5) * halo.w as f32 / w as f32 - 0.5, (y as f32 + 0.5) * halo.h as f32 / h as f32 - 0.5)
                    * HALO_STRENGTH;
                if c > 0.002 || gl > 0.002 {
                    r = screen(r, c * CORE_TINT[0] + gl * HALO_TINT[0]);
                    g = screen(g, c * CORE_TINT[1] + gl * HALO_TINT[1]);
                    b = screen(b, c * CORE_TINT[2] + gl * HALO_TINT[2]);
                }
            }
            if let Some(lk) = &leak {
                let fx = x as f32 / LEAK_GRID as f32;
                let fy = y as f32 / LEAK_GRID as f32;
                let lr = crate::util::sample(&lk.0, lk.3, lk.4, fx, fy);
                if lr > 0.002 {
                    let lg = crate::util::sample(&lk.1, lk.3, lk.4, fx, fy);
                    let lb = crate::util::sample(&lk.2, lk.3, lk.4, fx, fy);
                    r = screen(r, lr);
                    g = screen(g, lg);
                    b = screen(b, lb);
                    // a light pull towards the leak's hue so it still reads over bright areas
                    let pull = (lr * 0.3).min(0.18);
                    r += (255.0 - r) * pull;
                    g += (255.0 * lg / lr - g) * pull;
                    b += (255.0 * lb / lr - b) * pull;
                }
            }
            if let Some((tl, tr, tb)) = &tiles {
                let li = ((0.299 * r + 0.587 * g + 0.114 * b) as i32).clamp(0, 255) as usize;
                let wt = grain_weight[li];
                let k = ((y + tile_y) & (GRAIN_TILE - 1)) * GRAIN_TILE + ((x + tile_x) & (GRAIN_TILE - 1));
                let d = tl[k] * wt;
                if opt.monochrome {
                    r += d;
                    g += d;
                    b += d;
                } else {
                    let (cr, cb) = (tr[k] * wt, tb[k] * wt);
                    r += d + cr;
                    g += d - 0.5 * (cr + cb);
                    b += d + cb;
                }
            }
            row[x] = pack_f(r, g, b);
        }
    });
}

/// The leak's colour maps on a coarse grid: (r, g, b, grid w, grid h).
fn leak_map(w: usize, h: usize, rnd: &mut Rng, noise_seed: i32) -> (Vec<f32>, Vec<f32>, Vec<f32>, usize, usize) {
    let blobs = 1 + rnd.below(3) as usize;
    let vertical = rnd.below(4) == 0;
    let far = rnd.below(2) == 0;
    let (edge, along) = if vertical { (h as f32, w as f32) } else { (w as f32, h as f32) };
    let mut spec = Vec::new();
    for i in 0..blobs {
        let mut across = edge * (-0.03 + rnd.float() * 0.1);
        let pos = along * (0.1 + rnd.float() * 0.8);
        let s_across = edge * (0.14 + rnd.float() * 0.2);
        let s_along = along * (0.22 + rnd.float() * 0.35);
        if far {
            across = edge - 1.0 - across;
        }
        let (bx, by, sx, sy) = if vertical { (pos, across, s_along, s_across) } else { (across, pos, s_across, s_along) };
        let amp = if i == 0 { 0.42 + rnd.float() * 0.16 } else { 0.22 + rnd.float() * 0.18 };
        let c = LEAK_COLOURS[if rnd.below(10) == 0 { 3 } else { rnd.below(3) as usize }];
        let col = [((c >> 16) & 255) as f32 / 255.0, ((c >> 8) & 255) as f32 / 255.0, (c & 255) as f32 / 255.0];
        spec.push((bx, by, sx, sy, amp, col));
    }
    let noise = w as f32 * 0.14;
    let lw = (w / LEAK_GRID + 2).max(2);
    let lh = (h / LEAK_GRID + 2).max(2);
    let (mut lr, mut lg, mut lb) = (vec![0f32; lw * lh], vec![0f32; lw * lh], vec![0f32; lw * lh]);
    for gy in 0..lh {
        for gx in 0..lw {
            let (x, y) = ((gx * LEAK_GRID) as f32, (gy * LEAK_GRID) as f32);
            let (mut r, mut g, mut b) = (0.0, 0.0, 0.0);
            for (i, &(bx, by, sx, sy, amp, col)) in spec.iter().enumerate() {
                let (dx, dy) = ((x - bx) / sx, (y - by) / sy);
                let d2 = dx * dx + dy * dy;
                if d2 > 9.0 {
                    continue;
                }
                // uneven edges: two slow random fields nudge the falloff
                let s = noise_seed + i as i32 * 7919;
                let rough = 0.82 + 0.2 * value_noise(x / noise, y / noise, s) + 0.1 * value_noise(x / (noise * 0.4), y / (noise * 0.4), s + 53);
                let t = (-d2).exp() * amp * rough;
                r += t * col[0];
                g += t * col[1];
                b += t * col[2];
            }
            let k = gy * lw + gx;
            lr[k] = r;
            lg[k] = g;
            lb[k] = b;
        }
    }
    (lr, lg, lb, lw, lh)
}

fn highlights(img: &Image) -> Vec<f32> {
    img.px
        .iter()
        .map(|&p| {
            let (r, g, b) = (((p >> 16) & 255) as i32, ((p >> 8) & 255) as i32, (p & 255) as i32);
            let lum = (r * 77 + g * 150 + b * 29) >> 8;
            let v = lum.max(r.max(g).max(b) - 20);
            let t = if v <= THRESHOLD { 0.0 } else { (v - THRESHOLD) as f32 / (255 - THRESHOLD) as f32 };
            t * t
        })
        .collect()
}

fn hash(x: i32, y: i32, seed: i32) -> f32 {
    let mut h = x.wrapping_mul(374761393).wrapping_add(y.wrapping_mul(668265263)).wrapping_add(seed.wrapping_mul(1442695041));
    h = (h ^ ((h as u32) >> 13) as i32).wrapping_mul(1274126177);
    h ^= ((h as u32) >> 16) as i32;
    (h & 0xFFFF) as f32 / 32767.5 - 1.0
}

/// Grain noise that wraps at the tile edge, so the tile repeats without a seam.
fn tile_noise(x: usize, y: usize, cell: f32, seed: i32) -> f32 {
    let period = ((GRAIN_TILE as f32 / cell).round() as i32).max(1);
    let gx = x as f32 * period as f32 / GRAIN_TILE as f32;
    let gy = y as f32 * period as f32 / GRAIN_TILE as f32;
    let (xi, yi) = (gx as i32, gy as i32);
    let (mut fx, mut fy) = (gx - xi as f32, gy - yi as f32);
    fx = fx * fx * (3.0 - 2.0 * fx);
    fy = fy * fy * (3.0 - 2.0 * fy);
    let a = hash(xi % period, yi % period, seed);
    let b = hash((xi + 1) % period, yi % period, seed);
    let c = hash(xi % period, (yi + 1) % period, seed);
    let d = hash((xi + 1) % period, (yi + 1) % period, seed);
    let top = a + (b - a) * fx;
    let bot = c + (d - c) * fx;
    top + (bot - top) * fy
}

fn value_noise(x: f32, y: f32, seed: i32) -> f32 {
    let (xi, yi) = (x as i32, y as i32);
    let (mut fx, mut fy) = (x - xi as f32, y - yi as f32);
    fx = fx * fx * (3.0 - 2.0 * fx);
    fy = fy * fy * (3.0 - 2.0 * fy);
    let a = hash(xi, yi, seed);
    let b = hash(xi + 1, yi, seed);
    let c = hash(xi, yi + 1, seed);
    let d = hash(xi + 1, yi + 1, seed);
    let top = a + (b - a) * fx;
    let bot = c + (d - c) * fx;
    top + (bot - top) * fy
}
