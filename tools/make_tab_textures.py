# -*- coding: utf-8 -*-
"""Genera las pestanas de la hoja de personaje (Principal / Habilidades / Ataques) con la paleta de
cuero y laton de GuiStyle, en vez del gris azulado de piedra que venian de MCreator.

Dos cosas que hay que saber antes de tocar estos PNG:

  - ImageButton NO tiene dos estados, tiene TRES, y los apila en vertical: normal en v=0, hover en
    v=yDiffTex, y DESHABILITADO en v=yDiffTex*2 (AbstractWidget.renderTexture). Los PNG anteriores solo
    traian dos filas, asi que la fila de deshabilitado caia fuera de la imagen.
  - Y eso importa mucho aqui, porque updateTabs() marca la pestana SELECCIONADA con active=false para
    que no se pueda pulsar la que ya estas viendo. O sea que la pestana seleccionada se dibuja siempre
    con la fila de deshabilitado — la unica que faltaba. Por eso la seleccionada se veia plana.

Asi que la fila 3 no es "apagado": es el aspecto de "pestana abierta", y es la que mas se mira.
"""
from PIL import Image, ImageDraw

# Misma paleta que GuiStyle y que make_sheet_bg.py: la hoja, sus paneles y sus pestanas son el mismo mod.
LEATHER_IDLE = (36, 28, 19)
LEATHER_HOVER = (56, 43, 27)
PARCHMENT = (214, 197, 160)   # la pestana abierta es la hoja asomando por encima del marco
BEVEL_LIGHT = (90, 72, 48)
BEVEL_DARK = (11, 9, 6)
BRASS_DIM = (107, 86, 54)
BRASS_LIT = (201, 162, 39)

W = 50


def tab(draw, y, h, fill, rail, open_tab):
    """Una fila de estado. @param open_tab True = pestana abierta: se funde con la hoja por abajo, asi
    que no lleva borde inferior — el borde es justo lo que la haria parecer un boton suelto flotando."""
    bottom = y + h - 1
    draw.rectangle([0, y, W - 1, bottom], fill=fill)

    # Bisel de Minecraft, recortado arriba: claro arriba/izquierda, oscuro derecha.
    draw.rectangle([0, y, W - 1, y], fill=rail)
    draw.rectangle([0, y + 1, W - 1, y + 1], fill=rail if open_tab else BEVEL_LIGHT)
    draw.rectangle([0, y + 2, 0, bottom], fill=BEVEL_LIGHT)
    draw.rectangle([W - 1, y + 2, W - 1, bottom], fill=BEVEL_DARK)
    if not open_tab:
        # Sombra abajo: separa la pestana cerrada del marco, que es lo que da la profundidad de "detras".
        draw.rectangle([1, bottom, W - 2, bottom], fill=BEVEL_DARK)

    # Muescas de laton en las dos esquinas superiores, el mismo motivo que las cantoneras del panel.
    for x in (2, W - 4):
        draw.rectangle([x, y + 2, x + 1, y + 3], fill=BRASS_LIT if open_tab else BRASS_DIM)


def build(h, open_row_is_parchment):
    """Tres filas de altura h: normal, hover, deshabilitado."""
    img = Image.new('RGBA', (W, h * 3), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    tab(draw, 0, h, LEATHER_IDLE, BRASS_DIM, False)
    tab(draw, h, h, LEATHER_HOVER, BRASS_LIT, False)
    # Fila 3 = seleccionada (ver cabecera). En el PNG de la pestana abierta es pergamino; en el de las
    # cerradas nunca se usa, pero tiene que existir para que el muestreo no se salga de la imagen.
    tab(draw, h * 2, h, PARCHMENT if open_row_is_parchment else LEATHER_IDLE, BRASS_LIT, open_row_is_parchment)
    return img


if __name__ == '__main__':
    import sys
    out = sys.argv[1]
    build(15, False).save(out + '/imagebutton_tabbutton.png')
    build(20, True).save(out + '/imagebutton_tabbutton_active.png')
    print('pestanas generadas en', out)
