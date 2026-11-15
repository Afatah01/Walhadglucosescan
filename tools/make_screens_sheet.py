#!/usr/bin/env python3
"""Makes one picture that shows the screens of version 4.3 side by side.

Usage: python3 tools/make_screens_sheet.py OUTPUT.png

The single pictures come from docs/screenshots/. The tests make them
(./gradlew :app:testDebugUnitTest), so run the tests first.
Needs Pillow (pip install pillow) and the DejaVu fonts.
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHOTS = os.path.join(ROOT, "docs", "screenshots")
BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
PLAIN = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
PAGE = (240, 244, 252)
INK = (20, 28, 46)
BLUE = (11, 95, 214)
FRAME = (200, 210, 228)

TITLE = "Walhad Glucose Scan 4.3  \u00b7  Gemini or OpenAI, built in"

# file, number, two lines of words, and how the picture is shown:
#   True   the top of the screen and the bar at its bottom
#   False  the top of the screen only; it goes on below
#   PAGES  not a screen: the two pages of the daily report, one under the other
PAGES = "pages"
SCREENS = [
    ("40-home", "1", ["Home: the value, the next 30", "minutes, the scan."], True),
    ("52-home-linkup", "2", ["Home with LibreLinkUp: live", "values, new each minute."], True),
    ("43-analysis", "3", ["Analysis: the last hours", "in words and numbers."], True),
    ("54-analysis-ai-answer", "4", ["AI: the app asks an AI service", "and shows the answer."], True),
    ("60-more", "5", ["More: three short lists.", "On or Off at a glance."], True),
    ("51-linkup-on", "6", ["LibreLinkUp: Bluetooth stays", "with LibreLink."], False),
    ("56-backup-on", "7", ["Online copy: your values in", "your own database."], False),
    ("49-report-downloaded", "8", ["Report: choose a day,", "download the PDF."], True),
]
COLUMNS = 4

SHOT_W = 720          # width of a picture from the tests
CROP_H = 1500         # how much of its height is shown
BAR_H = 119           # height of the bar at the bottom of a screen, with the line above it
FADE = 90
COL_W, GAP, TOP, CAPTION_H, ROW_GAP = 480, 44, 104, 96, 40


def fade_rows(pixels, first, last, towards_end):
    """Fades the rows first..last into the page colour."""
    for row in range(first, last):
        t = (row - first) / float(last - first)
        if not towards_end:
            t = 1.0 - t
        for col in range(SHOT_W):
            r, g, b = pixels[col, row]
            pixels[col, row] = (round(r + (PAGE[0] - r) * t), round(g + (PAGE[1] - g) * t), round(b + (PAGE[2] - b) * t))


def pages_part(name):
    """Both pages of the report, one under the other, on the colour of the sheet."""
    part = Image.new("RGB", (SHOT_W, CROP_H), PAGE)
    draw = ImageDraw.Draw(part)
    gap = 24
    page_h = (CROP_H - gap) // 2
    for k in range(2):
        page = Image.open(os.path.join(SHOTS, name[:-1] + str(k + 1) + ".png")).convert("RGB")
        page_w = round(page.size[0] * page_h / float(page.size[1]))
        page = page.resize((page_w, page_h), Image.LANCZOS)
        left = (SHOT_W - page_w) // 2
        top = k * (page_h + gap)
        part.paste(page, (left, top))
        draw.rectangle((left, top, left + page_w - 1, top + page_h - 1), outline=FRAME, width=3)
    return part


def screen_part(name, with_bar):
    """The part of a screen picture that goes on the sheet."""
    if with_bar == PAGES:
        return pages_part(name)
    shot = Image.open(os.path.join(SHOTS, name + ".png")).convert("RGB")
    assert shot.size[0] == SHOT_W and shot.size[1] >= CROP_H, (name, shot.size)
    part = Image.new("RGB", (SHOT_W, CROP_H), PAGE)
    if with_bar:
        # the top of the screen, a soft break, and the bar from its lower end
        top_h = CROP_H - BAR_H
        top = shot.crop((0, 0, SHOT_W, top_h))
        fade_rows(top.load(), top_h - FADE, top_h, True)
        part.paste(top, (0, 0))
        part.paste(shot.crop((0, shot.size[1] - BAR_H, SHOT_W, shot.size[1])), (0, top_h))
    else:
        top = shot.crop((0, 0, SHOT_W, CROP_H))
        fade_rows(top.load(), CROP_H - FADE, CROP_H, True)
        part.paste(top, (0, 0))
    return part


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "docs", "screens-sheet.png")
    scale = COL_W / float(SHOT_W)
    shot_h = round(CROP_H * scale)
    rows = (len(SCREENS) + COLUMNS - 1) // COLUMNS
    width = COLUMNS * COL_W + (COLUMNS + 1) * GAP
    row_h = CAPTION_H + shot_h + ROW_GAP
    height = TOP + rows * row_h + 10
    sheet = Image.new("RGB", (width, height), PAGE)
    draw = ImageDraw.Draw(sheet)
    title_font = ImageFont.truetype(BOLD, 38)
    caption_font = ImageFont.truetype(PLAIN, 23)
    number_font = ImageFont.truetype(BOLD, 28)
    draw.text((GAP, 34), TITLE, font=title_font, fill=INK)

    for i, (name, number, lines, with_bar) in enumerate(SCREENS):
        x = GAP + (i % COLUMNS) * (COL_W + GAP)
        y = TOP + (i // COLUMNS) * row_h
        draw.ellipse((x, y + 6, x + 52, y + 58), fill=BLUE)
        box = draw.textbbox((0, 0), number, font=number_font)
        draw.text((x + 26 - (box[2] + box[0]) / 2, y + 32 - (box[3] + box[1]) / 2), number, font=number_font, fill="white")
        for k, line in enumerate(lines):
            assert draw.textlength(line, font=caption_font) <= COL_W - 64, line
            draw.text((x + 66, y + 4 + k * 31), line, font=caption_font, fill=INK)

        part = screen_part(name, with_bar).resize((COL_W, shot_h), Image.LANCZOS)
        mask = Image.new("L", (COL_W, shot_h), 0)
        if with_bar == PAGES:
            sheet.paste(part, (x, y + CAPTION_H))
        elif with_bar:
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, COL_W - 1, shot_h - 1), radius=26, fill=255)
            sheet.paste(part, (x, y + CAPTION_H), mask)
            draw.rounded_rectangle((x, y + CAPTION_H, x + COL_W - 1, y + CAPTION_H + shot_h - 1), radius=26, outline=FRAME, width=2)
        else:
            # the screen goes on below: the frame is open at its lower end
            ImageDraw.Draw(mask).rounded_rectangle((0, 0, COL_W - 1, shot_h + 60), radius=26, fill=255)
            sheet.paste(part, (x, y + CAPTION_H), mask)
            frame = Image.new("RGBA", (COL_W, shot_h), (0, 0, 0, 0))
            ImageDraw.Draw(frame).rounded_rectangle((0, 0, COL_W - 1, shot_h + 60), radius=26, outline=FRAME + (255,), width=2)
            pixels = frame.load()
            fade_from = shot_h - round(FADE * scale)
            for row in range(fade_from, shot_h):
                keep = 1.0 - (row - fade_from) / float(shot_h - fade_from)
                for col in list(range(0, 3)) + list(range(COL_W - 3, COL_W)):
                    r, g, b, a = pixels[col, row]
                    pixels[col, row] = (r, g, b, round(a * keep))
            sheet.paste(frame, (x, y + CAPTION_H), frame)

    sheet.save(out, optimize=True)
    print(out, sheet.size)


if __name__ == "__main__":
    main()
