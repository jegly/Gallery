//! The whole edit: RAW develop, film look, AI effect, diffusion, then halation, light leak and grain.
//! A Session holds one photo and caches the slow parts (RAW decoding, the models' results).

use crate::ai::{self, Pose, Subjects, MODEL_SIZES};
use crate::develop::{develop, DevelopOptions};
use crate::film;
use crate::fx::{self, contours::Contours, diffusion::{Diffusion, DiffusionOptions}, ghost::Ghost, mesh::Mesh, scene::Scene, skeleton::Skeleton};
use crate::geometry::{self, Geometry};
use crate::img::Image;
use crate::io::{self, RawLinear, RawOptions};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const EFFECTS: [&str; 31] = [
    "None", "Ghosts", "Wireframe", "Contours", "Face mesh", "Points", "Noir pop", "Bokeh", "Rim light", "Glitch", "Thermal", "Comic", "Skeleton",
    "Crowd remover", "Trails", "Subject light", "Zoom burst", "Motion pan", "Neon edges", "Pixel sort", "3D anaglyph", "Infrared",
    "Anamorphic flare", "Light rays", "Tilt-shift", "Duotone", "Oil paint", "Sketch", "Mirror", "VHS", "Double exposure",
];
/// The desktop effects (fx::extra) start here, in fx::extra::NAMES order.
pub const EXTRA_FIRST: usize = 15;
pub const DOUBLE: usize = EXTRA_FIRST + fx::extra::DOUBLE;
pub const NONE: usize = 0;
pub const GHOSTS: usize = 1;
pub const WIREFRAME: usize = 2;
pub const CONTOURS: usize = 3;
pub const FACE_MESH: usize = 4;
pub const POINTS: usize = 5;
pub const SCENE_FIRST: usize = 6; // Noir pop .. Comic, in Scene's order
pub const SCENE_LAST: usize = 11;
pub const SKELETON: usize = 12;
pub const CROWD: usize = 13;
pub const TRAILS: usize = 14;

/// Effects made from several photos (the others work on one).
pub fn is_multi(effect: usize) -> bool {
    effect == CROWD || effect == TRAILS || effect == DOUBLE
}

/// How many photos a several-photo effect needs.
pub fn photos_needed(effect: usize) -> usize {
    if effect == DOUBLE { 2 } else if is_multi(effect) { 2 } else { 1 }
}

/// Effects that run an AI model (and so take a moment the first time).
pub fn needs_model(effect: usize) -> bool {
    match effect {
        NONE | CROWD | TRAILS => false,
        e if e >= EXTRA_FIRST => fx::extra::uses_subjects(e - EXTRA_FIRST),
        _ => true,
    }
}

/// The choices for an effect's Look row (empty = no Look row).
pub fn looks(effect: usize) -> &'static [&'static str] {
    match effect {
        GHOSTS => &fx::ghost::LOOKS,
        e if e >= EXTRA_FIRST => fx::extra::looks(e - EXTRA_FIRST),
        _ => &[],
    }
}

#[derive(Clone, PartialEq, Debug, serde::Serialize, serde::Deserialize)]
#[serde(default)]
pub struct Settings {
    pub raw: RawOptions,
    /// Film file name from the library (e.g. "57.bin"), "lut:" + the path of a .cube file, or None.
    pub film: Option<String>,
    pub strength: f32,
    pub develop: DevelopOptions,
    pub effect: usize,
    pub target: usize,
    pub model: usize,
    pub look: usize,
    pub face_style: usize,
    /// Ghosts: opacity; Wireframe/Contours: how dim the subject is; scene effects: strength. 0..1
    pub amount: f32,
    pub colour: usize,
    pub glow: usize,
    pub diffusion: usize,
    pub diffusion_opt: DiffusionOptions,
    /// turn, straighten and crop
    pub geometry: Geometry,
    /// camera look (index into camera::CAMERAS, 0 = none) and how much of it
    pub camera: usize,
    pub camera_strength: f32,
    /// the orange date stamp
    pub date_stamp: bool,
    /// colour and detail
    pub adjust: crate::adjust::Adjust,
    /// frame or border round the picture (frame::NAMES)
    pub frame: usize,
    /// a local adjustment through a mask
    pub local: crate::local::Local,
}

