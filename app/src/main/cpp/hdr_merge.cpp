// 多帧合并。**照 Google 参考实现（hdr-plus-master/src/merge.cpp）重写**。
//
// 之前的版本是"论文派"：tile 做 32×32 DFT，逐频点做成对 Wiener 收缩，再乘窗反变换。
// 那一版出了三个只属于它自己的问题，而且都不好查：
//   · 频域统一收缩会连**色度**一起压（Bayer 的色度只落在双 Nyquist 一个频点），
//     每块压得不一样 → 块状偏色；
//   · 加窗重建必须除 Σ窗²，写成除 Σ窗 就会留下周期 16 像素的明暗网格；
//   · 按列分条带并行时，条带最左那一列少一块 tile 的贡献，Σ窗² 掉到 1e-5，
//     float 误差被放大五万倍 → 每 512 列一道又亮又花的竖线。
//
// Google 的 Halide 实现里没有这些。它的做法朴素得多：
//   1) 在**降采样一倍**的灰度层上，按 16×16 块算参考帧与其它帧的平均绝对差 d；
//   2) 权重 w = 1 / max(1, d/8 − 10/8)（d 小于 10 视为完美对齐，权重饱和为 1；
//      d 越大权重越小，300 以上视为完全对不上，权重 0）；
//   3) 合并值 = (参考帧 + Σ w_k·其它帧) / (1 + Σ w_k)；
//   4) tile 之间用修正升余弦窗加权相加，**窗和恒等于 1**，所以不需要任何除法。
//
// 逐像素加权平均在数学上就是"把所有帧在空间域平均"，没有任何频域取舍，
// 所以不可能压到色度、也不可能留下网格。稳健性靠权重本身（d 大 → 权重小），
// 而不是频域收缩。
#include "hdr_common.h"
#include "hdr_align.h"
#include "hdr_api.h"

#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <climits>
#include <vector>

#define AMERGE(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrMerge", __VA_ARGS__)

namespace camhdr {

namespace {

constexpr int TILE = 32;          // 合并块边长（Bayer 像素）
constexpr int STRIDE = TILE / 2;  // 半块重叠
constexpr int BANDS = 3;          // 滚动累积缓冲：3 个 16 行带
constexpr int MAXF = 16;          // 最多支持的帧数（编译期上限）

// 参考实现的三个常数（merge.cpp: factor / min_dist / max_dist）
// 权重：高斯式 exp(-(d/d0)²)，d0 是"还信得过"的平均绝对差尺度。
//
// 参考实现用的是 1/max(1, d/8 − 10/8)：d<18 时权重恒为 1（视为完美对齐），
// d=100 时还有 0.089。这个尾巴太长——5 帧里一帧错位 2 像素，
// 它仍然占 0.089/(1+4×0.089) ≈ 6%，叠加 4 帧就是 26% 的"第二层影像"，
// 肉眼正好能看成虚影/双边缘（用户拍的梯子就是这样）。
//
// 高斯尾巴掉得快：d=10 → 0.37，d=20 → 0.018，d=30 → 1e-4。
// 对齐好的 tile 照样合并 5 帧（压噪声），对不上的 tile 自动退化成只用参考帧，
// 于是"最差也和一帧一样清楚"。
constexpr float kTrustDist = 10.f;

// 修正升余弦窗。半块重叠下 Σw = 1（两个相差 16 的抽头：w(u)+w(u+16)=1）。
struct WinTab {
    float w[TILE];
    bool init = false;

