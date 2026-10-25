"""Software renderer for the creature archetypes: draws what the game draws, without WebGL (the headless and desktop
Blockbench viewports render blank on the dev machine). Reads the INSTALLED assets: geo.json, the mask + variants.json
(coloured exactly as client/MonsterTextures does) and the animation JSON, posed at any time.

Conventions, matching GeckoLib 4 (verified against its render path):
- The geo file is X-mirrored from Blockbench and GeckoLib mirrors it back, so we work in Blockbench (editor) space:
  cube x range = -(origin.x + size.x) .. -origin.x, pivot x negated.
- Rotations (bone rest + animation keys, both in file convention) become editor angles (-x, -y, z); a bone's matrix is
  Rz * Ry * Rx (right-handed), applied around its pivot, after its animated position (x negated) and before its scale.
- In the world the model's -Z is its front, and editor +X is the creature's RIGHT side.

Usage: python tools/blockbench/mobs/render.py [archetype ...]   (default: all)
Writes tools/blockbench/out/review/<model>/{sheet,variants,anim}.png"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'dndsheets')
OUT = os.path.join(HERE, '..', 'out', 'review')
ROLES = ['base', 'dark', 'belly', 'horn', 'membrane', 'eye']
BG = (46, 42, 52)


# --- loading ---------------------------------------------------------------------------------------------------------

class Model:
    def __init__(self, name):
        self.name = name
        geo = json.load(open(os.path.join(ASSETS, 'geo', 'entity', name + '.geo.json'), encoding='utf-8'))['minecraft:geometry'][0]
        self.tw, self.th = geo['description']['texture_width'], geo['description']['texture_height']
        self.bones = {}
        for b in geo['bones']:
            px, py, pz = b.get('pivot', [0, 0, 0])
            rx, ry, rz = b.get('rotation', [0, 0, 0])
            cubes = []
            for c in b.get('cubes', []):
                (ox, oy, oz), (sx, sy, sz) = c['origin'], c['size']
                g = c.get('inflate', 0.0)  # grows the box, not its texture rectangle
                # A cube's own rotation (Blockbench models use it; Java ones never do) turns it about its own pivot,
                # before the bone's transform. Same sign conventions as the bones.
                local = np.eye(4)
                if any(c.get('rotation', [0, 0, 0])):
                    cx, cy, cz = c.get('pivot', [0, 0, 0])
                    rx_, ry_, rz_ = c['rotation']
                    P, Pi, Rc = np.eye(4), np.eye(4), np.eye(4)
                    P[:3, 3], Pi[:3, 3] = [-cx, cy, cz], [cx, -cy, -cz]
                    Rc[:3, :3] = rot([-rx_, -ry_, rz_])
                    local = P @ Rc @ Pi
                cubes.append(dict(lo=np.array([-(ox + sx) - g, oy - g, oz - g], float), size=np.array([sx + 2 * g, sy + 2 * g, sz + 2 * g], float),
                                  uv=c['uv'], uv_size=np.array([sx, sy, sz], float), local=local))
            self.bones[b['name']] = dict(parent=b.get('parent'), pivot=np.array([-px, py, pz], float),
                                         rest=np.array([-rx, -ry, rz], float), cubes=cubes)
        tex = os.path.join(ASSETS, 'textures', 'entity', name)
        if os.path.exists(os.path.join(tex, 'mask.png')):
            self.mask = np.array(Image.open(os.path.join(tex, 'mask.png')).convert('RGBA'), dtype=np.int32)
            self.palettes = json.load(open(os.path.join(tex, 'variants.json'), encoding='utf-8'))
            self.pngs = {}
        else:  # an imported model: one finished PNG per variant (MonsterTextures draws it as is)
            self.pngs = {f[:-4]: os.path.join(tex, f) for f in sorted(os.listdir(tex)) if f.endswith('.png')}
            self.palettes = {v: None for v in self.pngs}
            self.mask = np.array(self.texture(next(iter(self.pngs))), dtype=np.int32)
        path = os.path.join(ASSETS, 'animations', 'entity', name + '.animation.json')
        self.animations = json.load(open(path, encoding='utf-8'))['animations'] if os.path.exists(path) else {}

    def texture(self, variant):
        """The mask coloured with one palette: the same formula as MonsterTextures.colour (RGBA mask, R=dark mix,
        G=role*40, B=whiten). An imported model's variant is its own PNG, scaled to the geo's texture size."""
        if self.pngs:
            img = Image.open(self.pngs.get(variant, next(iter(self.pngs.values())))).convert('RGBA')
            if img.size != (self.tw, self.th):
                img = img.resize((self.tw, self.th), Image.NEAREST)
            return np.array(img, float)
        pal = self.palettes[variant] if variant in self.palettes else derived(variant)
        P = np.array([pal[r] for r in ROLES], float)
        m = self.mask
        role = np.minimum(m[..., 1] // 40, len(ROLES) - 1)
        dark, white = m[..., 0, None] / 255.0, m[..., 2, None] / 255.0
        base, shade = P[role], P[1][None, None, :]
        col = base + (shade - base) * dark
        col = col + (255 - col) * white
        rgb = np.clip(np.round(col), 0, 255)
        return np.concatenate([rgb, m[..., 3:4].astype(float)], axis=-1)


def derived(hexcode):
    c = [int(hexcode[i:i + 2], 16) for i in (1, 3, 5)]
    toward = lambda t: [round(v + (255 - v) * t) for v in c]
    return dict(base=c, dark=[round(v * 0.6) for v in c], belly=toward(0.55), horn=toward(0.75),
                membrane=[round(v * 0.85) for v in c], eye=[255, 228, 90])


# --- posing ----------------------------------------------------------------------------------------------------------

def rot(deg):
    x, y, z = np.radians(deg)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    Rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def sample(frames, t, default):
    """frames: {"time": [x, y, z]} (Bedrock keys); linear interpolation, clamped at both ends."""
    if not frames:
        return np.array(default, float)
    if isinstance(frames, list):
        return np.array(frames, float)
    ks = sorted((float(k), v) for k, v in frames.items())
    if t <= ks[0][0]:
        return np.array(ks[0][1], float)
    for (t0, v0), (t1, v1) in zip(ks, ks[1:]):
        if t <= t1:
            a = (t - t0) / (t1 - t0) if t1 > t0 else 1.0
            return np.array(v0, float) + (np.array(v1, float) - np.array(v0, float)) * a
    return np.array(ks[-1][1], float)


def bone_matrices(model, anim=None, t=0.0):
    """World 4x4 matrix per bone (editor space) for animation `anim` at time t (None = rest pose)."""
    chans = model.animations.get(anim, {}).get('bones', {}) if anim else {}
    out = {}

    def world(name):
        if name in out:
            return out[name]
        b = model.bones[name]
        ch = chans.get(name, {})
        r = sample(ch.get('rotation'), t, [0, 0, 0])
        p = sample(ch.get('position'), t, [0, 0, 0])
        s = sample(ch.get('scale'), t, [1, 1, 1])
        angles = b['rest'] + np.array([-r[0], -r[1], r[2]])
        M = np.eye(4)
        M[:3, 3] = [-p[0], p[1], p[2]]
        P, Pinv = np.eye(4), np.eye(4)
        P[:3, 3], Pinv[:3, 3] = b['pivot'], -b['pivot']
        R, S = np.eye(4), np.eye(4)
        R[:3, :3] = rot(angles)
        S[:3, :3] = np.diag(s)
        local = M @ P @ R @ S @ Pinv
        out[name] = (world(b['parent']) @ local) if b['parent'] else local
        return out[name]

    for n in model.bones:
        world(n)
    return out


CORNERS = np.array([[i, j, k] for i in (0, 1) for j in (0, 1) for k in (0, 1)], float)


def posed_cubes(model, anim=None, t=0.0):
    """[(bone, cube, corners 8x3 in world)] for the pose: what lint.py measures (ground, detachment, bounds)."""
    mats = bone_matrices(model, anim, t)
    out = []
    for name, b in model.bones.items():
        M = mats[name]
        for c in b['cubes']:
            pts = c['lo'] + CORNERS * c['size']
            w = (M @ c['local'] @ np.c_[pts, np.ones(8)].T).T[:, :3]
            out.append((name, c, w))
    return out


# --- drawing ---------------------------------------------------------------------------------------------------------

def faces(c):
    """Box-UV faces: (corner origin, edge u, edge v, normal, texture rect (x, y, w, h)) in cube-local units.
    The texture rect's top row maps to the top of side faces."""
    w, h, d = (int(v) for v in c['uv_size'])
    u, v = c['uv']
    lo, sz = c['lo'], c['size']
    X, Y, Z = np.array([sz[0], 0, 0]), np.array([0, sz[1], 0]), np.array([0, 0, sz[2]])
    o = lo
    return [
        (o + Y + Z, X, -Z, (0, 1, 0), (u + d, v, w, d)),             # up (texture top = back edge)
        (o, X, Z, (0, -1, 0), (u + d + w, v, w, d)),                 # down
        (o + Y + X, Z, -Y, (1, 0, 0), (u, v + d, d, h)),             # +X side
        (o + Y, X, -Y, (0, 0, -1), (u + d, v + d, w, h)),            # front (-Z)
        (o + Y + Z, -Z, -Y, (-1, 0, 0), (u + d + w, v + d, d, h)),   # -X side
        (o + Y + Z + X, -X, -Y, (0, 0, 1), (u + 2 * d + w, v + d, w, h)),  # back (+Z)
    ]


