"""Imports a Blockbench-made model downloaded from Sketchfab (glTF) as a GeckoLib creature of this mod.

  python tools/blockbench/import/gltf.py                 (every entry of IMPORTS)
  python tools/blockbench/import/gltf.py kraken          (one)

Download: Sketchfab's download API needs a personal API token, read from the SKETCHFAB_TOKEN environment variable (or,
on Windows, the user's registry environment, where `setx` writes it). Zips are cached in tools/blockbench/out/import/
(git-ignored): only the converted model ships. Licence and author come from the same API and go to CREDITS.txt.

Conversion
- Blockbench exports a group as a node (translation = its origin relative to the parent's, rotation = its rotation) and
  a cube as a node whose mesh vertices are relative to the cube's origin. Groups become bones, cubes become geo cubes
  (with their own rotation/pivot when rotated), in Blockbench's editor space, then written in the geo file convention
  (x mirrored, X/Y rotations negated: the conventions render.py checked against GeckoLib's bytecode).
- Textures are RE-BAKED to box UV: every face texel is projected back onto the source face and sampled there. GeckoLib
  floors cube sizes for box UV, so the net is laid out exactly as it computes it, and one texture serves the model
  even when the source used several.
- Animations: glTF clips whose name says idle / attack / hurt / death / fly become those keys (rotations as Euler
  deltas from the rest pose, as GeckoLib adds them); missing ones come from a body plan in bodies.py."""
import io, json, math, os, re, sys, urllib.request, zipfile
import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'dndsheets')
CACHE = os.path.join(HERE, '..', 'out', 'import', 'sketchfab')
sys.path.insert(0, os.path.join(HERE, '..', 'mobs'))
import bodies as B
from mobkit import pack, Cube as PackCube

# name -> Sketchfab uid, cache folder, fallback body plan for clips the source lacks, bone renames (source -> ours).
# Scale: the source's size against the Medium creature our size categories scale from (written to creatures/scales.json,
# applied by MonsterModelRenderer). Evaluated and left out (reference/creature-model-survey.md): "Succubus Boss for Model
# Engine" (effect props floating around a T-pose), "Ghasklle" (loose fragments), "Golem" by bada9889 (no monster fits),
# "Realistic Dragon Minecraft" and "Fire dragon minecraft" (the dragon archetype covers them), "T-rex Minecraft",
# "Minecraft Bat" and "The Lich (Blockbench)" (triangulated OBJ/FBX meshes, not cubes), "Minecraft Centaur" (one
# merged block) and "Centaur // Satyr" (no horse body in the export).
IMPORTS = {
    'kraken': dict(uid='995973df0f0e47a7bde5603500bd4027', dir='pixel_kraken', scale=0.6),
    'drake': dict(uid='021865367838492a9839c64c6f3cb984', dir='leathern_drake_blockbench', scale=0.4),
    'blue_shark': dict(uid='8c404f3b7675490cb1655f0ac56d9b5a', dir='minecraft_shark', scale=0.7),
    'orc': dict(uid='695c63864a194871ac6764f25ad7d9af', dir='tri_orc', scale=1.7),
    'werewolf': dict(uid='043c1c55d5c046749bee1c9173ad61ef', dir='tri_werewolf', scale=1.0),
    'rattlesnake': dict(uid='30e37bb5818541f49346f4f49c4aa1da', dir='tri_snake', scale=1.0),
    'crocodile': dict(uid='21e46c7059d845ff8978a5cb2595bab3', dir='tri_crocodile', scale=0.5),
    'scorpion': dict(uid='00d2cdc2baac4ba2955ee2087311dbae', dir='tri_scorpion', scale=1.0),
    'rat': dict(uid='51386409da1f417f8c45b46bf8da6e55', dir='tri_rat', scale=1.0, source_clips=False),  # its "Stand" clip is a stand-up, not a rest
    'lion': dict(uid='a072e49ead7e4cc49eba840c0f3b2cfe', dir='tri_lion', scale=0.6),
    'minotaur': dict(uid='60e3f514f5e34c4086d0e7422d9f3099', dir='tri_minotaur', scale=0.4),
    'mummy': dict(uid='2bd48b1ca610462dbb9d8ee121949c48', dir='tri_mummy', scale=1.0),
    # Recovered once the converter stopped applying Sketchfab's display orientation, handled gimbal lock and nested scale.
    'ogre': dict(uid='85471a1dd86d4dac962ff58fc43a69ab', dir='tri_giant', scale=0.45),
    'troll': dict(uid='e7e5c3201f4841f0ba989120292ac6cb', dir='tri_troll', scale=0.45),
    'bear': dict(uid='452b6a429f08481db25be36395741dce', dir='tri_bear', scale=0.8),
    'eagle': dict(uid='ca159e950d784d8d8075cefbb2664f3d', dir='tri_eagle', scale=0.3),
    'earth_golem': dict(uid='d3ab72e33146440d937c688dba463f77', dir='tri_stone_golem', scale=1.0),
}

