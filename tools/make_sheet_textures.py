# Regenerates the character sheet backgrounds with the SAME identity as the panels (see
# make_panel_texture.py and GuiStyle): the frame is literally the tome's leather, the page is
# grainy parchment, the corner brackets are the 0xC9A227 brass (which was already the original's gold).
#
#   character_sheet.png     (1592x1152) — main tab, with a central divider at x=552..554
#   character_sheet_2.png   (1592x1152) — skills, no divider
#   character_sheet_3.png   (1592x1152) — attacks, no divider
#   atlas/imagebutton_tabbutton.png (50x45) — 3 states of the side tab, 50x15 each
#
# The geometry (parchment window at (41,41)-(1551,1111), divider, sizes) is MEASURED from the
# original: the field offsets in CharacterSheetScreen are hardcoded against it and are not
# touched. The originals live in the git history; this tool is the source from now on.
# Semilla fija: regenerar produce exactamente los mismos PNG.
import numpy as np
from PIL import Image
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "src/main/resources/assets/dndsheets/textures/screens"

WIDTH, HEIGHT = 1592, 1152
WINDOW = (41, 41, 1551, 1111)  # parchment window, measured from the original
DIVIDER_X = (552, 555)

LEATHER = np.array([26.0, 20.0, 14.0])      # GuiStyle's leather (0x1A140E)
PARCHMENT = np.array([221.0, 205.0, 169.0]) # the original's parchment
BRASS = np.array([201.0, 162.0, 39.0])      # 0xC9A227 — it was already the original's gold
EDGE = np.array([46.0, 36.0, 24.0])         # 0x2E2418
BEVEL_LIGHT = np.array([107.0, 86.0, 54.0]) # 0x6B5636
BEVEL_DARK = np.array([11.0, 9.0, 6.0])     # 0x0B0906
DIVIDER = np.array([150.0, 132.0, 102.0])   # soft ink stroke over the parchment


def tileable_noise(rng, height, width, exponent):
    spectrum = rng.normal(size=(height, width)) + 1j * rng.normal(size=(height, width))
    fx = np.fft.fftfreq(height)[:, None]
    fy = np.fft.fftfreq(width)[None, :]
    freq = np.sqrt(fx ** 2 + fy ** 2)
    freq[0, 0] = 1.0
    spectrum *= freq ** -exponent
    spectrum[0, 0] = 0.0
    noise = np.real(np.fft.ifft2(spectrum))
    return (noise - noise.mean()) / (noise.std() + 1e-9)


def leather_layer(rng, height, width):
    mottle = tileable_noise(rng, height, width, 1.6)
    grain = tileable_noise(rng, height, width, 0.4)
    value = 1.0 + 0.11 * mottle + 0.045 * grain
    warm = 1.0 + 0.035 * mottle
    return np.stack([LEATHER[0] * value * warm, LEATHER[1] * value, LEATHER[2] * value / warm], axis=-1)


def parchment_layer(rng, height, width):
    mottle = tileable_noise(rng, height, width, 1.5)
    grain = tileable_noise(rng, height, width, 0.3)
    value = 1.0 + 0.035 * mottle + 0.015 * grain
    return PARCHMENT[None, None, :] * value[:, :, None]


def vignette(height, width, depth_px, strength):
    """Soft darkening toward the edges, like the frame's shadow on the original's page."""
    y = np.arange(height)[:, None]
    x = np.arange(width)[None, :]
    dist = np.minimum(np.minimum(y, height - 1 - y), np.minimum(x, width - 1 - x))
    fade = np.clip(dist / depth_px, 0.0, 1.0)
    return 1.0 - strength * (1.0 - fade) ** 2


def paint(img, x0, y0, x1, y1, color):
    img[y0:y1, x0:x1] = color


def corner_brackets(img):
    """Brass L-brackets on the OUTSIDE, like the original's: 110px arm, thickness 14, 8px from the edge."""
    arm, thick, off = 110, 14, 8
    w, h = WIDTH, HEIGHT
    for (cx, cy, sx, sy) in ((0, 0, 1, 1), (w, 0, -1, 1), (0, h, 1, -1), (w, h, -1, -1)):
        x_edge = cx + sx * off
        y_edge = cy + sy * off
        x_arm = cx + sx * (off + arm)
        x_thick = cx + sx * (off + thick)
        y_arm = cy + sy * (off + arm)
        y_thick = cy + sy * (off + thick)
        paint(img, min(x_edge, x_arm), min(y_edge, y_thick), max(x_edge, x_arm), max(y_edge, y_thick), BRASS)
        paint(img, min(x_edge, x_thick), min(y_edge, y_arm), max(x_edge, x_thick), max(y_edge, y_arm), BRASS)


