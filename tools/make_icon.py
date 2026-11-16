import math
import os
import struct
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'res')

DENSITIES = [
    ('mipmap-mdpi', 48, 108),
    ('mipmap-hdpi', 72, 162),
    ('mipmap-xhdpi', 96, 216),
    ('mipmap-xxhdpi', 144, 324),
    ('mipmap-xxxhdpi', 192, 432),
]

TOP = (18, 106, 138)
BOTTOM = (9, 32, 62)
WHITE = (255, 255, 255)

POINTS = [
    (0.10, 0.66),
    (0.28, 0.44),
    (0.44, 0.68),
    (0.60, 0.28),
    (0.74, 0.52),
    (0.90, 0.34),
]

ADAPTIVE_LINES = [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">',
    '    <background android:drawable="@mipmap/ic_launcher_background" />',
    '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />',
    '</adaptive-icon>',
]
ADAPTIVE_XML = chr(10).join(ADAPTIVE_LINES) + chr(10)


def gradient_rows(size):
    rows = []
    for y in range(size):
        t = y / (size - 1)
        rows.append((
            int(TOP[0] + (BOTTOM[0] - TOP[0]) * t),
            int(TOP[1] + (BOTTOM[1] - TOP[1]) * t),
            int(TOP[2] + (BOTTOM[2] - TOP[2]) * t),
        ))
    return rows


def gradient_buffer(size):
    rows = gradient_rows(size)
    return [[(rows[y][0], rows[y][1], rows[y][2], 1.0) for _ in range(size)] for y in range(size)]


def transparent_buffer(size):
    return [[(0.0, 0.0, 0.0, 0.0) for _ in range(size)] for _ in range(size)]


def curve_samples(size, box):
    pts = [(box[0] + p[0] * box[2], box[1] + p[1] * box[3]) for p in POINTS]
    ext = [pts[0]] + pts + [pts[-1]]
    out = []
    for i in range(1, len(ext) - 2):
        p0, p1, p2, p3 = ext[i - 1], ext[i], ext[i + 1], ext[i + 2]
        steps = max(8, int(size / 24))
        for s in range(steps):
            t = s / steps
            t2 = t * t
            t3 = t2 * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    out.append(pts[-1])
    return out


def stamp(buf, size, cx, cy, r, color, alpha):
    x0 = max(0, int(cx - r - 1))
    x1 = min(size - 1, int(cx + r + 1))
    y0 = max(0, int(cy - r - 1))
    y1 = min(size - 1, int(cy + r + 1))
    r2 = r * r
    for y in range(y0, y1 + 1):
        row = buf[y]
        for x in range(x0, x1 + 1):
            dx = x - cx
            dy = y - cy
            if dx * dx + dy * dy <= r2:
                old = row[x]
                na = alpha + old[3] * (1 - alpha)
                if na <= 0:
                    continue
                row[x] = (
                    (color[0] * alpha + old[0] * old[3] * (1 - alpha)) / na,
                    (color[1] * alpha + old[1] * old[3] * (1 - alpha)) / na,
                    (color[2] * alpha + old[2] * old[3] * (1 - alpha)) / na,
                    na,
                )


def draw_ring(buf, size, radius, width, alpha):
    c = size / 2
    steps = int(radius * 8)
    for s in range(steps):
        ang = s * 6.28318530718 / steps
        stamp(buf, size, c + radius * math.cos(ang), c + radius * math.sin(ang), width, WHITE, alpha)


def draw_curve(buf, size, box, half_width):
    samples = curve_samples(size, box)
    for p in samples:
        stamp(buf, size, p[0], p[1], half_width, WHITE, 1.0)
    last = samples[-1]
    stamp(buf, size, last[0], last[1], half_width * 2.6, WHITE, 0.30)
    stamp(buf, size, last[0], last[1], half_width * 1.7, WHITE, 1.0)