CLIP_KEYS = [('death', r'death|die|dead'), ('hurt', r'hurt|damage|hit_?react'), ('attack', r'attack|bite|swing|slash|strike|melee|breath'),
             ('fly', r'fly|flight|glide|flap'), ('idle', r'idle|stand|breath')]


# --- download ------------------------------------------------------------------------------------------------------

def token():
    tok = os.environ.get('SKETCHFAB_TOKEN')
    if not tok and sys.platform == 'win32':
        import winreg
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Environment') as k:
                tok = winreg.QueryValueEx(k, 'SKETCHFAB_TOKEN')[0]
        except OSError:
            pass
    if not tok:
        raise SystemExit('Set SKETCHFAB_TOKEN (Sketchfab > Settings > Password & API > API token).')
    return tok


def api(path, auth=False):
    headers = {'User-Agent': 'dndsheets-import'}
    if auth:
        headers['Authorization'] = 'Token ' + token()
    return json.load(urllib.request.urlopen(urllib.request.Request('https://api.sketchfab.com/v3/' + path, headers=headers), timeout=60))


def fetch(name, uid, folder=None):
    d = os.path.join(CACHE, folder or name)
    if not os.path.exists(os.path.join(d, 'scene.gltf')):
        url = api('models/%s/download' % uid, auth=True)['gltf']['url']
        zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(url, timeout=180).read())).extractall(d)
    meta_path = os.path.join(d, 'meta.json')
    if not os.path.exists(meta_path):
        m = api('models/' + uid)
        json.dump(dict(name=m['name'], author=m['user']['displayName'], username=m['user']['username'],
                       licence=m['license']['label'], licence_url=m['license'].get('url'), url=m['viewerUrl']),
                  open(meta_path, 'w', encoding='utf-8'), indent=1)
    return d, json.load(open(meta_path, encoding='utf-8'))


# --- glTF reading --------------------------------------------------------------------------------------------------

class Gltf:
    COMPONENTS = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8}
    SIZES = {'SCALAR': 1, 'VEC2': 2, 'VEC3': 3, 'VEC4': 4, 'MAT4': 16}

    def __init__(self, d):
        self.d = d
        self.g = json.load(open(os.path.join(d, 'scene.gltf'), encoding='utf-8'))
        self.bin = open(os.path.join(d, self.g['buffers'][0]['uri']), 'rb').read()
        self.images = [Image.open(os.path.join(d, im['uri'])).convert('RGBA') for im in self.g.get('images', [])]

    def accessor(self, i):
        a = self.g['accessors'][i]
        v = self.g['bufferViews'][a['bufferView']]
        dtype, n = self.COMPONENTS[a['componentType']], self.SIZES[a['type']]
        start = v.get('byteOffset', 0) + a.get('byteOffset', 0)
        stride = v.get('byteStride')
        item = np.dtype(dtype).itemsize * n
        if stride and stride != item:
            raw = np.frombuffer(self.bin, np.uint8, count=stride * a['count'], offset=start).reshape(a['count'], stride)[:, :item]
            return raw.copy().view(dtype).reshape(a['count'], n).astype(float)
        return np.frombuffer(self.bin, dtype, count=n * a['count'], offset=start).reshape(a['count'], n).astype(float)

    def material_image(self, material):
        if material is None:
            return None
        pbr = self.g['materials'][material].get('pbrMetallicRoughness', {})
        tex = pbr.get('baseColorTexture')
        return self.images[self.g['textures'][tex['index']]['source']] if tex else None


def local_matrix(node):
    if 'matrix' in node:
        return np.array(node['matrix'], float).reshape(4, 4).T
    M = np.eye(4)
    if 'scale' in node:
        M = np.diag(list(node['scale']) + [1]) @ M
    if 'rotation' in node:
        R = np.eye(4)
        R[:3, :3] = quat(node['rotation'])
        M = R @ M
    if 'translation' in node:
        T = np.eye(4)
        T[:3, 3] = node['translation']
        M = T @ M
    return M


