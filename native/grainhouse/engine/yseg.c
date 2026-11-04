// YOLO11-seg engine, see yseg.h. Tensors are float CHW. Convolutions run as a matrix multiply
// (weights x image patches) with a 4x4 register block; image patches are gathered a few
// 4-pixel panels at a time so each weight block is reused from cache.
#include "yseg.h"

#include <math.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

enum { OP_CONV = 1, OP_ADD, OP_CONCAT, OP_SLICE, OP_MAXPOOL, OP_UP2, OP_CONVT2, OP_ATTN, OP_TCONV, OP_PRELU, OP_PAD, OP_MAXPOOL2,
       OP_TYPES };
enum { ACT_NONE = 0, ACT_SILU, ACT_RELU, ACT_RELU6 };

#define MAX_INTS 24
#define MAX_THREADS 8
#define NB 8 /* 4-pixel panels gathered at once */

typedef struct {
    int type, n;
    int a[MAX_INTS];
    int M, K;      /* matrix multiply size (M rows of weights, K inputs per output) */
    float *ap;     /* packed weights [ceil(M/4)][K][4] for matrix-multiply convs */
    float *dw;     /* depthwise weights [c][k*k] */
    float *bias;   /* per output row (M) */
    float *wino;   /* 3x3 stride-1 convs: Winograd F(2x2, 3x3) weights, 16 packed [ceil(M/4)][cin][4] blocks */
} Op;

typedef struct {
    int c, h, w;
    float *d;
    int uses, left;
} Tensor;

struct YSeg {
    int ntensors, nops, nout, reg_max;
    int strides[3];
    int *outputs;
    Op *ops;
    Tensor *t;
    int threads;
    float in_scale, in_offset; /* the engine feeds rgb / 255 * in_scale + in_offset */
    float *boxes; /* people found by the last yseg_people: x1 y1 x2 y2 score, input pixels */
    int nboxes;
    double ms[OP_TYPES + 2];
};

/* ---------------- loading ---------------- */

static float half_to_float(unsigned short h) {
    unsigned int s = (h >> 15) & 1, e = (h >> 10) & 31, f = h & 1023, bits;
    if (e == 0) {
        if (f == 0) {
            bits = s << 31;
        } else { /* subnormal */
            e = 127 - 15 + 1;
            while (!(f & 1024)) {
                f <<= 1;
                e--;
            }
            f &= 1023;
            bits = (s << 31) | (e << 23) | (f << 13);
        }
    } else if (e == 31) {
        bits = (s << 31) | (255u << 23) | (f << 13);
    } else {
        bits = (s << 31) | ((e - 15 + 127) << 23) | (f << 13);
    }
    float out;
    memcpy(&out, &bits, 4);
    return out;
}

typedef struct {
    const unsigned char *p, *end;
    int bad;
} Reader;

static int rd_int(Reader *r) {
    if (r->p + 4 > r->end) {
        r->bad = 1;
        return 0;
    }
    int v;
    memcpy(&v, r->p, 4);
    r->p += 4;
    return v;
}

static float *rd_halfs(Reader *r, size_t n) {
    if (r->p + n * 2 > r->end) {
        r->bad = 1;
        return NULL;
    }
    float *out = malloc(n * sizeof(float));
    if (!out) {
        r->bad = 1;
        return NULL;
    }
    for (size_t i = 0; i < n; i++) {
        unsigned short h;
        memcpy(&h, r->p + i * 2, 2);
        out[i] = half_to_float(h);
    }
    r->p += n * 2;
    return out;
}

static float *rd_floats(Reader *r, size_t n) {
    if (r->p + n * 4 > r->end) {
        r->bad = 1;
        return NULL;
    }
    float *out = malloc(n * sizeof(float));
    if (!out) {
        r->bad = 1;
        return NULL;
    }
    memcpy(out, r->p, n * 4);
    r->p += n * 4;
    return out;
}

/* Packs a row-major M x K weight matrix as [ceil(M/4)][K][4], zero padded. */
static float *pack_a(const float *w, int M, int K) {
    int mb = (M + 3) / 4;
    float *ap = calloc((size_t) mb * K * 4, sizeof(float));
    if (!ap) return NULL;
    for (int m = 0; m < M; m++) {
        float *dst = ap + (size_t) (m / 4) * K * 4 + (m % 4);
        const float *src = w + (size_t) m * K;
        for (int k = 0; k < K; k++) dst[k * 4] = src[k];
    }
    return ap;
}

/*
 * Winograd F(2x2, 3x3): each 2x2 block of outputs comes from a 4x4 block of input with 16 multiplies per
 * input channel instead of 36. Weights become U = G g G^T (16 values per filter); inputs V = B^T d B; the
 * 16 products are 16 matrix multiplies (out channels x in channels x tiles); outputs Y = A^T M A.
 */
static float *winograd_weights(const float *w, int cout, int cin) {
    int mb = (cout + 3) / 4;
    size_t block = (size_t) mb * cin * 4;
    float *u = calloc(16 * block, sizeof(float));
    if (!u) return NULL;
    for (int co = 0; co < cout; co++) {
        for (int ci = 0; ci < cin; ci++) {
            const float *g = w + ((size_t) co * cin + ci) * 9;
            float t[4][3]; /* G g */
            for (int j = 0; j < 3; j++) {
                t[0][j] = g[j];
                t[1][j] = 0.5f * (g[j] + g[3 + j] + g[6 + j]);
                t[2][j] = 0.5f * (g[j] - g[3 + j] + g[6 + j]);
                t[3][j] = g[6 + j];
            }
            for (int i = 0; i < 4; i++) { /* (G g) G^T */
                float v[4] = {t[i][0], 0.5f * (t[i][0] + t[i][1] + t[i][2]), 0.5f * (t[i][0] - t[i][1] + t[i][2]), t[i][2]};
                for (int j = 0; j < 4; j++) {
                    int e = i * 4 + j;
                    u[e * block + ((size_t) (co / 4) * cin + ci) * 4 + (co % 4)] = v[j];
                }
            }
        }
    }
    return u;
}