    void build() {
        if (init) return;
        for (int i = 0; i < TILE; ++i) {
            w[i] = 0.5f - 0.5f * std::cos(2.f * (float) M_PI * (i + 0.5f) / TILE);
        }
        init = true;
    }
};

WinTab gWin;

// 降采样一倍的灰度层（参考实现的 box_down2）。
// 两个作用：算 L1 距离时噪声被压掉一半（不然噪声大的帧全被判成"对不上"），
// 以及省掉 3/4 的读取量。
struct Gray {
    int w = 0, h = 0;
    std::vector<uint16_t> a;
};

Gray boxDown2(const uint16_t *raw, int w, int h, int black) {
    Gray g;
    g.w = std::max(1, w / 2);
    g.h = std::max(1, h / 2);
    g.a.resize((size_t) g.w * g.h);
    parallelRows(g.h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int r0 = (2 * y) * w;
            const int r1 = std::min(h - 1, 2 * y + 1) * w;
            const uint16_t *p0 = raw + r0;
            const uint16_t *p1 = raw + r1;
            uint16_t *o = g.a.data() + (size_t) y * g.w;
            for (int x = 0; x < g.w; ++x) {
                const int c0 = 2 * x, c1 = std::min(w - 1, 2 * x + 1);
                const int v = (int) p0[c0] + p0[c1] + p1[c0] + p1[c1] - 4 * black;
                o[x] = (uint16_t) clampInt((v + 2) >> 2, 0, 65535);
            }
        }
    });
    return g;
}

// tile 在给定位移下的**平均平方差**（在灰度层上，位移要除以 2），顺便给出平均信号。
//
// 为什么从"平均绝对差"换成"均方差"：下一步要**减掉噪声的贡献**，
// 而噪声的方差是可加、可减的（σ²_观测 = σ²_差异 + σ²_噪声），
// 绝对值做不到这件事。用均方才能干净地扣成"纯粹的差异"。
//
// 越界跳过而不是 clamp：clamp 会把图外内容伪造成"和图内一样"，
// 边界上所有候选得分相等，权重就失真了。
float meanSqDiff(const Gray &g, int ox, int oy, int dx, int dy, float *outMean) {
    const int bx = ox / 2, by = oy / 2;       // 灰度层坐标
    const int sx = dx / 2, sy = dy / 2;       // 位移也缩一半（dx、dy 恒为偶数）
    const int n = TILE / 2;                   // 16
    double sum2 = 0.0, sum = 0.0;
    int cnt = 0;
    for (int y = 0; y < n; ++y) {
        const int ry = by + y, ay = ry + sy;
        // 基准坐标也要检查：外圈 tile（ox/oy = -16）除 2 之后是 -8，
        // 只检查位移后的坐标会读到数组前面去（实测直接 SIGSEGV）。
        if (ry < 0 || ry >= g.h) continue;
        if (ay < 0 || ay >= g.h) continue;
        const uint16_t *rr = g.a.data() + (size_t) ry * g.w;
        const uint16_t *aa = g.a.data() + (size_t) ay * g.w;
        for (int x = 0; x < n; ++x) {
            const int rx = bx + x, ax = rx + sx;
            if (rx < 0 || rx >= g.w) continue;
            if (ax < 0 || ax >= g.w) continue;
            const double d = (double) rr[rx] - (double) aa[ax];
            sum2 += d * d;
            sum += rr[rx];
            ++cnt;
        }
    }
    if (cnt < (n * n) / 4) {                  // 有效样本太少，判成"对不上"
        if (outMean) *outMean = 0.f;
        return 1e12f;
    }
    if (outMean) *outMean = (float) (sum / cnt);
    return (float) (sum2 / cnt);
}

/**
 * 从**这一帧自己**估计噪声模型 σ² = A·x + B（x 是灰度层信号）。
 *
 * ---- 为什么必须按帧估，不能写死系数 ----
 * 我一开始用实测值 σ² = 1.79·x（那是 ISO 2600 的图）。但噪声随模拟增益线性放大：
 * ISO 19299 时同样的场景，噪声要大 7 倍以上。写死的系数会把真实噪声当成"错位"，
 * 融合权重被大规模误杀——实测平均有效帧只有 2.54/5，等于只合并了 2.5 帧。
 *
 * 估法：块内拉普拉斯响应的**中位数**。
 *   d = 4c − 上 − 下 − 左 − 右，若像素噪声是 σ 的白噪声，则 Var(d) = 20σ²。
 *   对噪声取中位数（median|d| / 0.6745 = √20·σ）而不是方差，是因为
 *   方差会被块内的真实纹理拉高十几个 dB，中位数只受"超过一半像素"的影响。
 * 然后按亮度分箱取中位数、最小二乘拟合 A、B，纹理在分箱+中位数里基本被剔除。
 *
 * 成本：3 Mpix × 每像素 5 次读，多线程约 10 ms，只在合并开始时算一次。
 */
