//! Face-mesh maths for MediaPipe's face detector (BlazeFace short range) and 478-point landmark model:
//! affine crops, anchor decoding, the landmark crop, and the mesh's edge list (Apache 2.0).

use crate::img::Image;

pub const DET_SIZE: usize = 128;
pub const LM_SIZE: usize = 256;
pub const POINTS: usize = 478;

/// The mesh's edges (point index pairs), the face outline in loop order, and the eye/lip/brow edges.
pub struct MeshEdges {
    pub edges: Vec<usize>,
    pub oval: Vec<usize>,
    pub features: Vec<usize>,
}

impl MeshEdges {
    pub fn load(data: &[u8]) -> MeshEdges {
        let mut pos = 0;
        let mut rd = || {
            let v = i16::from_le_bytes([data[pos], data[pos + 1]]) as usize;
            pos += 2;
            v
        };
        let n = rd();
        let edges: Vec<usize> = (0..n * 2).map(|_| rd()).collect();
        let n = rd();
        let oval: Vec<usize> = (0..n).map(|_| rd()).collect();
        let n = rd();
        let features: Vec<usize> = (0..n * 2).map(|_| rd()).collect();
        MeshEdges { edges, oval, features }
    }
}

/// A face, in the coordinates of whatever it was found in. Keypoints: eyes, nose, mouth, ears.
#[derive(Clone, Debug)]
pub struct Face {
    pub score: f32,
    pub cx: f32,
    pub cy: f32,
    pub w: f32,
    pub h: f32,
    pub kp: [f32; 12],
}

/// An affine map from output pixel coordinates to source coordinates:
/// sx = m0 x + m1 y + m2, sy = m3 x + m4 y + m5.
pub type Affine = [f64; 6];

/// Crops src into RGB bytes ow x oh through the map m (bilinear, black outside).
pub fn warp(src: &Image, m: &Affine, ow: usize, oh: usize) -> Vec<u8> {
    let mut out = vec![0u8; ow * oh * 3];
    for y in 0..oh {
        for x in 0..ow {
            let fx = m[0] * (x as f64 + 0.5) + m[1] * (y as f64 + 0.5) + m[2] - 0.5;
            let fy = m[3] * (x as f64 + 0.5) + m[4] * (y as f64 + 0.5) + m[5] - 0.5;
            let (ix, iy) = (fx.floor() as i64, fy.floor() as i64);
            let (ax, ay) = (fx - ix as f64, fy - iy as f64);
            let mut acc = [0f64; 3];
            for k in 0..4 {
                let (px, py) = (ix + (k & 1) as i64, iy + (k >> 1) as i64);
                let wgt = (if k & 1 == 1 { ax } else { 1.0 - ax }) * (if k >> 1 == 1 { ay } else { 1.0 - ay });
                if px < 0 || py < 0 || px >= src.w as i64 || py >= src.h as i64 || wgt == 0.0 {
                    continue;
                }
                let c = src.px[py as usize * src.w + px as usize];
                acc[0] += ((c >> 16) & 255) as f64 * wgt;
                acc[1] += ((c >> 8) & 255) as f64 * wgt;
                acc[2] += (c & 255) as f64 * wgt;
            }
            let o = (y * ow + x) * 3;
            for c in 0..3 {
                out[o + c] = acc[c].round().min(255.0) as u8;
            }
        }
    }
    out
}

pub fn square_crop(x0: f64, y0: f64, side: f64, size: usize) -> Affine {
    let s = side / size as f64;
    [s, 0.0, x0, 0.0, s, y0]
}

/// The best face in the detector's outputs (classificators at strides 8 and 16, then regressors, CHW).
pub fn detect(out: &[f32], min_score: f32) -> Option<Face> {
    let cls8 = 0;
    let cls16 = cls8 + 2 * 256;
    let reg8 = cls16 + 6 * 64;
    let reg16 = reg8 + 32 * 256;
    let mut best = (-1e9f32, 0usize, 0usize, 0usize); // logit, layer stride, anchor, cell
    for a in 0..2 {
        for cell in 0..256 {
            let v = out[cls8 + a * 256 + cell];
            if v > best.0 {
                best = (v, 8, a, cell);
            }
        }
    }
    for a in 0..6 {
        for cell in 0..64 {
            let v = out[cls16 + a * 64 + cell];
            if v > best.0 {
                best = (v, 16, a, cell);
            }
        }
    }
    let score = 1.0 / (1.0 + (-best.0.clamp(-100.0, 100.0)).exp());
    if score < min_score {
        return None;
    }
    let (grid, base) = if best.1 == 8 { (16, reg8) } else { (8, reg16) };
    let plane = grid * grid;
    let (a, cell) = (best.2, best.3);
    let ax = ((cell % grid) as f32 + 0.5) / grid as f32 * DET_SIZE as f32;
    let ay = ((cell / grid) as f32 + 0.5) / grid as f32 * DET_SIZE as f32;
    let r: Vec<f32> = (0..16).map(|v| out[base + (a * 16 + v) * plane + cell]).collect();
    let mut kp = [0f32; 12];
    for k in 0..6 {
        kp[2 * k] = ax + r[4 + 2 * k];
        kp[2 * k + 1] = ay + r[5 + 2 * k];
    }
    Some(Face { score, cx: ax + r[0], cy: ay + r[1], w: r[2], h: r[3], kp })
}