def quat(q):
    x, y, z, w = q
    return np.array([[1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w)],
                     [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w)],
                     [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y)]])


def euler_zyx(R):
    """R = Rz(z) Ry(y) Rx(x), degrees (Blockbench / GeckoLib order)."""
    y = math.degrees(math.asin(max(-1.0, min(1.0, -R[2, 0]))))
    if math.hypot(R[2, 1], R[2, 2]) < 1e-6:
        # Gimbal lock (Y = +-90, common in Blockbench): X and Z turn about the same axis, so X takes 0 and Z all of it.
        # The general formulas would read atan2(0, 0) and drop the rotation (a kraken's tentacles came out straight).
        return np.array([0.0, y, math.degrees(math.atan2(-R[0, 1], R[1, 1]))])
    x = math.degrees(math.atan2(R[2, 1], R[2, 2]))
    z = math.degrees(math.atan2(R[1, 0], R[0, 0]))
    return np.array([x, y, z])


def clean(name):
    return re.sub(r'_\d+$', '', name or '').strip() or 'part'


# --- conversion ----------------------------------------------------------------------------------------------------

def content_root(g):
    """The node whose children are the model itself (below Sketchfab's wrappers)."""
    nodes = g['nodes']
    by_name = {n.get('name'): i for i, n in enumerate(nodes)}
    if 'GLTF_SceneRootNode' in by_name:
        return by_name['GLTF_SceneRootNode']
    return g['scenes'][g.get('scene', 0)]['nodes'][0]


def is_cube(g, i):
    n = g['nodes'][i]
    if 'mesh' in n and not n.get('children'):
        return True
    kids = n.get('children', [])
    return bool(kids) and all('mesh' in g['nodes'][k] and not g['nodes'][k].get('children') for k in kids)


def convert(gl, rename, units=1.0):
    g = gl.g
    nodes = g['nodes']
    top = content_root(g)
    # Everything above the content root is Sketchfab's: its Z-up wrapper and the author's DISPLAY orientation and scale
    # (an orc turned 180 degrees for the viewer). The content below is Blockbench's own export, in editor space, which
    # is what we want: none of the wrappers is applied.
    F = np.eye(3)
    scale = units

    bones, cubes, names = [], [], {}
    rest = {}

    def unique(base):
        base = rename.get(base, base)
        name, k = base, 2
        while name in names.values():
            name, k = '%s%d' % (base, k), k + 1
        return name

    def walk(i, bone, origin, s=1.0):
        n = nodes[i]
        M = local_matrix(n)
        # A uniform scale inside the content (an FBX-converted export carries a 0.01 node) scales everything below it;
        # bones and cubes then keep only the rotation.
        own = float(np.cbrt(abs(np.linalg.det(M[:3, :3])))) or 1.0
        t = F @ (M[:3, 3] * scale * s)
        R = F @ (M[:3, :3] / own) @ F.T
        pos = origin + t
        s *= own
        if is_cube(g, i):
            verts, faces = [], []
            meshes = [n['mesh']] if 'mesh' in n else [nodes[k]['mesh'] for k in n.get('children', [])]
            locals_ = [np.eye(4)] if 'mesh' in n else [local_matrix(nodes[k]) for k in n.get('children', [])]
            for mesh, L in zip(meshes, locals_):
                for p in g['meshes'][mesh]['primitives']:
                    P = gl.accessor(p['attributes']['POSITION'])
                    P = (F @ (((L[:3, :3] @ P.T).T + L[:3, 3]) * scale * s).T).T
                    uv = gl.accessor(p['attributes']['TEXCOORD_0']) if 'TEXCOORD_0' in p['attributes'] else None
                    idx = gl.accessor(p['indices'])[:, 0].astype(int) if 'indices' in p else np.arange(len(P))
                    img = gl.material_image(p.get('material'))
                    for tri in idx.reshape(-1, 3):
                        faces.append((P[tri], uv[tri] if uv is not None else None, img))
                    verts.append(P)
            V = np.vstack(verts)
            lo, hi = V.min(0), V.max(0)
            cubes.append(dict(bone=bone, pivot=pos, rot=euler_zyx(R), lo=pos + lo, size=np.round(hi - lo, 4), faces=faces))
            return
        name = unique(clean(n.get('name')))
        names[i] = name
        bones.append(dict(name=name, parent=bone, pivot=pos, rot=euler_zyx(R)))
        rest[i] = (name, M)
        for c in n.get('children', []):
            walk(c, name, pos, s)

    bones.append(dict(name='root', parent=None, pivot=np.zeros(3), rot=np.zeros(3)))
    names[top] = 'root'
    for c in nodes[top].get('children', []):
        walk(c, 'root', np.zeros(3))
    return bones, cubes, rest, F, scale