struct NoiseModel {
    float A = 0.2f;
    float B = 1.0f;
};

NoiseModel estimateNoise(const Gray &g) {
    NoiseModel nm;
    constexpr int BS = 16;
    const int bx = g.w / BS, by = g.h / BS;
    if (bx < 8 || by < 8) return nm;

    const size_t nb = (size_t) bx * by;
    std::vector<float> mv(nb, -1.f), vv(nb, 0.f);

    parallelRows(by, [&](int ys, int ye) {
        std::vector<float> lap;
        lap.reserve(BS * BS);
        for (int ty = ys; ty < ye; ++ty) {
            for (int tx = 0; tx < bx; ++tx) {
                const int x0 = tx * BS, y0 = ty * BS;
                double sum = 0;
                int cnt = 0;
                lap.clear();
                for (int y = y0 + 1; y < y0 + BS - 1; ++y) {
                    const uint16_t *r0 = g.a.data() + (size_t) (y - 1) * g.w;
                    const uint16_t *r1 = g.a.data() + (size_t) y * g.w;
                    const uint16_t *r2 = g.a.data() + (size_t) (y + 1) * g.w;
                    for (int x = x0 + 1; x < x0 + BS - 1; ++x) {
                        const float c = (float) r1[x];
                        const float d = 4.f * c - (float) r1[x - 1] - (float) r1[x + 1]
                                        - (float) r0[x] - (float) r2[x];
                        lap.push_back(std::fabs(d));
                        sum += c;
                        ++cnt;
                    }
                }
                const size_t i = (size_t) ty * bx + tx;
                if (cnt < 32) continue;
                const size_t mid = lap.size() / 2;
                std::nth_element(lap.begin(), lap.begin() + mid, lap.end());
                const float sig = lap[mid] / 0.6745f;      // = √20·σ
                const float sp = sig / 4.4721f;            // 像素噪声 σ
                mv[i] = (float) (sum / cnt);
                vv[i] = sp * sp;
            }
        }
    });

    // 按亮度分箱取中位数（抗纹理），再对所有箱做一次最小二乘
    static const float edges[] = {0.f, 1.f, 2.f, 4.f, 8.f, 16.f, 32.f, 64.f,
                                  128.f, 256.f, 512.f, 1024.f, 4096.f, 65536.f};
    constexpr int NE = sizeof(edges) / sizeof(edges[0]);
    std::vector<float> bm, bv;
    for (int e = 0; e + 1 < NE; ++e) {
        std::vector<float> ms, vs;
        for (size_t i = 0; i < nb; ++i) {
            if (mv[i] < 0.f) continue;
            if (mv[i] >= edges[e] && mv[i] < edges[e + 1]) {
                ms.push_back(mv[i]);
                vs.push_back(vv[i]);
            }
        }
        if (ms.size() < 6) continue;
        const size_t k = ms.size() / 2;
        std::nth_element(ms.begin(), ms.begin() + k, ms.end());
        std::nth_element(vs.begin(), vs.begin() + k, vs.end());
        bm.push_back(ms[k]);
        bv.push_back(vs[k]);
    }
    if (bm.size() < 2) return nm;

    // σ² = A·x + B 的普通最小二乘
    double sx = 0, sy = 0, sxx = 0, sxy = 0;
    const double n2 = (double) bm.size();
    for (size_t i = 0; i < bm.size(); ++i) {
        sx += bm[i];
        sy += bv[i];
        sxx += (double) bm[i] * bm[i];
        sxy += (double) bm[i] * bv[i];
    }
    const double den = n2 * sxx - sx * sx;
    if (std::fabs(den) < 1e-6) return nm;
    nm.A = (float) ((n2 * sxy - sx * sy) / den);
    nm.B = (float) ((sy - nm.A * sx) / n2);
    if (nm.A < 0.f) nm.A = 0.f;
    if (nm.B < 0.05f) nm.B = 0.05f;
    return nm;
}

