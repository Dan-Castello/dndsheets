"""Builds the 16 ability medallions (rest of the ItemLooks) and the 21 generic weapon models.
Same pipeline as build_items.py: swatch palette + cubes -> bbmodel -> Java block JSON installed into the assets.
Weapons share ONE texture (weapon_3d.png) and live in models/item/weapon/<id>.json; weapons.json points at them
through "customModelData" (1001+) and assets/minecraft/models/item/<base>.json carries the overrides."""
import base64, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import bbmcp
from build_staff import cube, text, HANDHELD_DISPLAY, BB, ASSETS, png
from build_items import pal, rgb, palette, GENERATED_DISPLAY, WOOD, WOOD2, BRASS, BRASS2, GEM, SPARK, DARK, LEATHER, CLOTH, CLOTH2, FLAME, PARCH

STEEL, STEEL2, RED, GREEN = 12, 13, 14, 15
LETTERS = {'G': GEM, 'S': SPARK, 'B': BRASS, 'W': STEEL, 'w': STEEL2, 'R': RED, 'g': GREEN, 'F': FLAME, 'P': PARCH, 'L': LEATHER, 'C': CLOTH, 'D': DARK}

def pal16(gem, spark):
    return pal(gem, spark) + [rgb('a8b0b8'), rgb('6d7580'), rgb('c2342f'), rgb('3f8f4a')]

# ---------------------------------------------------------------- medallions
def medallion(glyph):
    ops = []
    for i, (a, b) in enumerate((([4, 1], [12, 15]), ([1, 4], [15, 12]), ([2, 2], [14, 14]))):
        ops.append(cube('rim%d' % i, a + [7.4], b + [8.4], BRASS))
    for i, (a, b) in enumerate((([5, 2], [11, 14]), ([2.6, 5], [13.4, 11]), ([3.4, 3.4], [12.6, 12.6]))):
        ops.append(cube('face%d' % i, a + [8.4], b + [8.8], DARK))
    for r, row in enumerate(glyph):
        c = 0
        while c < len(row):
            ch = row[c]
            if ch == '.':
                c += 1
                continue
            e = c
            while e < len(row) and row[e] == ch:
                e += 1
            ops.append(cube('px_%d_%d' % (r, c), [4 + c, 11 - r, 8.8], [4 + e, 12 - r, 9.6], LETTERS[ch]))
            c = e
    return ops

