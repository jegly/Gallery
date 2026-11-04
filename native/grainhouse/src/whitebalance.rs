//! White balance for RAW files: the as-shot colour temperature (worked out from the camera's white balance and
//! colour matrix) and the correction to any other temperature and tint, as a Bradford adaptation in linear sRGB.

pub const PRESETS: [&str; 8] = ["As shot", "Daylight", "Cloudy", "Shade", "Tungsten", "Fluorescent", "Flash", "Custom"];
/// Kelvin for each preset (0 = as shot or custom).
pub const PRESET_KELVIN: [f32; 8] = [0.0, 5500.0, 6500.0, 7500.0, 3200.0, 4000.0, 5500.0, 0.0];
pub const CUSTOM: usize = 7;

const BRADFORD: [[f32; 3]; 3] = [[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]];
const SRGB_TO_XYZ: [[f32; 3]; 3] = [[0.4124, 0.3576, 0.1805], [0.2126, 0.7152, 0.0722], [0.0193, 0.1192, 0.9505]];

type M3 = [[f32; 3]; 3];

fn mul(a: &M3, b: &M3) -> M3 {
    let mut m = [[0f32; 3]; 3];
    for i in 0..3 {
        for j in 0..3 {
            m[i][j] = (0..3).map(|k| a[i][k] * b[k][j]).sum();
        }
    }
    m
}

pub fn apply(m: &M3, v: [f32; 3]) -> [f32; 3] {
    [0, 1, 2].map(|i| m[i][0] * v[0] + m[i][1] * v[1] + m[i][2] * v[2])
}

pub fn invert(m: &M3) -> Option<M3> {
    let d = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0]);
    if d.abs() < 1e-12 {
        return None;
    }
    let c = |a: usize, b: usize, e: usize, f: usize| m[a][b] * m[e][f] - m[a][f] * m[e][b];
    Some([
        [c(1, 1, 2, 2) / d, -c(0, 1, 2, 2) / d, c(0, 1, 1, 2) / d],
        [-c(1, 0, 2, 2) / d, c(0, 0, 2, 2) / d, -c(0, 0, 1, 2) / d],
        [c(1, 0, 2, 1) / d, -c(0, 0, 2, 1) / d, c(0, 0, 1, 1) / d],
    ])
}

/// Chromaticity of a black body (Kim et al.'s fit, 1667 K to 25000 K).
pub fn planck_xy(t: f32) -> (f32, f32) {
    let t = t.clamp(1667.0, 25000.0) as f64;
    let x = if t <= 4000.0 {
        -0.2661239e9 / t.powi(3) - 0.2343589e6 / t.powi(2) + 0.8776956e3 / t + 0.179910
    } else {
        -3.0258469e9 / t.powi(3) + 2.1070379e6 / t.powi(2) + 0.2226347e3 / t + 0.240390
    };
    let y = if t <= 2222.0 {
        -1.1063814 * x.powi(3) - 1.34811020 * x.powi(2) + 2.18555832 * x - 0.20219683
    } else if t <= 4000.0 {
        -0.9549476 * x.powi(3) - 1.37418593 * x.powi(2) + 2.09137015 * x - 0.16748867
    } else {
        3.0817580 * x.powi(3) - 5.87338670 * x.powi(2) + 3.75112997 * x - 0.37001483
    };
    (x as f32, y as f32)
}

/// Colour temperature nearest to a chromaticity (searching along the black-body line).
pub fn kelvin_of(xy: (f32, f32)) -> f32 {
    let d = |t: f32| {
        let p = planck_xy(t);
        (p.0 - xy.0).powi(2) + (p.1 - xy.1).powi(2)
    };
    // in mireds the line is spaced evenly enough for a coarse scan then a fine one
    let mut best = 5000.0;
    for m in (40..600).map(|m| m as f32) {
        if d(1e6 / m) < d(best) {
            best = 1e6 / m;
        }
    }
    let mut step = best * 0.02;
    while step > 1.0 {
        for t in [best - step, best + step] {
            if d(t) < d(best) {
                best = t;
            }
        }
        step *= 0.5;
    }
    best
}