def downscale(src, tw, th):
    sh = len(src)
    sw = len(src[0])
    out = []
    for ty in range(th):
        y0 = ty * sh / th
        y1 = (ty + 1) * sh / th
        row = []
        for tx in range(tw):
            x0 = tx * sw / tw
            x1 = (tx + 1) * sw / tw
            sr = 0.0
            sg = 0.0
            sb = 0.0
            sa = 0.0
            wsum = 0.0
            iy = int(y0)
            while iy < min(sh, int(y1) + 1):
                oy = min(y1, iy + 1) - max(y0, iy)
                if oy > 0:
                    srow = src[iy]
                    ix = int(x0)
                    while ix < min(sw, int(x1) + 1):
                        ox = min(x1, ix + 1) - max(x0, ix)
                        if ox > 0:
                            p = srow[ix]
                            w = ox * oy
                            a = p[3] * w
                            sr += p[0] * a
                            sg += p[1] * a
                            sb += p[2] * a
                            sa += a
                            wsum += w
                        ix += 1
                iy += 1
            if sa > 0.0001:
                row.append((sr / sa, sg / sa, sb / sa, sa / wsum))
            else:
                row.append((0.0, 0.0, 0.0, 0.0))
        out.append(row)
    return out


def rounded_mask(size, radius):
    mask = []
    for y in range(size):
        row = []
        for x in range(size):
            cx = min(x, size - 1 - x)
            cy = min(y, size - 1 - y)
            qx = max(cx - (size / 2 - radius), 0.0)
            qy = max(cy - (size / 2 - radius), 0.0)
            d = math.sqrt(qx * qx + qy * qy)
            row.append(max(0.0, min(1.0, radius - d + 0.5)))
        mask.append(row)
    return mask


def circle_mask(size):
    mask = []
    c = (size - 1) / 2
    r = size / 2 - 0.5
    for y in range(size):
        row = []
        for x in range(size):
            d = math.sqrt((x - c) * (x - c) + (y - c) * (y - c))
            row.append(max(0.0, min(1.0, r - d + 0.5)))
        mask.append(row)
    return mask


def apply_mask(img, mask):
    return [[(p[0], p[1], p[2], p[3] * mask[y][x]) for x, p in enumerate(img[y])] for y in range(len(img))]


def write_png(path, img):
    h = len(img)
    w = len(img[0])
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for p in img[y]:
            raw.append(max(0, min(255, int(p[0] + 0.5))))
            raw.append(max(0, min(255, int(p[1] + 0.5))))
            raw.append(max(0, min(255, int(p[2] + 0.5))))
            raw.append(max(0, min(255, int(p[3] * 255 + 0.5))))

    def chunk(kind, data):
        block = kind + data
        return struct.pack('>I', len(data)) + block + struct.pack('>I', zlib.crc32(block) & 0xFFFFFFFF)

    png = bytearray()
    png += bytes([137, 80, 78, 71, 13, 10, 26, 10])
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(bytes(raw), 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(bytes(png))
    print('wrote ' + path + ' (' + str(w) + 'x' + str(h) + ')')


def main():
    leg = 1536
    adb = 1296
    print('drawing the legacy art at ' + str(leg))
    legacy = gradient_buffer(leg)
    draw_ring(legacy, leg, leg * 0.40, max(3, leg * 0.005), 0.20)
    draw_curve(legacy, leg, (0.10, 0.20, 0.80, 0.56), leg * 0.034)
    square = apply_mask(legacy, rounded_mask(leg, int(leg * 0.225)))
    round_icon = apply_mask(legacy, circle_mask(leg))

    print('drawing the adaptive foreground at ' + str(adb))
    fg = transparent_buffer(adb)
    draw_ring(fg, adb, adb * 0.30, max(3, adb * 0.005), 0.25)
    draw_curve(fg, adb, (0.26, 0.30, 0.48, 0.40), adb * 0.026)
    bg = gradient_buffer(adb)

    for folder, size, adsize in DENSITIES:
        outdir = os.path.join(RES, folder)
        if not os.path.isdir(outdir):
            os.makedirs(outdir)
        write_png(os.path.join(outdir, 'ic_launcher.png'), downscale(square, size, size))
        write_png(os.path.join(outdir, 'ic_launcher_round.png'), downscale(round_icon, size, size))
        write_png(os.path.join(outdir, 'ic_launcher_foreground.png'), downscale(fg, adsize, adsize))
        write_png(os.path.join(outdir, 'ic_launcher_background.png'), downscale(bg, adsize, adsize))

    anydpi = os.path.join(RES, 'mipmap-anydpi-v26')
    if not os.path.isdir(anydpi):
        os.makedirs(anydpi)
    for name in ('ic_launcher.xml', 'ic_launcher_round.xml'):
        with open(os.path.join(anydpi, name), 'w') as f:
            f.write(ADAPTIVE_XML)
        print('wrote ' + os.path.join('mipmap-anydpi-v26', name))


main()
