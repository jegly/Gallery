//! The image type every effect works on: 8-bit RGB packed as 0x00RRGGBB, row-major.

use image::{imageops, imageops::FilterType, RgbImage};

#[derive(Clone)]
pub struct Image {
    pub w: usize,
    pub h: usize,
    pub px: Vec<u32>,
}

impl Image {
    pub fn new(w: usize, h: usize) -> Self {
        Image { w, h, px: vec![0; w * h] }
    }

    pub fn from_rgb(img: &RgbImage) -> Self {
        let (w, h) = (img.width() as usize, img.height() as usize);
        let px = img.as_raw().chunks_exact(3).map(|c| pack(c[0], c[1], c[2])).collect();
        Image { w, h, px }
    }

    pub fn to_rgb(&self) -> RgbImage {
        let mut raw = Vec::with_capacity(self.w * self.h * 3);
        for &p in &self.px {
            raw.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8]);
        }
        RgbImage::from_raw(self.w as u32, self.h as u32, raw).expect("size matches")
    }

    /// Resized copy (Triangle filter, which averages properly when shrinking).
    pub fn resized(&self, w: usize, h: usize) -> Image {
        if w == self.w && h == self.h {
            return self.clone();
        }
        Image::from_rgb(&imageops::resize(&self.to_rgb(), w.max(1) as u32, h.max(1) as u32, FilterType::Triangle))
    }

    /// Resized so the long side is at most `long`.
    pub fn fit(&self, long: usize) -> Image {
        let m = self.w.max(self.h);
        if m <= long {
            return self.clone();
        }
        let s = long as f32 / m as f32;
        self.resized(((self.w as f32 * s).round() as usize).max(1), ((self.h as f32 * s).round() as usize).max(1))
    }

    /// Interleaved RGB bytes (for the AI engine).
    pub fn rgb_bytes(&self) -> Vec<u8> {
        let mut out = Vec::with_capacity(self.w * self.h * 3);
        for &p in &self.px {
            out.extend_from_slice(&[(p >> 16) as u8, (p >> 8) as u8, p as u8]);
        }
        out
    }
}

#[inline]
pub fn pack(r: u8, g: u8, b: u8) -> u32 {
    ((r as u32) << 16) | ((g as u32) << 8) | b as u32
}

#[inline]
pub fn unpack(p: u32) -> (f32, f32, f32) {
    (((p >> 16) & 255) as f32, ((p >> 8) & 255) as f32, (p & 255) as f32)
}

#[inline]
pub fn clamp255(v: f32) -> u32 {
    let i = (v + 0.5) as i32;
    i.clamp(0, 255) as u32
}

#[inline]
pub fn pack_f(r: f32, g: f32, b: f32) -> u32 {
    (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b)
}