void yseg_free(YSeg *m) {
    if (!m) return;
    if (m->ops) {
        for (int i = 0; i < m->nops; i++) {
            free(m->ops[i].ap);
            free(m->ops[i].wino);
            free(m->ops[i].dw);
            free(m->ops[i].bias);
        }
    }
    if (m->t) {
        for (int i = 0; i < m->ntensors; i++) free(m->t[i].d);
    }
    free(m->ops);
    free(m->t);
    free(m->boxes);
    free(m->outputs);
    free(m);
}

static int op_inputs(const Op *op, int *ids) {
    switch (op->type) {
        case OP_ADD:
            ids[0] = op->a[0];
            ids[1] = op->a[1];
            return 2;
        case OP_CONCAT:
            for (int i = 0; i < op->a[0]; i++) ids[i] = op->a[1 + i];
            return op->a[0];
        default:
            ids[0] = op->a[0];
            return 1;
    }
}

YSeg *yseg_load(const void *data, size_t len, char *err, int errlen) {
    Reader r = {data, (const unsigned char *) data + len, 0};
    if (len < 32 || memcmp(data, "YSEG", 4) != 0) {
        snprintf(err, errlen, "not a YSEG model");
        return NULL;
    }
    r.p += 4;
    YSeg *m = calloc(1, sizeof(YSeg));
    if (!m) goto oom;
    int version = rd_int(&r);
    m->ntensors = rd_int(&r);
    m->nops = rd_int(&r);
    m->nout = rd_int(&r);
    m->reg_max = rd_int(&r);
    int sc = rd_int(&r), of = rd_int(&r);
    memcpy(&m->in_scale, &sc, 4);
    memcpy(&m->in_offset, &of, 4);
    if (sc == 0 && of == 0) m->in_scale = 1, m->in_offset = 0;
    if (version != 1 || m->ntensors <= 0 || m->nops <= 0 || m->nout < 1 || m->nout > 64) {
        snprintf(err, errlen, "unsupported model (version %d, %d outputs)", version, m->nout);
        yseg_free(m);
        return NULL;
    }
    m->outputs = malloc(sizeof(int) * m->nout);
    m->ops = calloc(m->nops, sizeof(Op));
    m->t = calloc(m->ntensors, sizeof(Tensor));
    if (!m->outputs || !m->ops || !m->t) goto oom;
    for (int i = 0; i < m->nout; i++) m->outputs[i] = rd_int(&r);
    for (int i = 0; i < 3; i++) m->strides[i] = rd_int(&r);

    for (int i = 0; i < m->nops && !r.bad; i++) {
        Op *op = &m->ops[i];
        op->type = rd_int(&r);
        op->n = rd_int(&r);
        if (op->n < 0 || op->n > MAX_INTS) {
            snprintf(err, errlen, "op %d has %d ints", i, op->n);
            yseg_free(m);
            return NULL;
        }
        for (int j = 0; j < op->n; j++) op->a[j] = rd_int(&r);
        if (op->type == OP_CONV || op->type == OP_TCONV) {
            int cin = op->a[2], cout = op->a[3], k = op->a[4], groups = op->a[7];
            size_t nw = (size_t) cout * (cin / groups) * k * k;
            float *w = rd_halfs(&r, nw);
            op->bias = rd_floats(&r, cout);
            if (!w || !op->bias) goto oom;
            int s1 = op->a[5] == 1 && (op->type == OP_CONV ? op->a[6] == 1 : op->a[6] == 1);
            if (groups == 1 && k == 3 && s1) {
                op->M = cout;
                op->K = cin * 9;
                op->wino = winograd_weights(w, cout, cin);
                free(w);
                if (!op->wino) goto oom;
            } else if (groups == 1) {
                op->M = cout;
                op->K = cin * k * k;
                op->ap = pack_a(w, op->M, op->K);
                free(w);
                if (!op->ap) goto oom;
            } else {
                op->dw = w;
            }
        } else if (op->type == OP_CONVT2) {
            int cin = op->a[2], cout = op->a[3];
            float *w = rd_halfs(&r, (size_t) cin * cout * 4);
            float *b = rd_floats(&r, cout);
            if (!w || !b) goto oom;
            /* rows m = co*4 + dy*2 + dx, columns ci */
            op->M = cout * 4;
            op->K = cin;
            float *wm = malloc(sizeof(float) * op->M * op->K);
            op->bias = malloc(sizeof(float) * op->M);
            if (!wm || !op->bias) goto oom;
            for (int co = 0; co < cout; co++) {
                for (int q = 0; q < 4; q++) {
                    op->bias[co * 4 + q] = b[co];
                    for (int ci = 0; ci < cin; ci++) wm[(co * 4 + q) * cin + ci] = w[(ci * cout + co) * 4 + q];
                }
            }
            op->ap = pack_a(wm, op->M, op->K);
            free(wm);
            free(w);
            free(b);
            if (!op->ap) goto oom;
        } else if (op->type == OP_PRELU) {
            op->bias = rd_floats(&r, op->a[2]); /* alpha per channel */
            if (!op->bias) goto oom;
        } else if (op->type < OP_CONV || op->type >= OP_TYPES) {
            snprintf(err, errlen, "unknown op type %d", op->type);
            yseg_free(m);
            return NULL;
        }
    }
    if (r.bad) {
        snprintf(err, errlen, "model file is truncated or memory ran out");
        yseg_free(m);
        return NULL;
    }
    /* how many ops read each tensor, so intermediates can be freed after their last use */
    for (int i = 0; i < m->nops; i++) {
        int ids[MAX_INTS];
        int n = op_inputs(&m->ops[i], ids);
        for (int j = 0; j < n; j++) {
            if (ids[j] < 0 || ids[j] >= m->ntensors) {
                snprintf(err, errlen, "op %d reads tensor %d", i, ids[j]);
                yseg_free(m);
                return NULL;
            }
            m->t[ids[j]].uses++;
        }
    }
    for (int i = 0; i < m->nout; i++) m->t[m->outputs[i]].uses += 1000000;
    return m;
oom:
    snprintf(err, errlen, "out of memory loading the model");
    yseg_free(m);
    return NULL;
}

/* ---------------- threads ---------------- */

typedef void (*WorkFn)(void *ctx, int part, int parts);

typedef struct {
    WorkFn fn;
    void *ctx;
    int part, parts;
} Part;