def bake(cubes, density):
    """Box-UV atlas: lays the nets out as GeckoLib computes them (floored sizes) and paints each texel from the source
    face it lands on."""
    packing = [PackCube('c%d' % i, None, (0, 0, 0), tuple(max(1, int(math.floor(s))) for s in c['size'])) for i, c in enumerate(cubes)]
    size = 64
    while True:
        try:
            pack(packing, size, size)
            break
        except AssertionError:
            size *= 2
    k = density
    atlas = np.zeros((size * k, size * k, 4), np.uint8)
    for c, pc in zip(cubes, packing):
        c['uv'] = list(pc.uv)
        w, h, d = (int(math.floor(s)) for s in c['size'])
        u, v = pc.uv
        lo, sz = c['lo'] - c['pivot'], c['size']  # cube-local (unrotated) frame, as the mesh vertices are
        X, Y, Z = np.array([sz[0], 0, 0]), np.array([0, sz[1], 0]), np.array([0, 0, sz[2]])
        # Same face layout as render.faces(): (origin, u edge, v edge, outward normal, texture rect).
        layout = [(lo + Y + Z, X, -Z, (0, 1, 0), (u + d, v, w, d)), (lo, X, Z, (0, -1, 0), (u + d + w, v, w, d)),
                  (lo + Y + X, Z, -Y, (1, 0, 0), (u, v + d, d, h)), (lo + Y, X, -Y, (0, 0, -1), (u + d, v + d, w, h)),
                  (lo + Y + Z, -Z, -Y, (-1, 0, 0), (u + d + w, v + d, d, h)), (lo + Y + Z + X, -X, -Y, (0, 0, 1), (u + 2 * d + w, v + d, w, h))]
        for o, eu, ev, n, (tx, ty, tw, th) in layout:
            if tw <= 0 or th <= 0:
                continue
            src = [f for f in c['faces'] if f[1] is not None and f[2] is not None and np.dot(normal(f[0]), n) > 0.9]
            if not src:
                continue
            for j in range(th * k):
                for i in range(tw * k):
                    p = o + eu * ((i + 0.5) / (tw * k)) + ev * ((j + 0.5) / (th * k))
                    col = sample(src, p)
                    if col is not None:
                        atlas[ty * k + j, tx * k + i] = col
    return Image.fromarray(atlas), size


def normal(tri):
    n = np.cross(tri[1] - tri[0], tri[2] - tri[0])
    return n / (np.linalg.norm(n) or 1)


def sample(faces, p):
    """Colour of the source texture at point p of a cube face: barycentric in the source triangle containing p."""
    best = None
    for P, UV, img in faces:
        a, b, c = P
        v0, v1, v2 = b - a, c - a, p - a
        d00, d01, d11, d20, d21 = v0 @ v0, v0 @ v1, v1 @ v1, v2 @ v0, v2 @ v1
        den = d00 * d11 - d01 * d01
        if abs(den) < 1e-12:
            continue
        y = (d11 * d20 - d01 * d21) / den
        z = (d00 * d21 - d01 * d20) / den
        x = 1 - y - z
        err = -min(x, y, z)
        if best is None or err < best[0]:
            uv = x * UV[0] + y * UV[1] + z * UV[2]
            best = (err, uv, img)
        if err <= 1e-6:
            break
    if best is None:
        return None
    _, (u, v), img = best
    W, H = img.size
    return img.getpixel((min(W - 1, max(0, int(u * W))), min(H - 1, max(0, int(v * H)))))


