//! My LUTs: .cube files (3D or 1D, as made by Resolve, Photoshop, Lightroom profiles and LUT packs), kept in
//! ~/.local/share/grainhouse/luts. Applied in full (trilinear), unlike on the camera where they had to be
//! squeezed into a curve and a matrix. Expects LUTs made for normal (sRGB / Rec.709) pictures, not log footage.

use rayon::prelude::*;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

pub struct Lut {
    /// points per side (3D) or entries (1D)
    n: usize,
    three_d: bool,
    /// red changes fastest, as in the file
    data: Vec<[f32; 3]>,
    min: [f32; 3],
    max: [f32; 3],
}

/// Where the user's LUTs live.
pub fn dir() -> PathBuf {
    let base = std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/share"));
    base.join("grainhouse/luts")
}

/// The LUTs in the folder: (name without .cube, path), sorted by name.
pub fn list() -> Vec<(String, PathBuf)> {
    let mut out: Vec<(String, PathBuf)> = std::fs::read_dir(dir())
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.extension().and_then(|e| e.to_str()).is_some_and(|e| e.eq_ignore_ascii_case("cube")))
        .map(|p| (p.file_stem().and_then(|s| s.to_str()).unwrap_or("LUT").to_string(), p))
        .collect();
    out.sort_by_key(|(n, _)| n.to_lowercase());
    out
}

/// Copies a .cube file into the LUT folder (checking it first) and returns where it went.
pub fn add(src: &Path) -> Result<PathBuf, String> {
    let text = std::fs::read_to_string(src).map_err(|e| format!("cannot read {}: {e}", src.display()))?;
    parse(&text).map_err(|e| format!("{}: {e}", src.file_name().and_then(|n| n.to_str()).unwrap_or("LUT")))?;
    let d = dir();
    std::fs::create_dir_all(&d).map_err(|e| format!("cannot make {}: {e}", d.display()))?;
    let to = d.join(src.file_name().ok_or("no file name")?);
    if to != src {
        std::fs::copy(src, &to).map_err(|e| format!("cannot copy to {}: {e}", to.display()))?;
    }
    Ok(to)
}

/// Loads a LUT, from memory if the file hasn't changed.
pub fn load(path: &Path) -> Result<Arc<Lut>, String> {
    static CACHE: Mutex<Option<HashMap<PathBuf, (Option<SystemTime>, Arc<Lut>)>>> = Mutex::new(None);
    let modified = std::fs::metadata(path).and_then(|m| m.modified()).ok();
    let mut cache = CACHE.lock().unwrap();
    let cache = cache.get_or_insert_with(HashMap::new);
    if let Some((m, l)) = cache.get(path) {
        if *m == modified {
            return Ok(l.clone());
        }
    }
    let text = std::fs::read_to_string(path).map_err(|e| format!("cannot read LUT {}: {e}", path.display()))?;
    let lut = Arc::new(parse(&text).map_err(|e| format!("{}: {e}", path.display()))?);
    cache.insert(path.to_path_buf(), (modified, lut.clone()));
    Ok(lut)
}

pub fn parse(text: &str) -> Result<Lut, String> {
    let (mut n, mut three_d) = (0usize, true);
    let (mut min, mut max) = ([0f32; 3], [1f32; 3]);
    let mut data = Vec::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let mut words = line.split_whitespace();
        let first = words.next().unwrap_or("");
        let nums = |w: std::str::SplitWhitespace| -> Result<[f32; 3], String> {
            let v: Vec<f32> = w.map(|x| x.parse::<f32>().map_err(|_| format!("bad number in {line:?}"))).collect::<Result<_, _>>()?;
            v.get(..3).map(|v| [v[0], v[1], v[2]]).ok_or_else(|| format!("expected three numbers in {line:?}"))
        };
        match first.to_ascii_uppercase().as_str() {
            "LUT_3D_SIZE" => n = words.next().and_then(|v| v.parse().ok()).ok_or("bad LUT_3D_SIZE")?,
            "LUT_1D_SIZE" => {
                n = words.next().and_then(|v| v.parse().ok()).ok_or("bad LUT_1D_SIZE")?;
                three_d = false;
            }
            "DOMAIN_MIN" => min = nums(words)?,
            "DOMAIN_MAX" => max = nums(words)?,
            "TITLE" | "LUT_3D_INPUT_RANGE" | "LUT_1D_INPUT_RANGE" => {
                if first.eq_ignore_ascii_case("LUT_3D_INPUT_RANGE") || first.eq_ignore_ascii_case("LUT_1D_INPUT_RANGE") {
                    let v: Vec<f32> = words.filter_map(|x| x.parse().ok()).collect();
                    if v.len() == 2 {
                        (min, max) = ([v[0]; 3], [v[1]; 3]);
                    }
                }
            }
            w if w.starts_with(|c: char| c.is_ascii_digit() || c == '-' || c == '.') => data.push(nums(line.split_whitespace())?),
            _ => {} // other keywords are fine to skip
        }
    }
    if n < 2 {
        return Err("not a .cube LUT (no LUT_3D_SIZE or LUT_1D_SIZE)".into());
    }
    let want = if three_d { n * n * n } else { n };
    if data.len() < want {
        return Err(format!("expected {want} entries, found {}", data.len()));
    }
    data.truncate(want);
    Ok(Lut { n, three_d, data, min, max })
}

