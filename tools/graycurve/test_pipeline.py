"""Self-test for the gray-chart pipeline.

The quantity we can actually measure is:  (RAW linear value) -> (PS output code).
PS receives exactly the linear value in the DNG, so whatever curve it applies is
observable in that pairing regardless of exposure. What exposure DOES affect is
how much of the 0..1 range the chart covers, so we also check coverage.
"""
import json
import sys
import os

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze_gray import find_bbox, sample_blocks, oetf, our_tone


def oetf_inv(c):
    s = np.clip(np.asarray(c, dtype=np.float64), 0, 1)
    return np.where(s <= 0.04045, s / 12.92, np.power((s + 0.055) / 1.055, 2.4))


def simulate(curve, gain=1.0, vignette=0.18, noise=0.0012, seed=7, chart='gray_chart.png'):
    rng = np.random.default_rng(seed)
    ch = np.asarray(Image.open(chart).convert('L'), dtype=np.float64) / 255.0

    H, W = 3072, 4096
    disp_h = int(W * 9 / 16)
    top = (H - disp_h) // 2
    big = np.asarray(Image.fromarray((ch * 255).astype(np.uint8))
                     .resize((W, disp_h), Image.NEAREST), dtype=np.float64) / 255.0
    scene = np.zeros((H, W))
    scene[top:top + disp_h, :] = oetf_inv(big)

    yy, xx = np.mgrid[0:H, 0:W]
    r = np.sqrt(((xx - W / 2) / (W / 2)) ** 2 + ((yy - H / 2) / (H / 2)) ** 2) / np.sqrt(2)
    vig = np.clip(1 - vignette * r ** 2, 0, 1)

    lin = np.clip(scene * vig * gain + rng.normal(0, noise, scene.shape), 0, 1)
    return lin, curve(lin) * 255.0


def measure(lin, out):
    bbox = find_bbox(out, verbose=False)
    m_raw = sample_blocks(lin, bbox, 6, 4, shrink=0.55)
    p_out = sample_blocks(out, bbox, 6, 4, shrink=0.55)
    return bbox, m_raw, p_out


def report(name, curve, expect_ps_equals_curve=True):
    lin, out = simulate(curve)
    bbox, m_raw, p_out = measure(lin, out)
    pred = curve(m_raw) * 255.0
    err = p_out - pred
    cover = m_raw.max()
    print('%-26s coverage %.2f   sampler err %.2f code (max %.2f)'
          % (name, cover, np.abs(err).mean(), np.abs(err).max()))
    return m_raw, p_out, bbox


print('A. plain sRGB  -> the measured pair must trace oetf()')
m1, p1, b1 = report('plain sRGB', lambda x: oetf(x))
dev = (p1 - oetf(m1) * 255.0)
print('   deviation from sRGB encoding: mean %.2f, max %.2f code'
      % (np.abs(dev).mean(), np.abs(dev).max()))
print('   -> a real PS export showing this would mean "PS adds nothing"')
print()

print('B. S-curve k=0.16 -> the deviation is what we are hunting for')
m2, p2, _ = report('S-curve k=0.16', lambda x: np.clip(
    oetf(x) + 0.16 * (oetf(x) ** 2 * (3 - 2 * oetf(x)) - oetf(x)), 0, 1))
dev2 = p2 - oetf(m2) * 255.0
print('   vs sRGB: mean %.2f, max %.2f code   (dark side %.1f, light side %.1f)'
      % (np.abs(dev2).mean(), np.abs(dev2).max(), dev2[1, 1], dev2[1, 3]))
print()

print('C. ours (shoulder 0.88) -> deviation only above the shoulder')
m3, p3, _ = report('ours shoulder', our_tone)
dev3 = p3 - oetf(m3) * 255.0
print('   vs sRGB: mean %.2f, max %.2f code' % (np.abs(dev3).mean(), np.abs(dev3).max()))
print()

print('bbox used: %s' % (b1,))
print('expected chart frame: x 307..3788  y 557..2514')
