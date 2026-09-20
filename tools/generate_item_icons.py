# -*- coding: utf-8 -*-
"""Draws the mod's 16x16 item icons.

The textures are the result; THIS file is the source. It is kept in the repo for that reason: an icon is
touched up by changing its function here and running again, not by opening a PNG in an editor nobody
has installed. Original, hand-made art: the mod cannot redistribute third-party art.

    python tools/generate_item_icons.py

A single palette for all twenty, so they read as a family: parchment and ink as the base, and one color
por papel (rojo furia, oro divino, verde naturaleza, azul arcano, violeta metamagia, acero defensa).
"""
import os
import io
import struct
import zlib

OUT = os.path.join('src', 'main', 'resources', 'assets', 'dndsheets', 'textures', 'item')

C = {
    'K': (26, 20, 16, 255),      # tinta
    'k': (60, 48, 38, 255),      # tinta suave
    'W': (242, 236, 224, 255),   # luz
    'P': (217, 201, 163, 255),   # pergamino
    'p': (168, 148, 107, 255),   # parchment shadow
    'R': (192, 58, 43, 255),     # rojo
    'r': (125, 33, 24, 255),
    'G': (232, 177, 58, 255),    # oro
    'g': (156, 111, 26, 255),
    'B': (58, 110, 192, 255),    # azul
    'b': (29, 63, 125, 255),
    'V': (138, 79, 192, 255),    # violeta
    'v': (79, 42, 117, 255),
    'N': (79, 155, 70, 255),     # verde
    'n': (44, 95, 42, 255),
    'S': (185, 192, 200, 255),   # acero
    's': (108, 117, 126, 255),
    'M': (138, 90, 43, 255),     # madera
    'm': (90, 58, 26, 255),
    'C': (79, 208, 192, 255),    # cian
}


class Canvas:
    def __init__(self):
        self.px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]

    def set(self, x, y, c):
        if 0 <= x < 16 and 0 <= y < 16:
            self.px[y][x] = C[c] if isinstance(c, str) else c

    def rows(self, art, dx=0, dy=0):
        """Paints an ASCII grid; the dot is transparent."""
        for y, row in enumerate(art):
            for x, ch in enumerate(row):
                if ch != '.':
                    self.set(x + dx, y + dy, ch)

    def png(self, path):
        raw = b''
        for row in self.px:
            raw += b'\x00' + b''.join(struct.pack('BBBB', *p) for p in row)

        def chunk(tag, data):
            return (struct.pack('>I', len(data)) + tag + data
                    + struct.pack('>I', zlib.crc32(tag + data) & 0xffffffff))

        png = (b'\x89PNG\r\n\x1a\n'
               + chunk(b'IHDR', struct.pack('>IIBBBBB', 16, 16, 8, 6, 0, 0, 0))
               + chunk(b'IDAT', zlib.compress(raw, 9))
               + chunk(b'IEND', b''))
        with open(path, 'wb') as f:
            f.write(png)