def sheet(rng, with_divider):
    img = leather_layer(rng, HEIGHT, WIDTH)

    # Bevel of the tome's outer edge: outline + light top/left, shadow bottom/right (x4,
    # because the texture is at 4x the blit size — 8px here = GuiStyle's 2px on screen).
    paint(img, 0, 0, WIDTH, 4, EDGE); paint(img, 0, HEIGHT - 4, WIDTH, HEIGHT, EDGE)
    paint(img, 0, 0, 4, HEIGHT, EDGE); paint(img, WIDTH - 4, 0, WIDTH, HEIGHT, EDGE)
    paint(img, 4, 4, WIDTH - 8, 12, BEVEL_LIGHT); paint(img, 4, 4, 12, HEIGHT - 8, BEVEL_LIGHT)
    paint(img, 12, HEIGHT - 12, WIDTH - 4, HEIGHT - 4, BEVEL_DARK); paint(img, WIDTH - 12, 12, WIDTH - 4, HEIGHT - 4, BEVEL_DARK)

    corner_brackets(img)

    # Parchment window, recessed: inverted bevel (shadow top/left, light bottom/right).
    x0, y0, x1, y1 = WINDOW
    paint(img, x0 - 8, y0 - 8, x1 + 8, y0, BEVEL_DARK); paint(img, x0 - 8, y0 - 8, x0, y1 + 8, BEVEL_DARK)
    paint(img, x0 - 8, y1, x1 + 8, y1 + 8, BEVEL_LIGHT); paint(img, x1, y0 - 8, x1 + 8, y1 + 8, BEVEL_LIGHT)

    page = parchment_layer(rng, y1 - y0, x1 - x0)
    page *= vignette(y1 - y0, x1 - x0, depth_px=90, strength=0.14)[:, :, None]
    img[y0:y1, x0:x1] = page

    if with_divider:
        paint(img, DIVIDER_X[0], y0 + 20, DIVIDER_X[1], y1 - 20, DIVIDER)

    return Image.fromarray(np.clip(img, 0, 255).astype(np.uint8), "RGB")


def tab_atlas(rng):
    """Three stacked 50x15 states: rest, highlighted and active (with a brass rail), in the leather
    and bevel of TomeButton so the tab belongs to the same tome as the rest."""
    idle = np.array([36.0, 28.0, 19.0])   # 0x241C13
    hover = np.array([56.0, 43.0, 27.0])  # 0x382B1B
    atlas = np.zeros((45, 50, 4))
    noise = tileable_noise(rng, 45, 50, 1.2)
    for strip, base, brass_rail in ((0, idle, False), (1, hover, False), (2, hover, True)):
        y0 = strip * 15
        block = base[None, None, :] * (1.0 + 0.08 * noise[y0:y0 + 15, :, None])
        atlas[y0:y0 + 15, :, :3] = block
        atlas[y0:y0 + 15, :, 3] = 255
        atlas[y0, :, :3] = np.array([90.0, 72.0, 48.0])          # light bevel on top
        atlas[y0 + 14, :, :3] = BEVEL_DARK                        # shadow below
        if brass_rail:
            atlas[y0 + 1:y0 + 14, 0:2, :3] = BRASS                # rail meaning "this is the active one"
    return Image.fromarray(np.clip(atlas, 0, 255).astype(np.uint8), "RGBA")


def main():
    rng = np.random.default_rng(20260824)
    sheet(rng, with_divider=True).save(OUT_DIR / "character_sheet.png")
    sheet(rng, with_divider=False).save(OUT_DIR / "character_sheet_2.png")
    sheet(rng, with_divider=False).save(OUT_DIR / "character_sheet_3.png")
    tab_atlas(rng).save(OUT_DIR / "atlas" / "imagebutton_tabbutton.png")
    print("wrote 3 sheet plates + tab atlas")


if __name__ == "__main__":
    main()