def texel_density(cubes):
    """Source texels per model unit (median over faces), so the bake keeps the source's resolution."""
    ratios = []
    for c in cubes:
        for P, UV, img in c['faces'][:12]:
            if UV is None or img is None:
                continue
            e3 = np.linalg.norm(P[1] - P[0])
            e2 = np.linalg.norm((UV[1] - UV[0]) * np.array(img.size))
            if e3 > 1e-6 and e2 > 1e-6:
                ratios.append(e2 / e3)
    return max(1, int(round(float(np.median(ratios))))) if ratios else 1


def geo(name, bones, cubes, size):
    out = []
    for b in bones:
        bone = {'name': b['name'], 'pivot': [round(-b['pivot'][0], 4), round(b['pivot'][1], 4), round(b['pivot'][2], 4)]}
        if b['parent']:
            bone['parent'] = b['parent']
        if np.abs(b['rot']).max() > 1e-3:
            bone['rotation'] = [round(-b['rot'][0], 4), round(-b['rot'][1], 4), round(b['rot'][2], 4)]
        mine = [c for c in cubes if c['bone'] == b['name']]
        if mine:
            bone['cubes'] = []
            for c in mine:
                lo, sz = c['lo'], c['size']
                cube = {'origin': [round(-(lo[0] + sz[0]), 4), round(lo[1], 4), round(lo[2], 4)], 'size': [float(s) for s in sz], 'uv': c['uv']}
                if np.abs(c['rot']).max() > 1e-3:
                    cube['pivot'] = [round(-c['pivot'][0], 4), round(c['pivot'][1], 4), round(c['pivot'][2], 4)]
                    cube['rotation'] = [round(-c['rot'][0], 4), round(-c['rot'][1], 4), round(c['rot'][2], 4)]
                bone['cubes'].append(cube)
        out.append(bone)
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.' + name, 'texture_width': size, 'texture_height': size,
                        'visible_bounds_width': 4, 'visible_bounds_height': 4, 'visible_bounds_offset': [0, 1.5, 0]},
        'bones': out}]}


def clips(gl, rest, F, scale, plan, bone_names, use_source=True):
    """glTF clips -> our five keys, as deltas from the rest pose; the body plan fills the gaps."""
    g = gl.g
    found = {}
    for a in (g.get('animations', []) if use_source else []):
        key = next((k for k, pat in CLIP_KEYS if re.search(pat, a.get('name', ''), re.I)), None)
        if not key or key in found:
            continue
        bones, length = {}, 0.0
        for ch in a['channels']:
            node = ch['target'].get('node')
            if node not in rest:
                continue
            bone, M = rest[node]
            s = a['samplers'][ch['sampler']]
            times = gl.accessor(s['input'])[:, 0]
            vals = gl.accessor(s['output'])
            length = max(length, float(times[-1]))
            path = ch['target']['path']
            frames = {}
            for t, v in zip(times, vals):
                if path == 'rotation':
                    rest_e = euler_zyx(F @ M[:3, :3] @ F.T)
                    e = euler_zyx(F @ quat(v) @ F.T) - rest_e
                    e = (e + 180) % 360 - 180
                    frames[round(float(t), 3)] = [round(-e[0], 2), round(-e[1], 2), round(e[2], 2)]
                elif path == 'translation':
                    d = F @ ((v - M[:3, 3]) * scale)
                    frames[round(float(t), 3)] = [round(-d[0], 3), round(d[1], 3), round(d[2], 3)]
                elif path == 'scale':
                    frames[round(float(t), 3)] = [round(float(x), 3) for x in v]
            bones.setdefault(bone, {})[path if path != 'translation' else 'position'] = {('%g' % t): f for t, f in sorted(frames.items())}
        if bones:
            if key in ('idle', 'fly'):
                # Authors' loops often stop a frame short of their start; close them so the wrap does not jerk.
                end = '%g' % round(length, 3)
                for chans in bones.values():
                    for frames in chans.values():
                        if frames:
                            frames[end] = frames[min(frames, key=float)]
            found[key] = {'animation_length': round(length, 3), 'loop': True if key in ('idle', 'fly') else 'hold_on_last_frame', 'bones': bones}
    from_source = sorted(found)
    base = B.ghost_anims() if plan == 'ghost' else B.generic_anims(bone_names)
    for k, a in base.items():
        found.setdefault(k, dict(a, bones={b: ch for b, ch in a['bones'].items() if b in bone_names}))
    return found, from_source