impl Lut {
    /// A 3D LUT made from a colour function (sRGB 0..1 in and out), `n` points per side.
    pub fn from_fn(n: usize, f: impl Fn([f32; 3]) -> [f32; 3] + Sync) -> Lut {
        let n = n.max(2);
        let data: Vec<[f32; 3]> = (0..n * n * n)
            .into_par_iter()
            .map(|i| {
                let (r, g, b) = (i % n, (i / n) % n, i / (n * n));
                let k = (n - 1) as f32;
                f([r as f32 / k, g as f32 / k, b as f32 / k])
            })
            .collect();
        Lut { n, three_d: true, data, min: [0.0; 3], max: [1.0; 3] }
    }

    /// Applies the LUT to packed RGB pixels, mixed with the original by `strength` (0..1).
    pub fn apply(&self, px: &mut [u32], strength: f32) {
        let k = strength.clamp(0.0, 1.0);
        if k == 0.0 {
            return;
        }
        // where each 8-bit value falls in the table, per channel
        let pos: [Vec<(usize, usize, f32)>; 3] = [0, 1, 2].map(|c| {
            (0..256)
                .map(|v| {
                    let t = ((v as f32 / 255.0 - self.min[c]) / (self.max[c] - self.min[c]).max(1e-6)).clamp(0.0, 1.0) * (self.n - 1) as f32;
                    let i = (t as usize).min(self.n - 2);
                    (i, i + 1, t - i as f32)
                })
                .collect()
        });
        let n = self.n;
        let d = &self.data;
        px.par_chunks_mut(4096).for_each(|chunk| {
            for p in chunk.iter_mut() {
                let (r, g, b) = ((*p >> 16) & 255, (*p >> 8) & 255, *p & 255);
                let out = if self.three_d {
                    let (r0, r1, fr) = pos[0][r as usize];
                    let (g0, g1, fg) = pos[1][g as usize];
                    let (b0, b1, fb) = pos[2][b as usize];
                    let at = |ri: usize, gi: usize, bi: usize| d[(bi * n + gi) * n + ri];
                    let mut o = [0f32; 3];
                    for c in 0..3 {
                        let l = |a: f32, b: f32, t: f32| a + (b - a) * t;
                        let c00 = l(at(r0, g0, b0)[c], at(r1, g0, b0)[c], fr);
                        let c10 = l(at(r0, g1, b0)[c], at(r1, g1, b0)[c], fr);
                        let c01 = l(at(r0, g0, b1)[c], at(r1, g0, b1)[c], fr);
                        let c11 = l(at(r0, g1, b1)[c], at(r1, g1, b1)[c], fr);
                        o[c] = l(l(c00, c10, fg), l(c01, c11, fg), fb);
                    }
                    o
                } else {
                    [0, 1, 2].map(|c| {
                        let (i0, i1, f) = pos[c][[r, g, b][c] as usize];
                        d[i0][c] + (d[i1][c] - d[i0][c]) * f
                    })
                };
                let mix = |orig: u32, v: f32| {
                    let o = orig as f32;
                    (o + (v.clamp(0.0, 1.0) * 255.0 - o) * k + 0.5) as u32
                };
                *p = (mix(r, out[0]) << 16) | (mix(g, out[1]) << 8) | mix(b, out[2]);
            }
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_and_invert() {
        let mut text = String::from("TITLE \"test\"\nLUT_3D_SIZE 2\n");
        for b in 0..2 {
            for g in 0..2 {
                for r in 0..2 {
                    text += &format!("{} {} {}\n", 1 - r, 1 - g, 1 - b);
                }
            }
        }
        let lut = parse(&text).unwrap();
        let mut px = vec![0x204080u32, 0xffffff];
        lut.apply(&mut px, 1.0);
        assert_eq!(px, vec![0xdfbf7f, 0x000000]);
        let mut half = vec![0x000000u32];
        lut.apply(&mut half, 0.5);
        assert_eq!(half, vec![0x808080]);
        assert!(parse("LUT_3D_SIZE 3\n0 0 0\n").is_err());
    }
}
