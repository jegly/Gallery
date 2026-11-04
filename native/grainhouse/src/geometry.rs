//! Quarter turns, straightening and cropping. Kept as numbers and applied when rendering, so they can be changed
//! at any time without losing anything.

use crate::img::Image;
use rayon::prelude::*;

#[derive(Clone, Copy, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct Geometry {
    /// quarter turns clockwise, 0..3
    pub quarter: u8,
    /// straighten in degrees, positive turns the picture clockwise
    pub angle: f32,
    /// the crop, as fractions of the turned and straightened frame
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
    /// lens distortion: above 0 straightens barrel distortion (wide lenses), below 0 pincushion (-1..1)
    pub distortion: f32,
    /// perspective: above 0 straightens verticals that lean in at the top (-1..1)
    pub vertical: f32,
    /// perspective sideways (-1..1)
    pub horizontal: f32,
}

impl Default for Geometry {
    fn default() -> Self {
        Geometry { quarter: 0, angle: 0.0, x: 0.0, y: 0.0, w: 1.0, h: 1.0, distortion: 0.0, vertical: 0.0, horizontal: 0.0 }
    }
}

impl Geometry {
    pub fn is_cropped(&self) -> bool {
        self.x > 0.0005 || self.y > 0.0005 || self.w < 0.9995 || self.h < 0.9995
    }

    pub fn is_identity(&self) -> bool {
        self.quarter % 4 == 0 && self.angle == 0.0 && !self.is_cropped() && !self.has_lens()
    }

    pub fn has_lens(&self) -> bool {
        self.distortion != 0.0 || self.vertical != 0.0 || self.horizontal != 0.0
    }

    /// Where a point of the straightened frame (pixels from its centre) comes from in a w x h (turned) photo,
    /// also from its centre.
    fn source_of(&self, w: f32, h: f32, fx: f32, fy: f32) -> (f32, f32) {
        let (sn, cs) = self.angle.to_radians().sin_cos();
        let (sx, sy) = (fx * cs + fy * sn, -fx * sn + fy * cs);
        if !self.has_lens() {
            return (sx, sy);
        }
        let (mut u, mut v) = (sx / (w / 2.0), sy / (h / 2.0));
        // keystone: widen the top (or a side) to undo converging lines
        u *= 1.0 + self.vertical * 0.3 * v;
        v *= 1.0 + self.horizontal * 0.3 * u;
        let (px, py) = (u * w / 2.0, v * h / 2.0);
        let diag = (w * w + h * h).sqrt() / 2.0;
        let r2 = (px * px + py * py) / (diag * diag);
        let k = 1.0 + self.distortion * 0.25 * r2;
        (px * k, py * k)
    }

    /// The same turn and straighten with the whole frame (what the crop tool shows).
    pub fn uncropped(&self) -> Geometry {
        Geometry { x: 0.0, y: 0.0, w: 1.0, h: 1.0, ..*self }
    }

    /// Size of the frame after turning and straightening a w x h photo.
    pub fn frame(&self, w: usize, h: usize) -> (f32, f32) {
        let (w, h) = if self.quarter % 2 == 1 { (h, w) } else { (w, h) };
        frame_for(self, w as f32, h as f32)
    }

    /// Size of the finished crop of a w x h photo, in pixels.
    pub fn size(&self, w: usize, h: usize) -> (usize, usize) {
        let (fw, fh) = self.frame(w, h);
        (((fw * self.w).round() as usize).max(1), ((fh * self.h).round() as usize).max(1))
    }

    /// Turns the crop with the picture, a quarter clockwise (`cw`) or anticlockwise.
    pub fn turn(&mut self, cw: bool) {
        let (x, y, w, h) = (self.x, self.y, self.w, self.h);
        if cw {
            self.quarter = (self.quarter + 1) % 4;
            (self.x, self.y) = (1.0 - (y + h), x);
        } else {
            self.quarter = (self.quarter + 3) % 4;
            (self.x, self.y) = (y, 1.0 - (x + w));
        }
        (self.w, self.h) = (h, w);
        // straightening goes the same way after a turn, so the angle needs no change
    }
}

