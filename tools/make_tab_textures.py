# -*- coding: utf-8 -*-
"""Generates the character sheet tabs (Main / Skills / Attacks) with GuiStyle's leather
and brass palette, instead of the blue-gray stone that came from MCreator.

Two things to know before touching these PNGs:

  - ImageButton does NOT have two states, it has THREE, and stacks them vertically: normal at v=0, hover at
    v=yDiffTex, and DISABLED at v=yDiffTex*2 (AbstractWidget.renderTexture). The previous PNGs only
    had two rows, so the disabled row fell outside the image.
  - And that matters a lot here, because updateTabs() marks the SELECTED tab with active=false so
    the one you are already looking at cannot be clicked. So the selected tab is always drawn
    with the disabled row — the only one that was missing. That is why the selected one looked flat.

So row 3 is not "off": it is the look of the "open tab", and it is the one looked at the most.
"""
from PIL import Image, ImageDraw

# The same palette as GuiStyle and make_sheet_bg.py: the sheet, its panels and its tabs are the same mod.
LEATHER_IDLE = (36, 28, 19)
LEATHER_HOVER = (56, 43, 27)
PARCHMENT = (214, 197, 160)   # the open tab is the sheet peeking over the frame
BEVEL_LIGHT = (90, 72, 48)
BEVEL_DARK = (11, 9, 6)
BRASS_DIM = (107, 86, 54)
BRASS_LIT = (201, 162, 39)

W = 50


def tab(draw, y, h, fill, rail, open_tab):
    """One state row. @param open_tab True = open tab: it merges with the sheet below, so
    it has no bottom border — the border is exactly what would make it look like a loose floating button."""
    bottom = y + h - 1
    draw.rectangle([0, y, W - 1, bottom], fill=fill)

    # Bisel de Minecraft, recortado arriba: claro arriba/izquierda, oscuro derecha.
    draw.rectangle([0, y, W - 1, y], fill=rail)
    draw.rectangle([0, y + 1, W - 1, y + 1], fill=rail if open_tab else BEVEL_LIGHT)
    draw.rectangle([0, y + 2, 0, bottom], fill=BEVEL_LIGHT)
    draw.rectangle([W - 1, y + 2, W - 1, bottom], fill=BEVEL_DARK)
    if not open_tab:
        # Shadow below: it separates the closed tab from the frame, which is what gives the "behind" depth.
        draw.rectangle([1, bottom, W - 2, bottom], fill=BEVEL_DARK)

    # Brass notches on the two top corners, the same motif as the panel's corner pieces.
    for x in (2, W - 4):
        draw.rectangle([x, y + 2, x + 1, y + 3], fill=BRASS_LIT if open_tab else BRASS_DIM)


def build(h, open_row_is_parchment):
    """Three rows of height h: normal, hover, disabled."""
    img = Image.new('RGBA', (W, h * 3), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    tab(draw, 0, h, LEATHER_IDLE, BRASS_DIM, False)
    tab(draw, h, h, LEATHER_HOVER, BRASS_LIT, False)
    # Row 3 = selected (see header). In the open tab's PNG it is parchment; in the closed
    # ones it is never used, but it has to exist so sampling does not go off the image.
    tab(draw, h * 2, h, PARCHMENT if open_row_is_parchment else LEATHER_IDLE, BRASS_LIT, open_row_is_parchment)
    return img


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    build(15, False).save(out + '/imagebutton_tabbutton.png')
    build(20, True).save(out + '/imagebutton_tabbutton_active.png')
    print('pestanas generadas en', out)
