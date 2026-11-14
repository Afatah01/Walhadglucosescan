#!/usr/bin/env python3
"""Makes the app icon and the logo inside the app from the Walhad brand logo.

    python3 tools/make_brand_assets.py

Input:  docs/brand/walhad-logo-original.png   (the logo as delivered, white outside the blue frame)
Output: app/src/main/res/mipmap-*/ic_launcher.png              icon for Android 6 and 7
        app/src/main/res/mipmap-*/ic_launcher_foreground.png   icon picture for Android 8 and newer
        app/src/main/res/mipmap-*/ic_launcher_background.png   blue plate behind it
        app/src/main/res/drawable-nodpi/brand_logo.png         logo shown inside the app
        docs/brand/walhad-logo.png                             logo with see-through corners
        docs/brand/icon-preview.png                            how the icon looks on a phone

Needs Python 3 with Pillow.
"""
import os
from PIL import Image, ImageChops, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCE = os.path.join(ROOT, "docs/brand/walhad-logo-original.png")
RES = os.path.join(ROOT, "app/src/main/res")
BRAND = os.path.join(ROOT, "docs/brand")

# Android screen densities: name -> pixels per dp
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}

# Size of the logo inside the 108 dp icon canvas of Android 8+. The launcher shows the
# middle 72 dp and cuts it to its own shape (circle, rounded square ...). With 60 dp the
# white part of the logo stays whole in every shape; only blue frame meets blue plate.
ADAPTIVE_LOGO_DP = 60


def cut_out(source):
    """Returns the logo as a square RGBA picture; everything outside the blue frame is see-through."""
    rgb = source.convert("RGB")
    width, height = rgb.size
    # 1. find the white outside: fill from the four corners with a colour the logo does not have
    marker = (255, 0, 255)
    work = rgb.copy()
    for corner in ((0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1)):
        ImageDraw.floodfill(work, corner, marker, thresh=60)
    different = ImageChops.difference(work, Image.new("RGB", rgb.size, marker)).convert("L")
    inside = different.point(lambda v: 255 if v > 0 else 0)
    # 2. take a few pixels off the edge (they are a mix of blue and white) and soften it
    inside = inside.filter(ImageFilter.MinFilter(7)).filter(ImageFilter.GaussianBlur(1.2))
    box = inside.point(lambda v: 255 if v > 128 else 0).getbbox()
    logo = rgb.convert("RGBA")
    logo.putalpha(inside)
    logo = logo.crop(box)
    # 3. make it square, logo in the middle
    side = max(logo.size)
    square = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    square.paste(logo, ((side - logo.size[0]) // 2, (side - logo.size[1]) // 2))
    return square, frame_colors(rgb, box)


def frame_colors(rgb, box):
    """Colours of the blue frame at the top, the middle and the bottom."""
    left, top, right, bottom = box
    x = (left + right) // 2
    middle_y = (top + bottom) // 2
    return [rgb.getpixel((x, top + 14)), rgb.getpixel((left + 14, middle_y)), rgb.getpixel((x, bottom - 16))]


def resized(image, size):
    """High-quality resize that does not leave a light rim at see-through edges."""
    return image.convert("RGBa").resize((size, size), Image.LANCZOS).convert("RGBA")


def plate(size, logo_size, colors):
    """The blue plate behind the logo. It has the colour of the frame at the same height."""
    top = (size - logo_size) / 2.0
    image = Image.new("RGB", (size, size))
    draw = ImageDraw.Draw(image)
    for y in range(size):
        t = min(1.0, max(0.0, (y - top) / float(logo_size)))
        a, b, f = (colors[0], colors[1], t * 2) if t < 0.5 else (colors[1], colors[2], t * 2 - 1)
        draw.line([(0, y), (size, y)], fill=tuple(int(round(a[i] + (b[i] - a[i]) * f)) for i in range(3)))
    return image


def save(image, *path):
    target = os.path.join(*path)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    image.save(target, optimize=True)
    return target


def masks(size):
    """Shapes that launchers cut the icon to: circle, rounded square, squircle."""
    big = size * 4
    result = {}
    circle = Image.new("L", (big, big), 0)
    ImageDraw.Draw(circle).ellipse([0, 0, big - 1, big - 1], fill=255)
    result["circle"] = circle.resize((size, size), Image.LANCZOS)
    rounded = Image.new("L", (big, big), 0)
    ImageDraw.Draw(rounded).rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.22), fill=255)
    result["rounded square"] = rounded.resize((size, size), Image.LANCZOS)
    squircle = Image.new("L", (big, big), 0)
    pixels = squircle.load()
    half = big / 2.0
    for y in range(big):
        for x in range(big):
            if abs((x + 0.5 - half) / half) ** 4 + abs((y + 0.5 - half) / half) ** 4 <= 1:
                pixels[x, y] = 255
    result["squircle"] = squircle.resize((size, size), Image.LANCZOS)
    return result