impl Default for Settings {
    fn default() -> Self {
        Settings {
            raw: RawOptions::default(),
            film: None,
            strength: 1.0,
            develop: DevelopOptions::default(),
            effect: NONE,
            target: 0,
            model: 1,
            look: 1,
            face_style: 0,
            amount: 0.85,
            colour: 0,
            glow: 2,
            diffusion: 0,
            diffusion_opt: DiffusionOptions::default(),
            geometry: Geometry::default(),
            camera: 0,
            camera_strength: 1.0,
            date_stamp: false,
            adjust: crate::adjust::Adjust::default(),
            frame: 0,
            local: crate::local::Local::default(),
        }
    }
}

/// What the models found, in fractions of the photo's size (so it fits any output size).
#[derive(Clone)]
struct Found {
    subjects: Option<Subjects>,
    poses: Vec<Pose>,
    /// face landmarks, 478 x/y pairs as fractions
    faces: Vec<Vec<f32>>,
}

pub struct Session {
    pub path: PathBuf,
    raw: Option<RawLinear>,
    /// a JPEG/PNG/TIFF as loaded (the base is made from it with the light controls)
    original: Option<Image>,
    base: Mutex<(RawOptions, Image)>,
    /// the same at preview size, so moving a light slider stays quick
    small_raw: Option<RawLinear>,
    small_original: Option<Image>,
    small: Mutex<(RawOptions, Image)>,
    cache: Mutex<HashMap<String, Found>>,
    /// when the photo was taken, for the date stamp
    pub date: Option<(i32, u32, u32)>,
}

/// Long side of the preview-size copy.
const SMALL_LONG: usize = 2560;

/// Long side of the copy the models look at.
const DETECT_SIZE: usize = 2048;

impl Session {
    pub fn open(path: &Path) -> Result<Session, String> {
        let (raw, base, original, small_raw, small_original, small) = if io::is_raw(path) {
            let raw = io::decode_raw(path)?;
            let img = io::render_raw(&raw, RawOptions::default());
            let sr = raw.shrink(SMALL_LONG);
            let small = io::render_raw(&sr, RawOptions::default());
            (Some(raw), img, None, Some(sr), None, small)
        } else {
            let img = io::load_image(path)?;
            let so = img.fit(SMALL_LONG);
            (None, img.clone(), Some(img), None, Some(so.clone()), so)
        };
        let date = crate::camera::photo_date(path);
        Ok(Session {
            path: path.to_path_buf(),
            raw,
            original,
            base: Mutex::new((RawOptions::default(), base)),
            small_raw,
            small_original,
            small: Mutex::new((RawOptions::default(), small)),
            cache: Mutex::new(HashMap::new()),
            date,
        })
    }

    pub fn is_raw(&self) -> bool {
        self.raw.is_some()
    }

    /// The camera's own white balance as a colour temperature (RAW files only).
    pub fn as_shot_kelvin(&self) -> Option<f32> {
        self.raw.as_ref().and_then(|r| r.as_shot_kelvin())
    }

    /// The photo developed with these RAW settings (non-RAW photos ignore them).
    pub fn base(&self, raw_opts: RawOptions) -> Image {
        let mut b = self.base.lock().unwrap();
        self.develop(&mut b, raw_opts);
        b.1.clone()
    }

    /// Re-makes the base picture if the light settings changed.
    fn develop(&self, b: &mut (RawOptions, Image), opts: RawOptions) {
        if b.0 == opts {
            return;
        }
        if let Some(raw) = &self.raw {
            *b = (opts, io::render_raw(raw, opts));
        } else if let Some(o) = &self.original {
            *b = (opts, io::render_display(o, opts));
        }
    }

    /// Size of the photo as opened (upright, before any crop).
    pub fn size(&self) -> (usize, usize) {
        let b = self.base.lock().unwrap();
        (b.1.w, b.1.h)
    }

    /// Size of the finished picture at full resolution, after the crop.
    pub fn output_size(&self, s: &Settings) -> (usize, usize) {
        let (w, h) = self.size();
        s.geometry.size(w, h)
    }