# Each icon is 16 rows of 16 characters. A thick silhouette and a single stroke of color:
# at 16x16 and in a hotbar, detail is not seen — shape is.
ICONS = {
 'token': [
  '................',
  '......KKKK......',
  '....KKPPPPKK....',
  '..KKPPPPPPPPKK..',
  '.KPPPPPPPPPPPPK.',
  '.KPPPKKKKKKPPPK.',
  '.KPPKGGGGGGKPPK.',
  '.KPKGGKGGKGGKPK.',
  '.KPKGGKGGKGGKPK.',
  '.KPKGGGGGGGGKPK.',
  '.KPPKGGGGGGKPPK.',
  '.KPPPKKKKKKPPPK.',
  '..KPPPPPPPPPPK..',
  '...KKPPPPPPKK...',
  '.....KKPPKK.....',
  '.......KK.......',
 ],
 'dm_wand': [
  '..............G.',
  '.............GWG',
  '............GWWG',
  '...........KGWG.',
  '..........KmMK..',
  '.........KmMK...',
  '........KmMK....',
  '.......KmMK.....',
  '......KmMK......',
  '.....KmMK.......',
  '....KmMK........',
  '...KmMK.........',
  '..KmMK..........',
  '..KMK...........',
  '..KK............',
  '................',
 ],
 'move_wand': [
  '..............C.',
  '.............CWC',
  '............CWWC',
  '...........KCWC.',
  '..........KmMK..',
  '.........KmMK...',
  '........KmMK....',
  '.......KmMK.....',
  '......KmMK......',
  '.....KmMK.......',
  '....KmMK........',
  '...KmMK.........',
  '..KmMK..........',
  '..KMK...........',
  '..KK............',
  '................',
 ],
 'rest_kit': [
  '................',
  '................',
  '....KKKKKKKK....',
  '...KPPPPPPPPK...',
  '..KPWWPPPPWWPK..',
  '..KPPPPPPPPPPK..',
  '..KRRRRRRRRRRK..',
  '..KPPPPPPPPPPK..',
  '..KPPPPPPPPPPK..',
  '..KRRRRRRRRRRK..',
  '..KPPPPPPPPPPK..',
  '...KPPPPPPPPK...',
  '....KKKKKKKK....',
  '................',
  '................',
  '................',
 ],
 'turn_next': [
  '................',
  '..KKK.......KKK.',
  '..KGKK......KGK.',
  '..KGGKK.....KGK.',
  '..KGGGKK....KGK.',
  '..KGGGGKK...KGK.',
  '..KGGGGGKK..KGK.',
  '..KGGGGGGKK.KGK.',
  '..KGGGGGGKK.KGK.',
  '..KGGGGGKK..KGK.',
  '..KGGGGKK...KGK.',
  '..KGGGKK....KGK.',
  '..KGGKK.....KGK.',
  '..KGKK......KGK.',
  '..KKK.......KKK.',
  '................',
 ],
 'turn_undo': [
  '................',
  '.KKK.......KKK..',
  '.KSK......KKSK..',
  '.KSK.....KKSSK..',
  '.KSK....KKSSSK..',
  '.KSK...KKSSSSK..',
  '.KSK..KKSSSSSK..',
  '.KSK.KKSSSSSSK..',
  '.KSK.KKSSSSSSK..',
  '.KSK..KKSSSSSK..',
  '.KSK...KKSSSSK..',
  '.KSK....KKSSSK..',
  '.KSK.....KKSSK..',
  '.KSK......KKSK..',
  '.KKK.......KKK..',
  '................',
 ],
 'turn_actions': [
  '................',
  '..KKKKKKKKKKKK..',
  '..KPPPPPPPPPPK..',
  '..KPKKKKKKKKPK..',
  '..KPPPPPPPPPPK..',
  '..KPGGGGGGGGPK..',
  '..KPPPPPPPPPPK..',
  '..KPGGGGGGGGPK..',
  '..KPPPPPPPPPPK..',
  '..KPGGGGGGGGPK..',
  '..KPPPPPPPPPPK..',
  '..KPKKKKKKKKPK..',
  '..KPPPPPPPPPPK..',
  '..KKKKKKKKKKKK..',
  '................',
  '................',
 ],
 'rage': [
  '................',
  '....KKKKKKK.....',
  '...KRRRRRRRK....',
  '..KRrRRRRRrRK...',
  '..KRRKRRRKRRK...',
  '..KRRRRRRRRRK...',
  '..KRRKKKKKRRK...',
  '..KRKWWWWWKRK...',
  '..KRKWKKKWKRK...',
  '...KRWWWWWRK....',
  '....KRRRRRK.....',
  '.....KRRRK......',
  '......KRK.......',
  '.......K........',
  '................',
  '................',
 ],
 'second_wind': [
  '................',
  '...KKK...KKK....',
  '..KRRRK.KRRRK...',
  '.KRWRRRKRRRRRK..',
  '.KRWRRRRRRRRRK..',
  '.KRRRRRRRRRRRK..',
  '..KRRRRRRRRRK...',
  '...KRRRRRRRK....',
  '....KRRRRRK.....',
  '.....KRRRK......',
  '......KRK.......',
  '.......K........',
  '..KWK...KWK.....',
  '.KW.KWKW..KWK...',
  '................',
  '................',
 ],
 'inspiration': [
  '................',
  '..........KKKK..',
  '........KKGGGGK.',
  '......KKGGGGGGK.',
  '....KKGGGGGGGK..',
  '...KGGGGKKGGK...',
  '..KGGGGK.KGK....',
  '..KGGGK..KK.....',
  '.KGGGK..........',
  '.KGGK...........',
  '.KGGK...........',
  '.KGGK...........',
  '..KGGK..........',
  '...KGGKK........',
  '....KKGGKK......',
  '......KKKK......',
 ],
 'wild_shape': [
  '................',
  '..KK...KK...KK..',
  '.KNNK.KNNK.KNNK.',
  '.KNNK.KNNK.KNNK.',
  '..KK...KK...KK..',
  '................',
  '.....KKKKKK.....',
  '...KKNNNNNNKK...',
  '..KNNNNNNNNNNK..',
  '..KNNNNNNNNNNK..',
  '..KNNNNNNNNNNK..',
  '..KNNNNNNNNNNK..',
  '...KNNNNNNNNK...',
  '....KKNNNNKK....',
  '......KKKK......',
  '................',
 ],
 'twinned': [
  '................',
  '....KK..........',
  '...KVVK.........',
  '..KVVVVK........',
  '.KVVVVVVK.KK....',
  '..KVVVVK.KVVK...',
  '...KVVK.KVVVVK..',
  '....KK.KVVVVVVK.',
  '........KVVVVK..',
  '.........KVVK...',
  '..........KK....',
  '................',
  '....KWK..KWK....',
  '.....K....K.....',
  '................',
  '................',
 ],
 'smite': [
  '.......KK.......',
  '......KGGK......',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '..KKKKGWWGKKKK..',
  '..KGGGGWWGGGGK..',
  '..KKKKGWWGKKKK..',
  '.....KGWWGK.....',
  '......KGGK......',
  '.......KK.......',
  '...W....W....W..',
  '................',
  '................',
 ],
 'hunters_mark': [
  '.......KK.......',
  '.....KKNNKK.....',
  '...KKNNNNNNKK...',
  '..KNNKKKKKKNNK..',
  '..KNK......KNK..',
  '.KNK...KK...KNK.',
  '.KNK..KNNK..KNK.',
  'KNK..KNNNNK..KNK',
  'KNK..KNNNNK..KNK',
  '.KNK..KNNK..KNK.',
  '.KNK...KK...KNK.',
  '..KNK......KNK..',
  '..KNNKKKKKKNNK..',
  '...KKNNNNNNKK...',
  '.....KKNNKK.....',
  '.......KK.......',
 ],
 'shield': [
  '................',
  '..KKKKKKKKKKK...',
  '..KSSSSSSSSSK...',
  '..KSWSSSSSWSK...',
  '..KSSSSBSSSSK...',
  '..KSSSBBBSSSK...',
  '..KSSBBBBBSSK...',
  '..KSSSBBBSSSK...',
  '..KSSSSBSSSSK...',
  '..KSSSSSSSSSK...',
  '...KSSSSSSSK....',
  '....KSSSSSK.....',
  '.....KSSSK......',
  '......KSK.......',
  '.......K........',
  '................',
 ],
 'counterspell': [
  '................',
  '.....KKKKK......',
  '...KKBBBBBKK....',
  '..KBBBBBBBBBK...',
  '..KBBKKKKKBBK...',
  '.KBBK.....KBBK..',
  '.KBBK.....KBBK..',
  '.KBBK.....KBBK..',
  '.KBBK.....KBBK..',
  '..KBBKKKKKBBK...',
  '..KBBBBBBBBBK...',
  '...KKBBBBBKK....',
  '.....KKKKK......',
  '................',
  '..KRRRRRRRRRK...',
  '..KRRRRRRRRRK...',
 ],
 'turn_undead': [
  '.......KK.......',
  '.....KKGGKK.....',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '..KKKKGWWGKKKK..',
  '.KGGGGGWWGGGGGK.',
  '.KGWWWWWWWWWWGK.',
  '.KGGGGGWWGGGGGK.',
  '..KKKKGWWGKKKK..',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '.....KGWWGK.....',
  '......KGGK......',
  '.......KK.......',
  '..W..........W..',
  '................',
 ],
 'help': [
  '................',
  '....KK..KK......',
  '...KNNKKNNK.....',
  '...KNNKNNNK.KK..',
  '...KNNKNNNKKNNK.',
  '..KKNNKNNNKNNNK.',
  '.KNNNNNNNNNNNNK.',
  '.KNNNNNNNNNNNNK.',
  '.KNNNNNNNNNNNNK.',
  '..KNNNNNNNNNNK..',
  '...KNNNNNNNNK...',
  '....KNNNNNNK....',
  '.....KNNNNK.....',
  '.....KNNNNK.....',
  '......KKKK......',
  '................',
 ],
 'staff': [
  '.........KKK....',
  '........KBBBK...',
  '.......KBWWBK...',
  '.......KBWBBK...',
  '........KBBK....',
  '........KMK.....',
  '.......KmMK.....',
  '......KmMK......',
  '.....KmMK.......',
  '....KmMK........',
  '...KmMK.........',
  '..KmMK..........',
  '..KMK...........',
  '.KMK............',
  '.KK.............',
  '................',
 ],
 'summon_card': [
  '................',
  '..KKKKKKKKKKK...',
  '..KPPPPPPPPPK...',
  '..KPvvvvvvvPK...',
  '..KPvVVVVVvPK...',
  '..KPvVWKWVvPK...',
  '..KPvVKKKVvPK...',
  '..KPvVWKWVvPK...',
  '..KPvVVVVVvPK...',
  '..KPvvvvvvvPK...',
  '..KPPPPPPPPPK...',
  '..KPPKKKKKPPK...',
  '..KPPPPPPPPPK...',
  '..KKKKKKKKKKK...',
  '................',
  '................',
 ],
 #Same rounded fist as 'rage' (same silhouette, already proven at 16x16), in steel instead of fire: the
 #same idea of a blow/strength, applied to shoving instead of burning.
 'shove': [
  '................',
  '....KKKKKKK.....',
  '...KSSSSSSSK....',
  '..KSsSSSSSsSK...',
  '..KSSKSSSKSSK...',
  '..KSSSSSSSSSK...',
  '..KSSKKKKKSSK...',
  '..KSKWWWWWKSK...',
  '..KSKWKKKWKSK...',
  '...KSWWWWWSK....',
  '....KSSSSSK.....',
  '.....KSSSK......',
  '......KSK.......',
  '.......K........',
  '................',
  '................',
 ],
}