def install(name, spec):
    d, meta = fetch(name, spec['uid'], spec.get('dir'))
    if meta['licence'] not in ('CC0 Public Domain', 'CC Attribution'):
        raise SystemExit('%s: licence %s is not permissive' % (name, meta['licence']))
    gl = Gltf(d)
    bones, cubes, rest, F, scale = convert(gl, spec.get('rename', {}))
    extent = max(float((c['lo'] + c['size']).max() - c['lo'].min()) for c in cubes)
    if extent < 8:  # exported in blocks, not in Blockbench's 1/16 units
        bones, cubes, rest, F, scale = convert(gl, spec.get('rename', {}), units=16.0)
    # Stand it on the ground: a model whose lowest point floats above y=0 would hover in the game.
    floor = min(float(c['lo'][1]) for c in cubes)
    for item in bones + cubes:
        item['pivot'] = item['pivot'] - np.array([0, floor, 0])
        if 'lo' in item:
            item['lo'] = item['lo'] - np.array([0, floor, 0])
    density = spec.get('density') or texel_density(cubes)
    atlas, size = bake(cubes, density)
    model = geo(name, bones, cubes, size)
    names = {b['name'] for b in bones}
    anims, from_source = clips(gl, rest, F, scale, spec.get('plan', 'generic'), names, spec.get('source_clips', True))
    for sub in ('geo/entity', 'animations/entity', 'textures/entity/' + name):
        os.makedirs(os.path.join(ASSETS, sub), exist_ok=True)
    tex_dir = os.path.join(ASSETS, 'textures', 'entity', name)
    for f in os.listdir(tex_dir):
        os.remove(os.path.join(tex_dir, f))
    geo_path = os.path.join(ASSETS, 'geo', 'entity', name + '.geo.json')
    json.dump(model, open(geo_path, 'w', newline='\n'), indent=1)
    json.dump({'format_version': '1.8.0', 'animations': anims},
              open(os.path.join(ASSETS, 'animations', 'entity', name + '.animation.json'), 'w', newline='\n'), indent=1)
    atlas.save(os.path.join(tex_dir, spec.get('variant', name) + '.png'))
    # The rest pose (bone rotations included) decides where the feet are: a curled-up rest pose can float.
    import render
    rest_min = min(float(w[:, 1].min()) for _, _, w in render.posed_cubes(render.Model(name)))
    if abs(rest_min) > 0.01:
        for b in model['minecraft:geometry'][0]['bones']:
            if b['name'] != 'root':  # root stays on the ground: the death animation squashes toward its pivot
                b['pivot'][1] = round(b['pivot'][1] - rest_min, 4)
            for c in b.get('cubes', []):
                c['origin'][1] = round(c['origin'][1] - rest_min, 4)
                if 'pivot' in c:
                    c['pivot'][1] = round(c['pivot'][1] - rest_min, 4)
        json.dump(model, open(geo_path, 'w', newline='\n'), indent=1)
    scales_path = os.path.join(ASSETS, 'creatures', 'scales.json')  # NOT under geo/: GeckoLib loads every json there as a model
    scales = json.load(open(scales_path, encoding='utf-8')) if os.path.exists(scales_path) else {}
    scales[name] = spec.get('scale', 1.0)
    json.dump(dict(sorted(scales.items())), open(scales_path, 'w', newline=chr(10)), indent=1)
    credit(name, meta, [('textures/entity/%s/%s.png' % (name, spec.get('variant', name))), 'geo/entity/%s.geo.json' % name])
    print('%s: %d bones, %d cubes, atlas %d (x%d texels/unit), clips from the source: %s; source clip names: %s' % (
        name, len(bones), len(cubes), size, density, from_source or 'none', [a.get('name') for a in gl.g.get('animations', [])][:20]))


def credit(name, meta, paths):
    path = os.path.join(ASSETS, 'CREDITS.txt')
    lines = [l for l in open(path, encoding='utf-8').read().splitlines()
             if not (l.startswith('textures/entity/%s/' % name) or l.startswith('geo/entity/%s.geo.json' % name))]
    lic = 'CC BY 4.0' if meta['licence'] == 'CC Attribution' else 'CC0'
    for p in paths:
        lines.append('%s | Sketchfab "%s" (re-baked to box UV by tools/blockbench/import/gltf.py) | %s (%s) | %s | %s' % (
            p, meta['name'].replace('|', '/'), meta['author'], meta['username'], lic, meta['url']))
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    for n in sys.argv[1:] or IMPORTS:
        install(n, IMPORTS[n])
