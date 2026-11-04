//! Creative looks made from scratch as colour functions and turned into 3D LUTs when first used. They show up as
//! the "Creative" brand in the film list (ids "gen:<n>").

use crate::adjust::{from_oklab, oklab};
use crate::lut::Lut;
use std::sync::{Arc, Mutex};

pub const NAMES: [&str; 24] = [
    "Teal & Orange",
    "Bleach Bypass",
    "Cross Process",
    "Day for Night",
    "Cinema Print",
    "Cinematic Warm",
    "Cinematic Cool",
    "Neon Noir",
    "Golden Hour",
    "Moody Forest",
    "Matte Fade",
    "Pastel",
    "Arctic",
    "Desert",
    "Vaporwave",
    "Lomo",
    "Technicolor 2-Strip",
    "Technicolor 3-Strip",
    "Sepia",
    "Selenium",
    "Cyanotype",
    "B&W Red Filter",
    "B&W Green Filter",
    "B&W Soft Matte",
];

fn luma(c: [f32; 3]) -> f32 {
    0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]
}

/// S-curve: k > 0 adds contrast, k < 0 flattens.
fn s(x: f32, k: f32) -> f32 {
    let x = x.clamp(0.0, 1.0);
    x + k * (x * x * (3.0 - 2.0 * x) - x)
}

fn sat(c: [f32; 3], k: f32) -> [f32; 3] {
    let l = luma(c);
    c.map(|v| l + (v - l) * k)
}

fn mix(a: [f32; 3], b: [f32; 3], t: f32) -> [f32; 3] {
    [0, 1, 2].map(|i| a[i] + (b[i] - a[i]) * t)
}

fn levels(c: [f32; 3], black: f32, white: f32) -> [f32; 3] {
    c.map(|v| black + (white - black) * v)
}

/// Adds an Oklab tint (a, b) weighted by shadows and highlights.
fn split(c: [f32; 3], shadow: [f32; 2], high: [f32; 2]) -> [f32; 3] {
    let [l, a, b] = oklab(c);
    let (ws, wh) = ((1.0 - l).powi(2), l.powi(2));
    from_oklab([l, a + shadow[0] * ws + high[0] * wh, b + shadow[1] * ws + high[1] * wh])
}

/// Pulls hues toward `target` (degrees) when within `reach` degrees, by `k` (0..1).
fn pull_hue(c: [f32; 3], target: f32, reach: f32, k: f32) -> [f32; 3] {
    let [l, a, b] = oklab(c);
    let ch = (a * a + b * b).sqrt();
    let h = b.atan2(a).to_degrees();
    let d = (target - h + 540.0).rem_euclid(360.0) - 180.0;
    if d.abs() > reach {
        return c;
    }
    let w = k * (1.0 - d.abs() / reach);
    let nh = (h + d * w).to_radians();
    from_oklab([l, ch * nh.cos(), ch * nh.sin()])
}

fn tone(c: [f32; 3], lo: [f32; 3], hi: [f32; 3], k: f32) -> [f32; 3] {
    let l = s(luma(c), k);
    mix(lo, hi, l)
}