def main():
    logo, colors = cut_out(Image.open(SOURCE))
    print("logo cut out:", logo.size, "frame colours:", colors)
    save(resized(logo, 1024), BRAND, "walhad-logo.png")
    save(resized(logo, 480), RES, "drawable-nodpi", "brand_logo.png")

    for name, scale in DENSITIES.items():
        # Android 6 and 7: the logo itself, with a little room around it
        size = int(round(48 * scale))
        inner = int(round(46 * scale))
        icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        icon.alpha_composite(resized(logo, inner), ((size - inner) // 2, (size - inner) // 2))
        save(icon, RES, "mipmap-" + name, "ic_launcher.png")

        # Android 8 and newer: picture and plate, the launcher cuts the shape
        canvas = int(round(108 * scale))
        logo_size = int(round(ADAPTIVE_LOGO_DP * scale))
        foreground = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        foreground.alpha_composite(resized(logo, logo_size), ((canvas - logo_size) // 2, (canvas - logo_size) // 2))
        save(foreground, RES, "mipmap-" + name, "ic_launcher_foreground.png")
        save(plate(canvas, logo_size, colors), RES, "mipmap-" + name, "ic_launcher_background.png")

    # preview: the icon as phones show it, big and in real size (xxhdpi)
    scale = 4
    canvas = 108 * scale
    logo_size = ADAPTIVE_LOGO_DP * scale
    full = plate(canvas, logo_size, colors).convert("RGBA")
    full.alpha_composite(resized(logo, logo_size), ((canvas - logo_size) // 2, (canvas - logo_size) // 2))
    visible = full.crop((18 * scale, 18 * scale, 90 * scale, 90 * scale))  # the middle 72 dp
    shapes = masks(visible.size[0])
    gap = 40
    sheet = Image.new("RGB", (gap + (visible.size[0] + gap) * (len(shapes) + 1), visible.size[0] + 2 * gap + 190), (236, 239, 244))
    x = gap
    small_y = visible.size[0] + 2 * gap
    for shape in shapes.values():
        cut = visible.copy()
        cut.putalpha(shape)
        sheet.paste(cut, (x, gap), cut)
        small = resized(cut, 144)
        sheet.paste(small, (x + (visible.size[0] - 144) // 2, small_y), small)
        x += visible.size[0] + gap
    # last one: the plain icon of Android 6 and 7
    plain = Image.open(os.path.join(RES, "mipmap-xxxhdpi", "ic_launcher.png")).convert("RGBA")
    plain_big = resized(plain, visible.size[0])
    sheet.paste(plain_big, (x, gap), plain_big)
    plain_small = resized(plain, 144)
    sheet.paste(plain_small, (x + (visible.size[0] - 144) // 2, small_y), plain_small)
    save(sheet, BRAND, "icon-preview.png")
    print("done")


if __name__ == "__main__":
    main()
