// RAW 渲染段：暗角 + 归一化 + RCD 补绿 + 白平衡/矩阵 + 动态范围压缩 + 色调曲线。
//
// 这一段是整条链最贵的部分（原来 Kotlin 版 2.5~4 秒），因为它对每个像素做
// 9 次浮点乘加 + 3 次查表。搬到这里之后逐行并行 + NEON，目标是 <0.8 秒。
#include "hdr_common.h"
#include "hdr_drc.h"

// DRC 增益图分辨率的分母：8 = 原来的 1/8，4 = 1/4，2 = 1/2
#define DRcDiv 8

#include <android/log.h>

#include <algorithm>

#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "HdrRender", __VA_ARGS__)

namespace camhdr {

namespace {

// 定点编码：uint16 表示 0..4 的线性值。
constexpr float kQScale = 16383.75f;      // 65535 / 4
constexpr float kQInv = 4.f / 65535.f;

inline uint16_t encodeQ(float v) {
    int q = (int) (v * kQScale + 0.5f);
    return (uint16_t) clampInt(q, 0, 65535);
}

inline float decodeQ(uint16_t q) { return q * kQInv; }

// 低分辨率浮点图的双线性采样（增益图用）
inline float sampleBilinear(const float *a, int w, int h, float u, float v) {
    const float x = clampf(u, 0.f, (float) (w - 1));
    const float y = clampf(v, 0.f, (float) (h - 1));
    const int x0 = (int) x, y0 = (int) y;
    const int x1 = std::min(x0 + 1, w - 1), y1 = std::min(y0 + 1, h - 1);
    const float fx = x - x0, fy = y - y0;
    const float top = a[(size_t) y0 * w + x0] * (1.f - fx) + a[(size_t) y0 * w + x1] * fx;
    const float bot = a[(size_t) y1 * w + x0] * (1.f - fx) + a[(size_t) y1 * w + x1] * fx;
    return top * (1.f - fy) + bot * fy;
}

struct Ctx {
    const uint16_t *raw = nullptr;
    uint16_t *norm = nullptr;
    uint16_t *green = nullptr;
    int w = 0, h = 0;
    const int *black4 = nullptr;
    float whiteScale = 1.f;
    const float *shading = nullptr;
    int mw = 0, mh = 0;
    const int *channelOf = nullptr;
    int cfa[4] = {1, 0, 1, 2};   // RGGB 默认
};

// 暗角 + 归一化合成一遍。
// 暗角增益是相机上报的标定表（rows×cols×4 通道），双线性插值；
// 只在这个阶段乘一次，后面所有计算都在线性域。
void normalizePass(Ctx &c) {
    const int w = c.w, h = c.h;
    // x 方向的网格索引与权重预先算好，逐像素只剩查表
    std::vector<int> x0(w), x1(w);
    std::vector<float> fx(w);
    for (int x = 0; x < w; ++x) {
        float p = w > 1 ? (float) x / (w - 1) * (c.mw - 1) : 0.f;
        int i = clampInt((int) p, 0, c.mw - 1);
        x0[x] = i;
        x1[x] = std::min(i + 1, c.mw - 1);
        fx[x] = p - i;
    }

    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            float py = h > 1 ? (float) y / (h - 1) * (c.mh - 1) : 0.f;
            int iy0 = clampInt((int) py, 0, c.mh - 1);
            int iy1 = std::min(iy0 + 1, c.mh - 1);
            float wy = py - iy0;
            const int parityRow = (y & 1) << 1;
            const int rowBase = y * w;

            for (int x = 0; x < w; ++x) {
                const int i = rowBase + x;
                const int pos = parityRow | (x & 1);
                // 黑电平按 Bayer 位置；暗角增益按"这个位置是什么颜色"索引，两者不是一回事
                float v = (float) (c.raw[i] - c.black4[pos]);
                float gain = 1.f;
                if (c.shading) {
                    const int ch = c.channelOf[pos];
                    const int a = iy0 * c.mw * 4 + x0[x] * 4 + ch;
                    const int b = iy0 * c.mw * 4 + x1[x] * 4 + ch;
                    const int cc = iy1 * c.mw * 4 + x0[x] * 4 + ch;
                    const int d = iy1 * c.mw * 4 + x1[x] * 4 + ch;
                    const float top = c.shading[a] * (1.f - fx[x]) + c.shading[b] * fx[x];
                    const float bot = c.shading[cc] * (1.f - fx[x]) + c.shading[d] * fx[x];
                    gain = top * (1.f - wy) + bot * wy;
                }
                c.norm[i] = encodeQ(v * gain * c.whiteScale);
            }
        }
    });
}

