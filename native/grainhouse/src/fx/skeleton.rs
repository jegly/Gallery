//! Neon stick figures from the pose model: glowing limbs between the 17 COCO keypoints, a ring for the head and
//! a bright dot at each joint, sized to each person. Points are fractions of the image's width and height.

use super::{glow, tint, Effect};
use crate::img::{pack_f, unpack};

/// COCO keypoint pairs joined by limbs (Ultralytics' skeleton, 0-based).
const LIMBS: [(usize, usize); 12] = [(15, 13), (13, 11), (16, 14), (14, 12), (11, 12), (5, 11), (6, 12), (5, 6), (5, 7), (6, 8), (7, 9), (8, 10)];
const MIN_VIS: f32 = 0.45;

pub struct Skeleton {
    /// x0 y0 x1 y1 half-width (fractions; width as a fraction of the height)
    lines: Vec<[f32; 5]>,
    /// x y radius width (radius 0: a dot)
    rings: Vec<[f32; 4]>,
    dim: f32,
    glow: f32,
    c: [f32; 3],
    people: usize,
}

impl Skeleton {
    pub fn new(dim: f32, glow_level: usize, colour: usize) -> Skeleton {
        Skeleton { lines: Vec::new(), rings: Vec::new(), dim, glow: glow(glow_level), c: tint(colour), people: 0 }
    }

    pub fn people(&self) -> usize {
        self.people
    }

    /// One person: kp is 17 x (x, y, visibility) with x, y fractions of the image; size is the person's height
    /// as a fraction of the image height; aspect is width / height.
    pub fn add_person(&mut self, kp: &[f32; 51], size: f32, aspect: f32) {
        let vis = |k: usize| kp[3 * k + 2] >= MIN_VIS;
        let width = (size * 0.012).max(0.0012);
        for &(a, b) in &LIMBS {
            if vis(a) && vis(b) {
                self.lines.push([kp[3 * a], kp[3 * a + 1], kp[3 * b], kp[3 * b + 1], width]);
            }
        }
        let (mut hx, mut hy, mut n) = (0.0, 0.0, 0.0);
        for k in 0..5 {
            if vis(k) {
                hx += kp[3 * k];
                hy += kp[3 * k + 1];
                n += 1.0;
            }
        }
        if n > 0.0 {
            hx /= n;
            hy /= n;
            let mut r = size * 0.065;
            if vis(3) && vis(4) {
                let (dx, dy) = ((kp[9] - kp[12]) * aspect, kp[10] - kp[13]);
                r = r.max((dx * dx + dy * dy).sqrt() * 0.75);
            }
            self.rings.push([hx, hy, r, width]);
            if vis(5) && vis(6) {
                // the neck, stopping at the ring
                let (mx, my) = ((kp[15] + kp[18]) / 2.0, (kp[16] + kp[19]) / 2.0);
                let (dx, dy) = ((mx - hx) * aspect, my - hy);
                let len = (dx * dx + dy * dy).sqrt();
                if len > r {
                    self.lines.push([hx + dx / len * r / aspect, hy + dy / len * r, mx, my, width]);
                }
            }
        }
        for k in 5..17 {
            if vis(k) {
                self.rings.push([kp[3 * k], kp[3 * k + 1], 0.0, width * 1.9]);
            }
        }
        self.people += 1;
    }
}

struct Buf {
    v: Vec<f32>,
    y0: usize,
    count: usize,
    w: usize,
    glow: f32,
}

impl Buf {
    fn put(&mut self, i: usize, d: f32, half: f32) {
        let core = half + 0.5 - d;
        let mut v = core.clamp(0.0, 1.0);
        let g = half * 4.0 * self.glow;
        if g > 0.0 && d < half + g {
            let t = (1.0 - (d - half) / g).min(1.0);
            v = v.max(0.55 * t * t);
        }
        // cores of crossing lines run white-hot
        if v > self.v[i] {
            self.v[i] = v;
        } else if core > 0.0 {
            self.v[i] = (self.v[i] + v * 0.25).min(1.25);
        }
    }

    fn span(&self, lo: f32, hi: f32) -> Option<(usize, usize)> {
        let a = lo.floor().max(self.y0 as f32) as usize;
        let b = (hi.ceil() as isize).min((self.y0 + self.count) as isize - 1);
        if b < a as isize {
            None
        } else {
            Some((a, b as usize))
        }
    }

    fn seg(&mut self, ax: f32, ay: f32, bx: f32, by: f32, half: f32) {
        let reach = half * (1.0 + 4.0 * self.glow) + 1.0;
        let Some((y0, y1)) = self.span(ay.min(by) - reach, ay.max(by) + reach) else { return };
        let x0 = (ax.min(bx) - reach).floor().max(0.0) as usize;
        let x1 = ((ax.max(bx) + reach).ceil() as usize).min(self.w - 1);
        let (dx, dy) = (bx - ax, by - ay);
        let len2 = dx * dx + dy * dy;
        for yy in y0..=y1 {
            let cy = yy as f32 + 0.5;
            for xx in x0..=x1.max(x0) {
                let cx = xx as f32 + 0.5;
                let t = if len2 > 0.0 { (((cx - ax) * dx + (cy - ay) * dy) / len2).clamp(0.0, 1.0) } else { 0.0 };
                let (ex, ey) = (ax + t * dx - cx, ay + t * dy - cy);
                self.put((yy - self.y0) * self.w + xx, (ex * ex + ey * ey).sqrt(), half);
            }
        }
    }

    fn ring(&mut self, cx: f32, cy: f32, r: f32, half: f32) {
        let reach = r + half * (1.0 + 4.0 * self.glow) + 1.0;
        let Some((y0, y1)) = self.span(cy - reach, cy + reach) else { return };
        let x0 = (cx - reach).floor().max(0.0) as usize;
        let x1 = ((cx + reach).ceil() as usize).min(self.w - 1);
        for yy in y0..=y1 {
            for xx in x0..=x1.max(x0) {
                let (ex, ey) = (xx as f32 + 0.5 - cx, yy as f32 + 0.5 - cy);
                self.put((yy - self.y0) * self.w + xx, ((ex * ex + ey * ey).sqrt() - r).abs(), half);
            }
        }
    }
}

impl Effect for Skeleton {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let count = rows.len() / w;
        let mut b = Buf { v: vec![0.0; w * count], y0, count, w, glow: self.glow };
        let (wf, hf) = (w as f32, h as f32);
        for l in &self.lines {
            b.seg(l[0] * wf, l[1] * hf, l[2] * wf, l[3] * hf, (l[4] * hf).max(0.8));
        }
        for r in &self.rings {
            b.ring(r[0] * wf, r[1] * hf, r[2] * hf, (r[3] * hf).max(if r[2] == 0.0 { 1.2 } else { 0.8 }));
        }
        let k = 1.0 - self.dim * 0.6;
        for (i, p) in rows.iter_mut().enumerate() {
            let l = b.v[i];
            let (mut cr, mut cg, mut cb) = unpack(*p);
            cr *= k;
            cg *= k;
            cb *= k;
            if l > 0.0 {
                cr = 255.0 - (255.0 - cr) * (1.0 - (l * self.c[0]).min(1.0));
                cg = 255.0 - (255.0 - cg) * (1.0 - (l * self.c[1]).min(1.0));
                cb = 255.0 - (255.0 - cb) * (1.0 - (l * self.c[2]).min(1.0));
            }
            *p = pack_f(cr, cg, cb);
        }
    }
}