static void *part_main(void *arg) {
    Part *p = arg;
    p->fn(p->ctx, p->part, p->parts);
    return NULL;
}

static void parallel(int threads, WorkFn fn, void *ctx) {
    pthread_t th[MAX_THREADS];
    Part parts[MAX_THREADS];
    int started[MAX_THREADS] = {0};
    for (int i = 0; i < threads; i++) {
        parts[i] = (Part) {fn, ctx, i, threads};
        if (i > 0) started[i] = pthread_create(&th[i], NULL, part_main, &parts[i]) == 0;
    }
    fn(ctx, 0, threads);
    for (int i = 1; i < threads; i++) {
        if (started[i]) pthread_join(th[i], NULL);
        else fn(ctx, i, threads); /* couldn't start a thread: do its share here */
    }
}

/* ---------------- matrix-multiply convolution ---------------- */

static void kernel4x4(const float *ap, const float *bp, float *c, int K) {
    float c00 = 0, c01 = 0, c02 = 0, c03 = 0, c10 = 0, c11 = 0, c12 = 0, c13 = 0;
    float c20 = 0, c21 = 0, c22 = 0, c23 = 0, c30 = 0, c31 = 0, c32 = 0, c33 = 0;
    for (int k = 0; k < K; k++) {
        float a0 = ap[0], a1 = ap[1], a2 = ap[2], a3 = ap[3];
        float b0 = bp[0], b1 = bp[1], b2 = bp[2], b3 = bp[3];
        c00 += a0 * b0; c01 += a0 * b1; c02 += a0 * b2; c03 += a0 * b3;
        c10 += a1 * b0; c11 += a1 * b1; c12 += a1 * b2; c13 += a1 * b3;
        c20 += a2 * b0; c21 += a2 * b1; c22 += a2 * b2; c23 += a2 * b3;
        c30 += a3 * b0; c31 += a3 * b1; c32 += a3 * b2; c33 += a3 * b3;
        ap += 4;
        bp += 4;
    }
    c[0] = c00; c[1] = c01; c[2] = c02; c[3] = c03;
    c[4] = c10; c[5] = c11; c[6] = c12; c[7] = c13;
    c[8] = c20; c[9] = c21; c[10] = c22; c[11] = c23;
    c[12] = c30; c[13] = c31; c[14] = c32; c[15] = c33;
}

/* e^x to about 1e-7 relative error: 2^(x log2 e) split into an integer power (exponent bits)
 * and a polynomial for the fraction. Several times quicker than expf on the camera. */
static inline float fast_exp(float x) {
    if (x < -87.0f) x = -87.0f;
    if (x > 88.0f) x = 88.0f;
    float t = x * 1.44269504f;
    int i = (int) t;
    if (t < i) i--; /* floor */
    float f = t - i;
    float p = 1.0f + f * (0.69314718f + f * (0.24022650f + f * (0.05550411f + f * (0.00961813f + f * (0.00133336f + f * 0.00015403f)))));
    union {
        float f;
        int i;
    } u;
    u.i = (i + 127) << 23;
    return p * u.f;
}

static inline float silu(float v) {
    return v / (1.0f + fast_exp(-v));
}

static inline float activate(float v, int act) {
    switch (act) {
        case ACT_SILU: return silu(v);
        case ACT_RELU: return v > 0 ? v : 0;
        case ACT_RELU6: return v < 0 ? 0 : v > 6 ? 6 : v;
        default: return v;
    }
}

typedef struct {
    const Op *op;
    const Tensor *in;
    Tensor *out;
    int k, stride, pad, act, scatter; /* scatter: CONVT2 output layout */
    int oh, ow;
    int failed;
} ConvJob;

/* Gathers image patches for 4 output pixels starting at pix into bp[K][4] (zero outside). */
static void gather(const ConvJob *j, int pix, int npix, float *bp) {
    const Tensor *in = j->in;
    int K = j->op->K, k = j->k, s = j->stride, p = j->pad;
    int H = in->h, W = in->w, HW = H * W;
    if (k == 1 && s == 1 && p == 0) {
        if (npix == 4) {
            for (int kk = 0; kk < K; kk++) {
                const float *src = in->d + (size_t) kk * HW + pix;
                bp[kk * 4] = src[0];
                bp[kk * 4 + 1] = src[1];
                bp[kk * 4 + 2] = src[2];
                bp[kk * 4 + 3] = src[3];
            }
        } else {
            for (int kk = 0; kk < K; kk++) {
                for (int q = 0; q < 4; q++) bp[kk * 4 + q] = q < npix ? in->d[(size_t) kk * HW + pix + q] : 0;
            }
        }
        return;
    }
    int y0[4], x0[4];
    for (int q = 0; q < 4; q++) {
        int pp = q < npix ? pix + q : pix;
        y0[q] = (pp / j->ow) * s - p;
        x0[q] = (pp % j->ow) * s - p;
    }
    int cin = K / (k * k);
    float *dst = bp;
    /* most panels: 4 pixels on one row with their whole window inside the image, so no edge checks */
    if (npix == 4 && y0[0] == y0[3] && y0[0] >= 0 && y0[0] + k <= H && x0[0] >= 0 && x0[3] + k <= W) {
        int xa = x0[0], xb = x0[1], xc = x0[2], xd = x0[3];
        for (int ci = 0; ci < cin; ci++) {
            const float *plane = in->d + (size_t) ci * HW + (size_t) y0[0] * W;
            for (int ky = 0; ky < k; ky++) {
                const float *row = plane + ky * W;
                for (int kx = 0; kx < k; kx++) {
                    dst[0] = row[xa + kx];
                    dst[1] = row[xb + kx];
                    dst[2] = row[xc + kx];
                    dst[3] = row[xd + kx];
                    dst += 4;
                }
            }
        }
        return;
    }
    for (int ci = 0; ci < cin; ci++) {
        const float *plane = in->d + (size_t) ci * HW;
        for (int ky = 0; ky < k; ky++) {
            for (int kx = 0; kx < k; kx++) {
                for (int q = 0; q < 4; q++) {
                    int y = y0[q] + ky, x = x0[q] + kx;
                    dst[q] = (q < npix && y >= 0 && y < H && x >= 0 && x < W) ? plane[y * W + x] : 0;
                }
                dst += 4;
            }
        }
    }
}