ARROW = ['....G...', '....GG..', 'GGGGGGG.', 'GGGGGGGG', 'GGGGGGGG', 'GGGGGGG.', '....GG..', '....G...']
GLYPHS = {
    'turn_next': (ARROW, '2fb8a8', 'd8fff6'),
    'turn_undo': ([r[::-1] for r in ARROW], 'd68a2f', 'ffe9c4'),
    'turn_actions': (['BBBBBBBB', '.GGGGGG.', '..GGGG..', '...GG...', '...SS...', '..SSSS..', '.SSSSSS.', 'BBBBBBBB'], '8a4fd6', 'f1e4ff'),
    'rage': (['...R....', '...RR...', '..RRR.R.', '.RRRRRR.', '.RRFFRRR', 'RRRFFFRR', '.RRFFFR.', '..RRRR..'], 'c2342f', 'ffb347'),
    'second_wind': ([('.RR..RR.'), 'RRRRRRRR', 'RRSRRRRR', 'RRRRRRRR', '.RRRRRR.', '..RRRR..', '...RR...', '........'], 'c2342f', 'ffd0d0'),
    'inspiration': (['...BBBBB', '...BB..B', '...B...B', '...B...B', '...B..BB', '.BBB..BB', 'BBBB....', '.BB.....'], 'c9a227', 'fff2b0'),
    'wild_shape': (['.gg..gg.', '.gg..gg.', 'g..gg..g', '..gggg..', '.gggggg.', '.gggggg.', '..g..g..', '........'], '3f8f4a', 'b8ffc0'),
    'twinned': (['........', '.G....G.', 'GGG..GGG', 'GSGBBGSG', 'GGG..GGG', '.G....G.', '........', '........'], '8a4fd6', 'f1e4ff'),
    'smite': (['...S....', '.S.S.S..', '..BBB...', 'SSBBBSS.', '..BBB...', '.S.S.S..', '...S....', '........'], 'c9a227', 'fff6c0'),
    'hunters_mark': (['...RR...', '...RR...', '..R..R..', 'RR.GG.RR', 'RR.GG.RR', '..R..R..', '...RR...', '...RR...'], '3f8f4a', 'ffb347'),
    'shield': (['WWWWWWWW', 'WwwWWwwW', 'WwwWWwwW', 'WWWBBWWW', '.WWBBWW.', '..WWWW..', '...WW...', '........'], '4a7bd6', 'd8e8ff'),
    'counterspell': (['..GGGG..', '.G....G.', 'G...R..G', 'G..R...G', 'G.R....G', '.G....G.', '..GGGG..', '........'], '8a4fd6', 'f1e4ff'),
    'turn_undead': (['..SSSS..', '..S..S..', '..S..S..', '...SS...', 'SSSSSSSS', '...SS...', '...SS...', '...SS...'], 'c9a227', 'fffbe8'),
    'help': (['...gg...', '...gg...', '...gg...', 'gggggggg', 'gggggggg', '...gg...', '...gg...', '...gg...'], '3f8f4a', 'b8ffc0'),
    'shove': (['...W....', 'W..WW...', 'WW.WWW..', 'WWWWWWW.', 'WWWWWWW.', 'WW.WWW..', 'W..WW...', '...W....'], 'a8b0b8', 'ffffff'),
    'roleplay': (['.PPPPPP.', 'PPPPPPPP', 'P.LPPL.P', 'PPPPPPPP', 'PPPPPPPP', 'P.LLLL.P', '.PL..LP.', '..PPPP..'], 'c9a227', 'fff2b0'),
}

# ---------------------------------------------------------------- weapons
_n = [0]
def _nm(base):
    _n[0] += 1
    return '%s_%d' % (base, _n[0])

def D(name, t0, t1, w, z0, z1, idx, off=lambda t: 0):
    return [cube(_nm(name), [t + off(t), t, z0], [t + off(t) + w, t + w, z1], idx) for t in range(t0, t1 + 1)]

def X(name, c, k0, k1, w, z0, z1, idx):
    return [cube(_nm(name), [c + k, c - k, z0], [c + k + w, c - k + w, z1], idx) for k in range(k0, k1 + 1)]

def hilt(guard_c, gh):
    ops = [cube('pommel', [0, 0, 6.8], [1.8, 1.8, 9.2], BRASS2)]
    ops += D('grip', 1, guard_c - 1, 1.6, 7, 9, LEATHER)
    if gh:
        ops += X('guard', guard_c, -gh, gh, 1.4, 6.6, 9.4, BRASS)
    return ops

def blade(t0, t1, w, thick=1.2, off=lambda t: 0):
    z0, z1 = 8 - thick / 2, 8 + thick / 2
    ops = D('blade', t0, t1, w, z0, z1, STEEL, off)
    ops += D('fuller', t0 + 1, t1 - 1, max(w - 1.0, 0.4), z1, z1 + 0.2, STEEL2, off)
    ops.append(cube('tip', [t1 + 1 + off(t1), t1 + 1, z0], [t1 + 1 + off(t1) + w * 0.6, t1 + 1 + w * 0.6, z1], STEEL2))
    return ops

def shaft(t1, w=1.6):
    return [cube('pommel', [0, 0, 6.8], [1.8, 1.8, 9.2], BRASS2)] + D('shaft', 1, t1, w, 7, 9, WOOD) + D('wrap', 2, 3, w + 0.2, 6.9, 9.1, LEATHER)

def axe_head(c, k0, k1, rows):
    ops = []
    for r in rows:
        ops += X('head', c + r, k0, k1, 2, 7.2, 8.8, STEEL)
        ops += X('edge', c + r, k1 + 1, k1 + 1, 1.4, 7.4, 8.6, STEEL2)
    return ops

