# -*- coding: utf-8 -*-
"""Genera los seis iconos de caracteristica de la hoja (16x16).

Los de MCreator eran dibujos de colores saturados —un brazo rojo, un conejo blanco, un cerebro azul, un
buho— con estilos distintos entre si: no pertenecian a la paleta de cuero y laton del resto del mod.

La tentacion al tematizarlos era pasarlos todos a laton, como los iconos de tirada. Eso habria sido un
error: son lo UNICO que identifica cada fila del panel lateral, y seis siluetas del mismo color se
convierten en seis manchas parecidas. Lo que hace que se lean rapido es el color, no la forma.

Asi que se conserva un color por caracteristica, pero como pigmentos de manuscrito: todos apagados al
mismo nivel y todos con el MISMO contorno de tinta que el resto de la hoja. Es lo que convierte seis
dibujos sueltos en un juego: contorno comun, saturacion comun, tono e identidad distintos.

Siluetas elegidas por legibilidad a 16 px, no por ser las mas evocadoras:

  FUE  brazo flexionado    CON  corazon        SAB  ojo
  DES  flecha              INT  libro abierto  CAR  corona

La flecha en vez de una pluma, y el ojo en vez de un buho, porque a este tamano una pluma se confunde con
el icono de modo edicion (que ya es una) y un buho es una mancha redonda.
"""
from PIL import Image, ImageDraw

INK = (42, 33, 24, 255)      # la misma tinta que las etiquetas de la hoja
NONE = (0, 0, 0, 0)

# Pigmentos apagados, no colores puros: sobre pergamino un rojo saturado grita y rompe el conjunto.
# El de Carisma es berenjena y no dorado a proposito — el laton ya significa "pulsable" en esta interfaz.
OXIDE = (166, 67, 44, 255)      # FUE
VERDIGRIS = (75, 122, 82, 255)  # DES
BURGUNDY = (138, 42, 58, 255)   # CON
LAPIS = (53, 84, 127, 255)      # INT
TEAL = (47, 107, 107, 255)      # SAB
AUBERGINE = (99, 62, 116, 255)  # CAR


#FUE. Brazo flexionado, a mascara y no a poligono: con un poligono salia un arco simetrico, porque lo que
#distingue un brazo de una herradura son dos detalles asimetricos —el punho arriba a la derecha y el bulto
#del biceps abajo a la izquierda— y esos hay que ponerlos pixel a pixel.
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
    """Dibuja una mascara y le pone contorno de tinta en todo pixel vacio que toque el relleno."""
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
    """DES. Flecha en diagonal: direccion y punta, que a 16 px se leen mejor que una figura corriendo."""
    draw.polygon([(14, 2), (14, 7), (11, 5)], fill=fill, outline=INK)   # punta
    draw.line([(3, 13), (13, 3)], fill=INK, width=3)
    draw.line([(4, 12), (12, 4)], fill=fill, width=1)
    draw.polygon([(2, 14), (2, 10), (6, 14)], fill=fill, outline=INK)   # plumas


def heart(draw, fill):
    """CON."""
    draw.polygon([(8, 14), (2, 8), (2, 5), (4, 3), (8, 5), (12, 3), (14, 5), (14, 8)], fill=fill, outline=INK)


def book(draw, fill):
    """INT. Libro abierto, con el lomo marcado para que no parezca un rectangulo."""
    draw.polygon([(1, 4), (7, 6), (7, 14), (1, 12)], fill=fill, outline=INK)
    draw.polygon([(15, 4), (9, 6), (9, 14), (15, 12)], fill=fill, outline=INK)
    draw.line([(8, 6), (8, 14)], fill=INK, width=1)


def eye(draw, fill):
    """SAB. Ojo: la pupila en tinta le da un centro, que es lo que evita que se lea como una hoja."""
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
    print('%d iconos de caracteristica generados en %s' % (len(ICONS), out))