# EXACT order of the ItemLook enum: the CustomModelData is its position + 1 and travels inside each
# already-distributed ItemStack. Only append at the end. The self-test compares this list with the enum.
LOOKS = [
    'dm_wand', 'move_wand', 'rest_kit', 'turn_next', 'turn_undo', 'turn_actions',
    'rage', 'second_wind', 'inspiration', 'wild_shape', 'twinned', 'smite', 'hunters_mark',
    'shield', 'counterspell', 'turn_undead', 'help', 'staff', 'summon_card', 'shove',
]
# They are held like a wand: the "handheld" model tilts them in the hand instead of leaving them flat.
HANDHELD = {'dm_wand', 'move_wand', 'staff', 'smite'}

MODELS = os.path.join('src', 'main', 'resources', 'assets', 'dndsheets', 'models', 'item')


def write_models():
    os.makedirs(MODELS, exist_ok=True)
    for name in LOOKS:
        parent = 'minecraft:item/handheld' if name in HANDHELD else 'minecraft:item/generated'
        body = [
            '{',
            '  "parent": "%s",' % parent,
            '  "textures": { "layer0": "dndsheets:item/%s" }' % name,
            '}',
            '',
        ]
        with io.open(os.path.join(MODELS, name + '.json'), 'w', encoding='utf-8') as f:
            f.write('\n'.join(body))

    lines = ['{', '  "parent": "minecraft:item/generated",',
             '  "textures": { "layer0": "dndsheets:item/token" },', '  "overrides": [']
    for i, name in enumerate(LOOKS):
        comma = ',' if i < len(LOOKS) - 1 else ''
        lines.append('    { "predicate": { "custom_model_data": %d }, "model": "dndsheets:item/%s" }%s'
                     % (i + 1, name, comma))
    lines += ['  ]', '}', '']
    with io.open(os.path.join(MODELS, 'token.json'), 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines))
    return len(LOOKS) + 1


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for name, art in ICONS.items():
        assert len(art) == 16 and all(len(r) == 16 for r in art), name
        c = Canvas()
        c.rows(art)
        c.png(os.path.join(OUT, name + '.png'))
    print('wrote %d icons to %s' % (len(ICONS), OUT))
    print('escritos %d modelos en %s' % (write_models(), MODELS))