#define WINO_TB 64 /* tiles per block: keeps the transformed blocks in cache */

/* A 3x3, stride 1, pad 1 convolution by Winograd F(2x2, 3x3). */
static int winograd(const Op *op, const Tensor *in, Tensor *out, int act) {
    int C = in->c, H = in->h, W = in->w, M = op->M, mb = (M + 3) / 4;
    int TW = (W + 1) / 2, TH = (H + 1) / 2, T = TW * TH;
    size_t block = (size_t) mb * C * 4;
    float *V = malloc(sizeof(float) * 16 * (size_t) C * WINO_TB);       /* [e][panel][c][4] */
    float *Mo = malloc(sizeof(float) * 16 * (size_t) mb * 4 * WINO_TB);  /* [e][m][tile] */
    if (!V || !Mo) {
        free(V);
        free(Mo);
        return -1;
    }
    const int NP = WINO_TB / 4;
    float tmp[16];
    for (int t0 = 0; t0 < T; t0 += WINO_TB) {
        int nt = T - t0 < WINO_TB ? T - t0 : WINO_TB;
        /* input transform */
        for (int tb = 0; tb < WINO_TB; tb++) {
            int panel = tb / 4, q = tb % 4;
            if (tb >= nt) {
                for (int e = 0; e < 16; e++)
                    for (int c = 0; c < C; c++) V[(((size_t) e * NP + panel) * C + c) * 4 + q] = 0;
                continue;
            }
            int t = t0 + tb, ty = t / TW, tx = t % TW;
            int y0 = 2 * ty - 1, x0 = 2 * tx - 1;
            int inside = y0 >= 0 && x0 >= 0 && y0 + 4 <= H && x0 + 4 <= W;
            for (int c = 0; c < C; c++) {
                const float *plane = in->d + (size_t) c * H * W;
                float d[4][4];
                for (int i = 0; i < 4; i++) {
                    int yy = y0 + i;
                    for (int j = 0; j < 4; j++) {
                        int xx = x0 + j;
                        d[i][j] = inside || (yy >= 0 && yy < H && xx >= 0 && xx < W) ? plane[yy * W + xx] : 0;
                    }
                }
                float r[4][4]; /* B^T d */
                for (int j = 0; j < 4; j++) {
                    r[0][j] = d[0][j] - d[2][j];
                    r[1][j] = d[1][j] + d[2][j];
                    r[2][j] = d[2][j] - d[1][j];
                    r[3][j] = d[1][j] - d[3][j];
                }
                for (int i = 0; i < 4; i++) { /* (B^T d) B */
                    float v0 = r[i][0] - r[i][2], v1 = r[i][1] + r[i][2], v2 = r[i][2] - r[i][1], v3 = r[i][1] - r[i][3];
                    float *dst = V + (((size_t) (i * 4) * NP + panel) * C + c) * 4 + q;
                    size_t es = (size_t) NP * C * 4;
                    dst[0] = v0;
                    dst[es] = v1;
                    dst[2 * es] = v2;
                    dst[3 * es] = v3;
                }
            }
        }
        /* 16 matrix multiplies */
        int npanels = (nt + 3) / 4;
        for (int e = 0; e < 16; e++) {
            const float *A = op->wino + e * block;
            const float *Ve = V + (size_t) e * NP * C * 4;
            float *Me = Mo + (size_t) e * mb * 4 * WINO_TB;
            for (int b = 0; b < mb; b++) {
                const float *a = A + (size_t) b * C * 4;
                for (int pnl = 0; pnl < npanels; pnl++) {
                    kernel4x4(a, Ve + (size_t) pnl * C * 4, tmp, C);
                    for (int rr = 0; rr < 4; rr++) {
                        float *row = Me + (size_t) (b * 4 + rr) * WINO_TB + pnl * 4;
                        row[0] = tmp[rr * 4];
                        row[1] = tmp[rr * 4 + 1];
                        row[2] = tmp[rr * 4 + 2];
                        row[3] = tmp[rr * 4 + 3];
                    }
                }
            }
        }
        /* output transform */
        for (int mo = 0; mo < M; mo++) {
            float bias = op->bias[mo];
            float *oplane = out->d + (size_t) mo * H * W;
            for (int tb = 0; tb < nt; tb++) {
                float mm[16];
                for (int e = 0; e < 16; e++) mm[e] = Mo[((size_t) e * mb * 4 + mo) * WINO_TB + tb];
                /* A^T m: rows 0..1 */
                float s0[4], s1[4];
                for (int j = 0; j < 4; j++) {
                    s0[j] = mm[j] + mm[4 + j] + mm[8 + j];
                    s1[j] = mm[4 + j] - mm[8 + j] - mm[12 + j];
                }
                float y00 = s0[0] + s0[1] + s0[2] + bias, y01 = s0[1] - s0[2] - s0[3] + bias;
                float y10 = s1[0] + s1[1] + s1[2] + bias, y11 = s1[1] - s1[2] - s1[3] + bias;
                int t = t0 + tb, oy = (t / TW) * 2, ox = (t % TW) * 2;
                oplane[oy * W + ox] = activate(y00, act);
                if (ox + 1 < W) oplane[oy * W + ox + 1] = activate(y01, act);
                if (oy + 1 < H) {
                    oplane[(oy + 1) * W + ox] = activate(y10, act);
                    if (ox + 1 < W) oplane[(oy + 1) * W + ox + 1] = activate(y11, act);
                }
            }
        }
    }
    free(V);
    free(Mo);
    return 0;
}