    /// The developed photo turned, straightened and cropped, long side at most `long`. Uses the preview-size
    /// copy when it still has enough pixels for the crop.
    fn shaped(&self, s: &Settings, long: usize) -> Image {
        {
            let mut b = self.small.lock().unwrap();
            let (cw, ch) = s.geometry.size(b.1.w, b.1.h);
            let (fw, fh) = self.size();
            if cw.max(ch) >= long.min(s.geometry.size(fw, fh).0.max(s.geometry.size(fw, fh).1)) {
                if b.0 != s.raw {
                    let img = if let Some(r) = &self.small_raw {
                        io::render_raw(r, s.raw)
                    } else if let Some(o) = &self.small_original {
                        io::render_display(o, s.raw)
                    } else {
                        b.1.clone()
                    };
                    *b = (s.raw, img);
                }
                return geometry::apply(&b.1, &s.geometry, long);
            }
        }
        let mut b = self.base.lock().unwrap();
        self.develop(&mut b, s.raw);
        geometry::apply(&b.1, &s.geometry, long)
    }

    /// Light settings worked out from the photo (the Auto button): exposure to put the middle tones at mid-grey,
    /// then shadows, highlights, whites and blacks to use the whole range without clipping. Keeps white balance.
    pub fn auto_light(&self, current: RawOptions) -> RawOptions {
        let probe = RawOptions { kelvin: current.kelvin, tint: current.tint, ..RawOptions::default() };
        let img = {
            let b = self.small.lock().unwrap();
            if let Some(r) = &self.small_raw { io::render_raw(r, probe) } else if let Some(o) = &self.small_original { io::render_display(o, probe) } else { b.1.clone() }
        };
        let lin = |v: f32| if v <= 0.04045 { v / 12.92 } else { ((v + 0.055) / 1.055).powf(2.4) };
        let mut l: Vec<f32> = img
            .px
            .iter()
            .step_by(7)
            .map(|p| lin((0.299 * ((p >> 16) & 255) as f32 + 0.587 * ((p >> 8) & 255) as f32 + 0.114 * (p & 255) as f32) / 255.0))
            .collect();
        if l.is_empty() {
            return current;
        }
        l.sort_by(|a, b| a.total_cmp(b));
        let at = |q: f32| l[((l.len() - 1) as f32 * q) as usize];
        // the average brightness in stops (so a few bright or dark areas don't dominate), aimed at mid-grey,
        // but never so far that the brightest areas would clip
        let mean_log = l.iter().map(|v| v.max(1e-4).log2()).sum::<f32>() / l.len() as f32;
        let towards_grey = (0.16f32.log2() - mean_log) * 0.6;
        let headroom = (0.92 / at(0.985).max(1e-4)).log2();
        let exposure = towards_grey.min(headroom.max(0.0)).clamp(-1.5, 1.5);
        let gain = exposure.exp2();
        let bright = at(0.99) * gain;
        let dark = at(0.03) * gain;
        RawOptions {
            exposure: (exposure * 20.0).round() / 20.0,
            highlights: if bright > 0.85 { -(((bright - 0.85) * 2.0).min(0.6)) } else { 0.0 },
            shadows: if dark < 0.01 { (0.35 - dark * 20.0).clamp(0.0, 0.35) } else { 0.0 },
            whites: if bright < 0.7 { ((0.7 - bright) * 1.5).min(0.4) } else { 0.0 },
            blacks: if dark > 0.03 { -((dark - 0.03) * 6.0).min(0.3) } else { 0.0 },
            ..current
        }
    }

    /// The AI subject mask for local adjustments (people, from the cropped picture), cached like the effects'.
    fn subject_mask(&self, s: &Settings) -> Result<Option<fx::extra::Mask>, String> {
        let mut q = s.clone();
        q.effect = GHOSTS;
        q.target = 0;
        let f = self.found(&q)?;
        Ok(f.subjects.filter(|x| x.count > 0).map(|x| fx::extra::Mask::new(&x.mask, x.mw, x.mh)))
    }

