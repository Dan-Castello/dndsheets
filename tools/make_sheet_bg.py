# -*- coding: utf-8 -*-
"""Genera los fondos de la hoja de personaje: pergamino envejecido dentro de un marco de cuero y laton
con el biselado de Minecraft.

Decisiones que importan:

  - SIN texto horneado. Las etiquetas (NAME, RACE, ABILITIES...) las dibuja el codigo desde las claves
    de traduccion, asi que tenerlas tambien en el PNG las duplicaba Y las dejaba en ingles para siempre.
  - SIN casillas dibujadas. Los EditBox de Minecraft ya pintan su propio marco encima; las casillas del
    fondo eran redundantes, y ademas obligaban a que el dibujo cuadrara al pixel con unas coordenadas
    que solo viven en el codigo. Quitandolas, no hay nada que alinear y no hay nada que se pueda
    desalinear en el futuro.
  - Se dibuja a 4x (1592x1152) del tamano logico (398x288) con el que se hace el blit: Minecraft lo
    reduce, y ese supersampling es lo que evita que el grano se vea a bloques con GUI Scale alto.
"""
import math
import random
from PIL import Image, ImageDraw, ImageFilter

LOGICAL_W, LOGICAL_H = 398, 288
SCALE = 4
W, H = LOGICAL_W * SCALE, LOGICAL_H * SCALE

# Misma paleta que GuiStyle, para que la hoja y los paneles del resto del mod se lean como el mismo mod.
LEATHER      = (26, 20, 14)
LEATHER_EDGE = (11, 9, 6)
BEVEL_LIGHT  = (107, 86, 54)
BRASS        = (201, 162, 39)
PARCHMENT    = (214, 197, 160)
PARCHMENT_HI = (228, 213, 180)
INK_SHADOW   = (150, 132, 100)

FRAME = 10 * SCALE          # ancho del marco de cuero
random.seed(20240815)       # reproducible: regenerar no debe dar un fondo distinto


def parchment_field(w, h):
    """Pergamino con vetas: ruido suave a dos escalas, no ruido blanco — el ruido por pixel se ve
    sucio al reducir, y las vetas anchas sobreviven al downscale que hace Minecraft."""
    base = Image.new('RGB', (w, h), PARCHMENT)
    coarse = Image.new('L', (w // 16, h // 16))
    coarse.putdata([random.randint(96, 160) for _ in range(coarse.width * coarse.height)])
    coarse = coarse.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(6))

    fine = Image.new('L', (w // 4, h // 4))
    fine.putdata([random.randint(110, 145) for _ in range(fine.width * fine.height)])
    fine = fine.resize((w, h), Image.BICUBIC).filter(ImageFilter.GaussianBlur(2))

    # Mezcla continua y NO umbral: recortar el ruido en dos tonos daba manchas de camuflaje. El pergamino
    # tiene variacion suave y de poco contraste — comprimir el rango a +-8 niveles alrededor del medio es
    # lo que lo convierte en fibra en vez de en mapa topografico. Ademas asi sobrevive al downscale.
    grain = Image.blend(coarse, fine, 0.45).point(lambda v: 120 + (v - 128) // 8)
    light = Image.new('RGB', (w, h), PARCHMENT_HI)
    return Image.composite(light, base, grain).filter(ImageFilter.GaussianBlur(3))


def aged_edges(img, w, h):
    """Oscurecido hacia los bordes: es lo que hace que el pergamino parezca una hoja usada y no un
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
    """El bisel de Minecraft: claro arriba e izquierda, oscuro abajo y derecha. Es la gramatica visual
    que hace que un panel pertenezca al juego en vez de parecer una ventana pegada encima."""
    draw.rectangle([x0, y0, x1, y0 + thickness], fill=light)
    draw.rectangle([x0, y0, x0 + thickness, y1], fill=light)
    draw.rectangle([x0, y1 - thickness, x1, y1], fill=dark)
    draw.rectangle([x1 - thickness, y0, x1, y1], fill=dark)


def brass_corners(draw, x0, y0, x1, y1):
    """Cantoneras de laton, como las de un tomo encuadernado. Mismo motivo que dibuja GuiStyle para los
    paneles planos, para que la hoja y el resto del mod compartan identidad."""
    arm, thick = 22 * SCALE, 3 * SCALE
    for (cx, cy, dx, dy) in ((x0, y0, 1, 1), (x1, y0, -1, 1), (x0, y1, 1, -1), (x1, y1, -1, -1)):
        ax, ay = cx + dx * arm, cy + dy * thick
        draw.rectangle(sorted_box(cx, cy, ax, ay), fill=BRASS)
        ax, ay = cx + dx * thick, cy + dy * arm
        draw.rectangle(sorted_box(cx, cy, ax, ay), fill=BRASS)


def sorted_box(x0, y0, x1, y1):
    return [min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1)]


def build(divider_x_logical=None):
    """@param divider_x_logical columna donde va el filete vertical que separa la banda de
    caracteristicas del cuerpo de la hoja. None = sin filete (pestanas de habilidades y ataques)."""
    img = Image.new('RGB', (W, H), LEATHER)
    draw = ImageDraw.Draw(img)

    # Marco de cuero con bisel.
    draw.rectangle([0, 0, W - 1, H - 1], fill=LEATHER_EDGE)
    draw.rectangle([SCALE, SCALE, W - 1 - SCALE, H - 1 - SCALE], fill=LEATHER)
    bevel(draw, SCALE, SCALE, W - 1 - SCALE, H - 1 - SCALE, BEVEL_LIGHT, LEATHER_EDGE, 2 * SCALE)

    # Hoja de pergamino encajada dentro del marco, con su propio bisel hacia dentro (hundida).
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


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    # La pestana principal lleva el filete: separa la columna de caracteristicas del resto. Las otras dos
    # no, porque su contenido ocupa el ancho entero.
    build(divider_x_logical=138).save(out + '/character_sheet.png')
    build().save(out + '/character_sheet_2.png')
    build().save(out + '/character_sheet_3.png')
    print('fondos generados en', out)
