# -*- coding: utf-8 -*-
"""Genera los iconos de 16x16 de la hoja de personaje (tirar, salvacion, ataque, dano, anadir, borrar,
modo edicion) en laton y tinta, con la paleta de GuiStyle.

Los que venian de MCreator eran d20 magenta, una cruz verde y un engranaje gris: sobre el pergamino no
pertenecian ni al mod ni al juego.

Dos cosas de formato que hay que respetar:

  - Se dibuja DIRECTAMENTE a 16x16, sin supersampling. A este tamano el suavizado no ayuda: emborrona
    una silueta que solo tiene 16 pixeles de ancho. Los poligonos de PIL son de borde duro, que es
    justo lo que se quiere.
  - Dos filas de estado (normal, hover), no tres como las pestanas. Aqui basta porque
    CharacterSheetScreen.setActiveVisible y RollScrollWidget.setInactive apagan SIEMPRE "active" y
    "visible" a la vez: un icono deshabilitado no llega a dibujarse, asi que la fila de deshabilitado
    nunca se muestrea. Si alguna vez se apaga solo "active", habra que anadir la tercera fila (ver
    tools/make_tab_textures.py, donde eso si pasa).

Las variantes "_edit" comparten silueta con su version normal y se distinguen por ser HUECAS mas la
plumilla: son el mismo boton en el mismo sitio, alternando con el modo edicion, asi que lo que tienen que
comunicar es "esto edita lo de siempre", no una cosa distinta.
"""
from PIL import Image, ImageDraw

INK = (42, 33, 24, 255)          # tinta oscura, la misma de las etiquetas de la hoja
BRASS = (201, 162, 39, 255)      # laton envejecido de GuiStyle
BRASS_HI = (235, 203, 96, 255)   # laton encendido: es el estado hover
PARCHMENT = (214, 197, 160, 255) # interior de las variantes huecas
NONE = (0, 0, 0, 0)


def d20(draw, fill):
    """Hexagono con la cara triangular marcada: la silueta de un d20 de verdad, no un circulo."""
    draw.polygon([(8, 1), (14, 4), (14, 11), (8, 14), (2, 11), (2, 4)], fill=fill, outline=INK)
    draw.polygon([(8, 4), (11, 9), (5, 9)], fill=None, outline=INK)


def shield(draw, fill):
    """Salvacion. Escudo, no un dado: es lo unico que la distingue de una tirada normal de un vistazo."""
    draw.polygon([(3, 2), (13, 2), (13, 8), (8, 14), (3, 8)], fill=fill, outline=INK)


def sword(draw, fill):
    """Ataque."""
    draw.polygon([(8, 1), (10, 4), (10, 10), (6, 10), (6, 4)], fill=fill, outline=INK)
    draw.rectangle([3, 10, 12, 11], fill=INK)          # guarda
    draw.rectangle([7, 12, 8, 14], fill=INK)           # empunadura


def burst(draw, fill):
    """Dano. Estrella de cuatro puntas: se lee como impacto y no se confunde con el rombo del dado."""
    draw.polygon([(8, 0), (10, 6), (15, 8), (10, 10), (8, 15), (6, 10), (1, 8), (6, 6)],
                 fill=fill, outline=INK)


def plus(draw, fill):
    """UN solo poligono en cruz, no dos rectangulos superpuestos: con dos rectangulos, el contorno de
    cada uno cruza por dentro del otro y el icono sale con una reja de tinta en medio."""
    draw.polygon([(6, 2), (9, 2), (9, 6), (13, 6), (13, 9), (9, 9), (9, 13), (6, 13),
                  (6, 9), (2, 9), (2, 6), (6, 6)], fill=fill, outline=INK)


#Borrar, a 8x8. Mascara explicita en vez de draw.line: a este tamano dos diagonales gruesas se funden en
#una mancha, y una fina se ve rota. Tampoco lleva contorno — no caben tres pixeles (contorno, relleno,
#contorno) en un trazo de 8, asi que el aspa entera es del color del estado.
#El centro tiene que ser el punto MAS ESTRECHO del aspa. Con el cruce ancho (dos filas de cuatro pixeles
#seguidos) deja de leerse como una equis y se lee como una pajarita.
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
    #En reposo va en tinta y no en laton: el aspa es la unica accion destructiva de la hoja, y en laton se
    #leia igual que los botones de tirar. Al pasar por encima se enciende.
    color = fill if fill == BRASS_HI else INK
    for y, row in enumerate(CROSS_MASK):
        for x, pixel in enumerate(row):
            if pixel == '#':
                draw.point((x, y), fill=color)


def quill(draw, fill):
    """Modo edicion. Pluma: el engranaje gris de antes decia 'ajustes del programa', no 'escribe aqui'."""
    draw.line([(3, 13), (11, 3)], fill=INK, width=3)
    draw.line([(4, 12), (10, 4)], fill=fill, width=1)
    draw.polygon([(11, 2), (14, 5), (11, 5)], fill=fill, outline=INK)  # plumin
    draw.point((3, 13), fill=INK)


def nib(draw):
    """Marca de 'editar' en la esquina, para las variantes _edit."""
    draw.polygon([(11, 10), (15, 14), (11, 14)], fill=BRASS, outline=INK)


def icon(shape, size, hover, hollow=False, with_nib=False):
    img = Image.new('RGBA', (size, size), NONE)
    draw = ImageDraw.Draw(img)
    #Las variantes huecas TAMBIEN tienen que cambiar al pasar por encima. Con pergamino en los dos estados
    #(que es como salio a la primera) el boton no respondia a nada: se veia identico apuntado y sin apuntar.
    #Al pasar por encima se rellena de laton — "se entinta"— y sigue distinguiendose del boton de tirar
    #porque conserva la plumilla y el laton es mas apagado que el BRASS_HI de aquel.
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
    print('%d iconos generados en %s' % (len(ICONS), out))