// 逐相位的保边降噪。
//
// 为什么必须在**去马赛克之前**、而且要按 CFA 相位分开做：Bayer 平面上相邻像素不是
// 同一个颜色，直接滤波会把颜色抹到一起（等于用低通做假去马赛克）。
// 同一相位的像素才属于同一个颜色通道，所以按相位取 3×3（在原始图上就是跨 2 像素、
// 覆盖 ±2 像素）做保边平均。
//
// 为什么用保边而不是普通平均：合并之后剩下的噪声是空间白噪声，普通平均会把
// 边缘一起糊掉，USM 再把边缘锐化回来就成了一个"糊-锐"的循环。保边滤波只平均
// 亮度接近的邻居，边缘两侧互不干扰。
//
// 这一步对应 HDR+ 论文 §5 的"空域降噪"。我们原来是在频域做的（tile 的 DFT 上收缩），
// 但那个做法会连色度（落在双 Nyquist 频点）一起压，每块压得不一样 → 块状偏色。
// 换成空域做，只动亮度，色度完全不动。
/**
 * 逐相位保边降噪，保边阈值**按噪声模型逐像素定**。
 *
 * 原来的 sigmaQ 是一个写死的常数（70）。问题是噪声随信号增长 10 倍
 * （实测暗部 1.1 DN、亮部 11.8 DN），而这个阈值是"d 小于它就平均"的判据：
 *
 *   · 暗部：阈值 70（≈4.1 DN）远大于噪声 18（≈1.1 DN）
 *           → 噪声被当成"细节"整片保留 → **暗部颗粒感重**
 *   · 亮部：阈值 70 又小于噪声 193（≈11.8 DN）
 *           → 真细节被当成噪声抹掉 → **亮部纹理发糊**
 *
 * 现在按 σ² = A·x + B 逐像素算（x 是该像素的 DN 值）：
 *   σ_DN = √(A·x)，换成 norm 的单位：σ_norm = √(A · normVal · normPerDn)
 * 其中 normPerDn = 16383.75/(white−black) ≈ 17.1（Q14 定点下 1 DN 的码数）。
 *
 * ---- A 的定标 ----
 * 注意这个函数作用在**合并后**的数据上（5~7 帧平均过，噪声比单帧低 √N）。
 *
 * 单帧标定：拿真实 DNG 在 PC 上按信号分档统计平坦块，norm≈300 处实测 σ=63，
 * 对应单帧 A = 63²/(300·17.08) = 0.77。各档降噪率对比（同一张 DNG）：
 *     信号档        固定 σ=70     自适应(单帧 A=0.75)
 *     20..60         33.8%          15.8%
 *     150..400       27.7%          23.3%
 *     1000..3000     13.9%          29.9%
 *     3000+          13.9%          34.9%
 * 固定阈值的降噪率随亮度单调下降——亮部几乎不降噪，这正是"中灰/亮部噪点残留"
 * 的来源。
 *
 * 合并后折算：σ_merged = σ_single/√N，取 N=5 → σ 降到 63/2.24=28，
 * 反解 A = 28²/(300·17.08) = 0.15。用单帧的 0.75 会过度平滑 2.2 倍。
 * 若将来改了帧数策略，这个系数要跟着 N 走。
 *
 * 下限 25 码（≈1.5 DN）防止极暗区阈值趋零、退化成不降噪。
 */
void denoiseBayer(uint16_t *norm, int w, int h, float noiseA, float normPerDn) {
    if (w < 8 || h < 8) return;
    const float kMinSigma = 25.f;      // ≈1.5 DN
    const float kMaxSigma = 500.f;     // 防止高光区把一切都抹平
    std::vector<uint16_t> src(norm, norm + (size_t) w * h);
    // 采样统计实际用的阈值，出片后能直接核对"是不是按噪声在走"
    {
        float lo = 1e9f, hi = -1e9f;
        double sum = 0;
        int cnt = 0;
        for (size_t i = 0; i < (size_t) w * h; i += 997) {
            float sg = (noiseA > 0.f)
                    ? std::sqrt(std::max(0.f, noiseA * (float) norm[i] * normPerDn))
                    : 70.f;
            if (sg < kMinSigma) sg = kMinSigma;
            if (sg > kMaxSigma) sg = kMaxSigma;
            if (sg < lo) lo = sg;
            if (sg > hi) hi = sg;
            sum += sg;
            ++cnt;
        }
        ALOGD("逐相位降噪：保边阈值 %.0f ~ %.0f（均值 %.0f），A=%.2f normPerDn=%.1f",
              (double) lo, (double) hi, cnt ? sum / cnt : 0.0,
              (double) noiseA, (double) normPerDn);
    }
    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            for (int x = 0; x < w; ++x) {
                const int xl2 = x - 2 >= 0 ? x - 2 : x;
                const int xr2 = x + 2 < w ? x + 2 : x;
                const int yu2 = y - 2 >= 0 ? y - 2 : y;
                const int yd2 = y + 2 < h ? y + 2 : y;
                const float c = (float) src[(size_t) y * w + x];
                float sig = (noiseA > 0.f)
                        ? std::sqrt(std::max(0.f, noiseA * c * normPerDn))
                        : 70.f;                      // A<=0 = 旧的固定阈值（A/B 用）
                if (sig < kMinSigma) sig = kMinSigma;
                if (sig > kMaxSigma) sig = kMaxSigma;
                const float inv = 1.f / (2.f * sig * sig);
                float sw = 0.f, sv = 0.f;
                for (int dy = -1; dy <= 1; ++dy) {
                    const int yy = dy < 0 ? yu2 : (dy > 0 ? yd2 : y);
                    const size_t row = (size_t) yy * w;
                    for (int dx = -1; dx <= 1; ++dx) {
                        const int xx = dx < 0 ? xl2 : (dx > 0 ? xr2 : x);
                        const float v = (float) src[row + xx];
                        const float d = v - c;
                        const float wt = 1.f / (1.f + d * d * inv);
                        sw += wt;
                        sv += v * wt;
                    }
                }
                norm[(size_t) y * w + x] = (uint16_t) clampInt((int) (sv / sw + 0.5f), 0, 65535);
            }
        }
    });
}

