//! Wireframe: a glowing triangle mesh over each subject (points scattered over the figure, closer along the
//! outline, joined by Delaunay triangles kept inside the figure), with MediaPipe face meshes on faces.
//! Also the Points look (a point cloud) and the faces-only Face mesh look.

use super::contours::{line, signed_distance, soft};
use super::{alpha_from_mask, delaunay, glow, tint, Effect};
use crate::facemesh::MeshEdges;
use crate::img::{pack_f, unpack};
use crate::util::{lerp2, row_of, Cols, Rng};
use std::collections::HashSet;

pub const FACE_STYLES: [&str; 3] = ["Mesh", "Points", "Mesh + glowing eyes"];
pub const FACE_MESH: usize = 0;
pub const FACE_POINTS: usize = 1;
pub const FACE_EYES: usize = 2;

pub struct Mesh {
    mw: usize,
    mh: usize,
    alpha: Vec<f32>,
    dist: Vec<f32>,
    dim: f32,
    glow: f32,
    c: [f32; 3],
    points_only: bool,
    px: Vec<f32>,
    py: Vec<f32>,
    hidden: Vec<bool>,
    edges: Vec<[usize; 2]>,
    /// face lines (x0, y0, x1, y1, strength), dots (x, y, size), glowing eyes (x, y, core, glow), in mask px
    lines: Vec<[f32; 5]>,
    dots: Vec<[f32; 3]>,
    blobs: Vec<[f32; 4]>,
    ovals: Vec<(Vec<f32>, Vec<f32>, [f32; 4])>,
    face_dim: f32,
    faces: usize,
}

impl Mesh {
    /// With `points_only`, a point cloud: closer points, no edges and no outline. A mask of zeros gives an
    /// empty mesh for faces only.
    pub fn new(mask: &[u8], mw: usize, mh: usize, dim: f32, glow_level: usize, colour: usize, seed: u64, points_only: bool) -> Mesh {
        let (inside, dist) = signed_distance(mask, mw, mh);
        let s = (mw as f32 / if points_only { 90.0 } else { 42.0 }).max(if points_only { 3.0 } else { 5.0 });
        let mut rnd = Rng::new(seed);
        let mut pts = PointSet::new(mw, mh, s);
        // points: along the outline first, then filling the inside
        let mut edge = Vec::new();
        for y in 1..mh.saturating_sub(1) {
            for x in 1..mw.saturating_sub(1) {
                let i = y * mw + x;
                if inside[i] && (!inside[i - 1] || !inside[i + 1] || !inside[i - mw] || !inside[i + mw]) {
                    edge.push(i);
                }
            }
        }
        for i in (1..edge.len()).rev() {
            let j = rnd.below(i as u32 + 1) as usize;
            edge.swap(i, j);
        }
        for &i in &edge {
            pts.try_add((i % mw) as f32 + 0.5, (i / mw) as f32 + 0.5, s * 0.7);
        }
        let area = inside.iter().filter(|&&b| b).count();
        let tries = (area as f32 / (s * s) * 30.0) as usize;
        for _ in 0..tries {
            let (x, y) = (rnd.float() * mw as f32, rnd.float() * mh as f32);
            let i = (y as usize).min(mh - 1) * mw + (x as usize).min(mw - 1);
            if dist[i] < s * 0.35 {
                continue;
            }
            // a little closer together near the edge, roomier in the middle
            pts.try_add(x, y, s * (0.95 + 0.35 * (dist[i] / (s * 3.0)).min(1.0)));
        }
        let (px, py): (Vec<f32>, Vec<f32>) = pts.all.into_iter().unzip();
        let mut mesh = Mesh {
            mw,
            mh,
            alpha: alpha_from_mask(mask),
            dist,
            dim,
            glow: glow(glow_level),
            c: tint(colour),
            points_only,
            hidden: vec![false; px.len()],
            px,
            py,
            edges: Vec::new(),
            lines: Vec::new(),
            dots: Vec::new(),
            blobs: Vec::new(),
            ovals: Vec::new(),
            face_dim: 0.0,
            faces: 0,
        };
        // triangles, keeping only those inside a figure
        let max_len = s * 3.4;
        let mut seen = HashSet::new();
        for t in delaunay::triangulate(&mesh.px, &mesh.py) {
            if !mesh.inside_tri(t, max_len) {
                continue;
            }
            for (a, b) in [(t[0], t[1]), (t[1], t[2]), (t[2], t[0])] {
                let key = if a < b { (a, b) } else { (b, a) };
                if seen.insert(key) {
                    mesh.edges.push([a, b]);
                }
            }
        }
        mesh
    }

