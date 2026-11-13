"""Recover Photoshop's render curve by pairing every pixel of the DNG with the JPG.

No chart detection, no grid, no rotation bookkeeping. If the two images show the
same scene, then for each pixel x = linear value in the RAW and y = output code
in the JPG. Plotting y against x IS the curve PS applied, with tens of thousands
of samples instead of 24.
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
    lin = np.asarray(lin, dtype=np.float64)
    v = np.where(lin <= shoulder, lin,
                 shoulder + (1 - shoulder) * (1 - np.exp(-(lin - shoulder) / (1 - shoulder))))
    return oetf(v)


def load_raw_linear(dng, target_w=760, rotate=None):
    img = dngio.read_dng(dng)
    g1, g2 = dngio.green_planes(img)
    gmaps = dngio.read_gainmaps(dng)
    blk = float(np.mean(img['black'][1:3])) if img['black'] else 64.0
    wl = float(img['white'])

    f1 = np.clip(g1.astype(np.float32) - blk, 0, None)
    f2 = np.clip(g2.astype(np.float32) - blk, 0, None)
    applied = 0
    if gmaps:
        for key, arr in (((0, 1), f1), ((1, 0), f2)):
            if key in gmaps:
                mv, mh, grid = gmaps[key]
                arr *= dngio.bilinear_gain(grid, mv, mh, arr.shape[0], arr.shape[1])
                applied += 1

    h = min(f1.shape[0], f2.shape[0])
    w = min(f1.shape[1], f2.shape[1])
    lin = (f1[:h, :w] + f2[:h, :w]) * 0.5 / max(1e-6, wl - blk)
    lin = np.clip(lin, 0, 1)

    k = {1: 0, 6: -1, 3: 2, 8: 1}.get(rotate if rotate is not None else img['orientation'], 0)
    if k:
        lin = np.rot90(lin, k=k)

    hh, ww = lin.shape
    th = max(1, int(round(target_w * hh / ww)))
    small = np.asarray(Image.fromarray(lin.astype(np.float32), mode='F')
                       .resize((target_w, th), Image.BILINEAR), dtype=np.float32)
    return small, applied, img['orientation']


def load_jpg_gray(jpg, target_w=760):
    im = Image.open(jpg).convert('L')
    w, h = im.size
    th = max(1, int(round(target_w * h / w)))
    a = np.asarray(im.resize((target_w, th), Image.BILINEAR), dtype=np.float32)
    return a


def best_shift(x, y, max_shift=6):
    """Tiny translational search; PS crops a few pixels so exact alignment drifts."""
    best, bs = None, (0, 0)
    for dy in range(-max_shift, max_shift + 1, 2):
        for dx in range(-max_shift, max_shift + 1, 2):
            h, w = x.shape
            ys0, ys1 = max(0, dy), h + min(0, dy)
            xs0, xs1 = max(0, dx), w + min(0, dx)
            a = x[ys0:ys1, xs0:xs1]
            b = y[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx]
            m = (a > 0.02) & (a < 0.95)
            if m.sum() < 1000:
                continue
            r = np.median(np.abs(oetf(a[m]) * 255 - b[m]))
            if best is None or r < best:
                best, bs = r, (dy, dx)
    return bs, best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--dng', required=True)
    ap.add_argument('--jpg', required=True)
    ap.add_argument('--out', default='pair.json')
    ap.add_argument('--width', type=int, default=760)
    ap.add_argument('--plot', default=None)
    args = ap.parse_args()

    print('[1/3] RAW')
    lin, nap, orient = load_raw_linear(args.dng, args.width)
    print('      shape %s  gainmaps applied %d  orientation %d' % (lin.shape, nap, orient))

    print('[2/3] JPG')
    code = load_jpg_gray(args.jpg, args.width)
    print('      shape %s' % (code.shape,))
    if code.shape != lin.shape:
        h = min(code.shape[0], lin.shape[0])
        w = min(code.shape[1], lin.shape[1])
        lin, code = lin[:h, :w], code[:h, :w]
        print('      cropped to common %s' % (lin.shape,))

    (dy, dx), res = best_shift(lin, code)
    print('      best shift dy=%d dx=%d  median residual %.2f code' % (dy, dx, res))
    h, w = lin.shape
    ys0, ys1 = max(0, dy), h + min(0, dy)
    xs0, xs1 = max(0, dx), w + min(0, dx)
    a = lin[ys0:ys1, xs0:xs1].ravel()
    b = code[ys0 - dy:ys1 - dy, xs0 - dx:xs1 - dx].ravel()

    print('[3/3] pairing %d pixels' % a.size)
    nb = 128
    edges = np.linspace(0, 1, nb + 1)
    idx = np.clip(np.digitize(a, edges) - 1, 0, nb - 1)
    xs, med, p10, p90, cnt = [], [], [], [], []
    for i in range(nb):
        m = idx == i
        n = int(m.sum())
        if n < 8:
            continue
        xs.append((edges[i] + edges[i + 1]) / 2)
        med.append(float(np.median(b[m])))
        p10.append(float(np.percentile(b[m], 10)))
        p90.append(float(np.percentile(b[m], 90)))
        cnt.append(n)
    xs = np.array(xs); med = np.array(med)

    ideal = oetf(xs) * 255
    ours = our_tone(xs) * 255
    dev = med - ideal

    print()
    print('   lin     n      PS    sRGB   ours   PS-sRGB')
    for i in range(0, len(xs), max(1, len(xs) // 16)):
        print(' %.3f %6d %6.1f %6.1f %6.1f  %+6.1f'
              % (xs[i], cnt[i], med[i], ideal[i], ours[i], dev[i]))

    valid = xs > 0.002
    print()
    print('  mean |PS - sRGB| over measurable range : %.2f code' % np.abs(dev[valid]).mean())
    print('  mean |PS - ours|                        : %.2f code'
          % np.abs(med[valid] - ours[valid]).mean())
    print('  linear coverage                         : %.3f .. %.3f' % (xs.min(), xs.max()))

    payload = {'lin': [float(v) for v in xs], 'ps': [float(v) for v in med],
               'p10': [float(v) for v in p10], 'p90': [float(v) for v in p90],
               'count': [int(v) for v in cnt],
               'ideal': [float(v) for v in ideal], 'ours': [float(v) for v in ours],
               'shift': [int(dy), int(dx)], 'residual': float(res)}
    with open(args.out, 'w') as f:
        json.dump(payload, f)

    plot_png = args.plot or os.path.splitext(args.out)[0] + '_pair.png'
    plot(xs, med, p10, p90, plot_png)
    print('wrote', args.out, 'and', plot_png)


def plot(xs, med, p10, p90, out, w=900, h=640):
    from PIL import ImageDraw
    img = Image.new('RGB', (w, h), (255, 255, 255))
    d = ImageDraw.Draw(img)
    L, R, T, B = 74, w - 30, 36, h - 70

    def mx(x):
        return L + float(x) * (R - L)

    def my(y):
        return B - (float(y) / 255.0) * (B - T)

    for i in range(0, 256, 32):
        y = my(i)
        d.line([(L, y), (R, y)], fill=(234, 234, 234))
        d.text((L - 30, y - 5), '%d' % i, fill=(130, 130, 130))
    for xv in [0, .2, .4, .6, .8, 1.0]:
        d.line([(mx(xv), T), (mx(xv), B)], fill=(234, 234, 234))
        d.text((mx(xv) - 12, B + 8), '%.1f' % xv, fill=(130, 130, 130))
    d.line([(L, T), (L, B)], fill=(90, 90, 90))
    d.line([(L, B), (R, B)], fill=(90, 90, 90))
    d.text((L, T - 22), 'output code (Photoshop)', fill=(70, 70, 70))
    d.text((R - 160, B + 30), 'linear value in RAW', fill=(70, 70, 70))

    for lo, hi in zip(p10, p90):
        pass
    if len(xs) > 1:
        top = [(mx(x), my(hi)) for x, hi in zip(xs, p90)]
        bot = [(mx(x), my(lo)) for x, lo in zip(xs, p10)]
        d.polygon(top + bot[::-1], fill=(252, 232, 226))

    xx = np.linspace(0, 1, 240)
    d.line([(mx(x), my(float(oetf(x)) * 255)) for x in xx], fill=(150, 150, 150), width=2)
    d.line([(mx(x), my(float(our_tone(x)) * 255)) for x in xx], fill=(29, 158, 117), width=3)
    d.line([(mx(x), my(y)) for x, y in zip(xs, med)], fill=(216, 90, 48), width=3)

    d.line([(L, h - 42), (L + 24, h - 42)], fill=(150, 150, 150), width=2)
    d.text((L + 31, h - 49), 'sRGB encoding', fill=(110, 110, 110))
    d.line([(L + 190, h - 42), (L + 214, h - 42)], fill=(29, 158, 117), width=3)
    d.text((L + 221, h - 49), 'ours now', fill=(15, 110, 86))
    d.line([(L + 320, h - 42), (L + 344, h - 42)], fill=(216, 90, 48), width=3)
    d.text((L + 351, h - 49), 'Photoshop (median, band=10-90%)', fill=(153, 60, 29))
    img.save(out)


if __name__ == '__main__':
    main()