def view_matrix(yaw, pitch):
    """World -> view: x right, y up, z toward the viewer. yaw 180 = looking at the model's front."""
    return rot([pitch, 0, 0]) @ rot([0, yaw, 0])


VIEWS = {'front': (180, 0), 'right': (-90, 0), 'back': (0, 0), 'left': (90, 0), 'three-quarter': (215, 22), 'top': (180, 89)}


def project(model, tex, mats, V):
    """All visible face quads in view space with their texture rects."""
    quads = []
    for name, b in model.bones.items():
        for c in b['cubes']:
            M = mats[name] @ c['local']
            R3 = V @ M[:3, :3]
            for o, eu, ev, n, rect in faces(c):
                if rect[2] <= 0 or rect[3] <= 0:
                    continue
                O = V @ (M @ np.r_[o, 1])[:3]
                Eu, Ev = R3 @ eu, R3 @ ev
                N = R3 @ np.array(n, float)
                if N[2] <= 1e-6:
                    continue  # back face
                light = 0.55 + 0.45 * max(0.0, (M[:3, :3] @ np.array(n, float)) @ np.array([0.35, 0.85, -0.4]))
                quads.append((O, Eu, Ev, rect, light))
    return quads


def raster(quads, tex, scale, size, center):
    W, H = size
    img = np.zeros((H, W, 3), float)
    img[:] = BG
    depth = np.full((H, W), -1e9)
    for O, Eu, Ev, (tx, ty, tw, th), light in quads:
        o2 = (O[:2] - center) * scale + np.array([W / 2, H / 2])
        u2, v2 = Eu[:2] * scale, Ev[:2] * scale
        pts = np.array([o2, o2 + u2, o2 + v2, o2 + u2 + v2])
        x0, y0 = np.floor(pts.min(0)).astype(int)
        x1, y1 = np.ceil(pts.max(0)).astype(int)
        x0, y0, x1, y1 = max(x0, 0), max(y0, 0), min(x1, W), min(y1, H)
        if x1 <= x0 or y1 <= y0:
            continue
        A = np.array([[u2[0], v2[0]], [u2[1], v2[1]]])
        if abs(np.linalg.det(A)) < 1e-9:
            continue
        Ai = np.linalg.inv(A)
        xs, ys = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
        rel = np.stack([xs - o2[0], ys - o2[1]], -1)
        st = rel @ Ai.T
        s, t = st[..., 0], st[..., 1]
        inside = (s >= 0) & (s < 1) & (t >= 0) & (t < 1)
        if not inside.any():
            continue
        z = O[2] + s * Eu[2] + t * Ev[2]
        px = np.clip((tx + s * tw).astype(int), tx, tx + tw - 1)
        py = np.clip((ty + t * th).astype(int), ty, ty + th - 1)
        texel = tex[np.clip(py, 0, tex.shape[0] - 1), np.clip(px, 0, tex.shape[1] - 1)]
        sub = depth[y0:y1, x0:x1]
        ok = inside & (texel[..., 3] > 0) & (z > sub)
        sub[ok] = z[ok]
        img[y0:y1, x0:x1][ok] = texel[..., :3][ok] * light
    return Image.fromarray(np.flipud(np.clip(img, 0, 255).astype(np.uint8)))


