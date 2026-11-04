//! Loading photos (JPEG, PNG, TIFF, and camera RAW such as Sony ARW) and saving JPEGs.

use crate::img::Image;
use image::{codecs::jpeg::JpegEncoder, DynamicImage, ImageDecoder, ImageReader};
use rawler::decoders::RawDecodeParams;
use rawler::imgop::develop::{Intermediate, ProcessingStep, RawDevelop};
use rawler::rawsource::RawSource;
use rayon::prelude::*;
use std::fs::File;
use std::io::BufWriter;
use std::path::Path;

/// Light: white balance, exposure, shadows and highlights, whites and blacks, contrast. For RAW files this is how
/// the RAW is developed; JPEGs get the same controls on their pixels (and are left exactly as they are at the
/// defaults).
#[derive(Clone, Copy, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct RawOptions {
    /// Exposure change in stops.
    pub exposure: f32,
    /// 0 = flat (straight sRGB), 1 = camera-like contrast.
    pub contrast: f32,
    /// White balance in kelvin, None = as shot.
    pub kelvin: Option<f32>,
    /// -1 green .. 1 magenta
    pub tint: f32,
    /// -1 darker .. 1 lifted (local, so the picture doesn't go flat)
    pub shadows: f32,
    /// -1 recovered .. 1 brighter
    pub highlights: f32,
    /// -1 .. 1, moves the white point (levels)
    pub whites: f32,
    /// -1 crushed .. 1 lifted, moves the black point (levels)
    pub blacks: f32,
}

impl Default for RawOptions {
    fn default() -> Self {
        RawOptions { exposure: 0.0, contrast: 1.0, kelvin: None, tint: 0.0, shadows: 0.0, highlights: 0.0, whites: 0.0, blacks: 0.0 }
    }
}

pub fn is_raw(path: &Path) -> bool {
    let ext = path.extension().and_then(|e| e.to_str()).unwrap_or("").to_ascii_lowercase();
    matches!(
        ext.as_str(),
        "arw" | "srf" | "sr2" | "dng" | "cr2" | "cr3" | "nef" | "nrw" | "raf" | "orf" | "rw2" | "pef" | "srw" | "3fr" | "iiq" | "erf" | "mos"
            // Gallery's copy: the other RAW types Gallery lists, which rawler also reads
            | "crw" | "rwl" | "fff" | "mef" | "kdc" | "dcr"
    )
}

/// A RAW file decoded to linear camera-white-balanced sRGB, before exposure and tone, kept so the RAW
/// controls can be changed without decoding again.
pub struct RawLinear {
    pub w: usize,
    pub h: usize,
    pub rgb: Vec<f32>,
    pub orientation: u16,
    /// the white the camera balanced for (CIE xy), if known
    pub white_xy: Option<(f32, f32)>,
}

impl RawLinear {
    /// A smaller copy (whole-pixel box average) with the long side at most about `long`.
    pub fn shrink(&self, long: usize) -> RawLinear {
        let f = self.w.max(self.h).div_ceil(long.max(1)).max(1);
        if f == 1 {
            return RawLinear { w: self.w, h: self.h, rgb: self.rgb.clone(), orientation: self.orientation, white_xy: self.white_xy };
        }
        let (w, h) = (self.w / f, self.h / f);
        let mut rgb = vec![0f32; w * h * 3];
        rgb.par_chunks_mut(w * 3).enumerate().for_each(|(y, row)| {
            for x in 0..w {
                let mut s = [0f32; 3];
                for yy in y * f..(y + 1) * f {
                    for xx in x * f..(x + 1) * f {
                        let i = (yy * self.w + xx) * 3;
                        for c in 0..3 {
                            s[c] += self.rgb[i + c];
                        }
                    }
                }
                let n = (f * f) as f32;
                for c in 0..3 {
                    row[x * 3 + c] = s[c] / n;
                }
            }
        });
        RawLinear { w, h, rgb, orientation: self.orientation, white_xy: self.white_xy }
    }