    /// True when rendering with these settings will run the AI models first (the slow part).
    pub fn needs_ai(&self, s: &Settings) -> bool {
        let local = crate::local::uses_subject(s.local.kind) && {
            let mut q = s.clone();
            q.effect = GHOSTS;
            q.target = 0;
            !self.cache.lock().unwrap().contains_key(&found_key(&q))
        };
        local || (needs_model(s.effect) && (!is_multi(s.effect) || s.effect == DOUBLE) && !self.cache.lock().unwrap().contains_key(&found_key(s)))
    }

    /// After a render: a note when the effect found nothing to work on, with what to try.
    pub fn nothing_found(&self, s: &Settings) -> Option<String> {
        if !needs_model(s.effect) || (is_multi(s.effect) && s.effect != DOUBLE) {
            return None;
        }
        let cache = self.cache.lock().unwrap();
        let f = cache.get(&found_key(s))?;
        let empty = match s.effect {
            SKELETON => f.poses.is_empty(),
            FACE_MESH => f.faces.is_empty(),
            _ => f.subjects.as_ref().is_none_or(|x| x.count == 0),
        };
        if !empty {
            return None;
        }
        let bigger = if s.model < 2 { " Try AI model Fine." } else { "" };
        Some(match s.effect {
            SKELETON => format!("Skeleton found no people.{bigger}"),
            FACE_MESH => "Face mesh found no faces. They need to be fairly big in the picture and facing the camera.".to_string(),
            _ => {
                let what = ai::TARGET_NAMES[s.target.min(ai::TARGET_NAMES.len() - 1)].to_lowercase();
                let other = if s.target == ai::TARGET_NAMES.len() - 1 { "" } else { " Or set Target to Everything." };
                format!("{} found no {what}.{bigger}{other}", EFFECTS[s.effect])
            }
        })
    }

    /// The models' results for these settings, from the cache or by running them.
    fn found(&self, s: &Settings) -> Result<Found, String> {
        let key = found_key(s);
        if let Some(f) = self.cache.lock().unwrap().get(&key) {
            return Ok(f.clone());
        }
        let det = self.shaped(s, DETECT_SIZE);
        let size = MODEL_SIZES[s.model.min(2)];
        let (fw, fh) = (det.w as f32, det.h as f32);
        let to_frac = |pts: Vec<f32>| -> Vec<f32> { pts.chunks(2).flat_map(|p| [p[0] / fw, p[1] / fh]).collect() };
        let mut found = Found { subjects: None, poses: Vec::new(), faces: Vec::new() };
        match s.effect {
            SKELETON => {
                found.poses = ai::poses(&det, size)?
                    .into_iter()
                    .map(|mut p| {
                        for k in 0..17 {
                            p.kp[3 * k] /= fw;
                            p.kp[3 * k + 1] /= fh;
                        }
                        p.height /= fh;
                        p
                    })
                    .collect();
            }
            FACE_MESH => {
                found.faces = ai::faces(&det, None, 8)?.into_iter().map(to_frac).collect();
            }
            e if !needs_model(e) => {}
            _ => {
                let subj = ai::subjects(&det, size, s.target)?;
                if (s.effect == WIREFRAME || s.effect == POINTS) && s.target == 0 {
                    found.faces = ai::faces(&det, Some(&subj.boxes), 6)?.into_iter().map(to_frac).collect();
                }
                found.subjects = Some(subj);
            }
        }
        self.cache.lock().unwrap().insert(key, found.clone());
        Ok(found)
    }

    /// Renders the edit with the long side at most `long` pixels (usize::MAX for full size).
    pub fn render(&self, s: &Settings, long: usize) -> Result<Image, String> {
        let mut img = self.shaped(s, long);
        crate::camera::colour(&mut img, s.camera, s.camera_strength);
        apply_film(&mut img, s)?;
        crate::adjust::apply_curve(&mut img, &s.adjust);
        crate::adjust::apply_colour(&mut img, &s.adjust);
        crate::adjust::apply_detail(&mut img, &s.adjust);
        if s.local.kind != 0 {
            let mask = if crate::local::uses_subject(s.local.kind) { self.subject_mask(s)? } else { None };
            crate::local::apply(&mut img, &s.local, mask.as_ref());
        }
        if s.effect != NONE && !is_multi(s.effect) {
            let found = self.found(s)?;
            apply_effect(&mut img, s, &found, seed_of(&self.path), self.date);
        }
        finish(&mut img, s, seed_of(&self.path), self.date);
        Ok(img)
    }
}