/**
 * 单帧合并权重。
 *
 * ---- 改动的核心：把噪声的方差从"差异"里减掉 ----
 * 旧写法是 w = exp(−(d/10)²)，d 是"两帧同位置的平均绝对差"。问题在于
 * **这个 d 里混着噪声**：即使两帧完全对齐、场景完全静止，d 也不为 0，
 * 而是等于两帧噪声之差，其大小随亮度增长（实测 σ 从 1.1 DN 涨到 11.8 DN，差 10 倍）。
 *
 * 后果有两层：
 *   · 亮部/高 ISO 被系统性误判成"对不上"，白丢合并量；
 *   · 同一张图里亮部和暗部的合并策略不同 —— 亮暗交界处合并量突变。
 *
 * 现在先扣掉噪声那一项：
 *     d_eff² = max(0, MS − 2σ²)
 * 其中 MS 是均方差，σ² = A·x+B 来自 estimateNoise（**按这一帧自己估**）。
 *
 * 这个式子**只增不减**：
 *   · 纯噪声区（MS ≈ 2σ²）→ d_eff = 0 → w = 1（该合并就合并满）；
 *   · 真有错位时（MS ≫ 2σ²）→ 噪声项可忽略 → w 与旧写法完全一致，
 *     不会因为"放宽"而引入重影。
 *
 * 换句话说：以前是"错把噪声当成错位"，现在只把**超出噪声的部分**算作错位。
 */
inline float tileWeight(float ms, float meanSig, const NoiseModel &nm) {
    if (ms >= 1e11f) return 0.f;              // meanSqDiff 的"对不上"哨兵
    const float sg2 = std::max(0.05f, nm.A * meanSig + nm.B);
    const float vd = 2.f * sg2;               // 两帧之差的噪声方差
    float dEff2 = ms - vd;
    if (dEff2 < 0.f) dEff2 = 0.f;
    return std::exp(-dEff2 / (kTrustDist * kTrustDist));
}

// 位移场查表（双线性，避免 32 像素网格在位移场里留下方块边界）
inline void fieldAt(const AlignField &f, int px, int py, int *ox, int *oy) {
    if (f.cols <= 0 || f.rows <= 0 || f.tile <= 0) {
        *ox = 0;
        *oy = 0;
        return;
    }
    const float u = (float) px / (float) f.tile - 0.5f;
    const float v = (float) py / (float) f.tile - 0.5f;
    const int x0 = (int) std::floor(u), y0 = (int) std::floor(v);
    const float fx = u - x0, fy = v - y0;
    const int x1 = x0 + 1, y1 = y0 + 1;

    auto at = [&](int x, int y, const std::vector<int16_t> &t) -> float {
        return (float) t[(size_t) clampInt(y, 0, f.rows - 1) * f.cols + clampInt(x, 0, f.cols - 1)];
    };
    const float dxv = (at(x0, y0, f.dx) * (1 - fx) + at(x1, y0, f.dx) * fx) * (1 - fy) +
                      (at(x0, y1, f.dx) * (1 - fx) + at(x1, y1, f.dx) * fx) * fy;
    const float dyv = (at(x0, y0, f.dy) * (1 - fx) + at(x1, y0, f.dy) * fx) * (1 - fy) +
                      (at(x0, y1, f.dy) * (1 - fx) + at(x1, y1, f.dy) * fx) * fy;

    int rdx = (int) std::lround(dxv);
    int rdy = (int) std::lround(dyv);
    // 位移必须是偶数，否则 Bayer 四相位会被拆散，直接出彩色镶边
    rdx &= ~1;
    rdy &= ~1;
    *ox = clampInt(rdx, -512, 512);
    *oy = clampInt(rdy, -512, 512);
}

}  // namespace

