"""Builds the 3D wands, rest kit and summon cards with the headless Blockbench MCP server and installs them.
Reuses build_staff's swatch-palette trick: a 16x16 texture of 4x4 swatches, each cube face maps to one swatch."""
import base64, json, os, sys
sys.path.insert(0, os.path.dirname(__file__))
import bbmcp
from build_staff import png, faces, cube, text, HANDHELD_DISPLAY, BB, ASSETS

WOOD, WOOD2, BRASS, BRASS2, GEM, SPARK = 0, 1, 2, 3, 4, 5
DARK, LEATHER, CLOTH, CLOTH2, FLAME, PARCH = 6, 7, 8, 9, 10, 11

def palette(colors):
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    for i, c in enumerate(colors):
        bx, by = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                px[by + y][bx + x] = c
    return png(16, 16, px)

def rgb(h):
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), 255)

# Dark arcane palette (leather, brass, gems), matching DESIGN.md
def pal(gem, spark):
    return [rgb(h) for h in ('3b2a1c', '5a3f27', 'c9a227', '7a6014', gem, spark,
                             '1e1812', '4a3322', '6b2430', '4a1620', 'ffb347', 'e8dcc0')]

def shaft(length, a=WOOD, b=WOOD2):
    ops = [cube('ferrule', [0, 0, 6.8], [1.8, 1.8, 9.2], BRASS2)]
    for i in range(1, length):
        ops.append(cube('shaft_%d' % i, [i, i, 7], [i + 1.6, i + 1.6, 9], b if i % 2 else a))
    ops.append(cube('grip', [3, 3, 6.8], [4.8, 4.8, 9.2], LEATHER))
    return ops

def dm_wand():
    t = 10
    return shaft(t) + [
        cube('collar', [t, t, 6.6], [t + 2.2, t + 2.2, 9.4], BRASS),
        cube('gem', [t + 1, t + 1, 6.4], [t + 4, t + 4, 9.6], GEM),
        cube('gem_top', [t + 2.2, t + 2.2, 9.6], [t + 3.2, t + 3.2, 9.9], SPARK),
        cube('wing_a', [t, t + 2.6, 7.2], [t + 1.2, t + 5, 8.8], BRASS),
        cube('wing_b', [t + 2.6, t, 7.2], [t + 5, t + 1.2, 8.8], BRASS),
    ]

def move_wand():
    t = 10
    return shaft(t) + [
        cube('collar', [t, t, 6.6], [t + 2.2, t + 2.2, 9.4], BRASS),
        cube('gem', [t + 1.6, t + 1.6, 6.8], [t + 3.8, t + 3.8, 9.2], GEM),
        cube('arrow_n', [t + 2.2, t + 3.8, 7.4], [t + 3.2, t + 5.6, 8.6], BRASS),
        cube('arrow_e', [t + 3.8, t + 2.2, 7.4], [t + 5.6, t + 3.2, 8.6], BRASS),
        cube('arrow_s', [t + 2.2, t + 0.2, 7.4], [t + 3.2, t + 1.6, 8.6], BRASS2),
        cube('arrow_w', [t + 0.2, t + 2.2, 7.4], [t + 1.6, t + 3.2, 8.6], BRASS2),
        cube('spark', [t + 2.4, t + 2.4, 9.2], [t + 3.0, t + 3.0, 9.6], SPARK),
    ]

def rest_kit():
    return [
        cube('roll_back', [2, 3, 6], [14, 9, 10], CLOTH),
        cube('roll_front_hi', [2, 9, 6.5], [14, 10.5, 9.5], CLOTH2),
        cube('roll_end_l', [1, 3.5, 6.5], [2, 8.5, 9.5], PARCH),
        cube('roll_end_r', [14, 3.5, 6.5], [15, 8.5, 9.5], PARCH),
        cube('strap_l', [4, 2.6, 5.6], [5.4, 10.4, 10.4], LEATHER),
        cube('strap_r', [10.6, 2.6, 5.6], [12, 10.4, 10.4], LEATHER),
        cube('buckle', [4.4, 6, 10.4], [5.0, 7.6, 10.8], BRASS),
        cube('buckle_r', [11, 6, 10.4], [11.6, 7.6, 10.8], BRASS),
        cube('log_a', [3, 0.5, 6.5], [13, 2.5, 8.5], WOOD),
        cube('flame', [7, 10.5, 7.2], [9, 13.5, 8.8], FLAME),
        cube('flame_core', [7.6, 10.8, 7.6], [8.4, 12.2, 8.4], SPARK),
    ]

