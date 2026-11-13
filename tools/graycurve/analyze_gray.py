"""Fit the renderer curve that Photoshop applied to one of our DNGs.

Input : a DNG shot of gray_chart.png, plus the JPG Photoshop exported from that
        same DNG with "As Shot" / 原照设置 settings.
Output: per-patch table, and the code->code transfer curve PS used.

Why this works without knowing exposure or white balance: a monitor fed code C
emits luminance OETF^-1(C). The sensor records it linearly. PS renders that back
to code C'. If PS used plain sRGB encoding, C' == C exactly. So C'-C is the shape
of PS's render curve, with all gain terms cancelling out.
"""
import argparse
import json
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import dngio


def oetf(lin):
    lin = np.clip(lin, 0, 1)
    return np.where(lin <= 0.0031308, 12.92 * lin,
                    1.055 * np.power(np.maximum(lin, 1e-9), 1 / 2.4) - 0.055)


def our_tone(lin, shoulder=0.88):
    """Mirror of RawToBitmap.toneCurve() with SHOULDER=0.88, CONTRAST=0."""
    lin = np.asarray(lin, dtype=np.float64)
    v = np.where(lin <= shoulder, lin,
                 shoulder + (1 - shoulder) * (1 - np.exp(-(lin - shoulder) / (1 - shoulder))))
    return oetf(v)


def find_bbox(lum, expect_ratio=16 / 9, min_jump_frac=0.30, verbose=True):
    """Locate the chart rectangle from 1-D brightness profiles.

    The mid-grey frame makes the outer edge the strongest gradient in the image,
    an order of magnitude above any patch-to-patch step, so taking the first and
    last strong gradients is stable even when the room is not perfectly black.
    """
    h, w = lum.shape
    cp = lum.mean(axis=0)
    rp = lum.mean(axis=1)

    def first_last(prof):
        d = np.abs(np.diff(prof))
        mx = d.max() if d.size else 0
        if mx <= 0:
            return 0, len(prof) - 1, 0.0
        idx = np.where(d > mx * min_jump_frac)[0]
        if idx.size == 0:
            return 0, len(prof) - 1, mx
        return idx[0] + 1, idx[-1], mx

    x0, x1, jx = first_last(cp)
    y0, y1, jy = first_last(rp)
    if verbose:
        print('  bbox x %d..%d  y %d..%d   (jump %.1f / %.1f)' % (x0, x1, y0, y1, jx, jy))
    bw, bh = x1 - x0, y1 - y0
    if bw < w * 0.2 or bh < h * 0.2:
        print('  WARN: chart detected too small, check framing')
    got = bw / max(1, bh)
    if abs(got - expect_ratio) > 0.45:
        print('  WARN: aspect %.2f far from %.2f, detection may be off' % (got, expect_ratio))
    return x0, y0, x1, y1


def save_boxes(lum_path_or_arr, bbox, out_png):
    Image.fromarray(lum_path_or_arr).convert('L').save(out_png)