/// Unit vector across the black-body line at t, pointing to green.
fn green_dir(t: f32) -> (f32, f32) {
    let (a, b) = (planck_xy(t * 0.98), planck_xy(t * 1.02));
    let (tx, ty) = (b.0 - a.0, b.1 - a.1);
    let n = (tx * tx + ty * ty).sqrt().max(1e-9);
    let (nx, ny) = (-ty / n, tx / n);
    if ny < 0.0 { (-nx, -ny) } else { (nx, ny) }
}

/// The as-shot white point, from the camera's white balance multipliers and its XYZ-to-camera matrix (D65).
pub fn as_shot_xy(wb: [f32; 4], xyz_to_cam: &M3) -> Option<(f32, f32)> {
    if wb[0].is_nan() || wb[0] <= 0.0 || wb[1] <= 0.0 || wb[2] <= 0.0 {
        return None;
    }
    let cam_white = [1.0 / wb[0], 1.0 / wb[1], 1.0 / wb[2]];
    let xyz = apply(&invert(xyz_to_cam)?, cam_white);
    let s = xyz[0] + xyz[1] + xyz[2];
    if s <= 0.0 || !s.is_finite() {
        return None;
    }
    Some((xyz[0] / s, xyz[1] / s))
}

fn xyz_of(xy: (f32, f32)) -> [f32; 3] {
    [xy.0 / xy.1, 1.0, (1.0 - xy.0 - xy.1) / xy.1]
}

/// The linear sRGB matrix that re-balances a picture shot under `shot` (white point) for a temperature in kelvin
/// and a tint (-1 green .. 1 magenta). At the as-shot temperature with no tint it changes nothing.
pub fn correction(shot: (f32, f32), kelvin: f32, tint: f32) -> M3 {
    // keep the as-shot white's own distance from the black-body line, then add the tint
    let t0 = kelvin_of(shot);
    let p0 = planck_xy(t0);
    let (gx, gy) = green_dir(t0);
    let off = (shot.0 - p0.0) * gx + (shot.1 - p0.1) * gy;
    let p = planck_xy(kelvin);
    let (gx, gy) = green_dir(kelvin);
    // a greener light to neutralise makes the picture more magenta, as in Lightroom
    let k = off + tint.clamp(-1.0, 1.0) * 0.03;
    let target = (p.0 + gx * k, p.1 + gy * k);
    let (l0, l1) = (apply(&BRADFORD, xyz_of(shot)), apply(&BRADFORD, xyz_of(target)));
    let scale = [[l0[0] / l1[0], 0.0, 0.0], [0.0, l0[1] / l1[1], 0.0], [0.0, 0.0, l0[2] / l1[2]]];
    let to_lms = mul(&BRADFORD, &SRGB_TO_XYZ);
    let back = invert(&to_lms).unwrap();
    let m = mul(&back, &mul(&scale, &to_lms));
    // keep mid-grey's brightness the same
    let y = apply(&SRGB_TO_XYZ, apply(&m, [1.0, 1.0, 1.0]))[1];
    m.map(|r| r.map(|v| v / y))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn as_shot_is_unchanged_and_warmer_is_warmer() {
        let shot = planck_xy(5200.0);
        let shot = (shot.0 + 0.003, shot.1 + 0.004);
        let t0 = kelvin_of(shot);
        let m = correction(shot, t0, 0.0);
        let g = apply(&m, [0.5, 0.5, 0.5]);
        assert!(g.iter().all(|v| (v - 0.5).abs() < 0.005), "{g:?}");
        let warm = apply(&correction(shot, t0 + 2000.0, 0.0), [0.5, 0.5, 0.5]);
        assert!(warm[0] > warm[2] + 0.03, "{warm:?}");
        let magenta = apply(&correction(shot, t0, 0.5), [0.5, 0.5, 0.5]);
        assert!(magenta[1] < magenta[0] && magenta[1] < magenta[2], "{magenta:?}");
        assert!((kelvin_of(planck_xy(3200.0)) - 3200.0).abs() < 20.0);
    }
}
