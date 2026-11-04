//! Export: format, quality, size, Real-ESRGAN upscaling, sharpening, and keeping the original's EXIF.

use crate::img::{pack_f, unpack, Image};
use crate::upscale;
use crate::util::gaussian;
use image::{codecs::jpeg::JpegEncoder, imageops::FilterType, ImageFormat};
use rayon::prelude::*;
use std::fs::File;
use std::io::{BufWriter, Read, Write};
use std::path::{Path, PathBuf};

pub const FORMATS: [&str; 3] = ["JPEG", "PNG", "TIFF"];
pub const SIZES: [&str; 3] = ["Original size", "Long edge", "Percent"];
pub const UPSCALES: [&str; 3] = ["No upscaling", "2x (Real-ESRGAN)", "4x (Real-ESRGAN)"];
pub const SHARPEN: [&str; 3] = ["No sharpening", "Smart sharpen", "AI detail (Real-ESRGAN)"];

#[derive(Clone, Debug, PartialEq)]
pub struct ExportOptions {
    pub format: usize,
    pub quality: u8,
    pub size: usize,
    pub long_edge: usize,
    pub percent: f32,
    pub upscale: usize,
    pub upscale_model: usize,
    pub sharpen: usize,
    /// Smart sharpen strength, 0..2
    pub sharpen_amount: f32,
    pub keep_exif: bool,
    pub suffix: String,
    /// Folder to save into; None = next to the original.
    pub folder: Option<PathBuf>,
}

impl Default for ExportOptions {
    fn default() -> Self {
        ExportOptions {
            format: 0,
            quality: 92,
            size: 0,
            long_edge: 3000,
            percent: 50.0,
            upscale: 0,
            upscale_model: upscale::FAST,
            sharpen: 0,
            sharpen_amount: 0.8,
            keep_exif: true,
            suffix: "_grainhouse".into(),
            folder: None,
        }
    }
}

impl ExportOptions {
    pub fn extension(&self) -> &'static str {
        ["jpg", "png", "tif"][self.format.min(2)]
    }

    /// Where a photo exported from `src` goes.
    pub fn target(&self, src: &Path) -> PathBuf {
        let stem = src.file_stem().and_then(|s| s.to_str()).unwrap_or("photo");
        let dir = self.folder.clone().unwrap_or_else(|| src.parent().map(Path::to_path_buf).unwrap_or_default());
        dir.join(format!("{stem}{}.{}", self.suffix, self.extension()))
    }

    /// Rough seconds for the slow (AI) steps on an image of this size.
    pub fn estimate(&self, w: usize, h: usize) -> f32 {
        if self.upscale > 0 {
            upscale::estimate(w, h, self.upscale_model)
        } else if self.sharpen == 2 {
            upscale::estimate(w, h, upscale::FAST)
        } else {
            0.0
        }
    }
}

/// Applies the export steps to a finished edit. `progress` (0..1) returns false to cancel.
pub fn process(img: Image, opt: &ExportOptions, progress: &dyn Fn(f32) -> bool) -> Result<Image, String> {
    let (w0, h0) = (img.w, img.h);
    let mut img = img;
    if opt.upscale > 0 {
        let up = upscale::upscale4(&img, opt.upscale_model, progress)?;
        img = if opt.upscale == 1 { resize(&up, w0 * 2, h0 * 2) } else { up };
    } else if opt.sharpen == 2 {
        // AI detail: Real-ESRGAN at 4x, then back to the same size
        let up = upscale::upscale4(&img, upscale::FAST, progress)?;
        img = resize(&up, w0, h0);
    }
    let (w, h) = (img.w, img.h);
    let target = match opt.size {
        1 => {
            let k = opt.long_edge as f32 / w.max(h) as f32;
            Some(((w as f32 * k).round() as usize, (h as f32 * k).round() as usize))
        }
        2 => Some(((w as f32 * opt.percent / 100.0).round() as usize, (h as f32 * opt.percent / 100.0).round() as usize)),
        _ => None,
    };
    if let Some((tw, th)) = target {
        if tw >= 1 && th >= 1 && (tw, th) != (w, h) {
            img = resize(&img, tw, th);
        }
    }
    if opt.sharpen == 1 {
        smart_sharpen(&mut img, opt.sharpen_amount);
    }
    Ok(img)
}

