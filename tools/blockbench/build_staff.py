"""Builds the 3D staff (blank + charged) with the headless Blockbench MCP server, validates, exports,
and installs the model/texture into the mod's assets."""
import base64, json, os, struct, sys, zlib
sys.path.insert(0, os.path.dirname(__file__))
import bbmcp

REPO = r'C:\Users\DanCastello\Documents\GitHub\dndsheets'
BB = os.path.join(REPO, 'tools', 'blockbench')
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'dndsheets')

def png(w, h, px):
    raw = b''.join(b'\x00' + b''.join(bytes(px[y][x]) for x in range(w)) for y in range(h))
    def ch(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw, 9)) + ch(b'IEND', b'')

# 4x4 grid of 4x4 swatches; swatch i -> index i
BASE = [(90, 58, 26, 255), (138, 90, 43, 255), (201, 162, 39, 255), (122, 96, 20, 255), (58, 110, 192, 255), (242, 236, 224, 255)]
CHARGED = BASE[:4] + [(120, 210, 255, 255), (255, 255, 255, 255)]

def palette(colors):
    px = [[(0, 0, 0, 0)] * 16 for _ in range(16)]
    for i, c in enumerate(colors):
        bx, by = (i % 4) * 4, (i // 4) * 4
        for y in range(4):
            for x in range(4):
                px[by + y][bx + x] = c
    return png(16, 16, px)

def faces(idx):
    bx, by = (idx % 4) * 4 + 1, (idx // 4) * 4 + 1
    return {d: {'uv': [bx, by, bx + 2, by + 2], 'texture': 0} for d in ('north', 'east', 'south', 'west', 'up', 'down')}

def cube(name, a, b, idx):
    return {'op': 'add_cube', 'name': name, 'from': a, 'to': b, 'faces': faces(idx)}

def cubes(charged):
    ops = [cube('ferrule', [0, 0, 6.8], [1.8, 1.8, 9.2], 3)]
    for i in range(1, 10):
        ops.append(cube('shaft_%d' % i, [i, i, 7], [i + 1.6, i + 1.6, 9], 0 if i % 2 else 1))
    ops += [
        cube('grip', [4, 4, 6.8], [5.8, 5.8, 9.2], 2),
        cube('collar', [10, 10, 6.6], [12.2, 12.2, 9.4], 2),
        cube('prong_up', [10.6, 11.6, 7.2], [11.8, 15.2, 8.8], 2),
        cube('prong_right', [11.6, 10.6, 7.2], [15.2, 11.8, 8.8], 2),
        cube('gem', [11.8, 11.8, 6.4], [14.8, 14.8, 9.6], 4),
        cube('gem_highlight', [13.6, 13.6, 9.6], [14.6, 14.6, 9.8], 5),
    ]
    if charged:
        ops += [cube('spark_a', [15, 12.8, 7.8], [15.8, 13.6, 8.2], 5), cube('spark_b', [12.8, 15.2, 7.8], [13.6, 15.8, 8.2], 5)]
    return ops

HANDHELD_DISPLAY = {
    'thirdperson_righthand': {'rotation': [0, -90, 55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'thirdperson_lefthand': {'rotation': [0, 90, -55], 'translation': [0, 4, 0.5], 'scale': [0.85, 0.85, 0.85]},
    'firstperson_righthand': {'rotation': [0, -90, 25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'firstperson_lefthand': {'rotation': [0, 90, -25], 'translation': [1.13, 3.2, 1.13], 'scale': [0.68, 0.68, 0.68]},
    'ground': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [0.5, 0.5, 0.5]},
    'head': {'rotation': [0, 180, 0], 'translation': [0, 13, 7], 'scale': [1, 1, 1]},
    'fixed': {'rotation': [0, 180, 0], 'translation': [0, 0, 0], 'scale': [1, 1, 1]},
}

def text(r):
    res = r.get('result', {})
    return '\n'.join(c.get('text', '') for c in res.get('content', [])) + ('  [ERROR]' if res.get('isError') or 'error' in r else '')

def build(c, name, colors, charged):
    tex = os.path.join(BB, name + '.png')
    open(tex, 'wb').write(palette(colors))
    f = os.path.join(BB, name + '.bbmodel')
    print(name, 'create:', text(c.call('bbmodel_create', {'file': f, 'format': 'java_block', 'name': name, 'resolution': {'width': 16, 'height': 16}, 'overwrite': True}))[:200])
    data = 'data:image/png;base64,' + base64.b64encode(open(tex, 'rb').read()).decode()
    ops = [{'op': 'add_texture', 'name': name, 'source': data, 'width': 16, 'height': 16}] + cubes(charged)
    print(name, 'edit:', text(c.call('bbmodel_edit', {'file': f, 'operations': ops}))[:300])
    print(name, 'validate:', text(c.call('bbmodel_validate', {'file': f}))[:800])
    out = os.path.join(BB, 'out', name + '.json')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    print(name, 'export:', text(c.call('bbmodel_export_java_block', {'file': f, 'output': out, 'overwrite': True, 'version': '1.9.0'}))[:300])
    return out, tex

if __name__ == '__main__':
    c = bbmcp.Client()
    try:
        for name, colors, charged, target in (('staff_3d', BASE, False, 'staff'), ('staff_3d_charged', CHARGED, True, 'staff_charged')):
            out, tex = build(c, name, colors, charged)
            model = json.load(open(out, encoding='utf-8'))
            model.pop('parent', None)
            model['textures'] = {'0': 'dndsheets:item/' + name, 'particle': 'dndsheets:item/' + target}
            model['display'] = HANDHELD_DISPLAY
            model['gui_light'] = 'front'
            model.pop('credit', None)
            open(os.path.join(ASSETS, 'models', 'item', target + '.json'), 'w', encoding='utf-8', newline='\n').write(json.dumps(model, indent=2) + '\n')
            open(os.path.join(ASSETS, 'textures', 'item', name + '.png'), 'wb').write(open(tex, 'rb').read())
            print('installed', target, '->', len(model.get('elements', [])), 'elements')
    finally:
        c.close()
