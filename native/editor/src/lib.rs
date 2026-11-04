//! JNI bridge between Gallery's editor (com.gal.editor.GrainhouseNative) and Grainhouse's editing library.
//!
//! The Kotlin side owns the edit as Grainhouse `Settings` JSON and asks for renders; this side keeps the
//! decoded photo in a `Session` behind an opaque handle. Every entry point catches panics and turns errors
//! into a Java RuntimeException, so a bad photo can't take the app down.

use grainhouse::export::{self, ExportOptions};
use grainhouse::io::RawOptions;
use grainhouse::pipeline::{self, Session, Settings};
use grainhouse::{adjust, camera, creative, develop, film, frame, fx, local, presets, whitebalance};
use jni::objects::{JClass, JString};
use jni::sys::{jint, jintArray, jlong, jstring};
use jni::JNIEnv;
use serde_json::{json, Value};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

/// Local adjustment kinds that need the AI subject mask, which this build doesn't have.
const AI_LOCAL_KINDS: [usize; 3] = [1, 2, 6];

/// Runs `f`, throwing a RuntimeException (and returning `fallback`) on an error or a panic.
fn guard<T>(env: &mut JNIEnv, fallback: T, f: impl FnOnce(&mut JNIEnv) -> Result<T, String>) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| f(env)));
    let msg = match result {
        Ok(Ok(v)) => return v,
        Ok(Err(e)) => e,
        Err(p) => p
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| p.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "the editor engine crashed".into()),
    };
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new("java/lang/RuntimeException", msg);
    }
    fallback
}

fn string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|s| s.into()).map_err(|e| e.to_string())
}

fn session<'a>(handle: jlong) -> Result<&'a Session, String> {
    if handle == 0 {
        return Err("no photo is open".into());
    }
    // SAFETY: handles come from `open` and stay valid until `close`; Kotlin never uses one after closing it.
    Ok(unsafe { &*(handle as *const Session) })
}

/// The settings from Kotlin, without anything this build can't do (AI effects and AI masks).
fn settings(json: &str) -> Result<Settings, String> {
    let mut s: Settings = serde_json::from_str(json).map_err(|e| format!("bad settings: {e}"))?;
    s.effect = pipeline::NONE;
    if AI_LOCAL_KINDS.contains(&s.local.kind) {
        s.local.kind = 0;
    }
    Ok(s)
}

fn new_string(env: &mut JNIEnv, s: String) -> Result<jstring, String> {
    env.new_string(s).map(|s| s.into_raw()).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_open(mut env: JNIEnv, _: JClass, path: JString) -> jlong {
    guard(&mut env, 0, |env| {
        let path = string(env, &path)?;
        let s = Session::open(Path::new(&path))?;
        Ok(Box::into_raw(Box::new(s)) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_close(mut env: JNIEnv, _: JClass, handle: jlong) {
    guard(&mut env, (), |_| {
        if handle != 0 {
            // SAFETY: see `session`; this is the one place a handle is freed.
            drop(unsafe { Box::from_raw(handle as *mut Session) });
        }
        Ok(())
    })
}

/// {"w", "h", "raw", "asShotKelvin"} of the open photo.
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_info(mut env: JNIEnv, _: JClass, handle: jlong) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let s = session(handle)?;
        let (w, h) = s.size();
        let info = json!({ "w": w, "h": h, "raw": s.is_raw(), "asShotKelvin": s.as_shot_kelvin() });
        new_string(env, info.to_string())
    })
}

/// Renders the edit with its long side at most `long` pixels: [width, height, ARGB pixels...].
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_render(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    settings_json: JString,
    long: jint,
) -> jintArray {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let s = settings(&string(env, &settings_json)?)?;
        let img = session(handle)?.render(&s, long.max(64) as usize)?;
        let mut out = Vec::with_capacity(img.px.len() + 2);
        out.push(img.w as i32);
        out.push(img.h as i32);
        out.extend(img.px.iter().map(|&p| (p | 0xFF00_0000) as i32));
        let arr = env.new_int_array(out.len() as i32).map_err(|e| e.to_string())?;
        env.set_int_array_region(&arr, 0, &out).map_err(|e| e.to_string())?;
        Ok(arr.into_raw())
    })
}

