"""复刻 hdr-plus 参考实现的 tone_map（暴露融合 + 拉普拉斯金字塔，3 次迭代）。

目的：在改 C++ 之前先确认
  1) 这条曲线是不是单调的（之前用权重混合出过亮暗反转）；
  2) comp / gain 取什么值能把"欠曝 K 倍"的数据恢复成正确曝光，同时压住高光；
  3) 曲线形状和现在的全局肩部差多少。
"""
import numpy as np


def gamma(x):
    # sRGB OETF，输入 0..1 线性
    return np.where(x <= 0.0031308, 12.92 * x, 1.055 * np.power(np.maximum(x, 1e-9), 1 / 2.4) - 0.055)


def gamma_inv(y):
    return np.where(y <= 0.04045, y / 12.92, np.power(np.maximum((y + 0.055) / 1.055, 1e-9), 2.4))


def gauss(img, passes=1):
    """7×7 高斯（σ≈1.5），与参考实现的 gauss_7x7 同量级。"""
    k = np.array([1, 6, 15, 20, 15, 6, 1], dtype=np.float64)
    k = k / k.sum()
    out = img.astype(np.float64)
    for _ in range(passes):
        p = np.pad(out, ((0, 0), (3, 3)), mode="edge")
        out = sum(k[i] * p[:, i:i + out.shape[1]] for i in range(7))
        p = np.pad(out, ((3, 3), (0, 0)), mode="edge")
        out = sum(k[i] * p[i:i + out.shape[0], :] for i in range(7))
    return out


def dist(v):
    """参考实现的亮度分布函数：偏好中间调。"""
    return np.exp(-12.5 * (v - 0.5) ** 2)


def combine(a, b):
    """两幅灰度（显示域 0..1）用拉普拉斯金字塔按亮度权重融合。"""
    w1, w2 = dist(a), dist(b)
    m1 = w1 / (w1 + w2 + 1e-9)
    m2 = 1.0 - m1

    ga, gb = gauss(a), gauss(b)
    acc = (a - ga) * m1 + (b - gb) * m2          # 细节层用原始权重

    ga2, gb2 = gauss(ga), gauss(gb)
    acc += ga2 * gauss(m1) + gb2 * gauss(m2)      # 低频层用模糊后的权重

    return np.clip(acc, 0.0, 1.0)


def tone_map(x, comp, gain, passes=3):
    dark = np.clip(x, 0.0, 1.0).copy()
    comp_const = 1.0 + comp / passes
    gain_const = 1.0 + gain / passes
    comp_slope = (comp - comp_const) / (passes - 1)
    gain_slope = (gain - gain_const) / (passes - 1)
    for p in range(passes):
        nc = p * comp_slope + comp_const
        ng = p * gain_slope + gain_const
        bright = np.clip(nc * dark, 0.0, 1.0)
        fused = combine(gamma(dark), gamma(bright))
        dark = np.clip(ng * gamma_inv(fused), 0.0, 1.0)
    return dark


def curve_for(comp, gain, K, n=64):
    """在一条对数分布的斜坡上取曲线：x 是"欠曝 K 倍后的线性值"。"""
    xs = np.concatenate([[0.0], np.logspace(-4, 0, n)])
    ys = tone_map(xs.reshape(1, -1), comp, gain)[0]
    return xs, ys


if __name__ == "__main__":
    K = 2.0
    print("K=%.0f  目标：中灰(0.18/K=%.3f) → 线性 0.18" % (K, 0.18 / K))
    print()
    print("comp  gain | 中灰输出(线性) | 0.9Kx 输出 | 单调? | 0.5处增益")
    for comp in (1.5, 2.0, 3.0, 4.0):
        for gain in (1.0, 1.5, 2.0, 3.0):
            xs, ys = curve_for(comp, gain, K)
            mid = np.interp(0.18 / K, xs, ys)
            hi = np.interp(0.9, xs, ys)
            mono = np.all(np.diff(ys) >= -1e-9)
            g05 = np.interp(0.5, xs, ys) / 0.5
            print("%.1f  %.1f  | %12.4f | %10.4f | %5s | %6.2f"
                  % (comp, gain, mid, hi, "是" if mono else "否", g05))