// RCD 第一段：补绿。与 Kotlin 版逐式对应，只是换了语言和并行方式。
void greenPass(Ctx &c) {
    const int w = c.w, h = c.h;
    const int *colors = c.cfa;
    parallelRows(h, [&](int ys, int ye) {
        const float eps = 1e-5f;
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            const int rowUp1 = (y > 0 ? y - 1 : y) * w;
            const int rowDn1 = (y < h - 1 ? y + 1 : y) * w;
            const int rowUp2 = (y > 1 ? y - 2 : y) * w;
            const int rowDn2 = (y < h - 2 ? y + 2 : y) * w;
            const int parityRow = (y & 1) << 1;

            for (int x = 0; x < w; ++x) {
                const int i = row + x;
                if (colors[parityRow | (x & 1)] == 1) {
                    c.green[i] = c.norm[i];
                    continue;
                }
                const int xl1 = x > 0 ? x - 1 : 0;
                const int xr1 = x < w - 1 ? x + 1 : w - 1;
                const int xl2 = x > 1 ? x - 2 : 0;
                const int xr2 = x < w - 2 ? x + 2 : w - 1;

                const float cur = decodeQ(c.norm[i]);
                const float gl = decodeQ(c.norm[row + xl1]);
                const float gr = decodeQ(c.norm[row + xr1]);
                const float gu = decodeQ(c.norm[rowUp1 + x]);
                const float gd = decodeQ(c.norm[rowDn1 + x]);
                const float cl = decodeQ(c.norm[row + xl2]);
                const float cr = decodeQ(c.norm[row + xr2]);
                const float cu = decodeQ(c.norm[rowUp2 + x]);
                const float cd = decodeQ(c.norm[rowDn2 + x]);

                // 方向能量 + 逆方差加权（不硬选方向，硬选判错会出突兀色块）
                const float dh = std::fabs(cl - cr) + std::fabs(gl - gr);
                const float dv = std::fabs(cu - cd) + std::fabs(gu - gd);
                const float gh = 0.5f * (gl + gr) + 0.25f * (2.f * cur - cl - cr);
                const float gv = 0.5f * (gu + gd) + 0.25f * (2.f * cur - cu - cd);
                const float whRaw = 1.f / (dh * dh + eps);
                const float wvRaw = 1.f / (dv * dv + eps);
                const float wh = whRaw / (whRaw + wvRaw);
                float v = wh * gh + (1.f - wh) * gv;

                // 限幅压过冲
                const float localMax = std::max(std::max(gl, gr), std::max(gu, gd));
                const float localMin = std::min(std::min(gl, gr), std::min(gu, gd));
                const float hi = localMax * 1.18f + 0.01f;
                const float lo = std::max(0.f, localMin * 0.82f - 0.01f);
                if (v > hi) v = hi;
                if (v < lo) v = lo;
                c.green[i] = encodeQ(v);
            }
        }
    });
}

/**
 * 白平衡重估：在 log-chroma 空间统计"中性色在哪"，与 ISP 的结果保守融合。
 *
 * ---- 为什么需要 ----
 * 现在完全依赖 ISP 的 AWB。实测同一张白纸连拍两次，ISP 给出的增益是
 * (2.15,1,1.60) 和 (1.40,1,2.40)——**方向完全相反**。这正是 Night Sight 论文
 * 列为最严重失效模式的那一条：极低光下 ISP 的 AWB 会抖。
 *
 * ---- 关键：必须在"乘过暗角"之后统计 ----
 * 第一版我拿原始 RAW 统计，白纸充满画面时结果偏了 0.083 log（约 8%），
 * 修正后白纸从"偏红 2.4%"变成"偏绿 9.4%"。根因是镜头暗角是**逐通道**的，
 * 未校正时边角的 R/G、B/G 与中心不同，全图质心被空间色偏拖走。
 * 所以统计必须用 normalizePass 之后的 norm（已减黑电平 + 乘暗角 + 归一化），
 * 或者 SR 路径下已经乘过暗角的三平面 RGB。
 *
 * ---- 为什么用质心而不是找最高峰 ----
 * 白纸自身的 u/v 标准差就有 0.11（shading、光源不均、噪声），直方图被摊平，
 * 单个 bin 最多 0.7%。此时"最高峰"往往是画面里某个小色块（一个小红点也能成尖峰），
 * 而白纸主体在那团像素的平均位置上。质心统计的是后者。
 *
 * ---- 三重保险（峰值不一定等于光源：一面红墙也能成团）----
 *   1. 只在 ISP 先验 ±0.24 log 的窗口内统计，远处的样本看不到；
 *   2. 窗口内样本必须占 5% 以上，否则说明没有足够的中性色；
 *   3. 只走 alpha 的几何平均（0.35 起，样本占比高时升到 0.75）。
 */
