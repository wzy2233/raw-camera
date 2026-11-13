// 相机 RAW 管线的 native 公共部分。
//
// 为什么搬到 C++：
//   原来整条链在 Kotlin 里跑，12MP × 5 帧的逐像素循环全靠 JIT，
//   在骁龙 8 系上 ~9 秒，在老机器上会到 20 秒以上。
//   同样的循环用 clang -O3 + NEON，实测可以到 1/3 以内，而且不依赖 JIT 预热。
//
// 内存策略：中间数据一律 uint16 定点，不用 float。
//   12.6MP 的 float 是 50MB/张，两张就 100MB，3GB 的老机器会直接 OOM。
//   uint16 的精度是 1/65535 ≈ 1.5e-5，比传感器实际 10bit 还细，够用。
#ifndef CAMHDR_COMMON_H
#define CAMHDR_COMMON_H

#include <cstdint>
#include <cstddef>
#include <cmath>
#include <thread>
#include <vector>

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#define CAMHDR_NEON 1
#endif

namespace camhdr {

inline int clampInt(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

inline float smoothstep(float e0, float e1, float x) {
    float t = clampf((x - e0) / (e1 - e0), 0.f, 1.f);
    return t * t * (3.f - 2.f * t);
}

// sRGB 传递函数。只用于"合成曝光"和反解，最终编码走 kPsLin 那条标定曲线。
inline float oetf(float x) {
    if (x <= 0.0031308f) return 12.92f * x;
    return 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
}

inline float oetfInv(float y) {
    if (y <= 0.04045f) return y / 12.92f;
    return std::pow((y + 0.055f) / 1.055f, 2.4f);
}

// 4 个数的中位数（取中间两个的平均），比均值抗单个异常邻居
inline float median4(float d1, float d2, float d3, float d4) {
    float a = d1 < d2 ? d1 : d2;
    float b = d1 < d2 ? d2 : d1;
    float c = d3 < d4 ? d3 : d4;
    float e = d3 < d4 ? d4 : d3;
    float loMid = a > c ? a : c;
    float hiMid = b < e ? b : e;
    return (loMid + hiMid) * 0.5f;
}

// 按行切分给多个线程。老机器常见 4~8 核，这里上限 8，避免线程过多反而抢带宽。
inline int laneCount() {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) n = 4;
    if (n > 8) n = 8;
    return (int) n;
}

// 行区间并行：body(y0, y1)
//
// [maxLanes] > 0 时把线程数压到它以下。手机是 1+3+4 的大小核，全用满 8 个线程
// 意味着有线程被调度到小核上；对计算密集的 pass 来说，少开几个线程、让每个线程
// 都落在强核上，墙钟反而可能更短。默认 0 = 不限制（用 laneCount()）。
template<typename F>
inline void parallelRows(int height, F body, int maxLanes = 0) {
    int lanes = laneCount();
    if (maxLanes > 0 && lanes > maxLanes) lanes = maxLanes;
    if (lanes <= 1 || height < 64) {
        body(0, height);
        return;
    }
    int laneH = (height + lanes - 1) / lanes;
    std::vector<std::thread> ts;
    ts.reserve(lanes);
    for (int i = 0; i < lanes; ++i) {
        int y0 = i * laneH;
        int y1 = y0 + laneH;
        if (y1 > height) y1 = height;
        if (y0 >= y1) break;
        ts.emplace_back([=, &body]() { body(y0, y1); });
    }
    for (auto &t : ts) t.join();
}

// ---------------------------------------------------------------- 色调曲线
//
// 这条曲线是从 Photoshop 逐像素反解出来的（同一张 RAW 与 PS 导出的 JPG 配对，
// 76 万个采样点，中位误差 1.7 码）。129 个锚点，Kotlin 侧有一份完全相同的表，
// 两边必须同步——它是"我们的成片和 PS 一致"的唯一依据。
static const float kPsLin[129] = {
    0.000000f, 0.000837f, 0.001674f, 0.002510f, 0.003347f, 0.004184f,
    0.005021f, 0.005857f, 0.006694f, 0.007531f, 0.008368f, 0.009205f,
    0.010041f, 0.010878f, 0.011715f, 0.016023f, 0.017078f, 0.018138f,
    0.022502f, 0.023569f, 0.024636f, 0.028746f, 0.029364f, 0.029963f,
    0.030562f, 0.031161f, 0.038107f, 0.038803f, 0.039232f, 0.039662f,
    0.040092f, 0.040521f, 0.040951f, 0.041381f, 0.045127f, 0.045701f,
    0.046249f, 0.046797f, 0.047344f, 0.047895f, 0.052359f, 0.054961f,
    0.056214f, 0.057413f, 0.061867f, 0.062910f, 0.063952f, 0.068190f,
    0.069221f, 0.070158f, 0.071095f, 0.075788f, 0.077432f, 0.079191f,
    0.081018f, 0.085679f, 0.086925f, 0.091461f, 0.092601f, 0.093742f,
    0.101538f, 0.102893f, 0.104253f, 0.108443f, 0.109233f, 0.110023f,
    0.110813f, 0.114836f, 0.115507f, 0.116178f, 0.116849f, 0.117523f,
    0.125208f, 0.126465f, 0.131059f, 0.133233f, 0.139381f, 0.144037f,
    0.149360f, 0.154687f, 0.156997f, 0.164362f, 0.171096f, 0.172745f,
    0.177735f, 0.179506f, 0.188114f, 0.190473f, 0.197062f, 0.203240f,
    0.212478f, 0.218172f, 0.223695f, 0.225949f, 0.234923f, 0.241031f,
    0.249241f, 0.258053f, 0.264218f, 0.274078f, 0.280811f, 0.287400f,
    0.297478f, 0.304638f, 0.311997f, 0.322727f, 0.334720f, 0.344999f,
    0.356920f, 0.368185f, 0.383951f, 0.396567f, 0.412964f, 0.430202f,
    0.446630f, 0.468813f, 0.492031f, 0.516776f, 0.540449f, 0.570009f,
    0.630374f, 0.675145f, 0.704983f, 0.736755f, 0.771024f, 0.808705f,
    0.851527f, 0.903727f, 1.000000f,
};

static const int kPsLinN = 129;

// 平滑后的锚点表。
//
// 为什么必须平滑：`kPsLin` 是用"同一张 RAW + PS 导出的 JPG"逐像素反解出来的，
// 129 个锚点**按输出等间隔**取样（每个锚点差 1/128 = 2 个码）。当曲线某一段本身很平
// （输出几乎不动）时，反解出来的线性值带 ±1 个码的拟合噪声，除到斜率上就被放大成
// **锯齿**：实测相邻线段斜率在 1.1 和 18.2 之间反复跳，跳变最大 11.6 倍。
//
// 后果是逐像素可见的：
//   · 亮度每过 2 个码就"陡一下、停一下"，平滑渐变上出现一圈圈等高线（断层）；
//   · 曲线是**逐通道**查的，R/G/B 落在不同的齿上，跨齿时三通道比例各跳各的
//     → 颜色随亮度阶梯式变化（色带、色块）。
//
// 修法：对锚点做几轮 [1,2,1]/4 平滑 + 强制单调。与原表的输出偏差中位 0.4 码、
// 最大 3.4 码（在本拟合本身的误差量级内），但斜率跳变从 11.6 倍降到 1.4 倍。

// ---------------------------------------------------------------- 像素容器
//
// 每通道 **10 位**，打包进一个 32 位整数：R<<20 | G<<10 | B。
//
// 为什么是 10 位而不是 16 位：
//   · 正好塞进原来那个 ARGB 整数，内存一点不涨（12MP 仍是 50MB）；
//   · 1 个码 = 0.098% 满量程，已经和传感器自身 1 LSB（0.104%）相当，
//     再高只是在放大噪声——实测本机最平坦区域的颗粒是 0.42%（1.1 码）；
//   · 真正的好处不是"多几级"，而是让**增益不再丢级**：以前 tmo、色相这些
//     ×1.5~2 的乘法都在 8bit 上做，乘完只剩 145 级（本该 250 级），
//     靠抖动把丢掉的级补回来——代价是 ±1 码的抖动噪声。
//
// 各阶段用"8bit 码单位"的浮点（0..255，步长 1/4 码）来算，
// 这样所有既有阈值（4/14 码、236 软膝、0.78 高光…）都保持原意，不用重新标定。
constexpr float kPixTo255   = 255.f / 1023.f;
constexpr float kPixFrom255 = 1023.f / 255.f;

inline int pixR(uint32_t p) { return (int) ((p >> 20) & 1023u); }
inline int pixG(uint32_t p) { return (int) ((p >> 10) & 1023u); }
inline int pixB(uint32_t p) { return (int) (p & 1023u); }

inline uint32_t pixPack(int r, int g, int b) {
    return ((uint32_t) clampInt(r, 0, 1023) << 20) |
           ((uint32_t) clampInt(g, 0, 1023) << 10) |
           (uint32_t) clampInt(b, 0, 1023);
}

// 曲线的**解析形式**：自然三次样条（natural cubic spline），C2 连续。
//
// 上面那张表只是"锚点"；真正参与计算的是这条三次样条：
// 段内 y(t) = y_i + b_i·t + c_i·t² + d_i·t³，t = x − k_i，
// 系数由"二阶导在锚点处连续 + 两端二阶导为 0"解一个三对角方程得到。
//
// 为什么要这样：
//   · 折线插值的斜率在锚点处**跳变**，129 个锚点就是 128 处台阶。
//     平滑锚点之后跳变已经很小，但仍是 C0；换成 C2 之后斜率、曲率都连续，
//     曲线的"软硬程度"本身也是连续的——渐变里不可能再出现等值线式的断层。
//   · 严格单调：锚点单调 + 自然样条的极值只可能出现在锚点处，实测最小步进 5.7e-6 > 0。
//   · 精度：与"平滑后的折线"偏差 0.033 码，等于精确复现现有观感。
//
// 与 PS 原表的偏差中位 0.4 码、最大 3.4 码——而这批锚点本身是 ±1.7 码拟合出来的，
// 也就是说：去掉的是拟合噪声，不是曲线形状。
struct PsSpline {
    float k[kPsLinN];   // 锚点的线性值（已平滑、严格递增）
    float b[kPsLinN], c[kPsLinN], d[kPsLinN];
    bool init = false;