/// The largest frame of the photo's shape that fits inside it once turned by `deg`, so there are no empty corners.
fn frame_of(w: f32, h: f32, deg: f32) -> (f32, f32) {
    let (s, c) = deg.to_radians().abs().sin_cos();
    let k = (w / (w * c + h * s)).min(h / (w * s + h * c));
    (w * k, h * k)
}

/// The frame for a turned w x h photo: straightened analytically, or with lens corrections the largest frame
/// whose edge all maps inside the photo.
fn frame_for(g: &Geometry, w: f32, h: f32) -> (f32, f32) {
    if !g.has_lens() {
        return frame_of(w, h, g.angle);
    }
    let fits = |k: f32| {
        let (fw, fh) = (w * k, h * k);
        (0..=32).all(|i| {
            let t = i as f32 / 32.0;
            [(t - 0.5, -0.5), (t - 0.5, 0.5), (-0.5, t - 0.5), (0.5, t - 0.5)].iter().all(|&(a, b)| {
                let (sx, sy) = g.source_of(w, h, a * fw, b * fh);
                sx.abs() <= w / 2.0 && sy.abs() <= h / 2.0
            })
        })
    };
    let (mut lo, mut hi) = (0.2f32, 1.0f32);
    if fits(hi) {
        return (w, h);
    }
    for _ in 0..24 {
        let mid = (lo + hi) / 2.0;
        if fits(mid) { lo = mid } else { hi = mid }
    }
    (w * lo, h * lo)
}

fn turn(img: Image, quarter: u8) -> Image {
    match quarter % 4 {
        1 => crate::io::orient(img, 6),
        2 => crate::io::orient(img, 3),
        3 => crate::io::orient(img, 8),
        _ => img,
    }
}

/// The photo turned, straightened and cropped, with its long side at most `long`.
pub fn apply(img: &Image, g: &Geometry, long: usize) -> Image {
    if g.is_identity() {
        return img.fit(long);
    }
    let (cw, ch) = g.size(img.w, img.h);
    // shrink first (properly filtered) so the rest works at about the final size
    let k = (long as f32 / cw.max(ch) as f32).min(1.0);
    let pre = if k < 1.0 { img.resized(((img.w as f32 * k).round() as usize).max(2), ((img.h as f32 * k).round() as usize).max(2)) } else { img.clone() };
    let pre = turn(pre, g.quarter);
    let (fw, fh) = frame_for(g, pre.w as f32, pre.h as f32);
    let ow = ((fw * g.w).round() as usize).clamp(1, pre.w);
    let oh = ((fh * g.h).round() as usize).clamp(1, pre.h);
    let mut out = Image::new(ow, oh);
    if g.angle == 0.0 && !g.has_lens() {
        let x0 = ((fw * g.x).round() as usize).min(pre.w - ow);
        let y0 = ((fh * g.y).round() as usize).min(pre.h - oh);
        out.px.par_chunks_mut(ow).enumerate().for_each(|(y, row)| {
            let s = (y0 + y) * pre.w + x0;
            row.copy_from_slice(&pre.px[s..s + ow]);
        });
        return out;
    }
    let (cx, cy) = (pre.w as f32 / 2.0, pre.h as f32 / 2.0);
    let (pw, ph) = (pre.w as f32, pre.h as f32);
    let (step_x, step_y) = (fw * g.w / ow as f32, fh * g.h / oh as f32);
    out.px.par_chunks_mut(ow).enumerate().for_each(|(v, row)| {
        let fy = fh * g.y + (v as f32 + 0.5) * step_y - fh / 2.0;
        for (u, p) in row.iter_mut().enumerate() {
            let fx = fw * g.x + (u as f32 + 0.5) * step_x - fw / 2.0;
            // back from the straightened (and corrected) frame into the photo
            let (sx, sy) = g.source_of(pw, ph, fx, fy);
            *p = bilinear(&pre, cx + sx - 0.5, cy + sy - 0.5);
        }
    });
    out
}