static void conv_part(void *ctx, int part, int parts) {
    ConvJob *j = ctx;
    const Op *op = j->op;
    int K = op->K, M = op->M, mb = (M + 3) / 4;
    int N = j->oh * j->ow;
    int panels = (N + 3) / 4;
    int per = (panels + parts - 1) / parts;
    int q0 = part * per, q1 = q0 + per > panels ? panels : q0 + per;
    if (q0 >= q1) return;
    float *pack = malloc(sizeof(float) * (size_t) NB * K * 4);
    if (!pack) {
        j->failed = 1;
        return;
    }
    float tmp[16];
    float *out = j->out->d;
    int inW = j->in->w, outW = j->out->w;
    for (int qb = q0; qb < q1; qb += NB) {
        int nq = q1 - qb < NB ? q1 - qb : NB;
        for (int q = 0; q < nq; q++) {
            int pix = (qb + q) * 4;
            gather(j, pix, N - pix < 4 ? N - pix : 4, pack + (size_t) q * K * 4);
        }
        for (int b = 0; b < mb; b++) {
            const float *a = op->ap + (size_t) b * K * 4;
            for (int q = 0; q < nq; q++) {
                kernel4x4(a, pack + (size_t) q * K * 4, tmp, K);
                int pix0 = (qb + q) * 4;
                for (int r = 0; r < 4; r++) {
                    int m = b * 4 + r;
                    if (m >= M) break;
                    float bias = op->bias[m];
                    for (int c = 0; c < 4; c++) {
                        int pix = pix0 + c;
                        if (pix >= N) break;
                        float v = tmp[r * 4 + c] + bias;
                        if (j->act) v = activate(v, j->act);
                        if (j->scatter) {
                            /* CONVT2: row m = co*4 + dy*2 + dx, pixel = input (y, x) */
                            int co = m >> 2, dy = (m >> 1) & 1, dx = m & 1;
                            int y = pix / inW, x = pix % inW;
                            out[((size_t) co * j->out->h + 2 * y + dy) * outW + 2 * x + dx] = v;
                        } else {
                            out[(size_t) m * N + pix] = v;
                        }
                    }
                }
            }
        }
    }
    free(pack);
}

/* ---------------- depthwise convolution ---------------- */

typedef struct {
    const Op *op;
    const Tensor *in;
    Tensor *out;
    int pad;
} DwJob;

static void dw_part(void *ctx, int part, int parts) {
    DwJob *j = ctx;
    const Op *op = j->op;
    int k = op->a[4], s = op->a[5], act = op->a[8];
    int p = j->pad;
    int C = j->in->c, H = j->in->h, W = j->in->w, OH = j->out->h, OW = j->out->w;
    for (int c = part; c < C; c += parts) {
        const float *w = op->dw + c * k * k;
        const float *src = j->in->d + (size_t) c * H * W;
        float *dst = j->out->d + (size_t) c * OH * OW;
        float bias = op->bias[c];
        for (int oy = 0; oy < OH; oy++) {
            for (int ox = 0; ox < OW; ox++) {
                float sum = bias;
                for (int ky = 0; ky < k; ky++) {
                    int y = oy * s - p + ky;
                    if (y < 0 || y >= H) continue;
                    for (int kx = 0; kx < k; kx++) {
                        int x = ox * s - p + kx;
                        if (x < 0 || x >= W) continue;
                        sum += w[ky * k + kx] * src[y * W + x];
                    }
                }
                dst[oy * OW + ox] = activate(sum, act);
            }
        }
    }
}

/* ---------------- other ops ---------------- */

static int alloc_tensor(Tensor *t, int c, int h, int w) {
    t->c = c;
    t->h = h;
    t->w = w;
    free(t->d);
    t->d = malloc(sizeof(float) * (size_t) c * h * w);
    return t->d ? 0 : -1;
}

static void maxpool(const Tensor *in, Tensor *out, int k, int pad) {
    int H = in->h, W = in->w;
    float *row = malloc(sizeof(float) * H * W);
    if (!row) return;
    for (int c = 0; c < in->c; c++) {
        const float *src = in->d + (size_t) c * H * W;
        float *dst = out->d + (size_t) c * H * W;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int x0 = x - pad < 0 ? 0 : x - pad, x1 = x - pad + k > W ? W : x - pad + k;
                float v = src[y * W + x0];
                for (int i = x0 + 1; i < x1; i++) if (src[y * W + i] > v) v = src[y * W + i];
                row[y * W + x] = v;
            }
        }
        for (int y = 0; y < H; y++) {
            int y0 = y - pad < 0 ? 0 : y - pad, y1 = y - pad + k > H ? H : y - pad + k;
            for (int x = 0; x < W; x++) {
                float v = row[y0 * W + x];
                for (int i = y0 + 1; i < y1; i++) if (row[i * W + x] > v) v = row[i * W + x];
                dst[y * W + x] = v;
            }
        }
    }
    free(row);
}

static int attention(const Tensor *qkv, Tensor *o, Tensor *v, int heads, int kd, int hd) {
    int N = qkv->h * qkv->w;
    float scale = 1.0f / sqrtf((float) kd);
    float *attn = malloc(sizeof(float) * (size_t) N * N);
    if (!attn) return -1;
    for (int h = 0; h < heads; h++) {
        const float *q = qkv->d + (size_t) h * (2 * kd + hd) * N;
        const float *kk = q + (size_t) kd * N;
        const float *vv = kk + (size_t) kd * N;
        for (int i = 0; i < N; i++) {
            float *row = attn + (size_t) i * N;
            float mx = -1e30f;
            for (int j = 0; j < N; j++) {
                float s = 0;
                for (int d = 0; d < kd; d++) s += q[d * N + i] * kk[d * N + j];
                s *= scale;
                row[j] = s;
                if (s > mx) mx = s;
            }
            float sum = 0;
            for (int j = 0; j < N; j++) {
                row[j] = expf(row[j] - mx);
                sum += row[j];
            }
            float inv = 1.0f / sum;
            for (int j = 0; j < N; j++) row[j] *= inv;
        }
        for (int c = 0; c < hd; c++) {
            const float *vr = vv + (size_t) c * N;
            float *orow = o->d + (size_t) (h * hd + c) * N;
            for (int i = 0; i < N; i++) {
                const float *row = attn + (size_t) i * N;
                float s = 0;
                for (int j = 0; j < N; j++) s += vr[j] * row[j];
                orow[i] = s;
            }
            memcpy(v->d + (size_t) (h * hd + c) * N, vr, sizeof(float) * N);
        }
    }
    free(attn);
    return 0;
}

static double now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

/* ---------------- run ---------------- */

static void release(YSeg *m, int id) {
    Tensor *t = &m->t[id];
    if (--t->left == 0) {
        free(t->d);
        t->d = NULL;
    }
}

