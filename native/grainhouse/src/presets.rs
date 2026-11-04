//! Presets: whole looks in one click. Built-in ones, and the user's own saved as JSON in
//! ~/.local/share/grainhouse/presets. A preset holds everything except the crop and the photo's own exposure
//! and white balance, which belong to each photo.

use crate::adjust::Adjust;
use crate::pipeline::{self, Settings};
use std::path::PathBuf;

fn film(id: &str) -> Option<String> {
    Some(id.to_string())
}

fn effect(name: &str) -> usize {
    pipeline::EFFECTS.iter().position(|e| *e == name).unwrap_or(0)
}

fn camera(name: &str) -> usize {
    crate::camera::CAMERAS.iter().position(|c| c.name == name).unwrap_or(0)
}

/// The built-in presets.
pub fn builtin() -> Vec<(&'static str, Settings)> {
    let d = Settings::default;
    let mut v = Vec::new();
    let mut s = d();
    s.film = film("109.bin");
    s.strength = 0.85;
    s.diffusion = 1;
    s.develop.halation = true;
    s.develop.grain = 1;
    v.push(("CineStill night", s));
    let mut s = d();
    s.film = film("50.bin");
    s.develop.grain = 1;
    s.adjust = Adjust { vibrance: 0.15, ..Adjust::default() };
    v.push(("Portra summer", s));
    let mut s = d();
    s.film = film("59.bin");
    s.develop.grain = 2;
    s.develop.monochrome = true;
    s.adjust = Adjust { clarity: 0.3, vignette: -0.3, ..Adjust::default() };
    v.push(("Classic black and white", s));
    let mut s = d();
    s.film = film("gen:0");
    s.strength = 0.8;
    s.diffusion = 1;
    s.develop.halation = true;
    v.push(("Teal and orange film", s));
    let mut s = d();
    s.film = film("gen:8");
    s.diffusion = 2;
    s.diffusion_opt.warmth = 0.5;
    v.push(("Golden hour glow", s));
    let mut s = d();
    s.film = film("gen:10");
    s.strength = 0.6;
    s.diffusion = 6;
    s.raw.highlights = -0.2;
    v.push(("Dreamy mist", s));
    let mut s = d();
    s.camera = camera("Leica M9 CCD-style");
    s.adjust = Adjust { clarity: 0.25, vignette: -0.3, ..Adjust::default() };
    v.push(("Leica street", s));
    let mut s = d();
    s.camera = camera("Disposable camera");
    s.date_stamp = true;
    s.develop.grain = 2;
    s.develop.light_leak = true;
    v.push(("Disposable summer", s));
    let mut s = d();
    s.camera = camera("2000s CCD compact");
    s.date_stamp = true;
    v.push(("2000s digicam", s));
    let mut s = d();
    s.film = film("gen:7");
    s.effect = effect("Anamorphic flare");
    s.look = 0;
    s.amount = 0.6;
    s.develop.halation = true;
    v.push(("Neon city", s));
    let mut s = d();
    s.effect = effect("Infrared");
    s.look = 0;
    s.amount = 1.0;
    s.diffusion = 5;
    v.push(("Infrared dream", s));
    let mut s = d();
    s.film = film("08.bin");
    s.frame = 4;
    v.push(("Polaroid memories", s));
    let mut s = d();
    s.film = film("47.bin");
    s.develop.grain = 1;
    s.frame = 6;
    v.push(("35mm contact sheet", s));
    let mut s = d();
    s.effect = effect("VHS");
    s.look = 1;
    v.push(("Home video", s));
    let mut s = d();
    s.camera = camera("Game Boy Camera");
    s.frame = 3;
    v.push(("Game Boy", s));
    v
}

pub fn dir() -> PathBuf {
    crate::lut::dir().with_file_name("presets")
}

/// The user's presets: (name, file), sorted by name.
pub fn user() -> Vec<(String, PathBuf)> {
    let mut out: Vec<(String, PathBuf)> = std::fs::read_dir(dir())
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|e| e == "json"))
        .map(|p| (p.file_stem().and_then(|s| s.to_str()).unwrap_or("Preset").to_string(), p))
        .collect();
    out.sort_by_key(|(n, _)| n.to_lowercase());
    out
}

pub fn save(name: &str, s: &Settings) -> Result<PathBuf, String> {
    let clean: String = name.chars().filter(|c| !matches!(c, '/' | '\\' | '\0')).collect::<String>().trim().to_string();
    if clean.is_empty() {
        return Err("Give the preset a name".into());
    }
    std::fs::create_dir_all(dir()).map_err(|e| format!("cannot make the presets folder: {e}"))?;
    let path = dir().join(format!("{clean}.json"));
    let json = serde_json::to_string_pretty(s).map_err(|e| e.to_string())?;
    std::fs::write(&path, json).map_err(|e| format!("cannot save {}: {e}", path.display()))?;
    Ok(path)
}

pub fn load(path: &std::path::Path) -> Result<Settings, String> {
    let text = std::fs::read_to_string(path).map_err(|e| format!("cannot read {}: {e}", path.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("{}: {e}", path.display()))
}

/// A preset applied to the current settings: its look, keeping the photo's crop, exposure and white balance.
pub fn apply(current: &Settings, preset: &Settings) -> Settings {
    let mut s = preset.clone();
    s.geometry = current.geometry;
    s.raw.exposure = current.raw.exposure;
    s.raw.kelvin = current.raw.kelvin;
    s.raw.tint = current.raw.tint;
    s
}

#[cfg(test)]
mod tests {
    #[test]
    fn round_trip() {
        for (_, s) in super::builtin() {
            let json = serde_json::to_string(&s).unwrap();
            let back: crate::pipeline::Settings = serde_json::from_str(&json).unwrap();
            assert_eq!(back, s);
        }
        // old files without newer fields still load
        let s: crate::pipeline::Settings = serde_json::from_str(r#"{"effect": 3}"#).unwrap();
        assert_eq!(s.effect, 3);
    }
}