def bounds(model, V, poses):
    pts = []
    for anim, t in poses:
        for _, _, w in posed_cubes(model, anim, t):
            pts.append(w @ V.T)
    p = np.vstack(pts)
    return p[:, :2].min(0), p[:, :2].max(0)


def frame(model, tex, view, anim=None, t=0.0, size=(256, 256), fit=None):
    V = view_matrix(*VIEWS[view])
    lo, hi = fit if fit else bounds(model, V, [(anim, t)])
    scale = 0.85 * min(size[0] / max(hi[0] - lo[0], 1), size[1] / max(hi[1] - lo[1], 1))
    center = (lo + hi) / 2
    return raster(project(model, tex, bone_matrices(model, anim, t), V), tex, scale, size, center)


def label(img, text):
    ImageDraw.Draw(img).text((4, 2), text, fill=(235, 230, 220))
    return img


def grid(images, cols):
    w, h = images[0].size
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new('RGB', (w * cols, h * rows), BG)
    for i, im in enumerate(images):
        sheet.paste(im, ((i % cols) * w, (i // cols) * h))
    return sheet


def key_times(model, anim):
    """The keyframe times of a clip (start, every key, end): the poses worth looking at."""
    a = model.animations[anim]
    ts = {0.0, float(a.get('animation_length', 1.0))}
    for ch in a['bones'].values():
        for frames in ch.values():
            if isinstance(frames, dict):
                ts.update(float(k) for k in frames)
    ts = sorted(ts)
    if len(ts) > 6:  # sine sways: keep six evenly spread samples
        ts = [ts[round(i * (len(ts) - 1) / 5)] for i in range(6)]
    return ts


def review(name, out_dir=OUT):
    model = Model(name)
    d = os.path.join(out_dir, name)
    os.makedirs(d, exist_ok=True)
    first = next(iter(model.palettes))
    tex = model.texture(first)
    paths = []

    sheet = grid([label(frame(model, tex, v), '%s %s' % (v, first)) for v in VIEWS], 3)
    paths.append(os.path.join(d, 'sheet.png'))
    sheet.save(paths[-1])

    fit = bounds(model, view_matrix(*VIEWS['three-quarter']), [(None, 0)])
    variants = [label(frame(model, model.texture(v), 'three-quarter', size=(192, 192), fit=fit), v) for v in model.palettes]
    paths.append(os.path.join(d, 'variants.png'))
    grid(variants, min(5, len(variants))).save(paths[-1])

    rows = []
    for anim in ('idle', 'fly', 'attack', 'hurt', 'death'):
        if anim not in model.animations:
            continue
        ts = key_times(model, anim)
        V = view_matrix(*VIEWS['right'])
        fit = bounds(model, V, [(anim, t) for t in ts] + [(None, 0)])
        rows.append([label(frame(model, tex, 'right', anim, t, size=(160, 160), fit=fit), '%s %.2fs' % (anim, t)) for t in ts])
    if rows:
        cols = max(len(r) for r in rows)
        blank = Image.new('RGB', (160, 160), BG)
        paths.append(os.path.join(d, 'anim.png'))
        grid([im for r in rows for im in r + [blank] * (cols - len(r))], cols).save(paths[-1])
    return paths


def all_models():
    d = os.path.join(ASSETS, 'geo', 'entity')
    return sorted(f[:-len('.geo.json')] for f in os.listdir(d) if f.endswith('.geo.json'))


if __name__ == '__main__':
    for n in sys.argv[1:] or all_models():
        for p in review(n):
            print(os.path.relpath(p, REPO))