int yseg_run(YSeg *m, const unsigned char *rgb, int w, int h, int threads) {
    if (threads < 1) threads = 1;
    if (threads > MAX_THREADS) threads = MAX_THREADS;
    memset(m->ms, 0, sizeof(m->ms));
    for (int i = 0; i < m->ntensors; i++) {
        free(m->t[i].d);
        m->t[i].d = NULL;
        m->t[i].left = m->t[i].uses;
    }
    Tensor *in = &m->t[0];
    if (alloc_tensor(in, 3, h, w)) return -1;
    float sc = m->in_scale / 255.0f, of = m->in_offset;
    for (int i = 0; i < w * h; i++) {
        in->d[i] = rgb[i * 3] * sc + of;
        in->d[w * h + i] = rgb[i * 3 + 1] * sc + of;
        in->d[2 * w * h + i] = rgb[i * 3 + 2] * sc + of;
    }
    for (int i = 0; i < m->nops; i++) {
        Op *op = &m->ops[i];
        int *a = op->a;
        double t0 = now_ms();
        switch (op->type) {
            case OP_CONV: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                int k = a[4], s = a[5], p = a[6];
                if (alloc_tensor(y, a[3], (x->h + 2 * p - k) / s + 1, (x->w + 2 * p - k) / s + 1)) return -1;
                if (op->wino) {
                    if (winograd(op, x, y, a[8])) return -1;
                } else if (a[7] == 1) {
                    ConvJob j = {op, x, y, k, s, p, a[8], 0, y->h, y->w, 0};
                    parallel(threads, conv_part, &j);
                    if (j.failed) return -1;
                } else {
                    DwJob j = {op, x, y, p};
                    parallel(threads, dw_part, &j);
                }
                break;
            }
            case OP_TCONV: {
                /* TFLite padding: SAME gives ceil(in / stride) outputs, with the extra padding at the bottom/right */
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                int k = a[4], s = a[5], same = a[6];
                int oh = same ? (x->h + s - 1) / s : (x->h - k) / s + 1;
                int ow = same ? (x->w + s - 1) / s : (x->w - k) / s + 1;
                int p = 0;
                if (same) {
                    int total = (oh - 1) * s + k - x->h;
                    p = total > 0 ? total / 2 : 0;
                }
                if (alloc_tensor(y, a[3], oh, ow)) return -1;
                if (op->wino) {
                    if (winograd(op, x, y, a[8])) return -1;
                } else if (a[7] == 1) {
                    ConvJob j = {op, x, y, k, s, p, a[8], 0, oh, ow, 0};
                    parallel(threads, conv_part, &j);
                    if (j.failed) return -1;
                } else {
                    DwJob j = {op, x, y, p};
                    parallel(threads, dw_part, &j);
                }
                break;
            }
            case OP_PRELU: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                if (alloc_tensor(y, x->c, x->h, x->w)) return -1;
                size_t plane = (size_t) x->h * x->w;
                for (int c = 0; c < x->c; c++) {
                    float al = op->bias[c];
                    const float *src = x->d + c * plane;
                    float *dst = y->d + c * plane;
                    for (size_t q = 0; q < plane; q++) dst[q] = src[q] >= 0 ? src[q] : src[q] * al;
                }
                break;
            }
            case OP_PAD: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                int cb = a[2], ca = a[3], top = a[4], bottom = a[5], left = a[6], right = a[7];
                int oh = x->h + top + bottom, ow = x->w + left + right;
                if (alloc_tensor(y, x->c + cb + ca, oh, ow)) return -1;
                memset(y->d, 0, sizeof(float) * (size_t) y->c * oh * ow);
                for (int c = 0; c < x->c; c++) {
                    for (int yy = 0; yy < x->h; yy++) {
                        memcpy(y->d + ((size_t) (c + cb) * oh + yy + top) * ow + left, x->d + ((size_t) c * x->h + yy) * x->w,
                               sizeof(float) * x->w);
                    }
                }
                break;
            }
            case OP_MAXPOOL2: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                int k = a[2], s = a[3], same = a[4];
                int oh = same ? (x->h + s - 1) / s : (x->h - k) / s + 1;
                int ow = same ? (x->w + s - 1) / s : (x->w - k) / s + 1;
                int p = 0;
                if (same) {
                    int total = (oh - 1) * s + k - x->h;
                    p = total > 0 ? total / 2 : 0;
                }
                if (alloc_tensor(y, x->c, oh, ow)) return -1;
                for (int c = 0; c < x->c; c++) {
                    const float *src = x->d + (size_t) c * x->h * x->w;
                    float *dst = y->d + (size_t) c * oh * ow;
                    for (int oy = 0; oy < oh; oy++) {
                        for (int ox = 0; ox < ow; ox++) {
                            float v = -1e30f;
                            for (int ky = 0; ky < k; ky++) {
                                int iy = oy * s - p + ky;
                                if (iy < 0 || iy >= x->h) continue;
                                for (int kx = 0; kx < k; kx++) {
                                    int ix = ox * s - p + kx;
                                    if (ix < 0 || ix >= x->w) continue;
                                    if (src[iy * x->w + ix] > v) v = src[iy * x->w + ix];
                                }
                            }
                            dst[oy * ow + ox] = v;
                        }
                    }
                }
                break;
            }
            case OP_CONVT2: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                if (alloc_tensor(y, a[3], x->h * 2, x->w * 2)) return -1;
                ConvJob j = {op, x, y, 1, 1, 0, 0, 1, x->h, x->w, 0};
                parallel(threads, conv_part, &j);
                if (j.failed) return -1;
                break;
            }
            case OP_ADD: {
                Tensor *x = &m->t[a[0]], *x2 = &m->t[a[1]], *y = &m->t[a[2]];
                if (alloc_tensor(y, x->c, x->h, x->w)) return -1;
                size_t n = (size_t) x->c * x->h * x->w;
                int act = op->n >= 4 ? a[3] : ACT_NONE;
                for (size_t k = 0; k < n; k++) y->d[k] = activate(x->d[k] + x2->d[k], act);
                break;
            }
            case OP_CONCAT: {
                int n = a[0], c = 0;
                Tensor *first = &m->t[a[1]], *y = &m->t[a[n + 1]];
                for (int k = 0; k < n; k++) c += m->t[a[1 + k]].c;
                if (alloc_tensor(y, c, first->h, first->w)) return -1;
                size_t plane = (size_t) first->h * first->w, off = 0;
                for (int k = 0; k < n; k++) {
                    Tensor *x = &m->t[a[1 + k]];
                    memcpy(y->d + off, x->d, sizeof(float) * x->c * plane);
                    off += x->c * plane;
                }
                break;
            }
            case OP_SLICE: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[3]];
                if (alloc_tensor(y, a[2] - a[1], x->h, x->w)) return -1;
                size_t plane = (size_t) x->h * x->w;
                memcpy(y->d, x->d + a[1] * plane, sizeof(float) * y->c * plane);
                break;
            }
            case OP_MAXPOOL: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                if (alloc_tensor(y, x->c, x->h, x->w)) return -1;
                maxpool(x, y, a[2], a[3]);
                break;
            }
            case OP_UP2: {
                Tensor *x = &m->t[a[0]], *y = &m->t[a[1]];
                if (alloc_tensor(y, x->c, x->h * 2, x->w * 2)) return -1;
                for (int c = 0; c < x->c; c++) {
                    for (int yy = 0; yy < y->h; yy++) {
                        const float *src = x->d + ((size_t) c * x->h + yy / 2) * x->w;
                        float *dst = y->d + ((size_t) c * y->h + yy) * y->w;
                        for (int xx = 0; xx < y->w; xx++) dst[xx] = src[xx / 2];
                    }
                }
                break;
            }
            case OP_ATTN: {
                Tensor *x = &m->t[a[0]], *o = &m->t[a[1]], *v = &m->t[a[2]];
                int c = a[3] * a[5];
                if (alloc_tensor(o, c, x->h, x->w) || alloc_tensor(v, c, x->h, x->w)) return -1;
                if (attention(x, o, v, a[3], a[4], a[5])) return -1;
                break;
            }
        }
        int ids[MAX_INTS];
        int n = op_inputs(op, ids);
        for (int k = 0; k < n; k++) release(m, ids[k]);
        m->ms[op->type] += now_ms() - t0;
    }
    return 0;
}