    /// The camera's white balance as a colour temperature.
    pub fn as_shot_kelvin(&self) -> Option<f32> {
        self.white_xy.map(crate::whitebalance::kelvin_of)
    }
}

pub fn decode_raw(path: &Path) -> Result<RawLinear, String> {
    let src = RawSource::new(path).map_err(|e| format!("cannot open {}: {e}", path.display()))?;
    let decoder = rawler::get_decoder(&src).map_err(|e| format!("not a supported RAW file: {e}"))?;
    let params = RawDecodeParams::default();
    let raw = decoder.raw_image(&src, &params, false).map_err(|e| format!("RAW decode failed: {e}"))?;
    let orientation = decoder.raw_metadata(&src, &params).ok().and_then(|m| m.exif.orientation).unwrap_or(1);
    // everything up to (not including) the sRGB gamma: we apply exposure and tone in linear light
    let steps = [
        ProcessingStep::Rescale,
        ProcessingStep::Demosaic,
        ProcessingStep::CropActiveArea,
        ProcessingStep::WhiteBalance,
        ProcessingStep::Calibrate,
        ProcessingStep::CropDefault,
    ];
    let white_xy = raw
        .color_matrix_find_first([rawler::imgop::xyz::Illuminant::D65])
        .filter(|(_, m)| m.len() >= 9)
        .and_then(|(_, m)| crate::whitebalance::as_shot_xy(raw.wb_coeffs, &[[m[0], m[1], m[2]], [m[3], m[4], m[5]], [m[6], m[7], m[8]]]));
    let inter = RawDevelop::new_with(&steps).develop_intermediate(&raw).map_err(|e| format!("RAW develop failed: {e}"))?;
    let dim = inter.dim();
    let rgb: Vec<f32> = match inter {
        Intermediate::ThreeColor(p) => p.flatten(),
        Intermediate::Monochrome(p) => p.data.iter().flat_map(|&v| [v, v, v]).collect(),
        Intermediate::FourColor(p) => p.data.iter().flat_map(|c| [c[0], c[1], c[2]]).collect(),
    };
    Ok(RawLinear { w: dim.w, h: dim.h, rgb, orientation, white_xy })
}

/// Linear RAW data to an 8-bit picture: exposure, a soft highlight shoulder, sRGB, then a gentle S-curve
/// like a camera JPEG's.
/// Sony's look on top of a neutral develop, measured by matching our output to the A7 II's own JPEG of the same
/// shot (Standard creative style): deeper shadows, brighter mid-tones and highlights.
const CAMERA_CURVE: [u8; 256] = [0,1,2,2,3,4,5,6,7,8,9,10,11,12,13,15,16,17,18,20,21,23,25,26,28,30,32,35,37,39,42,44,46,48,50,52,55,57,59,61,63,65,67,69,71,73,75,77,79,81,83,85,87,90,92,94,96,98,100,101,103,105,107,109,111,113,115,116,118,120,121,123,124,126,127,129,130,132,133,135,136,138,139,140,142,143,145,146,147,148,150,151,152,153,155,156,157,158,160,161,162,163,164,165,167,168,169,170,171,172,173,175,176,177,178,179,180,181,182,183,184,185,186,187,188,189,190,191,193,194,195,196,197,198,198,199,200,201,202,203,203,204,205,206,207,207,208,209,210,211,211,212,213,214,214,215,216,216,217,218,218,219,220,220,221,222,222,223,223,224,224,225,225,226,226,227,227,228,228,229,229,230,230,230,231,231,232,232,232,233,233,233,234,234,234,235,235,235,236,236,236,237,237,238,238,238,239,239,239,240,240,240,241,241,241,242,242,242,243,243,243,244,244,244,245,245,245,246,246,246,247,247,247,248,248,248,249,249,249,250,250,250,251,251,251,252,252,252,253,253,253,254,254,254,255,255];
const CAMERA_SATURATION: f32 = 1.05;

