"""Shared kit for the creature archetypes: a bone/cube description -> .bbmodel (geckolib_model) -> Bedrock geo.json,
ONE painted mask texture per archetype plus a variants.json of palettes, and the animation JSON.

The mask does not hold colours: each pixel stores a role (base, dark, belly, horn, membrane, eye), how much of the
dark colour to mix in, and a highlight amount. The client (MonsterTextures) colours it from a palette the first time a
variant is drawn, so a new colour is one line of JSON instead of one more PNG.
Mask encoding (RGBA): R = mix toward 'dark' (0..255), G = role * 40, B = whiten (0..255), A = 255 painted / 0 empty.

All art is generated from code, i.e. original (see CREDITS.md: no third-party creature art is used)."""
import base64, hashlib, json, os, struct, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import bbmcp

REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'dndsheets')
ROLES = ['base', 'dark', 'belly', 'horn', 'membrane', 'eye']  # index * 40 = the mask's green channel


def png(w, h, px):
    raw = b''.join(b'\x00' + b''.join(bytes(px[y][x]) for x in range(w)) for y in range(h))

    def ch(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + ch(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)) + ch(b'IDAT', zlib.compress(raw, 9)) + ch(b'IEND', b'')


def text(r):
    res = r.get('result', {})
    return '\n'.join(c.get('text', '') for c in res.get('content', [])) + ('  [ERROR]' if res.get('isError') or 'error' in r else '')


class Cube:
    def __init__(self, name, bone, a, b, role='base', uvkey=None):
        self.name, self.bone, self.a, self.b, self.role = name, bone, a, b, role
        self.uvkey = uvkey or name
        self.size = tuple(b[i] - a[i] for i in range(3))
        self.uv = None  # (u, v), set by pack()


def pack(cubes, tw, th):
    """Shelf-packs each distinct uvkey's box-UV net; cubes sharing a key (left/right copies) share the net."""
    placed, x, y, row = {}, 0, 0, 0
    for c in cubes:
        if c.uvkey not in placed:
            w, h, d = (int(v) for v in c.size)
            nw, nh = 2 * d + 2 * w, d + h
            if x + nw > tw:
                x, y, row = 0, y + row, 0
            assert y + nh <= th, 'texture %dx%d too small at %s' % (tw, th, c.name)
            placed[c.uvkey] = (x, y)
            x += nw
            row = max(row, nh)
        c.uv = placed[c.uvkey]
    return placed


def _noise(x, y, k):
    return hashlib.md5(('%d,%d,%s' % (x, y, k)).encode()).digest()[0] / 255.0


