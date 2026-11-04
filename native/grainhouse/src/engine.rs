//! The C inference engine from Ghosts (engine/yseg.c), and the four models built into the program.

use std::os::raw::{c_char, c_float, c_int, c_uchar, c_void};

#[repr(C)]
struct YSeg {
    _private: [u8; 0],
}

unsafe extern "C" {
    fn yseg_load(data: *const c_void, len: usize, err: *mut c_char, errlen: c_int) -> *mut YSeg;
    fn yseg_free(m: *mut YSeg);
    fn yseg_run(m: *mut YSeg, rgb: *const c_uchar, w: c_int, h: c_int, threads: c_int) -> c_int;
    fn yseg_output(m: *mut YSeg, i: c_int, c: *mut c_int, h: *mut c_int, w: *mut c_int) -> *const c_float;
    fn yseg_num_outputs(m: *mut YSeg) -> c_int;
    fn yseg_objects(m: *mut YSeg, conf: c_float, iou: c_float, mask: *mut c_uchar, max: c_int, classes: *const c_uchar) -> c_int;
    fn yseg_boxes(m: *mut YSeg, out: *mut c_float, max: c_int) -> c_int;
    fn yseg_pose(m: *mut YSeg, conf: c_float, iou: c_float, out: *mut c_float, max: c_int) -> c_int;
}

// Gallery's copy has no AI models: loading one fails, so the AI effects report an error.
pub static SEG_MODEL: &[u8] = &[];
pub static POSE_MODEL: &[u8] = &[];
pub static FACE_DETECTOR: &[u8] = &[];
pub static FACE_LANDMARKS: &[u8] = &[];
pub static FACE_MESH_EDGES: &[u8] = &[];

pub struct Model {
    m: *mut YSeg,
}

// The engine keeps its tensors inside the model; one thread uses a model at a time (guarded by a Mutex).
unsafe impl Send for Model {}

impl Model {
    pub fn load(data: &[u8]) -> Result<Model, String> {
        let mut err = [0 as c_char; 200];
        let m = unsafe { yseg_load(data.as_ptr() as *const c_void, data.len(), err.as_mut_ptr(), err.len() as c_int) };
        if m.is_null() {
            let msg: Vec<u8> = err.iter().take_while(|&&c| c != 0).map(|&c| c as u8).collect();
            return Err(String::from_utf8_lossy(&msg).into_owned());
        }
        Ok(Model { m })
    }

    fn threads() -> c_int {
        std::thread::available_parallelism().map(|n| n.get().min(8)).unwrap_or(4) as c_int
    }

    /// Runs the network on interleaved RGB (w and h multiples of 32).
    pub fn run(&mut self, rgb: &[u8], w: usize, h: usize) -> Result<(), String> {
        assert!(rgb.len() >= w * h * 3);
        let r = unsafe { yseg_run(self.m, rgb.as_ptr(), w as c_int, h as c_int, Self::threads()) };
        if r != 0 {
            return Err("out of memory running the model".into());
        }
        Ok(())
    }

    /// All outputs, one after another (for the face models).
    pub fn outputs(&mut self) -> Vec<f32> {
        let mut out = Vec::new();
        unsafe {
            for i in 0..yseg_num_outputs(self.m) {
                let (mut c, mut h, mut w) = (0, 0, 0);
                let p = yseg_output(self.m, i, &mut c, &mut h, &mut w);
                out.extend_from_slice(std::slice::from_raw_parts(p, (c * h * w) as usize));
            }
        }
        out
    }

    /// After run(): mask (w x h, 0..255) of the wanted COCO classes; returns how many were found.
    pub fn objects(&mut self, conf: f32, classes: &[u8; 80], mask: &mut [u8]) -> i32 {
        unsafe { yseg_objects(self.m, conf, 0.7, mask.as_mut_ptr(), 300, classes.as_ptr()) }
    }

    /// After objects(): their boxes, x1 y1 x2 y2 score in input pixels.
    pub fn boxes(&mut self) -> Vec<[f32; 5]> {
        let mut buf = vec![0f32; 5 * 300];
        let n = unsafe { yseg_boxes(self.m, buf.as_mut_ptr(), 300) }.max(0) as usize;
        (0..n).map(|i| [buf[5 * i], buf[5 * i + 1], buf[5 * i + 2], buf[5 * i + 3], buf[5 * i + 4]]).collect()
    }

    /// After run() with the pose model: 56 floats per person (box, score, 17 x (x, y, visibility)).
    pub fn poses(&mut self, conf: f32) -> Vec<[f32; 56]> {
        let mut buf = vec![0f32; 56 * 64];
        let n = unsafe { yseg_pose(self.m, conf, 0.7, buf.as_mut_ptr(), 64) }.max(0) as usize;
        (0..n)
            .map(|i| {
                let mut a = [0f32; 56];
                a.copy_from_slice(&buf[56 * i..56 * i + 56]);
                a
            })
            .collect()
    }
}

impl Drop for Model {
    fn drop(&mut self) {
        unsafe { yseg_free(self.m) }
    }
}