    pub fn points(&self) -> usize {
        self.px.len()
    }

    pub fn faces(&self) -> usize {
        self.faces
    }

    /// Dims each face under its mesh (used when only faces are meshed).
    pub fn set_face_dim(&mut self, d: f32) {
        self.face_dim = d;
    }

    fn len(&self, a: usize, b: usize) -> f32 {
        ((self.px[a] - self.px[b]).powi(2) + (self.py[a] - self.py[b]).powi(2)).sqrt()
    }

    fn is_in(&self, x: f32, y: f32) -> bool {
        let (ix, iy) = ((x as usize).min(self.mw - 1), (y as usize).min(self.mh - 1));
        self.dist[iy * self.mw + ix] > -0.5
    }

    fn inside_tri(&self, t: [usize; 3], max_len: f32) -> bool {
        let [a, b, c] = t;
        if self.len(a, b) > max_len || self.len(b, c) > max_len || self.len(c, a) > max_len {
            return false;
        }
        let (px, py) = (&self.px, &self.py);
        self.is_in((px[a] + px[b] + px[c]) / 3.0, (py[a] + py[b] + py[c]) / 3.0)
            && self.is_in((px[a] + px[b]) / 2.0, (py[a] + py[b]) / 2.0)
            && self.is_in((px[b] + px[c]) / 2.0, (py[b] + py[c]) / 2.0)
            && self.is_in((px[c] + px[a]) / 2.0, (py[c] + py[a]) / 2.0)
    }

    /// Puts a face mesh over the body mesh: body points and edges inside the face outline go, the face's own
    /// edges (eyes, lips and brows brighter) and points come in. `pts`: 478 landmarks in mask pixels.
    pub fn add_face(&mut self, pts: &[f32], edges: &MeshEdges, style: usize) {
        let ox: Vec<f32> = edges.oval.iter().map(|&i| pts[2 * i]).collect();
        let oy: Vec<f32> = edges.oval.iter().map(|&i| pts[2 * i + 1]).collect();
        let bounds = [
            ox.iter().cloned().fold(f32::MAX, f32::min),
            oy.iter().cloned().fold(f32::MAX, f32::min),
            ox.iter().cloned().fold(f32::MIN, f32::max),
            oy.iter().cloned().fold(f32::MIN, f32::max),
        ];
        for i in 0..self.px.len() {
            if in_polygon(self.px[i], self.py[i], &ox, &oy) {
                self.hidden[i] = true;
            }
        }
        let (px, py, hidden) = (&self.px, &self.py, &self.hidden);
        self.edges.retain(|e| {
            let (mx, my) = ((px[e[0]] + px[e[1]]) / 2.0, (py[e[0]] + py[e[1]]) / 2.0);
            !(hidden[e[0]] || hidden[e[1]] || in_polygon(mx, my, &ox, &oy))
        });
        self.ovals.push((ox, oy, bounds));
        self.faces += 1;
        let style = if self.points_only { FACE_POINTS } else { style };
        if style == FACE_POINTS {
            for i in 0..pts.len() / 2 {
                self.dots.push([pts[2 * i], pts[2 * i + 1], 1.5]);
            }
            return;
        }
        if style == FACE_EYES && pts.len() >= 478 * 2 {
            for c in [468, 473] {
                let r: f32 = (1..=4).map(|k| ((pts[2 * (c + k)] - pts[2 * c]).powi(2) + (pts[2 * (c + k) + 1] - pts[2 * c + 1]).powi(2)).sqrt() / 4.0).sum();
                self.blobs.push([pts[2 * c], pts[2 * c + 1], r * 0.9, r * 4.5]);
            }
        }
        let features: HashSet<(usize, usize)> = edges.features.chunks(2).map(|e| (e[0].min(e[1]), e[0].max(e[1]))).collect();
        for e in edges.edges.chunks(2) {
            let (a, b) = (e[0], e[1]);
            let strong = features.contains(&(a.min(b), a.max(b)));
            self.lines.push([pts[2 * a], pts[2 * a + 1], pts[2 * b], pts[2 * b + 1], if strong { 1.0 } else { 0.55 }]);
        }
        for i in 0..pts.len() / 2 {
            self.dots.push([pts[2 * i], pts[2 * i + 1], 1.1]);
        }
    }

