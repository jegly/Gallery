//! Several shots of the same scene from a tripod: Crowd remover (the per-pixel median, so anything that moves
//! goes) and Trails (that empty scene with each shot's movers laid back on it, older ones fainter and paler).

use super::tint;
use crate::img::{pack_f, Image};
use crate::util::smooth;
use rayon::prelude::*;

pub const NAMES: [&str; 2] = ["Crowd remover", "Trails"];
pub const CROWD: usize = 0;
pub const TRAILS: usize = 1;

/// Combines same-sized frames (oldest first) into one image.
pub fn combine(frames: &[Image], kind: usize, amount: f32, colour: usize) -> Result<Image, String> {
    let first = frames.first().ok_or("no photos")?;
    let (w, h) = (first.w, first.h);
    if frames.iter().any(|f| f.w != w || f.h != h) {
        return Err("the photos aren't all the same size".into());
    }
    let n = frames.len();
    let c = tint(colour);
    let mut out = Image::new(w, h);
    out.px.par_chunks_mut(w).enumerate().for_each(|(y, row)| {
        let (mut vr, mut vg, mut vb) = (vec![0u32; n], vec![0u32; n], vec![0u32; n]);
        for x in 0..w {
            let i = y * w + x;
            for k in 0..n {
                let p = frames[k].px[i];
                vr[k] = (p >> 16) & 255;
                vg[k] = (p >> 8) & 255;
                vb[k] = p & 255;
            }
            let (mr, mg, mb) = (median(&mut vr), median(&mut vg), median(&mut vb));
            if kind == CROWD {
                row[x] = (mr << 16) | (mg << 8) | mb;
                continue;
            }
            let (mut r, mut g, mut b) = (mr as f32, mg as f32, mb as f32);
            for k in 0..n {
                let p = frames[k].px[i];
                let (mut fr, mut fg, mut fb) = (((p >> 16) & 255) as f32, ((p >> 8) & 255) as f32, (p & 255) as f32);
                let diff = (fr - mr as f32).abs().max((fg - mg as f32).abs()).max((fb - mb as f32).abs());
                let m = smooth(18.0, 46.0, diff);
                if m <= 0.0 {
                    continue;
                }
                let age = if n == 1 { 1.0 } else { k as f32 / (n - 1) as f32 };
                let pale = (1.0 - age) * amount * 0.7;
                fr += (255.0 * c[0] - fr) * pale;
                fg += (255.0 * c[1] - fg) * pale;
                fb += (255.0 * c[2] - fb) * pale;
                let a = m * (0.3 + 0.7 * age);
                r += (fr - r) * a;
                g += (fg - g) * a;
                b += (fb - b) * a;
            }
            row[x] = pack_f(r, g, b);
        }
    });
    Ok(out)
}

fn median(v: &mut [u32]) -> u32 {
    v.sort_unstable();
    let n = v.len();
    if n % 2 == 1 {
        v[n / 2]
    } else {
        (v[n / 2 - 1] + v[n / 2]) / 2
    }
}