fn resize(img: &Image, w: usize, h: usize) -> Image {
    Image::from_rgb(&image::imageops::resize(&img.to_rgb(), w as u32, h as u32, FilterType::Lanczos3))
}

/// Sharpening on brightness only, at two scales (fine detail and local contrast), with soft limits so
/// edges don't get halos and flat areas don't get noisy.
pub fn smart_sharpen(img: &mut Image, amount: f32) {
    let (w, h) = (img.w, img.h);
    let lum: Vec<f32> = img.px.par_iter().map(|&p| {
        let (r, g, b) = unpack(p);
        0.299 * r + 0.587 * g + 0.114 * b
    }).collect();
    let mut fine = lum.clone();
    let mut wide = lum.clone();
    gaussian(&mut fine, w, h, 1);
    gaussian(&mut wide, w, h, ((w.max(h) as f32 / 1500.0).round() as usize).max(2));
    img.px.par_iter_mut().enumerate().for_each(|(i, p)| {
        let d1 = lum[i] - fine[i];
        let d2 = fine[i] - wide[i];
        // ignore tiny differences (noise), limit big ones (halos)
        let soft = |d: f32, t: f32, lim: f32| {
            let m = d.abs();
            if m < t { d * (m / t) } else { d.signum() * (m.min(lim)) }
        };
        let delta = amount * (1.1 * soft(d1, 2.0, 24.0) + 0.35 * soft(d2, 1.0, 30.0));
        let (r, g, b) = unpack(*p);
        *p = pack_f(r + delta, g + delta, b + delta);
    });
}

pub fn save(img: &Image, path: &Path, opt: &ExportOptions, source: Option<&Path>) -> Result<(), String> {
    match opt.format {
        0 => {
            let mut buf = Vec::new();
            JpegEncoder::new_with_quality(&mut buf, opt.quality.clamp(1, 100))
                .encode_image(&img.to_rgb())
                .map_err(|e| format!("JPEG encode failed: {e}"))?;
            if opt.keep_exif {
                let exif = source.and_then(|p| if crate::io::is_raw(p) { raw_exif(p) } else { read_exif(p) });
                if let Some(exif) = exif {
                    buf = insert_exif(&buf, &exif);
                }
            }
            let mut f = BufWriter::new(File::create(path).map_err(|e| format!("cannot write {}: {e}", path.display()))?);
            f.write_all(&buf).map_err(|e| e.to_string())
        }
        1 => img.to_rgb().save_with_format(path, ImageFormat::Png).map_err(|e| format!("PNG save failed: {e}")),
        _ => img.to_rgb().save_with_format(path, ImageFormat::Tiff).map_err(|e| format!("TIFF save failed: {e}")),
    }
}

/// The source JPEG's EXIF (APP1) segment, with its orientation set to 1 (the pixels are already upright).
fn read_exif(path: &Path) -> Option<Vec<u8>> {
    let mut data = Vec::new();
    File::open(path).ok()?.read_to_end(&mut data).ok()?;
    if data.len() < 4 || data[0] != 0xFF || data[1] != 0xD8 {
        return None;
    }
    let mut i = 2;
    while i + 4 <= data.len() {
        if data[i] != 0xFF {
            return None;
        }
        let marker = data[i + 1];
        if marker == 0xDA || marker == 0xD9 {
            return None;
        }
        let len = u16::from_be_bytes([data[i + 2], data[i + 3]]) as usize;
        if marker == 0xE1 && data.get(i + 4..i + 10) == Some(b"Exif\0\0") {
            let mut seg = data[i..i + 2 + len].to_vec();
            set_orientation(&mut seg[10..], 1);
            return Some(seg);
        }
        i += 2 + len;
    }
    None
}