    fn inside_face(&self, x: f32, y: f32) -> bool {
        self.ovals.iter().any(|(ox, oy, b)| x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] && in_polygon(x, y, ox, oy))
    }
}

fn in_polygon(x: f32, y: f32, ox: &[f32], oy: &[f32]) -> bool {
    let mut inside = false;
    let mut j = ox.len() - 1;
    for i in 0..ox.len() {
        if (oy[i] > y) != (oy[j] > y) && x < (ox[j] - ox[i]) * (y - oy[i]) / (oy[j] - oy[i]) + ox[i] {
            inside = !inside;
        }
        j = i;
    }
    inside
}

/// A strip's light buffer, drawn into with anti-aliased lines and dots.
struct Light {
    v: Vec<f32>,
    y0: usize,
    count: usize,
    w: usize,
}

impl Light {
    fn put(&mut self, x: usize, y: usize, v: f32) {
        let i = (y - self.y0) * self.w + x;
        if v > self.v[i] {
            self.v[i] = v;
        }
    }

    fn rows(&self, a: f32, b: f32, pad: f32) -> Option<(usize, usize)> {
        let lo = (a.min(b) - pad).floor().max(self.y0 as f32) as usize;
        let hi = ((a.max(b) + pad).ceil() as isize).min((self.y0 + self.count) as isize - 1);
        if hi < lo as isize {
            None
        } else {
            Some((lo, hi as usize))
        }
    }

    fn line(&mut self, ax: f32, ay: f32, bx: f32, by: f32, half: f32, strength: f32) {
        let pad = half + 1.0;
        let Some((y0, y1)) = self.rows(ay, by, pad) else { return };
        let x0 = (ax.min(bx) - pad).floor().max(0.0) as usize;
        let x1 = ((ax.max(bx) + pad).ceil() as usize).min(self.w - 1);
        let (dx, dy) = (bx - ax, by - ay);
        let len2 = dx * dx + dy * dy;
        for yy in y0..=y1 {
            let cy = yy as f32 + 0.5;
            for xx in x0..=x1.max(x0) {
                let cx = xx as f32 + 0.5;
                let t = if len2 > 0.0 { (((cx - ax) * dx + (cy - ay) * dy) / len2).clamp(0.0, 1.0) } else { 0.0 };
                let (ex, ey) = (ax + t * dx - cx, ay + t * dy - cy);
                self.put(xx, yy, line((ex * ex + ey * ey).sqrt(), half) * strength);
            }
        }
    }

    fn dot(&mut self, cx: f32, cy: f32, r: f32, strength: f32) {
        self.blob(cx, cy, r - 0.5, 0.0, strength);
    }

    /// A glowing disc: a solid core of radius `core` and a soft glow out to `glow` (0 = none).
    fn blob(&mut self, cx: f32, cy: f32, core: f32, glow: f32, strength: f32) {
        let r = core.max(glow) + 1.5;
        let Some((y0, y1)) = self.rows(cy, cy, r) else { return };
        let x0 = (cx - r).floor().max(0.0) as usize;
        let x1 = ((cx + r).ceil() as usize).min(self.w - 1);
        for yy in y0..=y1 {
            for xx in x0..=x1.max(x0) {
                let (ex, ey) = (xx as f32 + 0.5 - cx, yy as f32 + 0.5 - cy);
                let d = (ex * ex + ey * ey).sqrt();
                let mut v = line(d, core);
                if glow > 0.0 {
                    v = v.max(0.9 * soft(d, glow));
                }
                self.put(xx, yy, v * strength);
            }
        }
    }
}