pub fn render_raw(raw: &RawLinear, opt: RawOptions) -> Image {
    let gain = 2f32.powf(opt.exposure);
    // tone lookup over linear 0..4 (anything brighter rolls off into white)
    const N: usize = 4096;
    let lut: Vec<u8> = (0..N)
        .map(|i| {
            let v = i as f32 / (N - 1) as f32 * 4.0;
            let s = shoulder(v);
            let g = srgb_gamma(s);
            let c = (g + 0.55 * (g * g * (3.0 - 2.0 * g) - g)).clamp(0.0, 1.0) * 255.0;
            // contrast blends from this neutral develop to the camera's look
            let cam = CAMERA_CURVE[(c + 0.5) as usize] as f32;
            // past 100%, more of the same S-curve
            let extra = (opt.contrast - 1.0).max(0.0);
            let k = (c + (cam - c) * opt.contrast.min(1.0)) / 255.0;
            let k = k + extra * 0.55 * (k * k * (3.0 - 2.0 * k) - k);
            (levels(k, opt.whites, opt.blacks) * 255.0).clamp(0.0, 255.0).round() as u8
        })
        .collect();
    let look = |v: f32| -> u8 {
        let x = (v * gain).max(0.0) / 4.0;
        lut[((x * (N - 1) as f32) as usize).min(N - 1)]
    };
    let sat = 1.0 + (CAMERA_SATURATION - 1.0) * opt.contrast;
    let wb = match (raw.white_xy, opt.kelvin) {
        (Some(xy), kelvin) if kelvin.is_some() || opt.tint != 0.0 => {
            let k = kelvin.unwrap_or_else(|| crate::whitebalance::kelvin_of(xy));
            Some(crate::whitebalance::correction(xy, k, opt.tint))
        }
        _ => None,
    };
    let balanced = |i: usize| -> [f32; 3] {
        let c = [raw.rgb[i * 3], raw.rgb[i * 3 + 1], raw.rgb[i * 3 + 2]];
        match &wb {
            Some(m) => crate::whitebalance::apply(m, c),
            None => c,
        }
    };
    let local = crate::localtone::LocalTone::new(raw.w, raw.h, gain, opt.shadows, opt.highlights, &balanced);
    let mut img = Image::new(raw.w, raw.h);
    img.px.par_iter_mut().enumerate().for_each(|(i, p)| {
        let mut c = balanced(i);
        if let Some(l) = &local {
            let k = l.gain(i % raw.w, i / raw.w, c);
            c = c.map(|v| v * k);
        }
        let (r, g, b) = (look(c[0]) as f32, look(c[1]) as f32, look(c[2]) as f32);
        let l = 0.299 * r + 0.587 * g + 0.114 * b;
        *p = crate::img::pack_f(l + (r - l) * sat, l + (g - l) * sat, l + (b - l) * sat);
    });
    orient(img, raw.orientation)
}

/// Whites and blacks: moves the white and black points, -1..1 each.
fn levels(y: f32, whites: f32, blacks: f32) -> f32 {
    if whites == 0.0 && blacks == 0.0 {
        return y;
    }
    let b = -blacks * 0.06;
    let w = 1.0 - whites * 0.08;
    ((y - b) / (w - b)).clamp(0.0, 1.0)
}