fn found_key(s: &Settings) -> String {
    let g = &s.geometry;
    format!("{}-{}-{}-{}-{}-{}-{}-{}-{}-{}", s.effect, s.model, s.target, s.raw.exposure, g.quarter, g.angle, g.x, g.y, g.w, g.h)
}

fn seed_of(path: &Path) -> u64 {
    path.to_string_lossy().bytes().fold(1469598103934665603u64, |h, b| (h ^ b as u64).wrapping_mul(1099511628211))
}

fn apply_film(img: &mut Image, s: &Settings) -> Result<(), String> {
    let Some(name) = s.film.as_deref() else { return Ok(()) };
    if let Some(n) = name.strip_prefix("gen:") {
        if let Some(l) = n.parse().ok().and_then(crate::creative::get) {
            l.apply(&mut img.px, s.strength);
        }
    } else if let Some(path) = name.strip_prefix("lut:") {
        crate::lut::load(Path::new(path))?.apply(&mut img.px, s.strength);
    } else if let Some(look) = film::load(name) {
        film::Applied::new(&look, s.strength).apply(&mut img.px);
    }
    Ok(())
}

/// The camera's lens and sensor character, diffusion, the date stamp, then the develop effects (grain last).
fn finish(img: &mut Image, s: &Settings, seed: u64, date: Option<(i32, u32, u32)>) {
    crate::camera::character(img, s.camera, s.camera_strength, seed);
    if s.diffusion > 0 {
        let (dw, dh) = scaled(img, 640);
        let preview = img.resized(dw, dh);
        fx::run(&Diffusion::new(&preview.px, dw, dh, s.diffusion, &s.diffusion_opt), img);
    }
    crate::adjust::apply_vignette(img, s.adjust.vignette, s.adjust.vignette_mid, s.adjust.vignette_feather);
    if s.date_stamp {
        if let Some(date) = date {
            crate::camera::date_stamp(img, date);
        }
    }
    let mut d = s.develop;
    d.seed = seed;
    develop(img, &d);
    if s.frame > 0 {
        let label = edge_label(s);
        *img = crate::frame::apply(std::mem::replace(img, Image::new(1, 1)), s.frame, seed, &label);
    }
}

/// What a 35 mm strip's edge says: the film's name if one is chosen.
fn edge_label(s: &Settings) -> String {
    let name = s.film.as_deref().and_then(|f| film::films().into_iter().find(|x| x.file == f).map(|x| x.name.to_string()));
    let name = name.unwrap_or_else(|| "GRAINHOUSE 400".to_string()).to_uppercase();
    name.chars().filter(|c| c.is_ascii_alphanumeric() || *c == ' ').take(18).collect()
}

fn scaled(img: &Image, long: usize) -> (usize, usize) {
    let k = long as f32 / img.w.max(img.h) as f32;
    (((img.w as f32 * k).round() as usize).max(2), ((img.h as f32 * k).round() as usize).max(2))
}