/// Sets the orientation tag (0x0112) in IFD0 of a TIFF-format EXIF block.
fn set_orientation(t: &mut [u8], value: u16) {
    if t.len() < 8 {
        return;
    }
    let le = &t[0..2] == b"II";
    let r16 = |b: &[u8], o: usize| if le { u16::from_le_bytes([b[o], b[o + 1]]) } else { u16::from_be_bytes([b[o], b[o + 1]]) };
    let r32 = |b: &[u8], o: usize| if le { u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]) } else { u32::from_be_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]]) };
    let ifd = r32(t, 4) as usize;
    if ifd + 2 > t.len() {
        return;
    }
    let n = r16(t, ifd) as usize;
    for k in 0..n {
        let e = ifd + 2 + k * 12;
        if e + 12 > t.len() {
            return;
        }
        if r16(t, e) == 0x0112 {
            let v = if le { value.to_le_bytes() } else { value.to_be_bytes() };
            t[e + 8] = v[0];
            t[e + 9] = v[1];
            return;
        }
    }
}

fn insert_exif(jpeg: &[u8], exif: &[u8]) -> Vec<u8> {
    // after SOI, and after a JFIF APP0 if the encoder wrote one
    let mut at = 2;
    if jpeg.len() > 4 && jpeg[2] == 0xFF && jpeg[3] == 0xE0 {
        at += 2 + u16::from_be_bytes([jpeg[4], jpeg[5]]) as usize;
    }
    let mut out = Vec::with_capacity(jpeg.len() + exif.len());
    out.extend_from_slice(&jpeg[..at]);
    out.extend_from_slice(exif);
    out.extend_from_slice(&jpeg[at..]);
    out
}

enum Tag {
    Ascii(String),
    Short(u16),
    Long(u32),
    Rational(u32, u32),
    SRational(i32, i32),
}

impl Tag {
    fn kind(&self) -> (u16, u32) {
        match self {
            Tag::Ascii(t) => (2, t.len() as u32 + 1),
            Tag::Short(_) => (3, 1),
            Tag::Long(_) => (4, 1),
            Tag::Rational(..) => (5, 1),
            Tag::SRational(..) => (10, 1),
        }
    }

    fn bytes(&self) -> Vec<u8> {
        match self {
            Tag::Ascii(t) => {
                let mut b = t.as_bytes().to_vec();
                b.push(0);
                b
            }
            Tag::Short(v) => v.to_be_bytes().to_vec(),
            Tag::Long(v) => v.to_be_bytes().to_vec(),
            Tag::Rational(n, d) => [n.to_be_bytes(), d.to_be_bytes()].concat(),
            Tag::SRational(n, d) => [n.to_be_bytes(), d.to_be_bytes()].concat(),
        }
    }
}

/// Size of an IFD with its out-of-line values.
fn ifd_size(tags: &[(u16, Tag)]) -> usize {
    2 + tags.len() * 12 + 4 + tags.iter().map(|(_, t)| t.bytes().len()).filter(|&n| n > 4).map(|n| n + (n & 1)).sum::<usize>()
}

/// Writes an IFD that starts `at` bytes into the TIFF block (big-endian).
fn write_ifd(out: &mut Vec<u8>, at: usize, tags: &mut [(u16, Tag)]) {
    tags.sort_by_key(|t| t.0);
    let mut data_at = at + 2 + tags.len() * 12 + 4;
    let mut data = Vec::new();
    out.extend_from_slice(&(tags.len() as u16).to_be_bytes());
    for (id, t) in tags.iter() {
        let (kind, count) = t.kind();
        out.extend_from_slice(&id.to_be_bytes());
        out.extend_from_slice(&kind.to_be_bytes());
        out.extend_from_slice(&count.to_be_bytes());
        let b = t.bytes();
        if b.len() <= 4 {
            let mut v = b.clone();
            v.resize(4, 0);
            out.extend_from_slice(&v);
        } else {
            out.extend_from_slice(&(data_at as u32).to_be_bytes());
            data.extend_from_slice(&b);
            if b.len() & 1 == 1 {
                data.push(0);
            }
            data_at += b.len() + (b.len() & 1);
        }
    }
    out.extend_from_slice(&0u32.to_be_bytes());
    out.extend_from_slice(&data);
}