def ball(cx, spikes):
    ops = [cube('core', [cx - 1.6, cx - 1.6, 6.4], [cx + 1.6, cx + 1.6, 9.6], STEEL)]
    for dx, dy in ((-2.6, 0), (2.6, 0), (0, -2.6), (0, 2.6)):
        ops.append(cube('flange', [cx + dx - 0.6, cx + dy - 0.6, 7.2], [cx + dx + 0.6, cx + dy + 0.6, 8.8], STEEL2))
    ops += [cube('cap_f', [cx - 0.6, cx - 0.6, 9.6], [cx + 0.6, cx + 0.6, 10.4], STEEL2), cube('cap_b', [cx - 0.6, cx - 0.6, 5.6], [cx + 0.6, cx + 0.6, 6.4], STEEL2)]
    if spikes:
        for dx, dy in ((-2, -2), (2, 2), (-2, 2), (2, -2)):
            ops.append(cube('spike', [cx + dx - 0.5, cx + dy - 0.5, 7.5], [cx + dx + 0.5, cx + dy + 0.5, 8.5], STEEL2))
    return ops

def tapered(t1, w0, w1, extra=()):
    ops = []
    for t in range(0, t1 + 1):
        w = w0 + (w1 - w0) * t / t1
        ops.append(cube(_nm('log'), [t, t, 8 - w / 2], [t + w, t + w, 8 + w / 2], WOOD2 if t % 2 else WOOD))
    return ops + list(extra)

WEAPONS = {
    'dagger': lambda: hilt(3, 1) + blade(4, 9, 1.6),
    'shortsword': lambda: hilt(3, 2) + blade(4, 11, 1.8),
    'longsword': lambda: hilt(4, 2) + blade(5, 13, 2.0),
    'greatsword': lambda: hilt(4, 3) + blade(5, 13, 2.8, 1.6),
    'rapier': lambda: hilt(3, 2) + D('cup', 3, 4, 2.4, 7.4, 8.6, BRASS) + blade(5, 14, 1.1, 0.9),
    'scimitar': lambda: hilt(4, 1) + blade(5, 13, 1.9, 1.2, lambda t: max(0, t - 8) * 0.6),
    'handaxe': lambda: shaft(9) + axe_head(9, 0, 3, (0, 1)),
    'battleaxe': lambda: shaft(11) + axe_head(10, -1, 4, (0, 1, 2)),
    'greataxe': lambda: shaft(12) + axe_head(10, 0, 4, (0, 1)) + axe_head(10, -5, -1, (0, 1)),
    'warhammer': lambda: shaft(10) + X('head', 10, -2, 2, 2.2, 6.6, 9.4, STEEL) + X('head', 11, -2, 2, 2.2, 6.6, 9.4, STEEL2),
    'light_hammer': lambda: shaft(8) + X('head', 8, -1, 1, 2.2, 6.8, 9.2, STEEL) + X('head', 9, -1, 1, 2.2, 6.8, 9.2, STEEL2),
    'maul': lambda: shaft(10) + sum((X('head', 10 + r, -3, 3, 2.2, 6.0, 10.0, STEEL2 if r % 2 else STEEL) for r in range(4)), []) + X('band', 9, -3, 3, 1.4, 5.8, 10.2, BRASS),
    'mace': lambda: shaft(9) + ball(11.5, False),
    'morningstar': lambda: shaft(9) + ball(11.5, True),
    'flail': lambda: shaft(5) + D('chain', 6, 8, 0.9, 7.5, 8.5, STEEL2) + ball(11.5, True),
    'club': lambda: tapered(12, 1.4, 3.2, D('wrap', 2, 3, 1.8, 6.9, 9.1, LEATHER)),
    'greatclub': lambda: tapered(13, 1.6, 4.0, D('wrap', 2, 3, 2.0, 6.8, 9.2, LEATHER) + [cube('stud%d' % i, [9 + i, 9 + i, 9.9], [10 + i, 10 + i, 10.5], BRASS) for i in range(0, 4, 2)]),
    'quarterstaff': lambda: [cube('cap_lo', [0, 0, 6.8], [1.8, 1.8, 9.2], BRASS)] + D('shaft', 1, 13, 1.6, 7, 9, WOOD) + D('grip', 6, 8, 1.8, 6.9, 9.1, LEATHER) + [cube('cap_hi', [14, 14, 6.8], [15.8, 15.8, 9.2], BRASS)],
    'sickle': lambda: shaft(7) + [cube(_nm('arc'), [x, y, 7.2], [x + 1.6, y + 1.6, 8.8], STEEL) for x, y in ((8.5, 8.5), (9.3, 10), (10.4, 11.3), (11.8, 12.2), (13.2, 12.6), (14, 11.6))],
    'war_pick': lambda: shaft(10) + X('head', 11, -2, 0, 2.2, 6.8, 9.2, STEEL2) + [cube(_nm('pick'), [11 + k, 11 - k, 7.2 + 0.2 * k], [11 + k + max(2.2 - 0.35 * k, 0.8), 11 - k + max(2.2 - 0.35 * k, 0.8), 8.8 - 0.2 * k], STEEL) for k in range(1, 5)],
    'glaive': lambda: shaft(11) + D('ferrule', 11, 11, 2.0, 6.8, 9.2, BRASS) + blade(12, 14, 2.6, 1.4, lambda t: -max(0, t - 12) * 0.5),
}
WEAPON_CMD = {n: 1001 + i for i, n in enumerate(WEAPONS)}
WEAPON_PAL = pal16('4a7bd6', 'e8f0ff')

