# -*- coding: utf-8 -*-
"""Genera la plantilla de estructura que necesitan los GameTest.

    python tools/make_gametest_arena.py

Un GameTest de Minecraft no arranca sin una estructura: el framework la coloca en un mundo vacio y
ejecuta la prueba dentro. No hace falta que tenga nada, pero SI hace falta que exista, y el formato es
un .nbt binario — no se puede escribir a mano en un editor de texto, que es justo por lo que existe
este script en vez de un archivo suelto en el repo sin forma de saber que contiene.

La arena son 9x5x9 de aire sobre un suelo de piedra. Suelo y no aire a secas porque las pruebas
invocan criaturas: sin algo debajo caen fuera del area antes de que la prueba mire nada.

Salida: src/main/resources/data/dndsheets/structures/arena.nbt (reproducible, sin aleatoriedad).
"""
import gzip
import io
import os
import struct

# Etiquetas NBT que se usan aqui. El formato completo tiene 13; con estas cuatro se escribe una
# estructura entera.
END, INT, LIST, COMPOUND, STRING = 0, 3, 9, 10, 8

DATA_VERSION = 3465  # 1.20.1. Minecraft lo usa para migrar estructuras viejas; con el suyo, no migra nada.
WIDTH, HEIGHT, DEPTH = 9, 5, 9


def name(out, tag, key):
    out.write(struct.pack('>Bh', tag, len(key)))
    out.write(key.encode('utf-8'))


def int_list(out, key, values):
    name(out, LIST, key)
    out.write(struct.pack('>Bi', INT, len(values)))
    for v in values:
        out.write(struct.pack('>i', v))


def build():
    out = io.BytesIO()
    name(out, COMPOUND, '')  # La raiz de un .nbt no tiene nombre.

    int_list(out, 'size', [WIDTH, HEIGHT, DEPTH])

    # Paleta: los estados de bloque distintos que aparecen, referenciados por indice desde "blocks".
    name(out, LIST, 'palette')
    out.write(struct.pack('>Bi', COMPOUND, 1))
    name(out, STRING, 'Name')
    out.write(struct.pack('>h', len('minecraft:stone')))
    out.write(b'minecraft:stone')
    out.write(struct.pack('>B', END))

    # Solo el suelo. Todo lo que no se lista queda como estaba (aire), que es lo que se quiere arriba.
    name(out, LIST, 'blocks')
    out.write(struct.pack('>Bi', COMPOUND, WIDTH * DEPTH))
    for x in range(WIDTH):
        for z in range(DEPTH):
            name(out, INT, 'state')
            out.write(struct.pack('>i', 0))
            int_list(out, 'pos', [x, 0, z])
            out.write(struct.pack('>B', END))

    # Vacia, pero tiene que estar: Minecraft la lee sin comprobar si existe.
    name(out, LIST, 'entities')
    out.write(struct.pack('>Bi', COMPOUND, 0))

    name(out, INT, 'DataVersion')
    out.write(struct.pack('>i', DATA_VERSION))

    out.write(struct.pack('>B', END))
    return out.getvalue()


path = os.path.join('src', 'main', 'resources', 'data', 'dndsheets', 'structures', 'arena.nbt')
os.makedirs(os.path.dirname(path), exist_ok=True)
# mtime=0: el gzip lleva marca de tiempo en la cabecera, y sin fijarla el archivo cambia en cada
# ejecucion aunque el contenido sea identico — un diff distinto cada vez, por nada.
with open(path, 'wb') as fh:
    with gzip.GzipFile(fileobj=fh, mode='wb', mtime=0) as gz:
        gz.write(build())
print('escrito %s (%d bytes)' % (path, os.path.getsize(path)))