// 主入口：等曝光连拍 -> tile 级对齐 -> 空间域加权平均 -> 升余弦窗拼块
//
// 并行方式按**列条带**切给多线程，每条带自带滚动缓冲。
// 每段要往前多累加一个 tile（左邻条带的最后一个），否则段首那 16 列
// 只有一块 tile 贡献、Σ窗只有 0.005，除出来就是一条噪声线。
void hdrplusMerge(const uint16_t *const *frames, int n, int w, int h,
                  int black, int white, uint16_t *out) {
    if (n <= 0 || w <= 0 || h <= 0) return;
    if (n > MAXF) n = MAXF;
    gWin.build();

    // ---- §4 分层对齐 + 选最锐帧（参考帧不一定还是第 0 帧）----
    std::vector<AlignField> fields;
    int refIdx = 0;
    alignBurst(frames, n, w, h, black, &fields, &refIdx);
    if (refIdx < 0 || refIdx >= n) refIdx = 0;
    const uint16_t *ref = frames[refIdx];

    // ---- 降采样灰度层：每帧一份，用来算 L1 距离和噪声模型 ----
    std::vector<Gray> gray(n);
    for (int k = 0; k < n; ++k) gray[k] = boxDown2(frames[k], w, h, black);

    // ---- 噪声模型：按这一帧自己估（ISO 变了，噪声就变了，写死系数会误杀权重）----
    const NoiseModel nm = estimateNoise(gray[refIdx]);
    AMERGE("噪声模型（灰度层）：σ² = %.4f·x + %.4f", (double) nm.A, (double) nm.B);

    const int tilesX = (w + STRIDE - 1) / STRIDE;
    const int tilesY = (h + STRIDE - 1) / STRIDE;
    const int bandRows = BANDS * STRIDE;

    // 统计"这个 tile 实际合并了几帧"（含参考帧），用来验证权重是否真的在合并。
    // 理论最大 = n；如果一直明显低于 n，说明权重把该合的也拒了。
    std::atomic<long long> statTotalMilli{0};
    std::atomic<long long> statTiles{0};

    auto runStrip = [&](int tx0, int tx1) {
        std::vector<float> acc((size_t) bandRows * w, 0.f);
        std::vector<float> wsum((size_t) bandRows * w, 0.f);
        std::vector<float> wgt(n, 0.f);
        int fxs[MAXF] = {0}, fys[MAXF] = {0};

        for (int ty = -1; ty < tilesY; ++ty) {
            const int oy = ty * STRIDE;
            const int accStart = std::max(-1, tx0 - 1);

            for (int tx = accStart; tx < tx1; ++tx) {
                const int ox = tx * STRIDE;

                // ---- 这个 tile 各帧的位移与权重（位移只算一次，内层循环直接查表）----
                float total = 1.f;                 // 1 = 参考帧自己（它不参与权重计算）
                for (int k = 0; k < n; ++k) {
                    fxs[k] = 0;
                    fys[k] = 0;
                    wgt[k] = 0.f;
                    if (k == refIdx) continue;
                    int fx = 0, fy = 0;
                    fieldAt(fields[k], ox + TILE / 2, oy + TILE / 2, &fx, &fy);
                    fxs[k] = fx;
                    fys[k] = fy;
                    float tileMean = 0.f;
                    const float ms = meanSqDiff(gray[k], ox, oy, fx, fy, &tileMean);
                    const float wk = tileWeight(ms, tileMean, nm);
                    wgt[k] = wk;
                    total += wk;
                }
                const float invTotal = 1.f / total;
                statTotalMilli += (long long) (total * 1000.f);
                statTiles += 1;

                // ---- 逐像素：(参考帧 + Σ w_k·其它帧) / total ----
                for (int y = 0; y < TILE; ++y) {
                    const int ay = oy + y;
                    if (ay >= h) break;
                    if (ay < 0) continue;          // 外圈 tile：图像上方那一圈不落盘
                    const int bi = (ay % bandRows) * w;
                    const int refRow = ay * w;
                    const float wy = gWin.w[y];
                    for (int x = 0; x < TILE; ++x) {
                        const int ax = ox + x;
                        if (ax >= w) break;
                        if (ax < 0) continue;

                        float s = (float) ref[refRow + ax] - (float) black;
                        for (int k = 0; k < n; ++k) {
                            if (k == refIdx) continue;
                            const float wk = wgt[k];
                            if (wk <= 0.f) continue;
                            const int sy = clampInt(ay + fys[k], 0, h - 1);
                            const int sx = clampInt(ax + fxs[k], 0, w - 1);
                            s += wk * ((float) frames[k][(size_t) sy * w + sx] - (float) black);
                        }
                        const float win = wy * gWin.w[x];
                        acc[bi + ax] += s * invTotal * win;
                        wsum[bi + ax] += win;
                    }
                }
            }

            // 这一 tile 行的前半个块已完整 -> 定稿
            if (ty < 0) continue;
            const int xStart = tx0 * STRIDE;
            const int xEnd = std::min(w, tx1 * STRIDE);
            for (int y = 0; y < STRIDE; ++y) {
                const int ay = oy + y;
                if (ay >= h) break;
                const int bi = (ay % bandRows) * w;
                for (int x = xStart; x < xEnd; ++x) {
                    const float wg = wsum[bi + x];
                    // 图内部 Σ窗 ≡ 1；除一下只是为了贴边（覆盖不到 4 块）时也正确
                    const float v = wg > 1e-4f ? acc[bi + x] / wg : 0.f;
                    out[(size_t) ay * w + x] =
                        (uint16_t) clampInt((int) (v + 0.5f) + black, 0, 65535);
                    acc[bi + x] = 0.f;
                    wsum[bi + x] = 0.f;
                }
            }
        }
    };

    const int groups = (tilesX + 3) / 4;
    int lanes = laneCount();
    const int lanesNeeded = std::max(1, groups);
    if (lanes > lanesNeeded) lanes = lanesNeeded;
    if (lanes <= 1) {
        runStrip(0, tilesX);
    } else {
        const int perGroups = (groups + lanes - 1) / lanes;
        std::vector<std::thread> ts;
        for (int i = 0; i < lanes; ++i) {
            const int g0 = i * perGroups;
            const int g1 = std::min(groups, g0 + perGroups);
            if (g0 >= g1) break;
            const int tx0 = g0 * 4;
            const int tx1 = std::min(tilesX, g1 * 4);
            ts.emplace_back([&, tx0, tx1]() { runStrip(tx0, tx1); });
        }
        for (auto &t : ts) t.join();
    }

    // 合并结果统计：为 0 的话下游一定是黑的，单独打一行方便分流
    {
        double s = 0;
        int mx = 0, zero = 0, cnt = 0;
        for (size_t i = 0; i < (size_t) w * h; i += 2003) {
            const int v = (int) out[i] - black;
            s += v;
            if (v > mx) mx = v;
            if (v <= 0) zero++;
            cnt++;
        }
        AMERGE("合并输出 (减黑电平) 均值 %.1f 最大 %d 非正占比 %.1f%% n=%d black=%d white=%d ref=%d 平均有效帧 %.2f/%.1f",
               cnt ? s / cnt : 0.0, mx, cnt ? zero * 100.0 / cnt : 0.0, n, black, white, refIdx,
               statTiles.load() ? statTotalMilli.load() / 1000.0 / statTiles.load() : 0.0,
               (double) n);
    }
}

}  // namespace camhdr
