"""Minimal DNG reader for DngCreator output (uncompressed, 16-bit container).

Only handles what our camera actually writes: Compression=1, PlanarConfig=1,
single strip per row. Raises on anything else rather than guessing.
"""
import struct
import numpy as np

TAG_NEW_SUBFILE = 254
TAG_WIDTH = 256
TAG_HEIGHT = 257
TAG_BITS = 258
TAG_COMPRESSION = 259
TAG_STRIP_OFFSETS = 273
TAG_ORIENTATION = 274
TAG_STRIP_BYTES = 279
TAG_ROWS_PER_STRIP = 278
TAG_PLANAR = 284
TAG_CFA_PATTERN = 33422
TAG_BLACK_REPEAT = 50713
TAG_BLACK_LEVEL = 50714
TAG_WHITE_LEVEL = 50717
TAG_DEFAULT_SCALE = 50718
TAG_BASELINE_EXPOSURE = 50730
TAG_ACTIVE_AREA = 50829

TYPE_SIZE = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 6: 1, 7: 1, 8: 2, 9: 4, 10: 8, 11: 4, 12: 8}


def _read_ifd(d, bo, off, depth=0):
    if depth > 2 or off <= 0 or off + 2 > len(d):
        return {}
    n = struct.unpack(bo + 'H', d[off:off + 2])[0]
    if n <= 0 or n > 1024:
        return {}
    out = {}
    for i in range(n):
        b = off + 2 + i * 12
        if b + 12 > len(d):
            break
        tag, typ, cnt = struct.unpack(bo + 'HHI', d[b:b + 8])
        if typ not in TYPE_SIZE:
            continue
        size = TYPE_SIZE[typ] * cnt
        if size <= 4:
            raw = d[b + 8:b + 8 + size]
        else:
            p = struct.unpack(bo + 'I', d[b + 8:b + 12])[0]
            if p + size > len(d):
                continue
            raw = d[p:p + size]
        if typ == 3:
            val = struct.unpack(bo + '%dH' % cnt, raw)
        elif typ in (4, 9):
            val = struct.unpack(bo + '%dI' % cnt, raw) if typ == 4 else struct.unpack(bo + '%di' % cnt, raw)
        elif typ in (5, 10):
            fmt = 'II' if typ == 5 else 'ii'
            val = tuple(
                struct.unpack(bo + fmt, raw[k * 8:k * 8 + 8])[0] /
                (struct.unpack(bo + fmt, raw[k * 8:k * 8 + 8])[1] or 1)
                for k in range(cnt)
            )
        elif typ == 1 or typ == 7:
            val = tuple(raw)
        elif typ == 2:
            val = raw.rstrip(b'\x00').decode('utf8', 'ignore')
        else:
            val = raw
        out[tag] = val
    return out


def read_dng(path):
    with open(path, 'rb') as f:
        d = f.read()
    if d[:2] == b'II':
        bo = '<'
    elif d[:2] == b'MM':
        bo = '>'
    else:
        raise ValueError('not a TIFF/DNG file')
    ifd_off = struct.unpack(bo + 'I', d[4:8])[0]
    t = _read_ifd(d, bo, ifd_off)

    w = t[TAG_WIDTH][0]
    h = t[TAG_HEIGHT][0]
    comp = t.get(TAG_COMPRESSION, (1,))[0]
    if comp != 1:
        raise ValueError('compressed DNG not supported (Compression=%d)' % comp)
    planar = t.get(TAG_PLANAR, (1,))[0]
    if planar != 1:
        raise ValueError('planar DNG not supported')

    bits = t.get(TAG_BITS, (16,))[0]
    dt = np.dtype(bo + 'u2') if bits == 16 else np.dtype('u1')
    bytes_per_px = dt.itemsize

    offsets = t[TAG_STRIP_OFFSETS]
    counts = t[TAG_STRIP_BYTES]
    rows_per_strip = t.get(TAG_ROWS_PER_STRIP, (h,))[0]

    raw = np.zeros((h, w), dtype=np.uint16)
    if rows_per_strip == 1:
        for r in range(min(h, len(offsets))):
            off, cnt = offsets[r], counts[r]
            row = np.frombuffer(d[off:off + cnt], dtype=dt)
            if row.size < w:
                raise ValueError('strip %d short: %d < %d' % (r, row.size, w))
            raw[r] = row[:w]
    else:
        # generic path: walk strips, fill row by row
        for i, (off, cnt) in enumerate(zip(offsets, counts)):
            buf = np.frombuffer(d[off:off + cnt], dtype=dt)
            r0 = i * rows_per_strip
            rows = min(rows_per_strip, h - r0)
            block = buf[:rows * w].reshape(rows, w)
            raw[r0:r0 + rows] = block

    cfa = t.get(TAG_CFA_PATTERN)
    black = t.get(TAG_BLACK_LEVEL)
    if black is not None and len(black) == 1:
        black = tuple(black) * 4
    white = t.get(TAG_WHITE_LEVEL, (1023,))[0]
    baseline_ev = t.get(TAG_BASELINE_EXPOSURE)
    baseline_ev = baseline_ev[0] if baseline_ev else 0.0
    orient = t.get(TAG_ORIENTATION, (1,))[0]
    active = t.get(TAG_ACTIVE_AREA)

    return {
        'width': w,
        'height': h,
        'bits': bits,
        'cfa': tuple(cfa) if cfa else None,   # (2,1,1,0) means BGGR
        'black': black,                        # per-phase, top-left order
        'white': white,
        'orientation': orient,
        'active': active,
        'baseline_ev': baseline_ev,
        'raw': raw,
    }


