//! Local adjustments: exposure, contrast, saturation and warmth through a mask, like Lightroom's masking.
//! The mask is the AI subject or its background, a gradient from the top (sky) or bottom (ground), or a radial
//! area round the centre or the subject.

use crate::fx::extra::Mask;
use crate::img::Image;
use crate::util::smooth;
use rayon::prelude::*;

pub const KINDS: [&str; 7] = ["Off", "Subject (AI)", "Background (AI)", "Sky gradient", "Ground gradient", "Radial, centre", "Radial, subject (AI)"];
const SUBJECT: usize = 1;
const BACKGROUND: usize = 2;
const TOP: usize = 3;
const BOTTOM: usize = 4;
const RADIAL: usize = 5;
const RADIAL_SUBJECT: usize = 6;

#[derive(Clone, Copy, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct Local {
    pub kind: usize,
    /// stops
    pub exposure: f32,
    /// -1..1
    pub contrast: f32,
    /// -1..1
    pub saturation: f32,
    /// -1 cooler .. 1 warmer
    pub warmth: f32,
    /// gradients: how far in from the edge; radial: how big (0..1)
    pub size: f32,
    /// 0..1 how soft the edge is
    pub feather: f32,
}

impl Default for Local {
    fn default() -> Self {
        Local { kind: 0, exposure: 0.0, contrast: 0.0, saturation: 0.0, warmth: 0.0, size: 0.5, feather: 0.5 }
    }
}

pub fn uses_subject(kind: usize) -> bool {
    matches!(kind, SUBJECT | BACKGROUND | RADIAL_SUBJECT)
}

/// Whether the Size and Feather sliders mean anything for this kind.
pub fn has_shape(kind: usize) -> bool {
    matches!(kind, TOP | BOTTOM | RADIAL | RADIAL_SUBJECT)
}

fn to_lin(v: f32) -> f32 {
    if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) }
}

fn to_srgb(v: f32) -> f32 {
    let v = v.clamp(0.0, 1.0);
    if v <= 0.0031308 { v * 12.92 } else { 1.055 * v.powf(1.0 / 2.4) - 0.055 }
}

pub fn apply(img: &mut Image, l: &Local, mask: Option<&Mask>) {
    if l.kind == 0 || (uses_subject(l.kind) && mask.is_none()) {
        return;
    }
    let (w, h) = (img.w, img.h);
    let aspect = w as f32 / h as f32;
    let soft = 0.02 + 0.4 * l.feather.clamp(0.0, 1.0);
    let centre = if l.kind == RADIAL_SUBJECT { mask.and_then(|m| m.centre).unwrap_or((0.5, 0.5)) } else { (0.5, 0.5) };
    let gain = l.exposure.exp2();
    img.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let fy = (y as f32 + 0.5) / h as f32;
        for (x, p) in row.iter_mut().enumerate() {
            let fx = (x as f32 + 0.5) / w as f32;
            let m = match l.kind {
                SUBJECT => mask.map_or(0.0, |k| k.at(fx, fy)),
                BACKGROUND => 1.0 - mask.map_or(0.0, |k| k.at(fx, fy)),
                TOP => 1.0 - smooth(l.size - soft, l.size + soft, fy),
                BOTTOM => smooth(1.0 - l.size - soft, 1.0 - l.size + soft, fy),
                _ => {
                    // an ellipse the shape of the picture
                    let (dx, dy) = ((fx - centre.0) * aspect.sqrt(), (fy - centre.1) / aspect.sqrt());
                    let d = (dx * dx + dy * dy).sqrt() / (0.05 + 0.75 * l.size);
                    1.0 - smooth(1.0 - soft * 2.0, 1.0 + soft, d)
                }
            };
            if m <= 0.001 {
                continue;
            }
            let c = [((*p >> 16) & 255) as f32 / 255.0, ((*p >> 8) & 255) as f32 / 255.0, (*p & 255) as f32 / 255.0];
            let warm = l.warmth * 0.12;
            let mut o = [0, 1, 2].map(|i| {
                let t = [1.0 + warm, 1.0, 1.0 - warm][i];
                to_srgb(to_lin(c[i]) * gain * t)
            });
            o = o.map(|v| v + l.contrast * 0.6 * (v * v * (3.0 - 2.0 * v) - v));
            let lum = 0.299 * o[0] + 0.587 * o[1] + 0.114 * o[2];
            o = o.map(|v| lum + (v - lum) * (1.0 + l.saturation));
            let q = |i: usize| ((c[i] + (o[i] - c[i]) * m).clamp(0.0, 1.0) * 255.0 + 0.5) as u32;
            *p = (q(0) << 16) | (q(1) << 8) | q(2);
        }
    });
}