impl Effect for Mesh {
    fn apply(&self, rows: &mut [u32], y0: usize, w: usize, h: usize) {
        let count = rows.len() / w;
        let (sx, sy) = (w as f32 / self.mw as f32, h as f32 / self.mh as f32);
        let half = (w as f32 / 2000.0).max(0.55);
        let dot = half * 2.4;
        let outline_half = half * 1.5;
        let mut light = Light { v: vec![0.0; w * count], y0, count, w };
        if !self.points_only {
            for e in &self.edges {
                light.line(self.px[e[0]] * sx, self.py[e[0]] * sy, self.px[e[1]] * sx, self.py[e[1]] * sy, half, 0.8);
            }
        }
        for l in &self.lines {
            light.line(l[0] * sx, l[1] * sy, l[2] * sx, l[3] * sy, half * 0.7, l[4]);
        }
        for i in 0..self.px.len() {
            if !self.hidden[i] {
                light.dot(self.px[i] * sx, self.py[i] * sy, if self.points_only { dot * 0.8 } else { dot }, 1.0);
            }
        }
        for d in &self.dots {
            light.dot(d[0] * sx, d[1] * sy, half * d[2], 0.8);
        }
        for b in &self.blobs {
            light.blob(b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sx, 1.0);
        }
        let cols = Cols::new(w, self.mw);
        for (r, row) in rows.chunks_mut(w).enumerate() {
            let yy = y0 + r;
            let (iy, iy2, wy) = row_of(yy, h, self.mh);
            let (r0, r1) = (iy * self.mw, iy2 * self.mw);
            let my = yy as f32 / sy;
            let face_row = self.face_dim > 0.0 && self.ovals.iter().any(|(_, _, b)| my >= b[1] && my <= b[3]);
            for x in 0..w {
                let (a0, a1, b0, b1) = (r0 + cols.x0[x], r0 + cols.x1[x], r1 + cols.x0[x], r1 + cols.x1[x]);
                let wx = cols.fx[x];
                let mut c = row[x];
                if face_row && self.inside_face(x as f32 / sx, my) {
                    let (cr, cg, cb) = unpack(c);
                    let k = 1.0 - self.face_dim;
                    c = pack_f(cr * k, cg * k, cb * k);
                    row[x] = c;
                }
                let l = light.v[r * w + x];
                let dpx = lerp2(&self.dist, a0, a1, b0, b1, wx, wy) * sx;
                if dpx < -outline_half * 8.0 && l == 0.0 {
                    continue;
                }
                let a = lerp2(&self.alpha, a0, a1, b0, b1, wx, wy);
                let outline = if self.points_only { 0.0 } else { line(dpx.abs(), outline_half) };
                let halo = if self.points_only { 0.0 } else { self.glow * 0.4 * soft(dpx.abs(), outline_half * 7.0) * (1.0 - a * 0.7) };
                let lit = (outline.max(l * a.max(0.6)) + halo).min(1.0);
                if lit < 0.002 && a < 0.002 {
                    continue;
                }
                let (mut cr, mut cg, mut cb) = unpack(c);
                let keep = 1.0 - self.dim * a;
                cr *= keep;
                cg *= keep;
                cb *= keep;
                cr = 255.0 - (255.0 - cr) * (1.0 - lit * self.c[0]);
                cg = 255.0 - (255.0 - cg) * (1.0 - lit * self.c[1]);
                cb = 255.0 - (255.0 - cb) * (1.0 - lit * self.c[2]);
                row[x] = pack_f(cr, cg, cb);
            }
        }
    }
}

/// Points with a minimum spacing, checked through a coarse grid.
struct PointSet {
    cell: f32,
    gw: usize,
    gh: usize,
    grid: Vec<Vec<usize>>,
    all: Vec<(f32, f32)>,
}

impl PointSet {
    fn new(w: usize, h: usize, s: f32) -> PointSet {
        let gw = (w as f32 / s) as usize + 2;
        let gh = (h as f32 / s) as usize + 2;
        PointSet { cell: s, gw, gh, grid: vec![Vec::new(); gw * gh], all: Vec::new() }
    }

    fn try_add(&mut self, x: f32, y: f32, min: f32) -> bool {
        let (gx, gy) = ((x / self.cell) as usize, (y / self.cell) as usize);
        let reach = (min / self.cell).ceil() as usize;
        let min2 = min * min;
        for j in gy.saturating_sub(reach)..=(gy + reach).min(self.gh - 1) {
            for i in gx.saturating_sub(reach)..=(gx + reach).min(self.gw - 1) {
                for &k in &self.grid[j * self.gw + i] {
                    let (px, py) = self.all[k];
                    if (px - x).powi(2) + (py - y).powi(2) < min2 {
                        return false;
                    }
                }
            }
        }
        let k = gy.min(self.gh - 1) * self.gw + gx.min(self.gw - 1);
        self.grid[k].push(self.all.len());
        self.all.push((x, y));
        true
    }
}