# ---------------------------------------------------------------- build
def build(c, model_name, cubes, colors, tex_name, out_rel, display):
    tex = os.path.join(BB, tex_name + '.png')
    open(tex, 'wb').write(palette(colors))
    f = os.path.join(BB, model_name + '.bbmodel')
    c.call('bbmodel_create', {'file': f, 'format': 'java_block', 'name': model_name, 'resolution': {'width': 16, 'height': 16}, 'overwrite': True})
    data = 'data:image/png;base64,' + base64.b64encode(open(tex, 'rb').read()).decode()
    r = text(c.call('bbmodel_edit', {'file': f, 'operations': [{'op': 'add_texture', 'name': tex_name, 'source': data, 'width': 16, 'height': 16}] + cubes}))
    v = text(c.call('bbmodel_validate', {'file': f}))
    out = os.path.join(BB, 'out', model_name + '.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    c.call('bbmodel_export_java_block', {'file': f, 'output': out, 'overwrite': True, 'version': '1.9.0'})
    model = json.load(open(out, encoding='utf-8'))
    for k in ('parent', 'credit'):
        model.pop(k, None)
    model['textures'] = {'0': 'dndsheets:item/' + tex_name, 'particle': 'dndsheets:item/' + tex_name}
    model['display'] = display
    model['gui_light'] = 'front'
    dest = os.path.join(ASSETS, 'models', 'item', out_rel + '.json')
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    open(dest, 'w', encoding='utf-8', newline='\n').write(json.dumps(model, indent=2) + '\n')
    open(os.path.join(ASSETS, 'textures', 'item', tex_name + '.png'), 'wb').write(open(tex, 'rb').read())
    ok = '"passed": true' in v and 'ERROR' not in r
    print('%-22s %3d elements  %s' % (out_rel, len(model['elements']), 'ok' if ok else 'CHECK: ' + v[:200] + r[:200]))

if __name__ == '__main__':
    c = bbmcp.Client()
    try:
        for name, (glyph, gem, spark) in GLYPHS.items():
            build(c, name + '_3d', medallion(glyph), pal16(gem, spark), name + '_3d', name, GENERATED_DISPLAY)
        for name, fn in WEAPONS.items():
            _n[0] = 0
            build(c, 'weapon_' + name, fn(), WEAPON_PAL, 'weapon_3d', 'weapon/' + name, HANDHELD_DISPLAY)
    finally:
        c.close()
    json.dump(WEAPON_CMD, open(os.path.join(BB, 'out', 'weapon_cmd.json'), 'w'), indent=1)