def sample_blocks(arr, bbox, cols, rows, shrink=0.55):
    """Median of the central shrink fraction of each cell. Returns rows x cols."""
    x0, y0, x1, y1 = bbox
    cw, ch = (x1 - x0) / cols, (y1 - y0) / rows
    out = np.zeros((rows, cols), dtype=np.float64)
    for r in range(rows):
        for c in range(cols):
            ax0 = int(x0 + c * cw + cw * (1 - shrink) / 2)
            ax1 = int(x0 + (c + 1) * cw - cw * (1 - shrink) / 2)
            ay0 = int(y0 + r * ch + ch * (1 - shrink) / 2)
            ay1 = int(y0 + (r + 1) * ch - ch * (1 - shrink) / 2)
            patch = arr[ay0:ay1, ax0:ax1]
            out[r, c] = np.median(patch)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', required=True)
    ap.add_argument('--jpg', required=True, help='Photoshop / 原照设置 export of the same DNG')
    ap.add_argument('--cols', type=int, default=6)
    ap.add_argument('--rows', type=int, default=4)
    ap.add_argument('--out', default='curve_result.json')
    args = ap.parse_args()

    print('[1/4] reading DNG')
    img = dngio.read_dng(args.dng)
    print('      %dx%d  cfa=%s  black=%s  white=%d  orient=%d  baseEV=%.3f'
          % (img['width'], img['height'], img['cfa'], img['black'], img['white'],
             img['orientation'], img['baseline_ev']))

    lum = dngio.luminance_thumb(img, target_w=320)
    lo, hi = np.percentile(lum, 1), np.percentile(lum, 99.5)
    lum8 = np.clip((lum - lo) / max(1e-6, hi - lo) * 255, 0, 255).astype(np.uint8)
    print('[2/4] locating chart in RAW')
    bbox_raw = find_bbox(lum8.astype(np.float64))
    save_boxes(lum8, bbox_raw, os.path.splitext(args.out)[0] + '_raw_detect.png')

    sx = img['width'] / lum8.shape[1]
    sy = img['height'] / lum8.shape[0]
    bbox_raw_full = (int(bbox_raw[0] * sx), int(bbox_raw[1] * sy),
                     int(bbox_raw[2] * sx), int(bbox_raw[3] * sy))

    g1, g2 = dngio.green_planes(img)
    blk = float(np.mean(img['black'][1:3])) if img['black'] else 64.0
    wl = float(img['white'])
    scale = wl - blk

    # Photoshop executes the DNG's GainMap opcodes, so we must too, otherwise the
    # corner patches are measured ~4x darker than what PS actually sees.
    gmaps = dngio.read_gainmaps(args.dng)
    g1f = np.clip(g1.astype(np.float32) - blk, 0, None)
    g2f = np.clip(g2.astype(np.float32) - blk, 0, None)
    if gmaps:
        keys = sorted(gmaps.keys())
        # green sits on the anti-diagonal: (0,1) and (1,0) in TL,TR,BL,BR terms
        k1 = (0, 1) if (0, 1) in gmaps else (keys[0] if keys else None)
        k2 = (1, 0) if (1, 0) in gmaps else k1
        if k1 is not None:
            mv, mh, grid = gmaps[k1]
            g1f *= dngio.bilinear_gain(grid, mv, mh, g1.shape[0], g1.shape[1])
        if k2 is not None:
            mv, mh, grid = gmaps[k2]
            g2f *= dngio.bilinear_gain(grid, mv, mh, g2.shape[0], g2.shape[1])
        gm = gmaps[k1][2]
        print('      GainMap applied: %d phases, corner/centre gain %.2f'
              % (len(gmaps), float(gm.max()) / max(1e-6, float(gm.min()))))
    else:
        print('      WARN: no GainMap in this DNG; corner patches will read low vs PS')

    def raw_patch_median(x0, y0, x1, y1):
        gy0, gy1 = int(y0 / 2), int(y1 / 2)
        gx0, gx1 = int(x0 / 2), int(x1 / 2)
        a = g1f[gy0:gy1, gx0:gx1].ravel()
        b = g2f[gy0:gy1, gx0:gx1].ravel()
        both = np.concatenate([a, b]).astype(np.float64)
        return np.median(both)

    bx0, by0, bx1, by1 = bbox_raw_full
    cw, ch = (bx1 - bx0) / args.cols, (by1 - by0) / args.rows
    raw_lin = np.zeros((args.rows, args.cols))
    shrink = 0.55
    for r in range(args.rows):
        for c in range(args.cols):
            ax0 = int(bx0 + c * cw + cw * (1 - shrink) / 2)
            ax1 = int(bx0 + (c + 1) * cw - cw * (1 - shrink) / 2)
            ay0 = int(by0 + r * ch + ch * (1 - shrink) / 2)
            ay1 = int(by0 + (r + 1) * ch - ch * (1 - shrink) / 2)
            v = raw_patch_median(ax0, ay0, ax1, ay1)
            raw_lin[r, c] = max(0.0, (v - blk) / scale)

    print('[3/4] reading PS JPG')
    pil = Image.open(args.jpg)
    if pil.mode != 'RGB':
        pil = pil.convert('RGB')
    jpg = np.asarray(pil, dtype=np.float64)
    jl = jpg.mean(axis=2)
    if pil.size[0] < 200:
        print('  WARN: jpg is tiny, geometry may be unreliable')
    print('      %dx%d' % pil.size)
    bbox_jpg = find_bbox(jl)
    save_boxes(jl.astype(np.uint8), bbox_jpg, os.path.splitext(args.out)[0] + '_jpg_detect.png')
    jpg_code = sample_blocks(jl, bbox_jpg, args.cols, args.rows, shrink)

    print('[4/4] comparing')
    codes = np.array([round(i * 255 / (args.cols * args.rows - 1))
                      for i in range(args.cols * args.rows)], dtype=np.float64)
    codes = codes.reshape(args.rows, args.cols)

    rows_out = []
    print()
    print('  lin(RAW)   screen_code   sRGB_ideal   ours_now    PS_out   PS-sRGB   PS-ours')
    for r in range(args.rows):
        for c in range(args.cols):
            lin = raw_lin[r, c]
            ideal = float(oetf(lin)) * 255.0
            ours = float(our_tone(lin)) * 255.0
            ps = float(jpg_code[r, c])
            rows_out.append({
                'lin': round(lin, 5),
                'screen_code': float(codes[r, c]),
                'srgb_ideal': round(ideal, 2),
                'ours': round(ours, 2),
                'ps': round(ps, 2),
                'ps_minus_srgb': round(ps - ideal, 2),
                'ps_minus_ours': round(ps - ours, 2),
            })
            print(' %9.5f  %11.0f  %12.1f  %10.1f  %9.1f  %8.1f  %8.1f'
                  % (lin, codes[r, c], ideal, ours, ps, ps - ideal, ps - ours))

    order = np.argsort([x['lin'] for x in rows_out])
    xs = np.array([rows_out[i]['lin'] for i in order])
    ys_ps = np.array([rows_out[i]['ps'] for i in order])
    ys_ours = np.array([rows_out[i]['ours'] for i in order])

    hi = float(xs.max())
    lo = float(xs.min())
    d_srgb = np.array([x['ps_minus_srgb'] for x in rows_out])
    d_ours = np.array([x['ps_minus_ours'] for x in rows_out])

    print()
    print('--- coverage / sanity ---')
    print('  linear range covered : %.3f .. %.3f' % (lo, hi))
    if hi < 0.80:
        print('  WARN: underexposed. Only %.0f%% of range. Raise exposure / screen brightness,' % (hi * 100))
        print('        otherwise the upper curve is extrapolated and the fit will be soft.')
    if hi > 0.995:
        print('  WARN: top patch clipped. Lower exposure one step, or the shoulder is unmeasurable.')
    mono = np.all(np.diff(ys_ps) >= -2.0)
    print('  monotonic            : %s' % ('OK' if mono else 'FAILED <- check *_detect.png'))

    print()
    print('--- verdict ---')
    m_srgb = float(np.abs(d_srgb).mean())
    m_ours = float(np.abs(d_ours).mean())
    print('  mean |PS - sRGB encoding| : %.2f code' % m_srgb)
    print('  mean |PS - ours|          : %.2f code' % m_ours)
    if m_srgb < 2.5:
        print('  => PS adds essentially no curve. Our tone curve is NOT the gap;')
        print('     the difference lives in colour rendering / denoise / sharpening.')
    elif m_srgb > 4.0:
        print('  => PS is applying a real render curve. Fitting it is worthwhile.')
        print('     Deviation by zone: shadows %.1f, mids %.1f, highs %.1f'
              % (float(d_srgb[xs < 0.18].mean() if np.any(xs < 0.18) else 0),
                 float(d_srgb[(xs >= 0.18) & (xs < 0.5)].mean() if np.any((xs >= 0.18) & (xs < 0.5)) else 0),
                 float(d_srgb[xs >= 0.5].mean() if np.any(xs >= 0.5) else 0)))
    else:
        print('  => small but non-zero. Probably the shoulder only; worth a light fit.')

    payload = {
        'pairs': rows_out,
        'monotonic': bool(mono),
        'coverage': [lo, hi],
        'mean_abs_ps_vs_srgb': m_srgb,
        'mean_abs_ps_vs_ours': m_ours,
        'bbox_raw': bbox_raw_full,
        'bbox_jpg': list(bbox_jpg),
    }
    with open(args.out, 'w') as f:
        json.dump(payload, f, indent=1)
    print()
    print('wrote', args.out)

    plot_png = os.path.splitext(args.out)[0] + '_plot.png'
    try:
        plot_curves(xs, ys_ps, ys_ours, plot_png)
        print('wrote', plot_png)
    except Exception as e:
        print('plot skipped:', e)


