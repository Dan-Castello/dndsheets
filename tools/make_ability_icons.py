# -*- coding: utf-8 -*-
"""Generates the sheet's six ability icons (16x16).

The MCreator ones were drawings in saturated colors —a red arm, a white rabbit, a blue brain, an
owl— with styles different from each other: they did not belong to the leather-and-brass palette of the rest of the mod.

The temptation when theming them was to turn them all brass, like the roll icons. That would have been a
mistake: they are the ONLY thing that identifies each row of the side panel, and six silhouettes of the same color
become six similar blobs. What makes them read quickly is the color, not the shape.

So one color per ability is kept, but as manuscript pigments: all muted to the
same level and all with the SAME ink outline as the rest of the sheet. It is what turns six
loose drawings into a set: common outline, common saturation, distinct hue and identity.

Silhouettes chosen for legibility at 16 px, not for being the most evocative:

  STR  flexed arm          CON  heart          WIS  eye
  DEX  arrow               INT  open book      CHA  crown

The arrow instead of a feather, and the eye instead of an owl, because at this size a feather is confused with
the edit-mode icon (which already is one) and an owl is a round blob.
"""
from PIL import Image, ImageDraw

INK = (42, 33, 24, 255)      # the same ink as the sheet labels
NONE = (0, 0, 0, 0)

# Muted pigments, not pure colors: on parchment a saturated red shouts and breaks the set.
# Charisma's is eggplant and not gold on purpose — brass already means "clickable" in this interface.
OXIDE = (166, 67, 44, 255)      # FUE
VERDIGRIS = (75, 122, 82, 255)  # DES
BURGUNDY = (138, 42, 58, 255)   # CON
LAPIS = (53, 84, 127, 255)      # INT
TEAL = (47, 107, 107, 255)      # SAB
AUBERGINE = (99, 62, 116, 255)  # CAR


#STR. A flexed arm, by mask and not by polygon: with a polygon it came out a symmetric arc, because what
#tells an arm from a horseshoe is two asymmetric details —the fist at the top right and the bulge
#of the biceps at the bottom left— and those have to be placed pixel by pixel.
ARM_MASK = (
    '................',
    '..........####..',
    '.........######.',
    '.........######.',
    '.........######.',
    '..........####..',
    '....##....####..',
    '...####...####..',
    '..######..####..',
    '..#######.####..',
    '..############..',
    '..############..',
    '..###########...',
    '...#########....',
    '....#######.....',
    '................',
)


def mask_shape(mask):
    """Draws a mask and gives it an ink outline on every empty pixel that touches the fill."""
    def draw_it(draw, fill):
        lleno = {(x, y) for y, fila in enumerate(mask) for x, c in enumerate(fila) if c == '#'}
        for (x, y) in lleno:
            draw.point((x, y), fill=fill)
        for (x, y) in lleno:
            for dx, dy in ((1,0), (-1,0), (0,1), (0,-1), (1,1), (1,-1), (-1,1), (-1,-1)):
                vecino = (x + dx, y + dy)
                if vecino not in lleno and 0 <= vecino[0] < 16 and 0 <= vecino[1] < 16:
                    draw.point(vecino, fill=INK)
    return draw_it


arm = mask_shape(ARM_MASK)


def arrow(draw, fill):
    """DEX. A diagonal arrow: direction and tip, which at 16 px read better than a running figure."""
    draw.polygon([(14, 2), (14, 7), (11, 5)], fill=fill, outline=INK)   # punta
    draw.line([(3, 13), (13, 3)], fill=INK, width=3)
    draw.line([(4, 12), (12, 4)], fill=fill, width=1)
    draw.polygon([(2, 14), (2, 10), (6, 14)], fill=fill, outline=INK)   # plumas


def heart(draw, fill):
    """CON."""
    draw.polygon([(8, 14), (2, 8), (2, 5), (4, 3), (8, 5), (12, 3), (14, 5), (14, 8)], fill=fill, outline=INK)


def book(draw, fill):
    """INT. An open book, with the spine marked so it doesn't look like a rectangle."""
    draw.polygon([(1, 4), (7, 6), (7, 14), (1, 12)], fill=fill, outline=INK)
    draw.polygon([(15, 4), (9, 6), (9, 14), (15, 12)], fill=fill, outline=INK)
    draw.line([(8, 6), (8, 14)], fill=INK, width=1)


def eye(draw, fill):
    """WIS. An eye: the ink pupil gives it a center, which is what keeps it from reading as a leaf."""
    draw.polygon([(1, 8), (5, 4), (11, 4), (15, 8), (11, 12), (5, 12)], fill=fill, outline=INK)
    draw.ellipse([6, 6, 10, 10], fill=INK)


def crown(draw, fill):
    """CAR."""
    draw.polygon([(2, 12), (2, 4), (5, 8), (8, 3), (11, 8), (14, 4), (14, 12)], fill=fill, outline=INK)
    draw.rectangle([2, 12, 14, 13], fill=INK)


ICONS = {
    'str':  (arm, OXIDE),
    'dex':  (arrow, VERDIGRIS),
    'cons': (heart, BURGUNDY),
    'int':  (book, LAPIS),
    'wis':  (eye, TEAL),
    'cha':  (crown, AUBERGINE),
}


def build(shape, fill):
    img = Image.new('RGBA', (16, 16), NONE)
    shape(ImageDraw.Draw(img), fill)
    return img


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    for name, (shape, fill) in ICONS.items():
        build(shape, fill).save('%s/%s.png' % (out, name))
    print('%d ability icons generated in %s' % (len(ICONS), out))