namespace {

constexpr int WB_HB = 64;
constexpr float WB_LO = -0.70f, WB_HI = 0.70f;
constexpr float WB_BINW = (WB_HI - WB_LO) / WB_HB;

/** 一个 RGB 样本 -> 直方图（带过滤与亮度加权，total 也是加权后的） */
inline void wbBinAdd(std::vector<float> &hist, double &total,
                     float R, float G, float B) {
    const float lum = 0.30f * R + 0.59f * G + 0.11f * B;
    if (lum < 0.03f) return;                        // 太暗，比值被噪声淹没
    if (std::max(R, std::max(G, B)) > 0.90f) return; // 接近饱和，比值被削顶破坏
    const float u = std::log(G / R);
    const float v = std::log(G / B);
    if (u < WB_LO || u > WB_HI || v < WB_LO || v > WB_HI) return;
    const int bu = clampInt((int) std::floor((u - WB_LO) / WB_BINW), 0, WB_HB - 1);
    const int bv = clampInt((int) std::floor((v - WB_LO) / WB_BINW), 0, WB_HB - 1);
    // 按亮度加权：暗部像素的比值噪声极大（σ/信号 10~30%），
    // 等权统计会把质心整个拖偏（实测偏 0.087 log）
    hist[(size_t) bv * WB_HB + bu] += lum;
    total += (double) lum;
}

/** 直方图 -> 光源估计 -> 修正 wb（就地） */
void wbFinish(const std::vector<float> &hist, double total, float alpha, float *wb) {
    if (total < 100.0) {
        ALOGD("WB 重估：有效样本太少，保持 ISP 结果");
        return;
    }
    const float gG = std::max(1e-4f, wb[1]);
    const float u0 = std::log(std::max(1e-4f, wb[0]) / gG);
    const float v0 = std::log(std::max(1e-4f, wb[2]) / gG);
    const int cu = clampInt((int) std::floor((u0 - WB_LO) / WB_BINW), 0, WB_HB - 1);
    const int cv = clampInt((int) std::floor((v0 - WB_LO) / WB_BINW), 0, WB_HB - 1);

    constexpr int RAD = 11;                         // ±11 bin ≈ ±0.24 log
    float sw = 0.f, su = 0.f, sv = 0.f;
    for (int dv = -RAD; dv <= RAD; ++dv) {
        const int yy = cv + dv;
        if (yy < 0 || yy >= WB_HB) continue;
        const int r0 = yy * WB_HB;
        for (int du = -RAD; du <= RAD; ++du) {
            const int xx = cu + du;
            if (xx < 0 || xx >= WB_HB) continue;
            const float wv = hist[r0 + xx];
            sw += wv;
            su += wv * ((float) xx + 0.5f);
            sv += wv * ((float) yy + 0.5f);
        }
    }
    const float frac = sw / (float) total;
    if (frac < 0.050f || sw < 1e-6f) {
        ALOGD("WB 重估：先验附近只占 %.2f%%（没有足够的中性色），保持 ISP 结果",
              frac * 100.0);
        return;
    }
    const float uEst = WB_LO + (su / sw) * WB_BINW;
    const float vEst = WB_LO + (sv / sw) * WB_BINW;

    // 超过 0.3 log（≈35%）的偏差多半是"把场景色当白"，宁可不动
    const float du = clampf(uEst - u0, -0.30f, 0.30f);
    const float dv = clampf(vEst - v0, -0.30f, 0.30f);

    float a = alpha;
    if (frac > 0.50f) a = std::max(a, 0.75f);
    else if (frac > 0.15f) a = std::max(a, 0.55f);

    const float oldR = wb[0], oldB = wb[2];
    wb[0] *= std::exp(a * du);
    wb[2] *= std::exp(a * dv);
    ALOGD("WB 重估：窗口占 %.2f%%  u %.4f→%.4f  v %.4f→%.4f  "
          "增益 R %.3f→%.3f B %.3f→%.3f（α=%.2f）",
          frac * 100.0, (double) u0, (double) uEst, (double) v0, (double) vEst,
          (double) oldR, (double) wb[0], (double) oldB, (double) wb[2], (double) a);
}

/**
 * 从原始 RAW 统计，**自带暗角校正**。
 *
 * 为什么不在 normalizePass 之后复用现成的 norm：那需要把 buildDrcGain
 * （它要用重估后的 wb）挪到 normalizePass 之后，改动面太大、容易碰坏别的东西。
 * 这里对 1/8 采样点做一次逐点查表，786K 次查表、约 20 ms，换来零结构风险。
 */
void wbFromRaw(const uint16_t *raw, int w, int h, const int *black4,
               const float *shading, int mw, int mh, const int *channelOf,
               const int *cfaColors, float whiteScale, float alpha, float *wb) {
    std::vector<float> hist((size_t) WB_HB * WB_HB, 0.f);
    double total = 0;
    const bool useShade = shading && mw > 1 && mh > 1 && channelOf;
    for (int y = 0; y + 1 < h; y += 8) {
        for (int x = 0; x + 1 < w; x += 8) {
            float acc[3] = {0.f, 0.f, 0.f};
            int cnt[3] = {0, 0, 0};
            for (int j = 0; j < 2; ++j) {
                for (int i = 0; i < 2; ++i) {
                    const int yy = y + j, xx = x + i;
                    const int pos = ((yy & 1) << 1) | (xx & 1);
                    const int c = cfaColors[pos];
                    float v = (float) raw[(size_t) yy * w + xx] - (float) black4[pos];
                    if (useShade) {
                        const float gx = w > 1 ? (float) xx / (w - 1) * (mw - 1) : 0.f;
                        const float gy = h > 1 ? (float) yy / (h - 1) * (mh - 1) : 0.f;
                        const int ix0 = clampInt((int) gx, 0, mw - 1);
                        const int ix1 = std::min(ix0 + 1, mw - 1);
                        const int iy0 = clampInt((int) gy, 0, mh - 1);
                        const int iy1 = std::min(iy0 + 1, mh - 1);
                        const float wx = gx - ix0, wy = gy - iy0;
                        const int ch = channelOf[pos];
                        const float a0 = shading[((size_t) iy0 * mw + ix0) * 4 + ch];
                        const float b0 = shading[((size_t) iy0 * mw + ix1) * 4 + ch];
                        const float c0 = shading[((size_t) iy1 * mw + ix0) * 4 + ch];
                        const float d0 = shading[((size_t) iy1 * mw + ix1) * 4 + ch];
                        const float t0 = a0 + (b0 - a0) * wx;
                        const float t1 = c0 + (d0 - c0) * wx;
                        v *= t0 + (t1 - t0) * wy;
                    }
                    acc[c] += v * whiteScale;
                    cnt[c]++;
                }
            }
            if (cnt[0] == 0 || cnt[1] == 0 || cnt[2] == 0) continue;
            wbBinAdd(hist, total, acc[0] / cnt[0], acc[1] / cnt[1], acc[2] / cnt[2]);
        }
    }
    wbFinish(hist, total, alpha, wb);
}

/** 从三平面 Q14 RGB（超分输出，已乘过暗角）统计 */
void wbFromRGB3(const uint16_t *rgb3, int w, int h, float alpha, float *wb) {
    std::vector<float> hist((size_t) WB_HB * WB_HB, 0.f);
    double total = 0;
    const size_t n = (size_t) w * h;
    for (int y = 0; y < h; y += 8) {
        for (int x = 0; x < w; x += 8) {
            const size_t i = (size_t) y * w + x;
            wbBinAdd(hist, total, decodeQ(rgb3[i]), decodeQ(rgb3[n + i]),
                     decodeQ(rgb3[2 * n + i]));
        }
    }
    wbFinish(hist, total, alpha, wb);
}

}  // namespace

}  // namespace