fn look(n: usize, c: [f32; 3]) -> [f32; 3] {
    match n {
        0 => {
            let c = pull_hue(c, 55.0, 70.0, 0.6);
            let c = pull_hue(c, 205.0, 80.0, 0.7);
            let c = split(c, [-0.03, -0.025], [0.015, 0.03]);
            sat(c.map(|v| s(v, 0.35)), 1.12)
        }
        1 => {
            let c = sat(c, 0.4);
            let c = c.map(|v| s(v, 0.75));
            split(c, [-0.005, -0.01], [0.0, 0.0])
        }
        2 => {
            let c = [s(c[0], 0.7), s(c[1], 0.35) * 1.03, 0.14 + 0.72 * c[2]];
            sat(c, 1.15)
        }
        3 => {
            let c = sat(c, 0.3).map(|v| (v * 0.42).powf(1.05));
            let c = split(c, [-0.02, -0.06], [-0.01, -0.04]);
            c.map(|v| s(v * 1.15, 0.2))
        }
        4 => {
            // a print-film look: dense, warm highlights, teal shadows, greens held back
            let c = pull_hue(c, 165.0, 40.0, 0.4);
            let c = split(c, [-0.025, -0.02], [0.01, 0.025]);
            let c = c.map(|v| s(v, 0.55).powf(1.12));
            sat(c, 0.95)
        }
        5 => {
            let c = split(c, [-0.012, -0.012], [0.015, 0.035]);
            levels(c.map(|v| s(v, 0.3)), 0.02, 0.98)
        }
        6 => {
            let c = split(c, [-0.02, -0.035], [-0.01, -0.02]);
            sat(c.map(|v| s(v, 0.35)), 0.85)
        }
        7 => {
            let c = c.map(|v| s(v, 0.6).powf(1.15));
            let c = split(c, [0.05, -0.05], [-0.04, -0.02]);
            sat(c, 1.25)
        }
        8 => {
            let c = split(c, [0.015, 0.02], [0.025, 0.06]);
            let c = sat(c, 1.15);
            levels(c.map(|v| s(v, 0.2)), 0.015, 1.0)
        }
        9 => {
            let c = pull_hue(c, 120.0, 50.0, 0.5);
            let c = sat(c, 0.75);
            let c = split(c, [-0.02, -0.005], [0.01, 0.015]);
            c.map(|v| s(v, 0.3).powf(1.15))
        }
        10 => {
            let c = sat(c, 0.85);
            let c = split(c, [0.0, 0.0], [0.005, 0.012]);
            levels(c.map(|v| s(v, 0.15)), 0.08, 0.94)
        }
        11 => {
            let c = sat(c, 0.7);
            let c = split(c, [0.01, -0.01], [0.015, 0.005]);
            levels(c.map(|v| s(v, -0.3)), 0.12, 0.98)
        }
        12 => {
            let c = sat(c, 0.6);
            let c = split(c, [-0.015, -0.03], [-0.008, -0.015]);
            levels(c.map(|v| v.powf(0.85)), 0.03, 1.0)
        }
        13 => {
            let c = pull_hue(c, 75.0, 60.0, 0.5);
            let c = pull_hue(c, 200.0, 60.0, 0.4);
            let c = split(c, [0.01, 0.02], [0.02, 0.045]);
            sat(c.map(|v| s(v, 0.4)), 1.05)
        }
        14 => {
            let c = split(c, [0.06, -0.06], [-0.04, -0.01]);
            let c = sat(c, 1.2);
            levels(c, 0.1, 0.97)
        }
        15 => {
            let c = c.map(|v| s(v, 0.8));
            let c = split(c, [-0.01, 0.01], [0.0, 0.02]);
            sat(c, 1.4)
        }
        16 => {
            // two-colour process: red and cyan-green, nothing truly blue or yellow
            let gb = (c[1] + c[2]) / 2.0;
            let c = [c[0], gb * 0.95 + c[1] * 0.05, gb];
            sat(c.map(|v| s(v, 0.35)), 1.2)
        }
        17 => {
            let m = (c[0] + c[1] + c[2]) / 3.0;
            let c = c.map(|v| v + 0.45 * (v - m));
            c.map(|v| s(v, 0.3))
        }
        18 => tone(c, [0.12, 0.07, 0.03], [1.0, 0.93, 0.8], 0.25),
        19 => {
            let l = s(luma(c), 0.35);
            let lo = [0.16, 0.12, 0.17];
            mix(lo, [0.98, 0.97, 0.95], l.powf(0.95))
        }
        20 => tone(c, [0.0, 0.12, 0.3], [0.88, 0.94, 0.96], 0.2),
        21 => {
            let l = 0.75 * c[0] + 0.25 * c[1];
            [s(l, 0.7); 3]
        }
        22 => {
            let l = 0.3 * c[0] + 0.6 * c[1] + 0.1 * c[2];
            [s(l, 0.25); 3]
        }
        _ => {
            let l = s(luma(c), -0.2);
            [0.07 + 0.88 * l; 3]
        }
    }
    .map(|v| v.clamp(0.0, 1.0))
}

/// The LUT for a creative look, made once.
pub fn get(n: usize) -> Option<Arc<Lut>> {
    if n >= NAMES.len() {
        return None;
    }
    static CACHE: Mutex<Vec<Option<Arc<Lut>>>> = Mutex::new(Vec::new());
    let mut cache = CACHE.lock().unwrap();
    if cache.is_empty() {
        cache.resize(NAMES.len(), None);
    }
    Some(cache[n].get_or_insert_with(|| Arc::new(Lut::from_fn(33, |c| look(n, c)))).clone())
}