fn bilinear(img: &Image, x: f32, y: f32) -> u32 {
    let x = x.clamp(0.0, (img.w - 1) as f32);
    let y = y.clamp(0.0, (img.h - 1) as f32);
    let (x0, y0) = (x as usize, y as usize);
    let (x1, y1) = ((x0 + 1).min(img.w - 1), (y0 + 1).min(img.h - 1));
    let (fx, fy) = (x - x0 as f32, y - y0 as f32);
    let (a, b, c, d) = (img.px[y0 * img.w + x0], img.px[y0 * img.w + x1], img.px[y1 * img.w + x0], img.px[y1 * img.w + x1]);
    let ch = |s: u32| {
        let f = |p: u32| ((p >> s) & 255) as f32;
        let top = f(a) + (f(b) - f(a)) * fx;
        let bot = f(c) + (f(d) - f(c)) * fx;
        ((top + (bot - top) * fy + 0.5) as u32).min(255)
    };
    (ch(16) << 16) | (ch(8) << 8) | ch(0)
}

/// The crop rectangle in the crop tool, in fractions of the frame.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl Geometry {
    pub fn rect(&self) -> Rect {
        Rect { x: self.x, y: self.y, w: self.w, h: self.h }
    }

    pub fn set_rect(&mut self, r: Rect) {
        (self.x, self.y, self.w, self.h) = (r.x, r.y, r.w, r.h);
    }
}

/// What part of the crop rectangle is being dragged.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Grab {
    Move,
    Edges { left: bool, top: bool, right: bool, bottom: bool },
}

const MIN_SIZE: f32 = 0.02;

/// The crop after dragging `grab` of `start` by (dx, dy), in frame fractions. `k` is the wanted width / height
/// in frame fractions (None = any shape). Dragging past the opposite edge flips the rectangle over.
pub fn drag(start: Rect, grab: Grab, dx: f32, dy: f32, k: Option<f32>) -> Rect {
    let Grab::Edges { left, top, right, bottom } = grab else {
        return Rect { x: (start.x + dx).clamp(0.0, 1.0 - start.w), y: (start.y + dy).clamp(0.0, 1.0 - start.h), ..start };
    };
    // per axis: Some((anchor, direction, size)) when an edge moves, None when the axis keeps its centre
    let axis = |lo: f32, size: f32, moves_lo: bool, moves_hi: bool, d: f32| -> Option<(f32, f32, f32)> {
        let (anchor, size) = if moves_hi {
            (lo, size + d)
        } else if moves_lo {
            (lo + size, -(size - d))
        } else {
            return None;
        };
        Some(if size < 0.0 { (anchor, -1.0, -size) } else { (anchor, 1.0, size) })
    };
    let ax = axis(start.x, start.w, left, right, dx);
    let ay = axis(start.y, start.h, top, bottom, dy);
    let (mut w, mut h) = (ax.map_or(start.w, |a| a.2), ay.map_or(start.h, |a| a.2));
    if let Some(k) = k {
        match (ax.is_some(), ay.is_some()) {
            (true, true) => {
                if w / h > k {
                    w = h * k
                } else {
                    h = w / k
                }
            }
            (true, false) => h = w / k,
            _ => w = h * k,
        }
    }
    // room left in each direction
    let room = |a: Option<(f32, f32, f32)>, lo: f32, size: f32| match a {
        Some((anchor, dir, _)) => if dir > 0.0 { 1.0 - anchor } else { anchor },
        None => 2.0 * (lo + size / 2.0).min(1.0 - (lo + size / 2.0)),
    };
    let (rx, ry) = (room(ax, start.x, start.w), room(ay, start.y, start.h));
    if k.is_some() {
        let grow = (MIN_SIZE / w).max(MIN_SIZE / h).max(1.0);
        let shrink = (rx / (w * grow)).min(ry / (h * grow)).min(1.0);
        w *= grow * shrink;
        h *= grow * shrink;
    } else {
        w = w.clamp(MIN_SIZE.min(rx), rx);
        h = h.clamp(MIN_SIZE.min(ry), ry);
    }
    let place = |a: Option<(f32, f32, f32)>, lo: f32, size: f32, new: f32| match a {
        Some((anchor, dir, _)) => if dir > 0.0 { anchor } else { anchor - new },
        None => lo + size / 2.0 - new / 2.0,
    };
    let x = place(ax, start.x, start.w, w).clamp(0.0, 1.0 - w);
    let y = place(ay, start.y, start.h, h).clamp(0.0, 1.0 - h);
    Rect { x, y, w, h }
}