int yseg_num_outputs(YSeg *m) {
    return m->nout;
}

const float *yseg_output(YSeg *m, int i, int *c, int *h, int *w) {
    Tensor *t = &m->t[m->outputs[i]];
    *c = t->c;
    *h = t->h;
    *w = t->w;
    return t->d;
}

int yseg_boxes(YSeg *m, float *out, int max) {
    int n = m->nboxes < max ? m->nboxes : max;
    if (n > 0) memcpy(out, m->boxes, sizeof(float) * 5 * n);
    return n;
}

void yseg_timings(YSeg *m, double *ms, int n) {
    for (int i = 0; i < n && i < OP_TYPES; i++) ms[i] = m->ms[i];
}

/* ---------------- people ---------------- */

typedef struct {
    float score, x1, y1, x2, y2;
    const float *coef; /* 32 mask coefficients (or 51 keypoint values), strided by cstride */
    int cstride;
    int gx, gy;   /* the anchor's grid cell */
    float stride;
} Det;

static int by_score(const void *a, const void *b) {
    float d = ((const Det *) b)->score - ((const Det *) a)->score;
    return d > 0 ? 1 : d < 0 ? -1 : 0;
}

static float iou(const Det *a, const Det *b) {
    float x1 = a->x1 > b->x1 ? a->x1 : b->x1, y1 = a->y1 > b->y1 ? a->y1 : b->y1;
    float x2 = a->x2 < b->x2 ? a->x2 : b->x2, y2 = a->y2 < b->y2 ? a->y2 : b->y2;
    float inter = (x2 > x1 && y2 > y1) ? (x2 - x1) * (y2 - y1) : 0;
    float u = (a->x2 - a->x1) * (a->y2 - a->y1) + (b->x2 - b->x1) * (b->y2 - b->y1) - inter;
    return u > 0 ? inter / u : 0;
}

/* The best logit among the wanted classes at anchor i (classes == NULL: class 0 only). */
static float best_logit(const Tensor *cls, int i, const unsigned char *classes) {
    int N = cls->h * cls->w;
    if (!classes || cls->c == 1) return cls->d[i];
    float best = -1e30f;
    for (int c = 0; c < cls->c; c++) {
        if (classes[c] && cls->d[(size_t) c * N + i] > best) best = cls->d[(size_t) c * N + i];
    }
    return best;
}

int yseg_people(YSeg *m, float conf, float iou_max, unsigned char *mask, int max_people) {
    return yseg_objects(m, conf, iou_max, mask, max_people, NULL);
}

/* Decodes the boxes of the wanted classes, best first after NMS. Each Det's coef points at the third
 * output of its scale (mask coefficients, or keypoints for a pose model). Returns how many, or -1. */
static int detect(YSeg *m, float conf, float iou_max, int max_people, const unsigned char *classes, Det **out) {
    float logit_min = logf(conf / (1 - conf));
    int R = m->reg_max;
    int total = 0;
    for (int l = 0; l < 3; l++) {
        Tensor *cls = &m->t[m->outputs[3 * l + 1]];
        int N = cls->h * cls->w;
        for (int i = 0; i < N; i++) if (best_logit(cls, i, classes) >= logit_min) total++;
    }
    Det *dets = malloc(sizeof(Det) * (total > 0 ? total : 1));
    if (!dets) return -1;
    int nd = 0;
    for (int l = 0; l < 3; l++) {
        Tensor *box = &m->t[m->outputs[3 * l]];
        Tensor *cls = &m->t[m->outputs[3 * l + 1]];
        Tensor *mc = &m->t[m->outputs[3 * l + 2]];
        int N = cls->h * cls->w, W = cls->w;
        float s = (float) m->strides[l];
        for (int i = 0; i < N; i++) {
            float lg = best_logit(cls, i, classes);
            if (lg < logit_min) continue;
            float dist[4];
            for (int side = 0; side < 4; side++) {
                const float *bins = box->d + (size_t) side * R * N + i;
                float mx = bins[0];
                for (int j = 1; j < R; j++) if (bins[j * N] > mx) mx = bins[j * N];
                float sum = 0, ev = 0;
                for (int j = 0; j < R; j++) {
                    float e = expf(bins[j * N] - mx);
                    sum += e;
                    ev += e * j;
                }
                dist[side] = ev / sum;
            }
            float cx = i % W + 0.5f, cy = i / W + 0.5f;
            Det *d = &dets[nd++];
            d->score = 1.0f / (1.0f + expf(-lg));
            d->x1 = (cx - dist[0]) * s;
            d->y1 = (cy - dist[1]) * s;
            d->x2 = (cx + dist[2]) * s;
            d->y2 = (cy + dist[3]) * s;
            d->coef = mc->d + i;
            d->cstride = N;
            d->gx = i % W;
            d->gy = i / W;
            d->stride = s;
        }
    }
    qsort(dets, nd, sizeof(Det), by_score);
    int kept = 0;
    for (int i = 0; i < nd && kept < max_people; i++) {
        int ok = 1;
        for (int j = 0; j < kept; j++) {
            if (iou(&dets[i], &dets[j]) > iou_max) {
                ok = 0;
                break;
            }
        }
        if (ok) dets[kept++] = dets[i];
    }
    *out = dets;
    return kept;
}