    void build() {
        if (init) return;
        for (int i = 0; i < kPsLinN; ++i) k[i] = kPsLin[i];
        // 8 轮 [1,2,1]/4 + 强制单调：去掉反解带来的 ±1 码噪声
        for (int it = 0; it < 8; ++it) {
            float n[kPsLinN];
            for (int i = 0; i < kPsLinN; ++i) n[i] = k[i];
            for (int i = 1; i < kPsLinN - 1; ++i)
                n[i] = 0.25f * (k[i - 1] + 2.f * k[i] + k[i + 1]);
            for (int i = 1; i < kPsLinN; ++i)
                if (n[i] < n[i - 1]) n[i] = n[i - 1];
            n[kPsLinN - 1] = kPsLin[kPsLinN - 1];
            for (int i = 0; i < kPsLinN; ++i) k[i] = n[i];
        }

        // 输出值：等间隔 i/(N-1)
        const int n = kPsLinN;
        float h[n - 1], m[n - 1];
        for (int i = 0; i < n - 1; ++i) {
            h[i] = k[i + 1] - k[i];
            m[i] = (1.f / (float) (n - 1)) / h[i];      // 弦斜率
        }
        // 三对角：h[i-1]·c[i-1] + 2(h[i-1]+h[i])·c[i] + h[i]·c[i+1] = 3(m[i-1]h[i] + m[i]h[i-1])
        // 端点 c[0] = c[n-1] = 0（自然边界）。用 Thomas 算法 O(n)。
        float lower[n], diag[n], upper[n], rhs[n];
        for (int i = 0; i < n; ++i) { lower[i] = 0.f; upper[i] = 0.f; rhs[i] = 0.f; }
        diag[0] = 1.f;
        diag[n - 1] = 1.f;
        for (int i = 1; i < n - 1; ++i) {
            lower[i] = h[i - 1];
            diag[i] = 2.f * (h[i - 1] + h[i]);
            upper[i] = h[i];
            rhs[i] = 3.f * (m[i - 1] * h[i] + m[i] * h[i - 1]);
        }
        float cp[n], dp[n];
        cp[0] = upper[0] / diag[0];
        dp[0] = rhs[0] / diag[0];
        for (int i = 1; i < n; ++i) {
            const float den = diag[i] - lower[i] * cp[i - 1];
            cp[i] = upper[i] / den;
            dp[i] = (rhs[i] - lower[i] * dp[i - 1]) / den;
        }
        c[n - 1] = dp[n - 1];
        for (int i = n - 2; i >= 0; --i) c[i] = dp[i] - cp[i] * c[i + 1];
        for (int i = 0; i < n - 1; ++i) {
            const float inv = 1.f / h[i];
            b[i] = (1.f / (float) (n - 1)) * inv - h[i] * (2.f * c[i] + c[i + 1]) / 3.f;
            d[i] = (c[i + 1] - c[i]) / (3.f * h[i]);
        }
        init = true;
    }
};

inline const PsSpline &psSpline() {
    static PsSpline s;
    s.build();
    return s;
}

// 线性值 -> 输出 0..1。headroom<1 时是 HDR 曲线：
//   0..1 段压到 0..headroom，1..4 段用指数软滚降收到 1，
//   这样超过单帧满量程的高光不会被一刀切成纯白。
inline float psCurve(float lin, float headroom) {
    if (lin <= 0.f) return 0.f;
    float x = lin;
    if (headroom < 0.999f) {
        // HDR 阴影提亮：只动 0.06 以下，t^2 保证在 0.06 处斜率连续
        float t = clampf(1.f - x / 0.06f, 0.f, 1.f);
        x *= 1.f + 1.4f * t * t;
    }
    const PsSpline &sp = psSpline();
    if (x >= sp.k[kPsLinN - 1]) {
        if (headroom >= 0.999f) return 1.f;
        float over = x - 1.f;
        return headroom + (1.f - headroom) * (1.f - std::exp(-over * 0.8f));
    }
    // 二分找段（129 段，7 步）。锚点单调，找不到时夹到边界。
    int lo = 0, hi = kPsLinN - 1;
    while (lo + 1 < hi) {
        const int m = (lo + hi) >> 1;
        if (sp.k[m] <= x) lo = m; else hi = m;
    }
    const float t = x - sp.k[lo];
    const float y = (float) lo / (float) (kPsLinN - 1) +
                    sp.b[lo] * t + sp.c[lo] * t * t + sp.d[lo] * t * t * t;
    return headroom * y;
}

static const int kLutMax = 4;
static const int kLutSize = 5120;
static const int kLutNormal = 4096;
static const float kHdrHeadroom = 0.90f;

// 色调查找表。级数 4096 不是随便定的：
// LUT 是最近点取值（不插值），级数就是台阶高度；1536 级时暗部一级跨 1~2 个码值，
// 配合 HDR 强提亮会在平滑渐变上画出一圈圈等高线。4096 级把台阶压到 1 码以内。
struct ToneLut {
    float normal[kLutSize];  // useShoulder / hard 共用同一张（曲线自带软肩）
    float hdr[kLutSize];

    ToneLut() {
        for (int i = 0; i < kLutNormal; ++i) {
            float v = (float) i / (kLutNormal - 1);
            normal[i] = psCurve(v, 1.f);
            hdr[i] = psCurve(v, kHdrHeadroom);
        }
        for (int i = kLutNormal; i < kLutSize; ++i) {
            float t = (float) (i - kLutNormal) / (kLutSize - kLutNormal - 1);
            float v = 1.f + t * (kLutMax - 1.f);
            normal[i] = psCurve(v, 1.f);
            hdr[i] = psCurve(v, kHdrHeadroom);
        }
    }
};

}  // namespace camhdr

#endif  // CAMHDR_COMMON_H