def read_gainmaps(path):
    """Parse OpcodeList2 GainMap (opcode 9), one grid per Bayer phase.

    DNG opcode payloads are always big-endian regardless of file byte order.
    Returns {(top, left): (mv, mh, grid_float32)} or {} when absent.

    This matters for measurement: the corner gain here is ~3.96, i.e. the raw
    corner is a quarter as bright as the centre. Photoshop executes these
    opcodes, so any comparison against uncorrected raw data is meaningless.
    """
    with open(path, 'rb') as f:
        d = f.read()
    fbo = '<' if d[:2] == b'II' else '>'
    off = struct.unpack(fbo + 'I', d[4:8])[0]
    n = struct.unpack(fbo + 'H', d[off:off + 2])[0]
    blob = None
    for i in range(n):
        b = off + 2 + i * 12
        tag, typ, cnt = struct.unpack(fbo + 'HHI', d[b:b + 8])
        if tag == 51009:
            p = struct.unpack(fbo + 'I', d[b + 8:b + 12])[0]
            blob = d[p:p + cnt]
    if not blob or len(blob) < 8:
        return {}

    cntop = struct.unpack('>I', blob[:4])[0]
    pos = 4
    out = {}
    for _ in range(cntop):
        if pos + 16 > len(blob):
            break
        oid, ver, flags, psize = struct.unpack('>IIII', blob[pos:pos + 16])
        q = blob[pos + 16:pos + 16 + psize]
        if oid == 9 and len(q) >= 76:
            top, left, bot, right = struct.unpack('>4I', q[:16])
            mv, mh = struct.unpack('>2I', q[32:40])
            cnt_pts = mv * mh
            if psize >= 76 + cnt_pts * 4:
                g = np.frombuffer(q[76:76 + cnt_pts * 4], dtype='>f4').astype(np.float32)
                out[(top, left)] = (mv, mh, g.reshape(mv, mh))
        pos += 16 + psize
    return out


def bilinear_gain(grid, mv, mh, hh, ww):
    """Resample an mv x mh gain grid to hh x ww by bilinear interpolation."""
    gy = np.linspace(0, mv - 1, hh)
    gx = np.linspace(0, mh - 1, ww)
    y0 = np.clip(np.floor(gy).astype(np.int32), 0, mv - 2)
    x0 = np.clip(np.floor(gx).astype(np.int32), 0, mh - 2)
    wy = (gy - y0)[:, None]
    wx = (gx - x0)[None, :]
    g00 = grid[np.ix_(y0, x0)]
    g01 = grid[np.ix_(y0, np.clip(x0 + 1, 0, mh - 1))]
    g10 = grid[np.ix_(np.clip(y0 + 1, 0, mv - 1), x0)]
    g11 = grid[np.ix_(np.clip(y0 + 1, 0, mv - 1), np.clip(x0 + 1, 0, mh - 1))]
    return (g00 * (1 - wy) * (1 - wx) + g01 * (1 - wy) * wx
            + g10 * wy * (1 - wx) + g11 * wy * wx).astype(np.float32)


def green_planes(img):
    """Return (G_at_even_row_odd_col, G_at_odd_row_even_col) for both BGGR and RGGB.

    Bayer phase names in the pattern tuple are 0=R 1=G 2=B in TL,TR,BL,BR order.
    Green always occupies the two anti-diagonal cells.
    """
    p = img['cfa']
    raw = img['raw']
    if p is None:
        return raw[0::2, 1::2], raw[1::2, 0::2]
    tl, tr, bl, br = p
    # greens are the ones equal to 1
    if tr == 1:
        g1 = raw[0::2, 1::2]
        g2 = raw[1::2, 0::2]
    else:
        g1 = raw[0::2, 0::2]
        g2 = raw[1::2, 1::2]
    return g1, g2


def luminance_thumb(img, target_w=256):
    """Rough luminance thumbnail from the green planes, black-subtracted and scaled."""
    g1, g2 = green_planes(img)
    # both planes share the same origin grid; use the min size
    hh = min(g1.shape[0], g2.shape[0])
    ww = min(g1.shape[1], g2.shape[1])
    g = (g1[:hh, :ww].astype(np.float32) + g2[:hh, :ww].astype(np.float32)) / 2.0
    blk = img['black'][1] if img['black'] else 64.0
    g = np.clip(g - blk, 0, None)
    h2 = max(1, int(hh * target_w / ww))
    ys = (np.arange(h2) * (hh / h2)).astype(int)
    xs = (np.arange(target_w) * (ww / target_w)).astype(int)
    return g[np.ix_(ys, xs)]


if __name__ == '__main__':
    import sys
    for p in sys.argv[1:]:
        im = read_dng(p)
        print(p)
        print('  size      ', im['width'], 'x', im['height'], 'bits', im['bits'])
        print('  cfa       ', im['cfa'], '(2,1,1,0)=BGGR')
        print('  black     ', im['black'])
        print('  white     ', im['white'])
        print('  orient    ', im['orientation'])
        print('  baselineEV', round(im['baseline_ev'], 4))
        g1, g2 = green_planes(im)
        print('  G p50/p99 ', int(np.percentile(g1, 50)), int(np.percentile(g1, 99)),
              '|', int(np.percentile(g2, 50)), int(np.percentile(g2, 99)))