def mix(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def pixels(cubes, tw, th, belly_roles=('base',)):
    """Per painted pixel: (role, dark mix 0..1, whiten 0..1). Scale lattice, lighter top, belly underneath."""
    px = [[None] * tw for _ in range(th)]
    done = set()
    for c in cubes:
        if c.uvkey in done:
            continue
        done.add(c.uvkey)
        w, h, d = (int(v) for v in c.size)
        u, v = c.uv
        faces = {'up': (u + d, v, w, d), 'down': (u + d + w, v, w, d), 'west': (u, v + d, d, h),
                 'north': (u + d, v + d, w, h), 'east': (u + d + w, v + d, d, h), 'south': (u + 2 * d + w, v + d, w, h)}
        role = 'horn' if c.role == 'claw' else c.role
        for face, (fx, fy, fw, fh) in faces.items():
            for j in range(fh):
                for i in range(fw):
                    n = _noise(fx + i, fy + j, role)
                    p = (role, 0.0, 0.0)
                    if role == 'base':
                        lattice = ((i + (j % 2) * 2) % 4 == 0 and j % 2 == 0)
                        p = ('base', 0.55 if lattice else 0.12 * n, 0.08 if face == 'up' else 0.0)
                        if face == 'down' or (face in ('north', 'south', 'east', 'west') and j >= fh * 0.7 and role in belly_roles):
                            p = ('belly', 0.15 * n + (0.25 if (i + j) % 4 == 0 else 0), 0.0)
                    elif role == 'membrane':
                        p = ('membrane', 0.5 if i % 4 == 0 else 0.1 * n, 0.0)
                    elif role == 'horn':
                        p = ('horn', 0.5 * (j / max(fh, 1)) + 0.1 * n, 0.0)
                    px[fy + j][fx + i] = p
    return px


def mask_png(px, tw, th):
    out = [[(0, 0, 0, 0)] * tw for _ in range(th)]
    for y in range(th):
        for x in range(tw):
            p = px[y][x]
            if p:
                out[y][x] = (int(p[1] * 255), ROLES.index(p[0]) * 40, int(p[2] * 255), 255)
    return png(tw, th, out)


def preview_png(px, tw, th, pal):
    """The same pixels coloured with one palette: what the .bbmodel shows and what the game will draw."""
    out = [[(0, 0, 0, 0)] * tw for _ in range(th)]
    for y in range(th):
        for x in range(tw):
            p = px[y][x]
            if p:
                col = mix(mix(pal[p[0]], pal['dark'], p[1]), (255, 255, 255), p[2])
                out[y][x] = col + (255,)
    return png(tw, th, out)


class Archetype:
    """name, bones [(name, parent, pivot, rotation)], cubes [Cube], palettes {variant: pal}, animations {key: {...}}."""
    tw = th = 128

    def __init__(self, name, bones, cubes, palettes, animations, belly_roles=('base',), size=128):
        self.name, self.bones, self.cubes = name, bones, cubes
        self.tw = self.th = size
        # A body plan's shared animations may name bones this archetype lacks (wings, tail): drop those channels.
        names = {b[0] for b in bones}
        animations = {k: dict(a, bones={b: ch for b, ch in a['bones'].items() if b in names}) for k, a in animations.items()}
        self.palettes, self.animations, self.belly_roles = palettes, animations, belly_roles
        pack(cubes, self.tw, self.th)
        self.px = pixels(cubes, self.tw, self.th, belly_roles)

    def build(self, c, first_variant):
        bb = os.path.join(HERE, self.name + '.bbmodel')
        tex = preview_png(self.px, self.tw, self.th, self.palettes[first_variant])
        c.call('bbmodel_create', {'file': bb, 'format': 'geckolib_model', 'name': self.name, 'box_uv': True, 'overwrite': True,
                                  'resolution': {'width': self.tw, 'height': self.th}})
        ops = [{'op': 'add_texture', 'name': first_variant, 'source': 'data:image/png;base64,' + base64.b64encode(tex).decode(),
                'width': self.tw, 'height': self.th}]
        ops.append({'op': 'set_model_properties', 'model_identifier': self.name})
        for name, parent, pivot, rot in self.bones:
            # Rest poses are written in the Bedrock FILE convention (as GeckoLib reads them, like the animation keys);
            # Blockbench's editor angles are X and Y negated, and its export negates them back.
            ops.append({'op': 'add_group', 'name': name, 'origin': pivot, 'rotation': [-rot[0], -rot[1], rot[2]], 'parent': parent})
        for cu in self.cubes:
            ops.append({'op': 'add_cube', 'name': cu.name, 'from': list(cu.a), 'to': list(cu.b), 'parent': cu.bone, 'box_uv': True,
                        'uv_offset': list(cu.uv), 'texture': first_variant})
        print(self.name, 'edit:', text(c.call('bbmodel_edit', {'file': bb, 'operations': ops}))[:160].replace(chr(10), ' '))
        v = text(c.call('bbmodel_validate', {'file': bb}))
        print(self.name, 'validate passed:', '"passed": true' in v, '' if '"passed": true' in v else v[:600])
        # The server only writes inside its workspace (tools/blockbench), so export there and copy into the assets.
        tmp = os.path.join(HERE, '..', 'out', self.name + '.geo.json')
        os.makedirs(os.path.dirname(tmp), exist_ok=True)
        print(self.name, 'export:', text(c.call('bbmodel_export_bedrock_geometry', {'file': bb, 'output': os.path.abspath(tmp), 'overwrite': True, 'identifier': 'geometry.' + self.name}))[:140].replace(chr(10), ' '))
        geo = os.path.join(ASSETS, 'geo', 'entity', self.name + '.geo.json')
        os.makedirs(os.path.dirname(geo), exist_ok=True)
        open(geo, 'w', newline=chr(10)).write(open(tmp, encoding='utf-8').read())

    def install_textures(self):
        d = os.path.join(ASSETS, 'textures', 'entity', self.name)
        os.makedirs(d, exist_ok=True)
        for f in os.listdir(d):  # the old one-PNG-per-variant files
            if f.endswith('.png'):
                os.remove(os.path.join(d, f))
        open(os.path.join(d, 'mask.png'), 'wb').write(mask_png(self.px, self.tw, self.th))
        variants = {v: {r: list(pal[r]) for r in ROLES} for v, pal in self.palettes.items()}
        open(os.path.join(d, 'variants.json'), 'w', newline=chr(10)).write(json.dumps(variants, indent=1) + chr(10))

    def install_animations(self):
        d = os.path.join(ASSETS, 'animations', 'entity')
        os.makedirs(d, exist_ok=True)
        out = {'format_version': '1.8.0', 'animations': self.animations}
        open(os.path.join(d, self.name + '.animation.json'), 'w', newline=chr(10)).write(json.dumps(out, indent=1) + chr(10))

    def run(self):
        c = bbmcp.Client()
        try:
            self.build(c, next(iter(self.palettes)))
        finally:
            c.close()
        self.install_textures()
        self.install_animations()
        print(self.name, 'installed:', len(self.cubes), 'cubes, 1 mask +', len(self.palettes), 'palettes,', len(self.animations), 'animations')


def keys(length, frames):
    """frames: {time: [x,y,z]} -> Bedrock keyframe dict with string times."""
    return {('%g' % t): list(v) for t, v in sorted(frames.items())}