/// The Auto button: light settings worked out from the photo, as RawOptions JSON.
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_autoLight(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    raw_json: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let current: RawOptions = serde_json::from_str(&string(env, &raw_json)?).map_err(|e| e.to_string())?;
        let auto = session(handle)?.auto_light(current);
        new_string(env, serde_json::to_string(&auto).map_err(|e| e.to_string())?)
    })
}

/// A preset applied over the current edit (keeps the crop and the photo's own light, like the desktop app).
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_applyPreset(
    mut env: JNIEnv,
    _: JClass,
    current_json: JString,
    preset_json: JString,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let current = settings(&string(env, &current_json)?)?;
        let preset = settings(&string(env, &preset_json)?)?;
        let out = presets::apply(&current, &preset);
        new_string(env, serde_json::to_string(&out).map_err(|e| e.to_string())?)
    })
}

/// Renders at full size and writes `out_path` (format 0 JPEG, 1 PNG, 2 TIFF), keeping `source_path`'s EXIF.
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_export(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    settings_json: JString,
    out_path: JString,
    format: jint,
    quality: jint,
) {
    guard(&mut env, (), |env| {
        let s = settings(&string(env, &settings_json)?)?;
        let out = string(env, &out_path)?;
        let session = session(handle)?;
        let img = session.render(&s, usize::MAX)?;
        let opt = ExportOptions { format: format.clamp(0, 2) as usize, quality: quality.clamp(1, 100) as u8, keep_exif: true, ..ExportOptions::default() };
        export::save(&img, Path::new(&out), &opt, Some(session.path.as_path()))
    })
}

/// Everything the editor's pickers list, and the built-in presets that don't need AI.
#[no_mangle]
pub extern "system" fn Java_com_gal_editor_GrainhouseNative_catalog(mut env: JNIEnv, _: JClass) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let films: Vec<Value> = film::films()
            .iter()
            .map(|f| json!({ "file": f.file, "name": f.name, "brand": f.brand, "kind": f.kind }))
            .collect();
        let local_kinds: Vec<Value> = local::KINDS
            .iter()
            .enumerate()
            .filter(|(i, _)| !AI_LOCAL_KINDS.contains(i))
            .map(|(i, n)| json!({ "index": i, "name": n }))
            .collect();
        let wb: Vec<Value> = whitebalance::PRESETS
            .iter()
            .zip(whitebalance::PRESET_KELVIN)
            .map(|(n, k)| json!({ "name": n, "kelvin": k }))
            .collect();
        let mut preset_list = Vec::new();
        for (name, s) in presets::builtin() {
            if s.effect == pipeline::NONE && !AI_LOCAL_KINDS.contains(&s.local.kind) {
                preset_list.push(json!({ "name": name, "settings": serde_json::to_value(&s).map_err(|e| e.to_string())? }));
            }
        }
        let catalog = json!({
            "films": films,
            "brands": film::brands(),
            "creative": creative::NAMES,
            "cameras": camera::names(),
            "frames": frame::NAMES,
            "diffusion": fx::diffusion::NAMES,
            "grain": develop::GRAIN_NAMES,
            "localKinds": local_kinds,
            "whiteBalance": wb,
            "mixer": adjust::MIXER_NAMES,
            "grades": adjust::GRADE_NAMES,
            "curveChannels": adjust::CURVE_CHANNELS,
            "curveX": adjust::CURVE_X,
            "presets": preset_list,
            "defaults": serde_json::to_value(Settings::default()).map_err(|e| e.to_string())?,
        });
        new_string(env, catalog.to_string())
    })
}