def plot_curves(xs, ys_ps, ys_ours, out_png, w=940, h=640):
    """Plain PIL plot: no matplotlib dependency."""
    from PIL import ImageDraw
    img = Image.new('RGB', (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    L, R, T, B = 78, w - 34, 40, h - 74

    def mx(x):
        return L + float(x) * (R - L)

    def my(y):
        return B - (float(y) / 255.0) * (B - T)

    for i in range(0, 256, 32):
        y = my(i)
        d.line([(L, y), (R, y)], fill=(236, 236, 236))
        d.text((L - 32, y - 5), '%d' % i, fill=(130, 130, 130))
    for xv in [0, 0.2, 0.4, 0.6, 0.8, 1.0]:
        x = mx(xv)
        d.line([(x, T), (x, B)], fill=(236, 236, 236))
        d.text((x - 12, B + 10), '%.1f' % xv, fill=(130, 130, 130))

    d.line([(L, T), (L, B)], fill=(90, 90, 90))
    d.line([(L, B), (R, B)], fill=(90, 90, 90))
    d.text((L, T - 24), 'output code (PS export)', fill=(70, 70, 70))
    d.text((R - 150, B + 34), 'linear value in RAW', fill=(70, 70, 70))

    xx = np.linspace(0, 1, 260)
    pts = [(mx(x), my(float(oetf(x)) * 255)) for x in xx]
    d.line(pts, fill=(160, 160, 160), width=2)
    for k in range(0, len(pts), 6):
        d.rectangle([pts[k][0] - 1, pts[k][1] - 1, pts[k][0] + 1, pts[k][1] + 1],
                    fill=(160, 160, 160))

    pts = [(mx(x), my(float(our_tone(x)) * 255)) for x in xx]
    d.line(pts, fill=(29, 158, 117), width=3)

    pts = [(mx(x), my(y)) for x, y in zip(xs, ys_ps)]
    d.line(pts, fill=(216, 90, 48), width=3)
    for x, y in zip(xs, ys_ps):
        d.ellipse([mx(x) - 4, my(y) - 4, mx(x) + 4, my(y) + 4], fill=(216, 90, 48))

    d.line([(L, h - 46), (L + 26, h - 46)], fill=(160, 160, 160), width=2)
    d.text((L + 34, h - 53), 'sRGB encoding only', fill=(110, 110, 110))
    d.line([(L + 220, h - 46), (L + 246, h - 46)], fill=(29, 158, 117), width=3)
    d.text((L + 254, h - 53), 'ours now', fill=(15, 110, 86))
    d.line([(L + 350, h - 46), (L + 376, h - 46)], fill=(216, 90, 48), width=3)
    d.text((L + 384, h - 53), 'Photoshop', fill=(153, 60, 29))

    img.save(out_png)


if __name__ == '__main__':
    main()