/// An EXIF (APP1) segment made from a RAW file's metadata: camera, lens, exposure and date.
fn raw_exif(path: &Path) -> Option<Vec<u8>> {
    use rawler::decoders::RawDecodeParams;
    let src = rawler::rawsource::RawSource::new(path).ok()?;
    let decoder = rawler::get_decoder(&src).ok()?;
    let meta = decoder.raw_metadata(&src, &RawDecodeParams::default()).ok()?;
    let e = &meta.exif;
    let mut ifd0: Vec<(u16, Tag)> = vec![
        (0x010F, Tag::Ascii(meta.make.clone())),
        (0x0110, Tag::Ascii(meta.model.clone())),
        (0x0112, Tag::Short(1)),
        (0x0131, Tag::Ascii(format!("Grainhouse {}", env!("CARGO_PKG_VERSION")))),
    ];
    if let Some(d) = e.modify_date.clone().or(e.date_time_original.clone()) {
        ifd0.push((0x0132, Tag::Ascii(d)));
    }
    if let Some(a) = &e.artist {
        ifd0.push((0x013B, Tag::Ascii(a.clone())));
    }
    if let Some(c) = &e.copyright {
        ifd0.push((0x8298, Tag::Ascii(c.clone())));
    }
    let mut sub: Vec<(u16, Tag)> = vec![(0xA001, Tag::Short(1))];
    let rat = |r: &rawler::formats::tiff::Rational| Tag::Rational(r.n, r.d.max(1));
    if let Some(r) = &e.exposure_time {
        sub.push((0x829A, rat(r)));
    }
    if let Some(r) = e.fnumber.as_ref().filter(|r| r.n > 0) {
        sub.push((0x829D, rat(r)));
    }
    if let Some(v) = e.exposure_program {
        sub.push((0x8822, Tag::Short(v)));
    }
    if let Some(v) = e.iso_speed_ratings {
        sub.push((0x8827, Tag::Short(v)));
    }
    if let Some(d) = &e.date_time_original {
        sub.push((0x9003, Tag::Ascii(d.clone())));
    }
    if let Some(d) = &e.create_date {
        sub.push((0x9004, Tag::Ascii(d.clone())));
    }
    if let Some(r) = &e.exposure_bias {
        sub.push((0x9204, Tag::SRational(r.n, r.d.max(1))));
    }
    if let Some(v) = e.metering_mode {
        sub.push((0x9207, Tag::Short(v)));
    }
    if let Some(v) = e.flash {
        sub.push((0x9209, Tag::Short(v)));
    }
    if let Some(r) = e.focal_length.as_ref().filter(|r| r.n > 0) {
        sub.push((0x920A, rat(r)));
    }
    let lens_make = e.lens_make.clone().or(meta.lens.as_ref().map(|l| l.lens_make.clone())).filter(|s| !s.is_empty() && !s.starts_with("--"));
    let lens_model = e.lens_model.clone().or(meta.lens.as_ref().map(|l| l.lens_model.clone())).filter(|s| !s.is_empty() && !s.starts_with("--"));
    if let Some(m) = lens_make {
        sub.push((0xA433, Tag::Ascii(m)));
    }
    if let Some(m) = lens_model {
        sub.push((0xA434, Tag::Ascii(m)));
    }
    // IFD0 (with a pointer to the EXIF IFD) right after the 8-byte header, the EXIF IFD after it
    ifd0.push((0x8769, Tag::Long(0)));
    let exif_at = 8 + ifd_size(&ifd0);
    if let Some(p) = ifd0.iter_mut().find(|t| t.0 == 0x8769) {
        p.1 = Tag::Long(exif_at as u32);
    }
    let mut tiff = b"MM\0\x2a\0\0\0\x08".to_vec();
    write_ifd(&mut tiff, 8, &mut ifd0);
    write_ifd(&mut tiff, exif_at, &mut sub);
    let mut seg = vec![0xFF, 0xE1];
    seg.extend_from_slice(&((2 + 6 + tiff.len()) as u16).to_be_bytes());
    seg.extend_from_slice(b"Exif\0\0");
    seg.extend_from_slice(&tiff);
    Some(seg)
}
