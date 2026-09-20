# -*- coding: utf-8 -*-
"""Generates the 16x16 icons of the character sheet (roll, save, attack, damage, add, delete,
edit mode) in brass and ink, with GuiStyle's palette.

The ones that came from MCreator were a magenta d20, a green cross and a gray gear: on the parchment they
belonged neither to the mod nor to the game.

Two formatting things to respect:

  - It is drawn DIRECTLY at 16x16, with no supersampling. At this size smoothing does not help: it blurs
    a silhouette that is only 16 pixels wide. PIL polygons have hard edges, which is
    exactly what is wanted.
  - Two state rows (normal, hover), not three like the tabs. Here it is enough because
    CharacterSheetScreen.setActiveVisible and RollScrollWidget.setInactive ALWAYS turn "active" and
    "visible" off together: a disabled icon never gets drawn, so the disabled row
    is never sampled. If "active" alone is ever turned off, the third row will have to be added (see
    tools/make_tab_textures.py, where that does happen).

The "_edit" variants share a silhouette with their normal version and are told apart by being HOLLOW plus the
quill: they are the same button in the same place, alternating with edit mode, so what they have to
communicate is "this edits the usual thing", not something different.
"""
from PIL import Image, ImageDraw

INK = (42, 33, 24, 255)          # dark ink, the same as the sheet labels
BRASS = (201, 162, 39, 255)      # aged brass of GuiStyle
BRASS_HI = (235, 203, 96, 255)   # lit brass: it is the hover state
PARCHMENT = (214, 197, 160, 255) # interior of the hollow variants
NONE = (0, 0, 0, 0)


def d20(draw, fill):
    """A hexagon with the triangular face marked: the silhouette of a real d20, not a circle."""
    draw.polygon([(8, 1), (14, 4), (14, 11), (8, 14), (2, 11), (2, 4)], fill=fill, outline=INK)
    draw.polygon([(8, 4), (11, 9), (5, 9)], fill=None, outline=INK)


def shield(draw, fill):
    """Save. A shield, not a die: it is the only thing that tells it apart from a normal roll at a glance."""
    draw.polygon([(3, 2), (13, 2), (13, 8), (8, 14), (3, 8)], fill=fill, outline=INK)


def sword(draw, fill):
    """Ataque."""
    draw.polygon([(8, 1), (10, 4), (10, 10), (6, 10), (6, 4)], fill=fill, outline=INK)
    draw.rectangle([3, 10, 12, 11], fill=INK)          # guarda
    draw.rectangle([7, 12, 8, 14], fill=INK)           # empunadura


def burst(draw, fill):
    """Damage. A four-pointed star: it reads as an impact and isn't confused with the die's rhombus."""
    draw.polygon([(8, 0), (10, 6), (15, 8), (10, 10), (8, 15), (6, 10), (1, 8), (6, 6)],
                 fill=fill, outline=INK)


def plus(draw, fill):
    """ONE single cross-shaped polygon, not two overlapping rectangles: with two rectangles, the outline of
    each crosses inside the other and the icon comes out with a grid of ink in the middle."""
    draw.polygon([(6, 2), (9, 2), (9, 6), (13, 6), (13, 9), (9, 9), (9, 13), (6, 13),
                  (6, 9), (2, 9), (2, 6), (6, 6)], fill=fill, outline=INK)


#Delete, at 8x8. An explicit mask instead of draw.line: at this size two thick diagonals melt into
#a blob, and a thin one looks broken. It has no outline either — three pixels (outline, fill,
#outline) do not fit in a stroke of 8, so the whole X is the color of the state.
#The center has to be the NARROWEST point of the X. With a wide crossing (two rows of four pixels
#in a row) it stops reading as an X and reads as a bow tie.
CROSS_MASK = (
    '##....##',
    '.##..##.',
    '..####..',
    '...##...',
    '...##...',
    '..####..',
    '.##..##.',
    '##....##',
)


def cross(draw, fill):
    #At rest it is ink and not brass: the X is the sheet's only destructive action, and in brass it
    #read the same as the roll buttons. When hovered it lights up.
    color = fill if fill == BRASS_HI else INK
    for y, row in enumerate(CROSS_MASK):
        for x, pixel in enumerate(row):
            if pixel == '#':
                draw.point((x, y), fill=color)


def quill(draw, fill):
    """Edit mode. A quill: the old gray gear said 'program settings', not 'write here'."""
    draw.line([(3, 13), (11, 3)], fill=INK, width=3)
    draw.line([(4, 12), (10, 4)], fill=fill, width=1)
    draw.polygon([(11, 2), (14, 5), (11, 5)], fill=fill, outline=INK)  # plumin
    draw.point((3, 13), fill=INK)


def nib(draw):
    """'Edit' mark in the corner, for the _edit variants."""
    draw.polygon([(11, 10), (15, 14), (11, 14)], fill=BRASS, outline=INK)


def icon(shape, size, hover, hollow=False, with_nib=False):
    img = Image.new('RGBA', (size, size), NONE)
    draw = ImageDraw.Draw(img)
    #The hollow variants ALSO have to change on hover. With parchment in both states
    #(which is how it came out the first time) the button responded to nothing: it looked identical pointed at and not.
    #On hover it fills with brass — "gets inked"— and is still told apart from the roll button
    #because it keeps the quill and the brass is more muted than that one's BRASS_HI.
    if hollow:
        fill = BRASS if hover else PARCHMENT
    else:
        fill = BRASS_HI if hover else BRASS
    shape(draw, fill)
    if with_nib:
        nib(draw)
    return img


def build(shape, size=16, hollow=False, with_nib=False):
    """Dos filas apiladas: normal arriba, hover abajo (AbstractWidget.renderTexture)."""
    sheet = Image.new('RGBA', (size, size * 2), NONE)
    sheet.paste(icon(shape, size, False, hollow, with_nib), (0, 0))
    sheet.paste(icon(shape, size, True, hollow, with_nib), (0, size))
    return sheet


ICONS = {
    'imagebutton_d20':             (d20, 16, False, False),
    'imagebutton_d20_save':        (shield, 16, False, False),
    'imagebutton_d20_attack':      (sword, 16, False, False),
    'imagebutton_d20_damage':      (burst, 16, False, False),
    'imagebutton_d20_edit':        (d20, 16, True, True),
    'imagebutton_d20_save_edit':   (shield, 16, True, True),
    'imagebutton_d20_attack_edit': (sword, 16, True, True),
    'imagebutton_d20_damage_edit': (burst, 16, True, True),
    'imagebutton_add':             (plus, 16, False, False),
    'imagebutton_delete':          (cross, 8, False, False),
    'imagebutton_editmode':        (quill, 16, False, False),
}


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    for name, (shape, size, hollow, with_nib) in ICONS.items():
        build(shape, size, hollow, with_nib).save('%s/%s.png' % (out, name))
    print('%d icons generated in %s' % (len(ICONS), out))