int yseg_objects(YSeg *m, float conf, float iou_max, unsigned char *mask, int max_people, const unsigned char *classes) {
    if (m->nout != 10) return -1;
    Det *dets;
    int kept = detect(m, conf, iou_max, max_people, classes, &dets);
    if (kept < 0) return -1;

    /* Each person's mask logits at the proto resolution (a quarter of the input), upsampled
     * bilinearly to the input size, cropped to the person's box there, then combined. */
    free(m->boxes);
    m->boxes = malloc(sizeof(float) * 5 * (kept > 0 ? kept : 1));
    m->nboxes = 0;
    if (m->boxes) {
        for (int k = 0; k < kept; k++) {
            float *b = m->boxes + 5 * k;
            b[0] = dets[k].x1;
            b[1] = dets[k].y1;
            b[2] = dets[k].x2;
            b[3] = dets[k].y2;
            b[4] = dets[k].score;
        }
        m->nboxes = kept;
    }
    Tensor *proto = &m->t[m->outputs[9]];
    int PH = proto->h, PW = proto->w, PN = PH * PW, NC = proto->c;
    int W = m->t[0].w, H = m->t[0].h;
    float *logit = malloc(sizeof(float) * PN);
    if (!logit) {
        free(dets);
        return -1;
    }
    memset(mask, 0, (size_t) W * H);
    for (int k = 0; k < kept; k++) {
        Det *d = &dets[k];
        int x0 = (int) ceilf(d->x1), y0 = (int) ceilf(d->y1), x1 = (int) ceilf(d->x2), y1 = (int) ceilf(d->y2);
        if (x0 < 0) x0 = 0;
        if (y0 < 0) y0 = 0;
        if (x1 > W) x1 = W;
        if (y1 > H) y1 = H;
        if (x0 >= x1 || y0 >= y1) continue;
        /* proto cells needed by the bilinear samples inside the box */
        int px0 = (int) floorf((x0 + 0.5f) * 0.25f - 0.5f), px1 = (int) floorf((x1 - 0.5f) * 0.25f - 0.5f) + 1;
        int py0 = (int) floorf((y0 + 0.5f) * 0.25f - 0.5f), py1 = (int) floorf((y1 - 0.5f) * 0.25f - 0.5f) + 1;
        if (px0 < 0) px0 = 0;
        if (py0 < 0) py0 = 0;
        if (px1 > PW - 1) px1 = PW - 1;
        if (py1 > PH - 1) py1 = PH - 1;
        float coef[64];
        for (int c = 0; c < NC && c < 64; c++) coef[c] = d->coef[(size_t) c * d->cstride];
        for (int y = py0; y <= py1; y++) {
            for (int x = px0; x <= px1; x++) {
                int pi = y * PW + x;
                float sum = 0;
                for (int c = 0; c < NC; c++) sum += coef[c] * proto->d[(size_t) c * PN + pi];
                logit[pi] = sum;
            }
        }
        for (int y = y0; y < y1; y++) {
            float fy = (y + 0.5f) * 0.25f - 0.5f;
            if (fy < 0) fy = 0;
            int iy = (int) fy;
            if (iy > PH - 1) iy = PH - 1;
            int iy2 = iy + 1 < PH ? iy + 1 : iy;
            float wy = fy - iy;
            for (int x = x0; x < x1; x++) {
                float fx = (x + 0.5f) * 0.25f - 0.5f;
                if (fx < 0) fx = 0;
                int ix = (int) fx;
                if (ix > PW - 1) ix = PW - 1;
                int ix2 = ix + 1 < PW ? ix + 1 : ix;
                float wx = fx - ix;
                float top = logit[iy * PW + ix] * (1 - wx) + logit[iy * PW + ix2] * wx;
                float bot = logit[iy2 * PW + ix] * (1 - wx) + logit[iy2 * PW + ix2] * wx;
                float p = 1.0f / (1.0f + expf(-(top * (1 - wy) + bot * wy)));
                int v = (int) (p * 255 + 0.5f);
                unsigned char *dst = mask + (size_t) y * W + x;
                if (v > *dst) *dst = (unsigned char) v;
            }
        }
    }
    free(logit);
    free(dets);
    return kept;
}

int yseg_pose(YSeg *m, float conf, float iou_max, float *out, int max) {
    if (m->nout != 9) return -1;
    Det *dets;
    int kept = detect(m, conf, iou_max, max, NULL, &dets);
    if (kept < 0) return -1;
    for (int k = 0; k < kept; k++) {
        Det *d = &dets[k];
        float *o = out + 56 * k;
        o[0] = d->x1;
        o[1] = d->y1;
        o[2] = d->x2;
        o[3] = d->y2;
        o[4] = d->score;
        for (int j = 0; j < 17; j++) {
            float rx = d->coef[(size_t) (3 * j) * d->cstride], ry = d->coef[(size_t) (3 * j + 1) * d->cstride];
            float rv = d->coef[(size_t) (3 * j + 2) * d->cstride];
            o[5 + 3 * j] = (rx * 2 + d->gx) * d->stride;
            o[6 + 3 * j] = (ry * 2 + d->gy) * d->stride;
            o[7 + 3 * j] = 1.0f / (1.0f + expf(-rv));
        }
    }
    free(dets);
    return kept;
}