/// The biggest crop of shape `k` (width / height in frame fractions), centred where `r` is.
pub fn fit_shape(r: Rect, k: f32) -> Rect {
    let (w, h) = if k >= 1.0 { (1.0, 1.0 / k) } else { (k, 1.0) };
    let (cx, cy) = (r.x + r.w / 2.0, r.y + r.h / 2.0);
    Rect { x: (cx - w / 2.0).clamp(0.0, 1.0 - w), y: (cy - h / 2.0).clamp(0.0, 1.0 - h), w, h }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: Rect, b: Rect) -> bool {
        (a.x - b.x).abs() < 1e-4 && (a.y - b.y).abs() < 1e-4 && (a.w - b.w).abs() < 1e-4 && (a.h - b.h).abs() < 1e-4
    }

    #[test]
    fn corner_drag_free_and_locked() {
        let full = Rect { x: 0.0, y: 0.0, w: 1.0, h: 1.0 };
        let br = Grab::Edges { left: false, top: false, right: true, bottom: true };
        assert!(close(drag(full, br, -0.5, -0.2, None), Rect { x: 0.0, y: 0.0, w: 0.5, h: 0.8 }));
        // square in fractions: the smaller side wins, top-left stays put
        assert!(close(drag(full, br, -0.5, -0.2, Some(1.0)), Rect { x: 0.0, y: 0.0, w: 0.5, h: 0.5 }));
        let tl = Grab::Edges { left: true, top: true, right: false, bottom: false };
        assert!(close(drag(full, tl, 0.3, 0.1, Some(1.0)), Rect { x: 0.3, y: 0.3, w: 0.7, h: 0.7 }));
    }

    #[test]
    fn new_rect_flips_and_stays_inside() {
        let at = Rect { x: 0.5, y: 0.5, w: 0.0, h: 0.0 };
        let br = Grab::Edges { left: false, top: false, right: true, bottom: true };
        assert!(close(drag(at, br, -0.2, -0.1, None), Rect { x: 0.3, y: 0.4, w: 0.2, h: 0.1 }));
        let r = drag(at, br, 0.9, 0.9, None);
        assert!(close(r, Rect { x: 0.5, y: 0.5, w: 0.5, h: 0.5 }));
    }

    #[test]
    fn edge_drag_keeps_shape_centred() {
        let r = Rect { x: 0.2, y: 0.2, w: 0.4, h: 0.4 };
        let right = Grab::Edges { left: false, top: false, right: true, bottom: false };
        assert!(close(drag(r, right, 0.2, 0.0, Some(1.0)), Rect { x: 0.2, y: 0.1, w: 0.6, h: 0.6 }));
    }

    #[test]
    fn move_and_turn() {
        let r = Rect { x: 0.2, y: 0.2, w: 0.4, h: 0.4 };
        assert!(close(drag(r, Grab::Move, 0.9, -0.9, None), Rect { x: 0.6, y: 0.0, w: 0.4, h: 0.4 }));
        let mut g = Geometry { x: 0.1, y: 0.2, w: 0.3, h: 0.4, ..Default::default() };
        let before = g;
        for _ in 0..4 {
            g.turn(true);
        }
        assert!(close(g.rect(), before.rect()) && g.quarter == 0);
        g.turn(true);
        g.turn(false);
        assert!(close(g.rect(), before.rect()));
    }
}
