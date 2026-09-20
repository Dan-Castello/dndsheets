# -*- coding: utf-8 -*-
"""Generates the character sheet backgrounds: aged parchment inside a leather-and-brass frame
with Minecraft's bevel.

Decisiones que importan:

  - NO baked-in text. The labels (NAME, RACE, ABILITIES...) are drawn by the code from the translation
    keys, so having them in the PNG too duplicated them AND left them in one language forever.
  - NO drawn boxes. Minecraft's EditBoxes already paint their own frame on top; the background's
    boxes were redundant, and they also forced the drawing to match to the pixel some coordinates
    that only live in the code. With them removed, there is nothing to align and nothing that can
    get misaligned in the future.
  - It is drawn at 4x (1592x1152) of the logical size (398x288) used for the blit: Minecraft
    scales it down, and that supersampling is what keeps the grain from looking blocky at a high GUI Scale.
"""
import math
import random
from PIL import Image, ImageDraw, ImageFilter

LOGICAL_W, LOGICAL_H = 398, 288
SCALE = 4
W, H = LOGICAL_W * SCALE, LOGICAL_H * SCALE

# The same palette as GuiStyle, so the sheet and the rest of the mod's panels read as the same mod.
LEATHER      = (26, 20, 14)
LEATHER_EDGE = (11, 9, 6)
BEVEL_LIGHT  = (107, 86, 54)
BRASS        = (201, 162, 39)
PARCHMENT    = (214, 197, 160)
PARCHMENT_HI = (228, 213, 180)
INK_SHADOW   = (150, 132, 100)

FRAME = 10 * SCALE          # width of the leather frame
random.seed(20240815)       # reproducible: regenerating must not give a different background