/// A JPEG (or PNG, TIFF) with the light controls applied: white balance relative to its own (6500 K = as it is),
/// exposure, shadows and highlights in linear light, then contrast (1 = unchanged) and whites/blacks.
pub fn render_display(img: &Image, opt: RawOptions) -> Image {
    let neutral = RawOptions { contrast: 1.0, ..RawOptions::default() };
    if opt == neutral {
        return img.clone();
    }
    let lin: Vec<f32> = (0..256)
        .map(|i| {
            let x = i as f32 / 255.0;
            if x <= 0.04045 { x / 12.92 } else { ((x + 0.055) / 1.055).powf(2.4) }
        })
        .collect();
    const N: usize = 4096;
    let s = opt.contrast - 1.0;
    let out: Vec<u8> = (0..=N)
        .map(|i| {
            let g = srgb_gamma(i as f32 / N as f32);
            let g = g + s * 0.55 * (g * g * (3.0 - 2.0 * g) - g);
            (levels(g.clamp(0.0, 1.0), opt.whites, opt.blacks) * 255.0).round() as u8
        })
        .collect();
    let gain = 2f32.powf(opt.exposure);
    let d65 = (0.3127, 0.3290);
    let wb = match opt.kelvin {
        Some(k) => Some(crate::whitebalance::correction(d65, k, opt.tint)),
        None if opt.tint != 0.0 => Some(crate::whitebalance::correction(d65, crate::whitebalance::kelvin_of(d65), opt.tint)),
        None => None,
    };
    let pixel = |i: usize| -> [f32; 3] {
        let p = img.px[i];
        let c = [lin[((p >> 16) & 255) as usize], lin[((p >> 8) & 255) as usize], lin[(p & 255) as usize]];
        match &wb {
            Some(m) => crate::whitebalance::apply(m, c),
            None => c,
        }
    };
    let local = crate::localtone::LocalTone::new(img.w, img.h, gain, opt.shadows, opt.highlights, &pixel);
    let mut res = Image::new(img.w, img.h);
    res.px.par_iter_mut().enumerate().for_each(|(i, p)| {
        let mut c = pixel(i);
        let k = gain * local.as_ref().map_or(1.0, |l| l.gain(i % img.w, i / img.w, c));
        c = c.map(|v| v * k);
        let e = |v: f32| out[((v.clamp(0.0, 1.0) * N as f32) + 0.5) as usize] as u32;
        *p = (e(c[0]) << 16) | (e(c[1]) << 8) | e(c[2]);
    });
    res
}

fn shoulder(v: f32) -> f32 {
    const K: f32 = 0.82;
    if v <= K {
        v
    } else {
        K + (1.0 - K) * (1.0 - (-(v - K) / (1.0 - K)).exp())
    }
}

fn srgb_gamma(v: f32) -> f32 {
    if v <= 0.0031308 {
        v * 12.92
    } else {
        1.055 * v.powf(1.0 / 2.4) - 0.055
    }
}

/// Loads a non-RAW photo, turned upright from its EXIF orientation.
pub fn load_image(path: &Path) -> Result<Image, String> {
    let mut dec = ImageReader::open(path)
        .map_err(|e| format!("cannot open {}: {e}", path.display()))?
        .with_guessed_format()
        .map_err(|e| e.to_string())?
        .into_decoder()
        .map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    let orientation = dec.orientation().ok();
    let mut img = DynamicImage::from_decoder(dec).map_err(|e| format!("cannot decode {}: {e}", path.display()))?;
    if let Some(o) = orientation {
        img.apply_orientation(o);
    }
    Ok(Image::from_rgb(&img.to_rgb8()))
}

/// Turns an image upright from an EXIF orientation value (1..8).
pub fn orient(img: Image, o: u16) -> Image {
    let rgb = img.to_rgb();
    let out = match o {
        2 => image::imageops::flip_horizontal(&rgb),
        3 => image::imageops::rotate180(&rgb),
        4 => image::imageops::flip_vertical(&rgb),
        5 => image::imageops::flip_horizontal(&image::imageops::rotate90(&rgb)),
        6 => image::imageops::rotate90(&rgb),
        7 => image::imageops::flip_horizontal(&image::imageops::rotate270(&rgb)),
        8 => image::imageops::rotate270(&rgb),
        _ => return img,
    };
    Image::from_rgb(&out)
}

pub fn save_jpeg(img: &Image, path: &Path, quality: u8) -> Result<(), String> {
    let f = File::create(path).map_err(|e| format!("cannot write {}: {e}", path.display()))?;
    let mut enc = JpegEncoder::new_with_quality(BufWriter::new(f), quality);
    enc.encode_image(&img.to_rgb()).map_err(|e| format!("JPEG encode failed: {e}"))
}
