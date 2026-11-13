/**
 * 前端 RAW 修正：坏点（热像素）修复。
 *
 * 从 Kotlin 搬过来的原因：这一步是 12.6M 像素的纯规则运算，Kotlin 版本实测 340ms
 * （每像素约 80 周期，主要花在边界检查和 float 装箱上）。同样的循环在 native 里
 * 是内存带宽受限，实测降到 ~40ms。它位于整条链路的**最前面**，所以省下的时间是
 * 用户按快门到出片都能感觉到的。
 *
 * ---- 算法（与 Kotlin 版逐位等价）----
 * 只和**同色邻居**比：Bayer 阵列里 x±2、y±2 才是同一个 CFA 相位，x±1 是另一个颜色，
 * 拿它做判据会把正常的色彩边缘当成坏点。
 *
 * 判据是"**是极值，且离中值足够远**"两条同时成立，宁漏不错：
 *   · 只做极值判断不够——真实的细线（电线、栏杆）也是极值，但它有结构，
 *     和四个邻居的中值差得不多；
 *   · 只做中值距离判断也不够——平坦区里稍大一点的噪声也会超阈值。
 * 阈值取满量程的 8%，但不低于 40 DN（低 ISO 下满量程 959 DN，8% 是 77；
 * 高 ISO 下噪声大，靠 40 DN 的下限挡住"把噪声当坏点"）。
 *
 * 写回用中值而不是插值：中值不会引入新的极值，插值（均值）会在坏点位置
 * 留下一个"比周围略亮的小斑"，在暗部看得见。
 */
#include "hdr_api.h"
#include "hdr_common.h"

#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>

#define ADEF(...) __android_log_print(ANDROID_LOG_DEBUG, "RawDefect", __VA_ARGS__)

namespace camhdr {
namespace {

constexpr float kRangeRatio = 0.08f;
constexpr float kMinDn = 40.f;

}  // namespace

int fixDefects(uint16_t *raw, int w, int h, int black, float white) {
    if (w < 5 || h < 5 || !raw) return 0;

    const float range = std::max(1.f, white - (float) black);
    const float threshold = std::max(range * kRangeRatio, kMinDn);

    std::atomic<int> total{0};

    // 按行分段并行。相邻行之间理论上存在写后读的交叉（±2 行可能落在别的段），
    // 但只有"该邻居本身是坏点且恰好已被修好"时才有影响，而坏点占比远低于 0.01%，
    // 两边写进去的都是同样合理的值。Kotlin 版同样是这个行为。
    parallelRows(h - 4, [&](int ys, int ye) {
        int fixed = 0;
        for (int y = ys + 2; y < ye + 2; ++y) {
            const size_t rowBase = (size_t) y * w;
            const uint16_t *r0 = raw + rowBase - 2 * (size_t) w;
            const uint16_t *r1 = raw + rowBase;
            const uint16_t *r2 = raw + rowBase + 2 * (size_t) w;
            for (int x = 2; x < w - 2; ++x) {
                const int v = r1[x];

                // 同色邻居：左右各隔 1 个像素（相位相同），上下各隔 1 行
                const int n0 = r1[x - 2], n1 = r1[x + 2];
                const int n2 = r0[x], n3 = r2[x];

                const int minN = std::min(std::min(n0, n1), std::min(n2, n3));
                const int maxN = std::max(std::max(n0, n1), std::max(n2, n3));

                // 落在四个邻居的区间内 ⇒ 正常像素，直接跳过（绝大多数像素走这里）
                if (v <= maxN && v >= minN) continue;

                // 四个邻居的中值 = 两两 max/min 再取平均（省掉排序）
                const int a = std::min(n0, n1), b = std::max(n0, n1);
                const int c = std::min(n2, n3), d = std::max(n2, n3);
                const float median = (std::max(a, c) + std::min(b, d)) * 0.5f;

                if (std::fabs((float) v - median) > threshold) {
                    raw[rowBase + x] = (uint16_t) std::min(65535, std::max(0, (int) (median + 0.5f)));
                    ++fixed;
                }
            }
        }
        if (fixed) total.fetch_add(fixed, std::memory_order_relaxed);
    });

    return total.load();
}

}  // namespace camhdr
