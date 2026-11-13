"""Merge every measured RAW->PS pair into one curve, then fit it.

Outputs both a fit (for readable code) and a 2048-entry LUT (for exactness).
"""
import glob
import json
import os

import numpy as np


def oetf(lin):
    lin = np.clip(lin, 0, 1)
    return np.where(lin <= 0.0031308, 12.92 * lin,
                    1.055 * np.power(np.maximum(lin, 1e-9), 1 / 2.4) - 0.055)


def collect(pattern='p_*.json'):
    xs, ys, ws = [], [], []
    for f in sorted(glob.glob(pattern)):
        d = json.load(open(f))
        n = np.array(d['count'], dtype=np.float64)
        xs.append(np.array(d['lin']))
        ys.append(np.array(d['ps']))
        ws.append(n)
        print('  %-28s %d bins  lin %.3f..%.3f' % (os.path.basename(f), len(d['lin']),
                                                   min(d['lin']), max(d['lin'])))
    x = np.concatenate(xs); y = np.concatenate(ys); w = np.concatenate(ws)
    grid = np.linspace(0.002, 0.66, 200)
    out = []
    for g in grid:
        m = np.abs(x - g) < 0.012
        if m.sum() < 2:
            continue
        out.append((g, np.average(y[m], weights=w[m])))
    gx = np.array([p[0] for p in out])
    gy = np.array([p[1] for p in out])
    # enforce monotonicity (noise in dark bins can wobble by a code or two)
    gy = np.maximum.accumulate(gy)
    return gx, gy


def fit_log(gx, gy):
    """y = A + B * ln(x + C); scan C, solve A,B by weighted least squares."""
    best = None
    for C in np.arange(0.0, 0.30, 0.002):
        t = np.log(gx + C)
        M = np.vstack([np.ones_like(t), t]).T
        coef, *_ = np.linalg.lstsq(M, gy, rcond=None)
        pred = M @ coef
        err = np.abs(pred - gy).max()
        if best is None or err < best[0]:
            best = (err, C, coef[0], coef[1])
    return best


def main():
    gx, gy = collect()
    print('merged %d points, lin %.3f..%.3f, out %.1f..%.1f'
          % (len(gx), gx.min(), gx.max(), gy.min(), gy.max()))

    # Below lin 0.01 the measurement is not trustworthy: the screen's black patch
    # leaks light, the sensor has a noise floor, and different scenes gave 21.9
    # and 10.0 for the same bin. Keeping those points flattened the curve into a
    # plateau and lifted the whole shadow range by ~10 codes. Drop them and anchor
    # the curve at the origin instead, which is the physically correct behaviour.
    keep = gx >= 0.010
    dropped = int((~keep).sum())
    gx, gy = gx[keep], gy[keep]
    gx = np.concatenate([[0.0], gx])
    gy = np.concatenate([[0.0], gy])
    print('dropped %d unreliable shadow points, curve now anchored at (0,0)' % dropped)

    err, C, A, B = fit_log(gx[1:], gy[1:])
    print('log fit (reference only): y = %.3f + %.3f*ln(x + %.3f)  max err %.2f'
          % (A, B, C, err))

    xx = np.linspace(0.0, 1.0, 2048)

    # Main body: interpolate the MEASURED points. The log fit is only reported
    # for reference — it is off by up to 22 codes and must not build the table.
    body = np.interp(xx, gx, gy)
    body = np.where(xx < gx[0], gy[0] * xx / gx[0], body)
    knee = float(gx.max())
    knee_y = float(gy[-1])
    t = np.clip((xx - knee) / (1.0 - knee), 0, 1)
    tail = knee_y + (255 - knee_y) * (1 - (1 - t) ** 1.6)
    lut = np.where(xx <= knee, body, tail)
    lut = np.maximum.accumulate(lut)
    lut = np.clip(lut, 0, 255)
    print('curve is measured up to lin %.3f (code %.1f); beyond that it is a' % (knee, knee_y))
    print('  smooth saturating tail, because exposure never reached full scale')

    print('LUT: 0 -> %.1f, 0.18 -> %.1f, 0.5 -> %.1f, 1.0 -> %.1f'
          % (lut[0], np.interp(0.18, xx, lut), np.interp(0.5, xx, lut), lut[-1]))
    print('sRGB for comparison: 0 -> 0, 0.18 -> %.1f, 0.5 -> %.1f, 1.0 -> 255'
          % (oetf(0.18) * 255, oetf(0.5) * 255))

    json.dump({'A': float(A), 'B': float(B), 'C': float(C), 'knee': float(knee),
               'knee_y': knee_y,
               'lut': [round(float(v), 3) for v in lut],
               'lin_max': float(xx[-1])},
              open('curve_merged.json', 'w'))

    with open('lut_kotlin.txt', 'w') as f:
        f.write('LUT_SIZE = %d\n' % len(lut))
        f.write('LUT values (0..1 linear -> 0..255 output):\n')
        for i in range(0, len(lut), 10):
            f.write('    ' + ', '.join('%.2f' % v for v in lut[i:i + 10]) + ',\n')

    # 256-entry table, sqrt re-sampling so the shadows (where the curve bends
    # hardest) get as many entries as the highlights. 8 bits of index is enough:
    # the residual interpolation error stays under half a code everywhere.
    N = 256
    prof = np.sqrt(np.linspace(0.0, 1.0, N))
    lut256 = np.interp(prof, xx, lut)
    lut256 = np.maximum.accumulate(np.clip(lut256, 0, 255))
    lut256[0] = 0.0
    json.dump([round(float(v), 2) for v in lut256], open('lut256.json', 'w'))
    with open('lut256_kotlin.txt', 'w') as f:
        f.write('private val PS_CURVE = floatArrayOf(\n')
        for i in range(0, N, 8):
            f.write('    ' + ', '.join('%.1ff' % v for v in lut256[i:i + 8]) + ',\n')
        f.write(')\n')
    print('wrote lut256.json and lut256_kotlin.txt (%d entries, sqrt-spaced)' % N)
    print('  spot check: lin 0.002->%.1f  0.05->%.1f  0.18->%.1f  0.5->%.1f  0.9->%.1f'
          % tuple(np.interp([0.002, 0.05, 0.18, 0.5, 0.9], prof, lut256)))
    # 129 anchors uniform in OUTPUT code (2 codes apart). Storing the inverse —
    # "for output code c, which linear value produced it" — keeps the table tiny
    # AND accurate, including the shadow end where the curve bends hardest.
    codes_anchor = np.linspace(0, 255, 129)
    lins_anchor = np.interp(codes_anchor, lut, xx)
    lins_anchor = np.maximum.accumulate(lins_anchor)
    lins_anchor[0] = 0.0
    with open('lins129_kotlin.txt', 'w') as f:
        f.write('private val PS_LIN = floatArrayOf(\n')
        for i in range(0, 129, 6):
            f.write('    ' + ', '.join('%.6ff' % v for v in lins_anchor[i:i + 6]) + ',\n')
        f.write(')\n')
    print('wrote lins129_kotlin.txt (129 anchors, uniform in output code)')
    print('  lin at code 0/32/64/128/192/255: ' +
          '  '.join('%.4f' % v for v in lins_anchor[[0, 16, 32, 64, 96, 128]]))
    print('wrote curve_merged.json and lut_kotlin.txt')
    return xx, lut, gx, gy, A, B, C


if __name__ == '__main__':
    main()
