//! Delaunay triangulation (Bowyer-Watson), points added in order of x; triangles whose circumcircle lies
//! wholly to the left of the current point can't change any more and are set aside, keeping the search short.

use std::collections::HashMap;

struct Tri {
    a: usize,
    b: usize,
    c: usize,
    cx: f64,
    cy: f64,
    r2: f64,
}

impl Tri {
    fn new(a: usize, b: usize, c: usize, x: &[f64], y: &[f64]) -> Tri {
        let (ax, ay, bx, by, qx, qy) = (x[a], y[a], x[b], y[b], x[c], y[c]);
        let d = 2.0 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by));
        if d.abs() < 1e-12 {
            return Tri { a, b, c, cx: (ax + bx + qx) / 3.0, cy: (ay + by + qy) / 3.0, r2: f64::MAX };
        }
        let (a2, b2, c2) = (ax * ax + ay * ay, bx * bx + by * by, qx * qx + qy * qy);
        let cx = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / d;
        let cy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / d;
        Tri { a, b, c, cx, cy, r2: (ax - cx).powi(2) + (ay - cy).powi(2) }
    }
}

/// Triangles as vertex index triples.
pub fn triangulate(px: &[f32], py: &[f32]) -> Vec<[usize; 3]> {
    let n = px.len();
    if n < 3 {
        return Vec::new();
    }
    let mut order: Vec<usize> = (0..n).collect();
    order.sort_by(|&p, &q| px[p].total_cmp(&px[q]));
    let mut x: Vec<f64> = px.iter().map(|&v| v as f64).collect();
    let mut y: Vec<f64> = py.iter().map(|&v| v as f64).collect();
    let (minx, maxx) = x.iter().fold((f64::MAX, f64::MIN), |(a, b), &v| (a.min(v), b.max(v)));
    let (miny, maxy) = y.iter().fold((f64::MAX, f64::MIN), |(a, b), &v| (a.min(v), b.max(v)));
    let span = (maxx - minx).max(maxy - miny) + 1.0;
    let (mx, my) = ((minx + maxx) / 2.0, (miny + maxy) / 2.0);
    x.extend([mx - 20.0 * span, mx, mx + 20.0 * span]);
    y.extend([my - span, my + 20.0 * span, my - span]);

    let mut open = vec![Tri::new(n, n + 1, n + 2, &x, &y)];
    let mut done: Vec<Tri> = Vec::new();
    let mut edges: HashMap<(usize, usize), u32> = HashMap::new();
    for &p in &order {
        let (qx, qy) = (x[p], y[p]);
        edges.clear();
        let mut keep = Vec::with_capacity(open.len());
        for t in open.drain(..) {
            let dx = t.cx - qx;
            if dx < 0.0 && dx * dx > t.r2 {
                done.push(t); // wholly to the left: finished
                continue;
            }
            let dy = t.cy - qy;
            if dx * dx + dy * dy < t.r2 {
                for (u, v) in [(t.a, t.b), (t.b, t.c), (t.c, t.a)] {
                    *edges.entry(if u < v { (u, v) } else { (v, u) }).or_insert(0) += 1;
                }
            } else {
                keep.push(t);
            }
        }
        open = keep;
        for (&(u, v), &count) in &edges {
            if count == 1 {
                open.push(Tri::new(u, v, p, &x, &y));
            }
        }
    }
    done.extend(open);
    done.into_iter().filter(|t| t.a < n && t.b < n && t.c < n).map(|t| [t.a, t.b, t.c]).collect()
}