def parchment_field(w, h):
    """Veined parchment: soft noise at two scales, not white noise — per-pixel noise looks
    dirty when scaled down, and wide veins survive Minecraft's downscale."""
    base = Image.new('RGB', (w, h), PARCHMENT)
    coarse = Image.new('L', (w // 16, h // 16))
    coarse.putdata([random.randint(96, 160) for _ in range(coarse.width * coarse.height)])
    coarse = coarse.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(6))

    fine = Image.new('L', (w // 4, h // 4))
    fine.putdata([random.randint(110, 145) for _ in range(fine.width * fine.height)])
    fine = fine.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(2))

    # Continuous blending and NOT a threshold: clipping the noise to two tones gave camouflage blotches. The parchment
    # has soft, low-contrast variation — compressing the range to +-8 levels around the middle is
    # what turns it into fiber instead of a topographic map. This way it also survives the downscale.
    grain = Image.blend(coarse, fine, 0.45).point(lambda v: 120 + (v - 128) // 8)
    light = Image.new('RGB', (w, h), PARCHMENT_HI)
    return Image.composite(light, base, grain).filter(ImageFilter.GaussianBlur(3))


def aged_edges(img, w, h):
    """Darkened toward the edges: it is what makes the parchment look like a used sheet and not a
    rectangulo de color plano."""
    shade = Image.new('L', (w, h), 0)
    sd = ImageDraw.Draw(shade)
    steps = 26 * SCALE
    for i in range(steps):
        v = int(70 * (1 - i / steps) ** 2)
        sd.rectangle([i, i, w - 1 - i, h - 1 - i], outline=v)
    shade = shade.filter(ImageFilter.GaussianBlur(4 * SCALE))
    dark = Image.new('RGB', (w, h), (120, 100, 68))
    return Image.composite(dark, img, shade.point(lambda v: min(255, v * 3))) \
        if False else Image.blend(img, dark, 0.0) if False else _apply_shade(img, shade, dark)


def _apply_shade(img, shade, dark):
    out = img.copy()
    out.paste(dark, (0, 0), shade)
    return out


def bevel(draw, x0, y0, x1, y1, light, dark, thickness):
    """Minecraft's bevel: light on top and left, dark at the bottom and right. It is the visual grammar
    that makes a panel belong to the game instead of looking like a window pasted on top."""
    draw.rectangle([x0, y0, x1, y0 + thickness], fill=light)
    draw.rectangle([x0, y0, x0 + thickness, y1], fill=light)
    draw.rectangle([x0, y1 - thickness, x1, y1], fill=dark)
    draw.rectangle([x1 - thickness, y0, x1, y1], fill=dark)


def brass_corners(draw, x0, y0, x1, y1):
    """Brass corner pieces, like those of a bound tome. The same motif GuiStyle draws for the
    flat panels, so the sheet and the rest of the mod share an identity."""
    arm, thick = 22 * SCALE, 3 * SCALE
    for (cx, cy, dx, dy) in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        ax, ay = cx + dx * arm, cy + dy * thick
        draw.rectangle(sorted_box(cx, cy, ax, ay), fill=BRASS)
        ax, ay = cx + dx * thick, cy + dy * arm
        draw.rectangle(sorted_box(cx, cy, ax, ay), fill=BRASS)


def sorted_box(x0, y0, x1, y1):
    return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]


def build(divider_x_logical=None):
    """@param divider_x_logical column where the vertical rule goes that separates the band of
    abilities from the body of the sheet. None = no rule (skills and attacks tabs)."""
    img = Image.new('RGB', (W, H), LEATHER)
    draw = ImageDraw.Draw(img)

    # Leather frame with bevel.
    draw.rectangle([0, 0, W - 1, H - 1], fill=LEATHER_EDGE)
    draw.rectangle([SCALE, SCALE, W - 1 - SCALE, H - 1 - SCALE], fill=LEATHER)
    bevel(draw, SCALE, SCALE, W - 1 - SCALE, H - 1 - SCALE, BEVEL_LIGHT, LEATHER_EDGE, 2 * SCALE)

    # Parchment sheet fitted inside the frame, with its own inward bevel (recessed).
    px0, py0 = FRAME, FRAME
    px1, py1 = W - 1 - FRAME, H - 1 - FRAME
    sheet = parchment_field(px1 - px0 + 1, py1 - py0 + 1)
    sheet = aged_edges(sheet, sheet.width, sheet.height)
    img.paste(sheet, (px0, py0))
    bevel(draw, px0 - SCALE, py0 - SCALE, px1 + SCALE, py1 + SCALE, LEATHER_EDGE, BEVEL_LIGHT, SCALE)

    if divider_x_logical is not None:
        x = divider_x_logical * SCALE
        draw.rectangle([x, py0 + 6 * SCALE, x + max(1, SCALE // 2), py1 - 6 * SCALE], fill=INK_SHADOW)

    brass_corners(draw, 2 * SCALE, 2 * SCALE, W - 1 - 2 * SCALE, H - 1 - 2 * SCALE)
    return img


def save(img, path):
    """Saves as is, in 24-bit RGB.

    Do NOT quantize to a 256 palette. It was tried (5.7 MB -> 3.8 MB across the three, a third less) and
    reverted: it FAILED the visual review, case S1-06 of the test bench. The analysis that approved it
    looked at the error PER PIXEL —max 8 of 255, mean 0.003— and that is the wrong statistic for
    this background. Banding is not a large error in one pixel: it is a small, CORRELATED error
    across a soft gradient, which the eye reads as steps even though each step is minimal. A
    parchment is exactly that, a large, soft gradient.

    If weight ever has to be cut here, the way is to reduce SCALE (see above), accepting the cost
    at a high GUI Scale, not lowering the color depth.
    """
    img.save(path)


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    # The main tab carries the rule: it separates the abilities column from the rest. The other two
    # do not, because their content takes the full width.
    save(build(divider_x_logical=138), out + '/character_sheet.png')
    save(build(), out + '/character_sheet_2.png')
    save(build(), out + '/character_sheet_3.png')
    print('fondos generados en', out)