// 主体：白平衡（可选重估）-> 色差域插值 R/B -> 3x3 -> 高光处理
//       -> 动态范围压缩 -> 曲线 -> ARGB
void render(const uint16_t *raw, int w, int h,
            const int *black4, float white,
            const float *shading, int mw, int mh, const int *channelOf,
            const int *cfaColors,
            const float *wbGains, const float *matrix,
            int useRcd, int hlRecon, float drcK, float huePreserve,
            int wbRefine,
            const uint16_t *rgb3,
            uint32_t *out) {
    static ToneLut lut;   // 只建一次（4096+1024 个锚点，几毫秒）

    // ---- 白平衡：可选地用图像本身重估（见 refineWhiteBalance 的注释）----
    // 只在这张函数内部用一份副本，不改调用方的数组（调用方可能复用同一份常量）。
    float wb[3] = {wbGains[0], wbGains[1], wbGains[2]};
    if (wbRefine > 0) {
        const float wsWB = 1.f / std::max(1.f, white - (float) black4[0]);
        if (rgb3) {
            // 超分分支：三平面已经是"减黑电平 + 乘暗角"的线性 RGB
            wbFromRGB3(rgb3, w, h, 0.35f, wb);
        } else {
            // 普通分支：自带暗角校正（必须，否则白纸充满画面时质心被边角拖偏）
            wbFromRaw(raw, w, h, black4, shading, mw, mh, channelOf,
                      cfaColors, wsWB, 0.35f, wb);
        }
    }

    // ---- 动态范围压缩：低分辨率增益图 ----
    //
    // ---- DRC 增益图的工作分辨率 ----
    //
    // 这里原来是 1/8。那个选择基于一个**错误的前提**："局部影调的增益本来就是低频的"。
    //
    // buildDrcGain 内部走的正是论文 §6 第 7 步的曝光融合（灰度双合成曝光 + 拉普拉斯
    // 金字塔 + 亮度权重 LUT）。融合产出的关键信息不是"整体提多少"，而是
    // **"这块暗部提 3 倍、紧挨着的那块只提 1.2 倍"**——这个逐块差异本身就是
    // dodge & burn 的局部对比。压到 1/8 分辨率后，它被平滑进 8 像素的格子里，
    // 暗部就只剩下均匀提亮，局部起伏全丢 -> 用户看到的"暗部发糊/发平"。
    //
    // 实测依据：暗部 3~10px 尺度（归一化频率 0.10~0.35）的高频能量只有非 HDR 的
    // 0.5 倍，而整幅的高频反而比非 HDR 高 1.4~2.2 倍——方向相反，说明丢的正是
    // 这一个尺度。用 DRcDiv 可以快速 A/B。
    const bool useDrc = drcK > 1.01f;
    constexpr int kDrcDiv = DRcDiv;
    int gw = std::max(8, w / kDrcDiv), gh = std::max(8, h / kDrcDiv);
    gw = std::min(gw, w);
    gh = std::min(gh, h);
    std::vector<float> gainMap;
    float dehazeB = 0.f;
    if (useDrc) {
        gainMap.assign((size_t) gw * gh, 1.f);
        std::vector<float> proxy((size_t) gw * gh, 0.f);
        const float whiteScale0 = 1.f / std::max(1.f, white - (float) black4[0]);
        buildDrcGain(raw, w, h, black4, whiteScale0, wb, matrix, cfaColors,
                     shading, mw, mh, channelOf,
                     drcK, gw, gh, gainMap.data(), proxy.data());

        // 去雾（论文 §6 第 8 步）：镜头内部的漫射眩光会把整幅画面的黑位抬起来。
        // 做法是把最暗的那一小撮压到 0——按 0.1% 分位取黑点，且不超过白电平的 7%，
        // 只影响 7% 以下的像素，中间调和高光一点不动。
        const int HB = 512;
        std::vector<int> hist(HB, 0);
        int maxBin = 0;
        for (float v : proxy) {
            const int b = clampInt((int) (v * HB), 0, HB - 1);
            hist[b]++;
            if (b > maxBin) maxBin = b;
        }
        const long tot = (long) proxy.size();
        long acc2 = 0;
        float q = 0.f;
        for (int b = 0; b <= maxBin; ++b) {
            acc2 += hist[b];
            if (acc2 * 1000 >= tot) {      // 0.1%
                q = (float) b / HB;
                break;
            }
        }
        dehazeB = clampf(q, 0.f, 0.07f);
    }

    std::vector<uint16_t> norm;
    std::vector<uint16_t> green;

    Ctx c;
    c.raw = raw; c.norm = nullptr; c.green = nullptr;
    c.w = w; c.h = h; c.black4 = black4;
    c.whiteScale = 1.f / std::max(1.f, white - (float) black4[0]);
    c.shading = shading; c.mw = mw; c.mh = mh; c.channelOf = channelOf;
    for (int i = 0; i < 4; ++i) c.cfa[i] = cfaColors[i];

    if (rgb3) {
        // ---- 超分分支 ----
        // SR 的输出已经是"减黑电平 + 归一化 + 乘过暗角"的线性 RGB，
        // 所以这里要**跳过**归一化、逐相位降噪、补绿、去马赛克这四步。
        // 后面的白平衡 / 矩阵 / 高光 / DRC / 色调曲线 / 色相保持全部共用。
        const size_t npx = (size_t) w * h;
        (void) rgb3;
        (void) npx;
    } else {
        norm.resize((size_t) w * h);
        green.resize((size_t) w * h);
        c.norm = norm.data();
        c.green = green.data();
        normalizePass(c);
        // 逐相位保边降噪（空域，只压噪声不动色度）。σ 用 Q16 单位：
        // 合并后残差 σ 约 3~5 DN，Q16 是 16.4 倍 → 50~80，取 70。
        // A=0.15 = 单帧标定值 0.75 按"合并 5 帧"折算（σ ∝ 1/√N）；
        // normPerDn 把 DN 换算成 Q14 码数
        denoiseBayer(c.norm, w, h, 0.15f, 16383.75f / std::max(1.f, white - (float) black4[0]));
        greenPass(c);
    }

    // 归一化后的统计（抽样，几万次读取，忽略不计）。
    // 出"全黑/全白"这类问题时，第一个要看的就是这里：raw 减黑电平之后到底有没有信号。
    //
    // **只在普通分支执行**：超分分支下 norm 是空数组，这里读 norm[i] 会直接
    // 空指针崩溃（真机 SIGSEGV，fault addr 0x0）。统计本身对 SR 也没有意义——
    // SR 的输入不是 RAW。
    if (!rgb3) {
        double s = 0;
        int mx = 0, nz = 0, cnt = 0;
        for (size_t i = 0; i < (size_t) w * h; i += 2003) {
            const int v = norm[i];
            s += v;
            if (v > mx) mx = v;
            if (v > 0) nz++;
            cnt++;
        }
        ALOGD("归一化 均值 %.1f 最大 %d 非零 %.1f%%  white=%.0f black=%d/%d/%d/%d scale=%.5f",
              cnt ? s / cnt : 0.0, mx, cnt ? nz * 100.0 / cnt : 0.0,
              (double) white, black4[0], black4[1], black4[2], black4[3], (double) c.whiteScale);
    }

    // 动态范围压缩之后所有值都落在 0..1，用同一条标定曲线即可，
    // 不再需要"0..1 压到 0.9 + 超范围软滚降"那套 HDR 专用曲线。
    const float *tone = lut.normal;
    const int *colors = c.cfa;
    const float gR = wb[0], gG = wb[1], gB = wb[2];
    const float m0 = matrix[0], m1 = matrix[1], m2 = matrix[2];
    const float m3 = matrix[3], m4 = matrix[4], m5 = matrix[5];
    const float m6 = matrix[6], m7 = matrix[7], m8 = matrix[8];

    // 超分分支的三平面偏移（普通分支用不到，置 0 也无害）
    const size_t npx = (size_t) w * h;

    parallelRows(h, [&](int ys, int ye) {
        for (int y = ys; y < ye; ++y) {
            const int row = y * w;
            const int rowUp = (y > 0 ? y - 1 : y) * w;
            const int rowDn = (y < h - 1 ? y + 1 : y) * w;
            const int parityRow = (y & 1) << 1;

            for (int x = 0; x < w; ++x) {
                const int i = row + x;
                const int color = colors[parityRow | (x & 1)];
                const int l = row + (x > 0 ? x - 1 : 0);
                const int rIdx = row + (x < w - 1 ? x + 1 : w - 1);
                const int u = rowUp + x;
                const int d = rowDn + x;

                float rr, gHere, bb;
                if (rgb3) {
                    // 超分分支：三平面已经是线性、对齐、乘过暗角的 RGB
                    rr = decodeQ(rgb3[i]);
                    gHere = decodeQ(rgb3[npx + i]);
                    bb = decodeQ(rgb3[2 * npx + i]);
                } else if (useRcd) {
                    gHere = decodeQ(green[i]);
                    // 色差域（同色 − 绿）插值，避免把亮度变化当成颜色
                    if (color == 0) {           // R
                        rr = decodeQ(norm[i]);
                        bb = gHere + median4(decodeQ(norm[rowUp + (x > 0 ? x - 1 : 0)]) - decodeQ(green[rowUp + (x > 0 ? x - 1 : 0)]),
                                             decodeQ(norm[rowUp + (x < w - 1 ? x + 1 : w - 1)]) - decodeQ(green[rowUp + (x < w - 1 ? x + 1 : w - 1)]),
                                             decodeQ(norm[rowDn + (x > 0 ? x - 1 : 0)]) - decodeQ(green[rowDn + (x > 0 ? x - 1 : 0)]),
                                             decodeQ(norm[rowDn + (x < w - 1 ? x + 1 : w - 1)]) - decodeQ(green[rowDn + (x < w - 1 ? x + 1 : w - 1)]));
                    } else if (color == 2) {    // B
                        bb = decodeQ(norm[i]);
                        rr = gHere + median4(decodeQ(norm[rowUp + (x > 0 ? x - 1 : 0)]) - decodeQ(green[rowUp + (x > 0 ? x - 1 : 0)]),
                                             decodeQ(norm[rowUp + (x < w - 1 ? x + 1 : w - 1)]) - decodeQ(green[rowUp + (x < w - 1 ? x + 1 : w - 1)]),
                                             decodeQ(norm[rowDn + (x > 0 ? x - 1 : 0)]) - decodeQ(green[rowDn + (x > 0 ? x - 1 : 0)]),
                                             decodeQ(norm[rowDn + (x < w - 1 ? x + 1 : w - 1)]) - decodeQ(green[rowDn + (x < w - 1 ? x + 1 : w - 1)]));
                    } else {                    // G
                        const int hColor = colors[parityRow | ((x + 1) & 1)];
                        if (hColor == 0) {
                            rr = gHere + 0.5f * ((decodeQ(norm[l]) - decodeQ(green[l])) +
                                                 (decodeQ(norm[rIdx]) - decodeQ(green[rIdx])));
                            bb = gHere + 0.5f * ((decodeQ(norm[u]) - decodeQ(green[u])) +
                                                 (decodeQ(norm[d]) - decodeQ(green[d])));
                        } else {
                            bb = gHere + 0.5f * ((decodeQ(norm[l]) - decodeQ(green[l])) +
                                                 (decodeQ(norm[rIdx]) - decodeQ(green[rIdx])));
                            rr = gHere + 0.5f * ((decodeQ(norm[u]) - decodeQ(green[u])) +
                                                 (decodeQ(norm[d]) - decodeQ(green[d])));
                        }
                    }
                } else {
                    // 双线性（诊断用）
                    gHere = decodeQ(green[i]);
                    if (color == 0) {
                        rr = decodeQ(norm[i]);
                        bb = 0.25f * (decodeQ(norm[rowUp + (x > 0 ? x - 1 : 0)]) + decodeQ(norm[rowUp + (x < w - 1 ? x + 1 : w - 1)]) +
                                      decodeQ(norm[rowDn + (x > 0 ? x - 1 : 0)]) + decodeQ(norm[rowDn + (x < w - 1 ? x + 1 : w - 1)]));
                    } else if (color == 2) {
                        bb = decodeQ(norm[i]);
                        rr = 0.25f * (decodeQ(norm[rowUp + (x > 0 ? x - 1 : 0)]) + decodeQ(norm[rowUp + (x < w - 1 ? x + 1 : w - 1)]) +
                                      decodeQ(norm[rowDn + (x > 0 ? x - 1 : 0)]) + decodeQ(norm[rowDn + (x < w - 1 ? x + 1 : w - 1)]));
                    } else {
                        const int hColor = colors[parityRow | ((x + 1) & 1)];
                        if (hColor == 0) {
                            rr = 0.5f * (decodeQ(norm[l]) + decodeQ(norm[rIdx]));
                            bb = 0.5f * (decodeQ(norm[u]) + decodeQ(norm[d]));
                        } else {
                            bb = 0.5f * (decodeQ(norm[l]) + decodeQ(norm[rIdx]));
                            rr = 0.5f * (decodeQ(norm[u]) + decodeQ(norm[d]));
                        }
                    }
                }

                float rG = rr * gR, gGv = gHere * gG, bG = bb * gB;
                float r2 = rG * m0 + gGv * m1 + bG * m2;
                float g2 = rG * m3 + gGv * m4 + bG * m5;
                float b2 = rG * m6 + gGv * m7 + bG * m8;

                // 高光重建：任一通道打到白电平就按中性白处理（比例已经被增益破坏，再算颜色是假的）
                if (hlRecon && (rr >= 0.99f || gHere >= 0.99f || bb >= 0.99f)) {
                    float mx = std::max(r2, std::max(g2, b2));
                    r2 = g2 = b2 = mx;
                }

                // 高光软去饱和：白平衡给 R×1.8、B×1.7、G×1.0，亮部爬升时 G 先到顶，
                // R/B 还在涨，比例被破坏就滑向品红。接近削顶就开始收敛到中性。
                float mx = std::max(r2, std::max(g2, b2));
                if (mx > 0.75f) {
                    float t = clampf((mx - 0.75f) / 0.85f, 0.f, 1.f);
                    float k = 0.9f * t * t * (3.f - 2.f * t);
                    float yl = 0.299f * r2 + 0.587f * g2 + 0.114f * b2;
                    r2 += (yl - r2) * k;
                    g2 += (yl - g2) * k;
                    b2 += (yl - b2) * k;
                }

                if (useDrc) {
                    // 动态范围压缩：从低分辨率增益图双线性取值。
                    //
                    // 增益是**整体乘到三个通道**上的，不是逐通道各算一次——
                    // 逐通道会让高光里三个通道被压缩的程度不同，颜色被洗掉；
                    // 整体乘等于只改曝光，通道比例（也就是颜色）原样保留，
                    // 这正是论文说的"copy per-pixel chroma ratios from the linear RGB image"。
                    const float u = ((float) x + 0.5f) * gw / w - 0.5f;
                    const float vv = ((float) y + 0.5f) * gh / h - 0.5f;
                    const float gl = sampleBilinear(gainMap.data(), gw, gh, u, vv);
                    r2 *= gl;
                    g2 *= gl;
                    b2 *= gl;

                    // 去雾：只压白电平 7% 以下，7% 处平滑收敛到"不处理"。
                    //
                    // **只减亮度、按比例缩放三通道**，不要逐通道各减一个常数。
                    // 逐通道减是"加性"的：暗部的线性值本来就只有 0.006~0.016，
                    // 减掉 0.0039 之后 R:G:B 的比例被彻底改写——暗部会整体推向红
                    // （实测 R/G 相对 DNG 高了 48%，而越暗越严重，正是"暗部发红、
                    //   阴影里成片色带"的来源）。按亮度缩放则是"乘性"的：
                    // 通道比例一点不变，只是把最暗的那一小撮压得更黑，正是去雾想做的事。
                    if (dehazeB > 1e-5f) {
                        const float yl = 0.299f * r2 + 0.587f * g2 + 0.114f * b2;
                        const float t = smoothstep(0.f, 0.07f, yl);
                        const float drop = dehazeB * (1.f - t);
                        const float y2 = std::max(0.f, yl - drop);
                        const float s = yl > 1e-6f ? y2 / yl : 0.f;
                        r2 *= s;
                        g2 *= s;
                        b2 *= s;
                    }
                }

                // 查表：**线性插值**，不是取最近点。
                //
                // 取最近点的误差上限是"半级"，4096 级下约 1 个码——那本身就是一道
                // 一码高的台阶，正是 8bit 渐变里最容易被看成"断层"的东西。
                // 插值之后误差降到 (Δ)²·y''/8 量级（<0.01 码），
                // 等于把"曲线本身"和"量化"这两层台阶都拆掉了，
                // 剩下的只有 8bit 输出本身的量化，交给抖动去打散。
                auto lutAt = [&](float v) -> float {
                    if (v <= 0.f) return tone[0];
                    if (v >= (float) kLutMax) return tone[kLutSize - 1];
                    const float pos = (v < 1.f)
                        ? v * (float) (kLutNormal - 1)
                        : (float) kLutNormal + (v - 1.f) / (kLutMax - 1.f) * (float) (kLutSize - kLutNormal - 1);
                    const int i0 = (int) pos;
                    if (i0 >= kLutSize - 1) return tone[kLutSize - 1];
                    const float f = pos - (float) i0;
                    return tone[i0] + (tone[i0 + 1] - tone[i0]) * f;
                };
                float fr = lutAt(std::min(r2, 1.f));
                float fg = lutAt(std::min(g2, 1.f));
                float fb = lutAt(std::min(b2, 1.f));

                // ---- 色相保持修正 ----
                //
                // 显示曲线是**逐通道**套上去的，而逐通道非线性会随亮度旋转色相：
                // 同一块木头，亮一点和暗一点的部分色相差 10 度以上，投影到画面里
                // 就是"跟着明暗走的色带/断层"。实测（同一张 DNG 用中性渲染做参照）：
                // 我们在 Y≈55~90 这一带多转了 +12.5°，两头只有 +3°——正是带通形状。
                //
                // 论文（HDR+ §6）对增益的要求是 "copy per-pixel chroma ratios from the
                // linear RGB image"，也就是只改亮度、不改通道比例。这里把同一条曲线
                // 改成作用于**亮度**再等比缩放 RGB，得到的色相就是线性的、和亮度无关的；
                // 但这个做法在暗部会把饱和度一起放大（实测 +20~30%，容易又出彩色阴影），
                // 所以只取它的**色相方向**，色度幅值仍用逐通道版本的——
                // 也就是"把色相转到保色相版本的方向"，饱和度和现在完全一致。
                if (huePreserve > 0.01f && r2 > 0.f && g2 > 0.f && b2 > 0.f) {
                    const float Yl = 0.299f * r2 + 0.587f * g2 + 0.114f * b2;
                    const float Yout = lutAt(std::min(Yl, (float) kLutMax));
                    if (Yl > 1e-5f && Yout > 1e-5f) {
                        const float s = Yout / Yl;
                        const float br = r2 * s, bg = g2 * s, bb = b2 * s;
                        const float Yb = 0.299f * br + 0.587f * bg + 0.114f * bb;
                        const float Crb = 0.713f * (br - Yb), Cbb = 0.564f * (bb - Yb);
                        const float Mb = std::sqrt(Crb * Crb + Cbb * Cbb);
                        const float Ya = 0.299f * fr + 0.587f * fg + 0.114f * fb;
                        const float Cra = 0.713f * (fr - Ya), Cba = 0.564f * (fb - Ya);
                        const float Ma = std::sqrt(Cra * Cra + Cba * Cba);
                        if (Mb > 1e-4f && Ma > 1e-4f) {
                            const float t = clampf(huePreserve, 0.f, 1.f);
                            float ur = (Cra / Ma) * (1.f - t) + (Crb / Mb) * t;
                            float uc = (Cba / Ma) * (1.f - t) + (Cbb / Mb) * t;
                            const float nrm = std::sqrt(ur * ur + uc * uc);
                            if (nrm > 1e-4f) {
                                ur /= nrm;
                                uc /= nrm;
                                const float Cr2 = Ma * ur, Cb2 = Ma * uc;
                                const float r3 = Ya + Cr2 / 0.713f;
                                const float b3 = Ya + Cb2 / 0.564f;
                                const float g3 = (Ya - 0.299f * r3 - 0.114f * b3) / 0.587f;
                                fr = r3;
                                fg = g3;
                                fb = b3;
                            }
                        }
                    }
                }

                // 输出 10 位（R<<20|G<<10|B），**不是** ARGB。
                // 收尾链全程在这个精度上做，只在最后一步落到 8bit（见 finish 的抖动）。
                out[i] = pixPack((int) (clampf(fr, 0.f, 1.f) * 1023.f + 0.5f),
                                 (int) (clampf(fg, 0.f, 1.f) * 1023.f + 0.5f),
                                 (int) (clampf(fb, 0.f, 1.f) * 1023.f + 0.5f));
            }
        }
    });

    // 输出统计：SRGB 均值 + 亮度分位。全黑/全白一眼就能看出来。
    {
        double sr = 0, sg = 0, sb = 0;
        int cnt = 0, dark = 0, blown = 0;
        for (size_t i = 0; i < (size_t) w * h; i += 2003) {
            const uint32_t p = out[i];
            const int r = (int) (pixR(p) * kPixTo255), g = (int) (pixG(p) * kPixTo255), b = (int) (pixB(p) * kPixTo255);
            sr += r; sg += g; sb += b;
            const int yl = (77 * r + 150 * g + 29 * b) / 256;
            if (yl < 4) dark++;
            if (yl > 250) blown++;
            cnt++;
        }
        ALOGD("渲染输出 R %.1f G %.1f B %.1f  近黑 %.1f%%  近白 %.1f%%  drcK=%.2f useDrc=%d dehaze=%.4f",
              cnt ? sr / cnt : 0.0, cnt ? sg / cnt : 0.0, cnt ? sb / cnt : 0.0,
              cnt ? dark * 100.0 / cnt : 0.0, cnt ? blown * 100.0 / cnt : 0.0,
              (double) drcK, useDrc ? 1 : 0, (double) dehazeB);
    }
}

}  // namespace camhdr