fn apply_effect(img: &mut Image, s: &Settings, found: &Found, seed: u64, date: Option<(i32, u32, u32)>) {
    if s.effect >= EXTRA_FIRST {
        let mask = found.subjects.as_ref().filter(|x| x.count > 0).map(|x| fx::extra::Mask::new(&x.mask, x.mw, x.mh));
        fx::extra::apply(s.effect - EXTRA_FIRST, img, mask.as_ref(), s.look, s.amount, s.colour, seed, date);
        return;
    }
    let edges = ai::mesh_edges();
    match s.effect {
        SKELETON => {
            let mut sk = Skeleton::new(s.amount, s.glow, s.colour);
            for p in &found.poses {
                sk.add_person(&p.kp, p.height, img.w as f32 / img.h as f32);
            }
            fx::run(&sk, img);
        }
        FACE_MESH => {
            let (mw, mh) = scaled(img, 480);
            let mut mesh = Mesh::new(&vec![0u8; mw * mh], mw, mh, 0.0, s.glow, s.colour, seed, false);
            mesh.set_face_dim(s.amount * 0.5);
            for f in &found.faces {
                mesh.add_face(&to_px(f, mw, mh), edges, s.face_style);
            }
            fx::run(&mesh, img);
        }
        _ => {
            let Some(subj) = &found.subjects else { return };
            if subj.count == 0 {
                return;
            }
            let (mw, mh, mask) = (subj.mw, subj.mh, &subj.mask);
            let preview = img.resized(mw, mh);
            match s.effect {
                GHOSTS => fx::run(&Ghost::new(mask, mw, mh, s.glow, s.look, s.amount, s.colour, &preview.px), img),
                CONTOURS => fx::run(&Contours::new(mask, mw, mh, s.amount, s.glow, s.colour), img),
                WIREFRAME | POINTS => {
                    let mut mesh = Mesh::new(mask, mw, mh, s.amount, s.glow, s.colour, seed, s.effect == POINTS);
                    for f in &found.faces {
                        mesh.add_face(&to_px(f, mw, mh), edges, s.face_style);
                    }
                    fx::run(&mesh, img);
                }
                e if (SCENE_FIRST..=SCENE_LAST).contains(&e) => {
                    let (bw, bh) = scaled(img, 1600.min(img.w.max(img.h)));
                    let big = img.resized(bw, bh);
                    let sc = Scene::new(e - SCENE_FIRST, mask, mw, mh, &big.px, bw, bh, s.amount, s.colour, seed as i32);
                    fx::run(&sc, img);
                }
                _ => {}
            }
        }
    }
}

fn to_px(frac: &[f32], mw: usize, mh: usize) -> Vec<f32> {
    frac.chunks(2).flat_map(|p| [p[0] * mw as f32, p[1] * mh as f32]).collect()
}

/// Crowd remover / Trails over several photos (oldest first), then the film look and finishing.
pub fn render_multi(sessions: &[&Session], s: &Settings, long: usize) -> Result<Image, String> {
    let frames: Vec<Image> = sessions.iter().map(|x| x.shaped(s, long)).collect();
    let mut img = if s.effect == DOUBLE {
        if frames.len() < 2 {
            return Err("Double exposure needs two photos: a portrait first, then the photo to fill it with".into());
        }
        let found = sessions[0].found(s)?;
        let subj = found.subjects.filter(|x| x.count > 0).ok_or("Double exposure found nobody in the first photo")?;
        let mask = fx::extra::Mask::new(&subj.mask, subj.mw, subj.mh);
        fx::extra::double_exposure(&frames[0], &frames[1], &mask, s.look, s.amount)
    } else {
        let kind = if s.effect == TRAILS { fx::multishot::TRAILS } else { fx::multishot::CROWD };
        fx::multishot::combine(&frames, kind, s.amount, s.colour)?
    };
    crate::camera::colour(&mut img, s.camera, s.camera_strength);
    apply_film(&mut img, s)?;
    crate::adjust::apply_curve(&mut img, &s.adjust);
    crate::adjust::apply_colour(&mut img, &s.adjust);
    crate::adjust::apply_detail(&mut img, &s.adjust);
    if s.local.kind != 0 {
        let mask = if crate::local::uses_subject(s.local.kind) { sessions[0].subject_mask(s)? } else { None };
        crate::local::apply(&mut img, &s.local, mask.as_ref());
    }
    finish(&mut img, s, sessions.first().map(|x| seed_of(&x.path)).unwrap_or(1), sessions.first().and_then(|x| x.date));
    Ok(img)
}

#[cfg(test)]
mod tests {
    #[test]
    fn auto_light_is_sane_on_a_dark_scene() {
        let path = std::path::Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/testdata/JES05253.JPG"));
        if !path.exists() {
            return;
        }
        let s = super::Session::open(path).unwrap();
        let r = s.auto_light(crate::io::RawOptions::default());
        assert!(r.exposure.abs() <= 1.5, "{r:?}");
    }
}