/// MediaPipe's face crop for the landmark model: centred on the detection, 1.5x its size, turned so the
/// eyes are level. Returns the map from landmark input pixels to source pixels.
pub fn landmark_crop(f: &Face) -> Affine {
    let angle = -(-(f.kp[3] - f.kp[1]) as f64).atan2((f.kp[2] - f.kp[0]) as f64);
    let size = f.w.max(f.h) as f64 * 1.5;
    let s = size / LM_SIZE as f64;
    let (c, sn) = (angle.cos() * s, angle.sin() * s);
    let half = LM_SIZE as f64 / 2.0;
    [c, -sn, f.cx as f64 - c * half + sn * half, sn, c, f.cy as f64 - sn * half - c * half]
}

/// Face presence from the landmark model's outputs, 0..1.
pub fn presence(out: &[f32]) -> f32 {
    1.0 / (1.0 + (-out[POINTS * 3]).exp())
}

/// The landmarks in source pixels (x, y pairs), given the crop map.
pub fn landmarks(out: &[f32], m: &Affine) -> Vec<f32> {
    let mut p = vec![0f32; POINTS * 2];
    for i in 0..POINTS {
        let (u, v) = (out[i * 3] as f64, out[i * 3 + 1] as f64);
        p[2 * i] = (m[0] * u + m[1] * v + m[2]) as f32;
        p[2 * i + 1] = (m[3] * u + m[4] * v + m[5]) as f32;
    }
    p
}

/// A face found in a DET_SIZE crop (cropped by square map m) in source pixels.
pub fn to_source(f: &Face, m: &Affine) -> Face {
    let mut kp = [0f32; 12];
    for k in 0..6 {
        kp[2 * k] = (m[0] * f.kp[2 * k] as f64 + m[2]) as f32;
        kp[2 * k + 1] = (m[4] * f.kp[2 * k + 1] as f64 + m[5]) as f32;
    }
    Face {
        score: f.score,
        cx: (m[0] * f.cx as f64 + m[2]) as f32,
        cy: (m[4] * f.cy as f64 + m[5]) as f32,
        w: (m[0] * f.w as f64) as f32,
        h: (m[4] * f.h as f64) as f32,
        kp,
    }
}

/// Square crops covering a w x h image at two sizes (the short side, then half of it).
pub fn tiles(w: usize, h: usize) -> Vec<Affine> {
    let mut out = Vec::new();
    let big = w.min(h) as f64;
    for side in [big, big / 2.0] {
        let nx = (((w as f64 - side) / side).ceil() as usize + 1).max(1);
        let ny = (((h as f64 - side) / side).ceil() as usize + 1).max(1);
        for j in 0..ny {
            for i in 0..nx {
                let x = if nx == 1 { (w as f64 - side) / 2.0 } else { (w as f64 - side) * i as f64 / (nx - 1) as f64 };
                let y = if ny == 1 { (h as f64 - side) / 2.0 } else { (h as f64 - side) * j as f64 / (ny - 1) as f64 };
                out.push(square_crop(x, y, side, DET_SIZE));
            }
        }
    }
    out
}

/// Drops overlapping duplicates (keeping the higher score), biggest first, at most max.
pub fn merge(mut faces: Vec<Face>, max: usize) -> Vec<Face> {
    faces.sort_by(|a, b| b.score.total_cmp(&a.score));
    let mut kept: Vec<Face> = Vec::new();
    for f in faces {
        if !kept.iter().any(|k| iou(&f, k) > 0.3) {
            kept.push(f);
        }
    }
    kept.sort_by(|a, b| (b.w * b.h).total_cmp(&(a.w * a.h)));
    kept.truncate(max);
    kept
}

fn iou(a: &Face, b: &Face) -> f32 {
    let iw = (a.cx + a.w / 2.0).min(b.cx + b.w / 2.0) - (a.cx - a.w / 2.0).max(b.cx - b.w / 2.0);
    let ih = (a.cy + a.h / 2.0).min(b.cy + b.h / 2.0) - (a.cy - a.h / 2.0).max(b.cy - b.h / 2.0);
    if iw <= 0.0 || ih <= 0.0 {
        return 0.0;
    }
    let inter = iw * ih;
    inter / (a.w * a.h + b.w * b.h - inter)
}
