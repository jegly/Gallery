//! Running the models on a photo: the subject mask and boxes (YOLO11n-seg), poses (YOLO11n-pose) and face
//! meshes (MediaPipe). Everything is in the photo's own pixel coordinates, and results are kept so changing
//! a look doesn't run the models again.

use crate::engine::{self, Model};
use crate::facemesh::{self, MeshEdges};
use crate::img::Image;
use std::sync::{Mutex, OnceLock};

/// Model input sizes (long side). Bigger than on the camera: a computer can afford it, and small or half-hidden
/// people are missed at 480.
pub const MODEL_SIZES: [usize; 3] = [480, 640, 1024];
pub const MODEL_NAMES: [&str; 3] = ["Fast", "Normal", "Fine"];

pub const TARGET_NAMES: [&str; 5] = ["People", "Vehicles", "Animals", "People + animals", "Everything"];
const VEHICLES: [usize; 8] = [1, 2, 3, 4, 5, 6, 7, 8];
const ANIMALS: [usize; 10] = [14, 15, 16, 17, 18, 19, 20, 21, 22, 23];

pub fn classes(target: usize) -> [u8; 80] {
    let mut c = [0u8; 80];
    match target {
        0 => c[0] = 1,
        1 => VEHICLES.iter().for_each(|&i| c[i] = 1),
        2 => ANIMALS.iter().for_each(|&i| c[i] = 1),
        3 => {
            c[0] = 1;
            ANIMALS.iter().for_each(|&i| c[i] = 1);
        }
        _ => c = [1; 80],
    }
    c
}

struct Models {
    seg: Option<Model>,
    pose: Option<Model>,
    det: Option<Model>,
    lm: Option<Model>,
}

fn models() -> &'static Mutex<Models> {
    static M: OnceLock<Mutex<Models>> = OnceLock::new();
    M.get_or_init(|| Mutex::new(Models { seg: None, pose: None, det: None, lm: None }))
}

pub fn mesh_edges() -> &'static MeshEdges {
    static E: OnceLock<MeshEdges> = OnceLock::new();
    E.get_or_init(|| MeshEdges::load(engine::FACE_MESH_EDGES))
}

fn get<'a>(slot: &'a mut Option<Model>, data: &[u8]) -> Result<&'a mut Model, String> {
    if slot.is_none() {
        *slot = Some(Model::load(data)?);
    }
    Ok(slot.as_mut().unwrap())
}

/// Model input size for a photo: `size` on the long side, the other side rounded to a multiple of 32.
pub fn model_dims(w: usize, h: usize, size: usize) -> (usize, usize) {
    let r32 = |v: f32| ((v / 32.0).round() as usize * 32).max(32);
    if w >= h {
        (size, r32(size as f32 * h as f32 / w as f32))
    } else {
        (r32(size as f32 * w as f32 / h as f32), size)
    }
}

/// Subjects found in a photo: a soft mask (0..255) at model size, and boxes in photo pixels.
#[derive(Clone)]
pub struct Subjects {
    pub mw: usize,
    pub mh: usize,
    pub mask: Vec<u8>,
    pub boxes: Vec<[f32; 5]>,
    pub count: usize,
}

pub fn subjects(img: &Image, size: usize, target: usize) -> Result<Subjects, String> {
    let (mw, mh) = model_dims(img.w, img.h, size);
    let small = img.resized(mw, mh);
    let mut guard = models().lock().unwrap();
    let m = get(&mut guard.seg, engine::SEG_MODEL)?;
    m.run(&small.rgb_bytes(), mw, mh)?;
    let mut mask = vec![0u8; mw * mh];
    let n = m.objects(0.2, &classes(target), &mut mask).max(0) as usize;
    let (sx, sy) = (img.w as f32 / mw as f32, img.h as f32 / mh as f32);
    let boxes = m.boxes().into_iter().map(|b| [b[0] * sx, b[1] * sy, b[2] * sx, b[3] * sy, b[4]]).collect();
    Ok(Subjects { mw, mh, mask, boxes, count: n })
}

/// People's poses: 17 COCO keypoints (x, y in photo pixels, visibility) and box height in pixels.
#[derive(Clone)]
pub struct Pose {
    pub kp: [f32; 51],
    pub height: f32,
}

pub fn poses(img: &Image, size: usize) -> Result<Vec<Pose>, String> {
    let (mw, mh) = model_dims(img.w, img.h, size);
    let small = img.resized(mw, mh);
    let mut guard = models().lock().unwrap();
    let m = get(&mut guard.pose, engine::POSE_MODEL)?;
    m.run(&small.rgb_bytes(), mw, mh)?;
    let (sx, sy) = (img.w as f32 / mw as f32, img.h as f32 / mh as f32);
    Ok(m.poses(0.3)
        .into_iter()
        .map(|p| {
            let mut kp = [0f32; 51];
            for k in 0..17 {
                kp[3 * k] = p[5 + 3 * k] * sx;
                kp[3 * k + 1] = p[6 + 3 * k] * sy;
                kp[3 * k + 2] = p[7 + 3 * k];
            }
            Pose { kp, height: (p[3] - p[1]) * sy }
        })
        .collect())
}

/// Smallest head crop worth looking in (photo pixels): smaller faces don't show a mesh anyway.
const MIN_HEAD: f32 = 48.0;

/// Face landmarks (478 x/y pairs in photo pixels). With boxes, looks in each subject's head area;
/// without, scans the whole frame.
pub fn faces(img: &Image, boxes: Option<&[[f32; 5]]>, max: usize) -> Result<Vec<Vec<f32>>, String> {
    let mut guard = models().lock().unwrap();
    let mut found = Vec::new();
    let mut candidates = Vec::new();
    {
        let det = get(&mut guard.det, engine::FACE_DETECTOR)?;
        let crops: Vec<facemesh::Affine> = match boxes {
            Some(b) => b
                .iter()
                .filter_map(|b| {
                    let side = (b[2] - b[0]).min(b[3] - b[1]) * 0.9;
                    if side < MIN_HEAD {
                        return None;
                    }
                    Some(facemesh::square_crop(((b[0] + b[2]) / 2.0 - side / 2.0) as f64, (b[1] - side * 0.05) as f64, side as f64, facemesh::DET_SIZE))
                })
                .collect(),
            None => facemesh::tiles(img.w, img.h),
        };
        for cm in crops {
            let crop = facemesh::warp(img, &cm, facemesh::DET_SIZE, facemesh::DET_SIZE);
            det.run(&crop, facemesh::DET_SIZE, facemesh::DET_SIZE)?;
            if let Some(f) = facemesh::detect(&det.outputs(), if boxes.is_some() { 0.5 } else { 0.6 }) {
                candidates.push(facemesh::to_source(&f, &cm));
            }
        }
    }
    let lm = get(&mut guard.lm, engine::FACE_LANDMARKS)?;
    for f in facemesh::merge(candidates, max) {
        if f.w.max(f.h) < MIN_HEAD / 3.0 {
            continue;
        }
        let m = facemesh::landmark_crop(&f);
        let crop = facemesh::warp(img, &m, facemesh::LM_SIZE, facemesh::LM_SIZE);
        lm.run(&crop, facemesh::LM_SIZE, facemesh::LM_SIZE)?;
        let out = lm.outputs();
        if facemesh::presence(&out) < 0.5 {
            continue;
        }
        found.push(facemesh::landmarks(&out, &m));
    }
    Ok(found)
}