def card(filled):
    ops = [
        cube('card_body', [3, 1, 7.6], [13, 15, 8.4], DARK),
        cube('border_t', [3, 14.2, 7.4], [13, 15, 8.6], BRASS),
        cube('border_b', [3, 1, 7.4], [13, 1.8, 8.6], BRASS),
        cube('border_l', [3, 1.8, 7.4], [3.8, 14.2, 8.6], BRASS2),
        cube('border_r', [12.2, 1.8, 7.4], [13, 14.2, 8.6], BRASS2),
        cube('rune_frame', [5.4, 5.4, 7.3], [10.6, 10.6, 8.7], BRASS2),
        cube('rune_slot', [6.2, 6.2, 7.2], [9.8, 9.8, 8.8], GEM if filled else DARK),
    ]
    if filled:
        ops += [cube('rune_glow', [7.2, 7.2, 8.8], [8.8, 8.8, 9.2], SPARK),
                cube('corner_a', [4.2, 12.4, 7.2], [5.6, 13.8, 8.8], SPARK),
                cube('corner_b', [10.4, 2.2, 7.2], [11.8, 3.6, 8.8], SPARK)]
    return ops

GENERATED_DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'thirdperson_lefthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 1], 'scale': [0.55, 0.55, 0.55]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
}

# target model name -> (build name, cubes, palette, display)
ITEMS = {
    'dm_wand': ('dm_wand_3d', dm_wand, pal('8a4fd6', 'f1e4ff'), HANDHELD_DISPLAY),
    'move_wand': ('move_wand_3d', move_wand, pal('2fb8a8', 'd8fff6'), HANDHELD_DISPLAY),
    'rest_kit': ('rest_kit_3d', rest_kit, pal('8a4fd6', 'fff2b0'), GENERATED_DISPLAY),
    'summon_card': ('summon_card_3d', lambda: card(False), pal('3a1d52', 'e8dcc0'), GENERATED_DISPLAY),
    'summon_card_filled': ('summon_card_filled_3d', lambda: card(True), pal('8a4fd6', 'f1e4ff'), GENERATED_DISPLAY),
}

def build(c, target, name, cubes, colors, display):
    tex = os.path.join(BB, name + '.png')
    open(tex, 'wb').write(png_bytes(colors))
    f = os.path.join(BB, name + '.bbmodel')
    c.call('bbmodel_create', {'file': f, 'format': 'java_block', 'name': name, 'resolution': {'width': 16, 'height': 16}, 'overwrite': True})
    data = 'data:image/png;base64,' + base64.b64encode(open(tex, 'rb').read()).decode()
    ops = [{'op': 'add_texture', 'name': name, 'source': data, 'width': 16, 'height': 16}] + cubes()
    print(name, 'edit:', text(c.call('bbmodel_edit', {'file': f, 'operations': ops}))[:200])
    print(name, 'validate:', text(c.call('bbmodel_validate', {'file': f}))[:400])
    out = os.path.join(BB, 'out', name + '.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    print(name, 'export:', text(c.call('bbmodel_export_java_block', {'file': f, 'output': out, 'overwrite': True, 'version': '1.9.0'}))[:200])
    model = json.load(open(out, encoding='utf-8'))
    model.pop('parent', None)
    model.pop('credit', None)
    model['textures'] = {'0': 'dndsheets:item/' + name, 'particle': 'dndsheets:item/' + name}
    model['display'] = display
    model['gui_light'] = 'front'
    open(os.path.join(ASSETS, 'models', 'item', target + '.json'), 'w', encoding='utf-8', newline='\n').write(json.dumps(model, indent=2) + '\n')
    open(os.path.join(ASSETS, 'textures', 'item', name + '.png'), 'wb').write(open(tex, 'rb').read())
    print('installed', target, len(model.get('elements', [])), 'elements')

def png_bytes(colors):
    return palette(colors)

if __name__ == '__main__':
    only = set(sys.argv[1:])
    c = bbmcp.Client()
    try:
        for target, (name, cubes, colors, display) in ITEMS.items():
            if not only or target in only:
                build(c, target, name, cubes, colors, display)
    finally:
        c.close()
