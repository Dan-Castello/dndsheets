"""Body plans shared by the creature archetypes: each builds bones + cubes from a few proportions and returns the
anchor points the archetype hangs its extras on (ears, horns, tusks, tails, wings), plus one animation set per plan.
So an archetype script is proportions + extras + palettes, and every biped animates the same way.

Conventions (as the dragon): the model faces -Z, +Y is up, '_r' is +X in Blockbench, which GeckoLib draws as the
creature's RIGHT side (checked against its bytecode, see render.py), and '_l' its mirror at -X.
All rotations here (bone rest poses and animation keys) are in the Bedrock FILE convention, as GeckoLib reads them;
mobkit converts the rest poses for the .bbmodel.
Bedrock rotation signs used in the animations: on X, negative swings a hanging limb forward and lifts a forward-pointing
part (jaw tip, neck); positive tilts a standing part (torso, head) forward. On Z, positive lifts a +X limb (left wing)."""
import math
from mobkit import Cube, keys


class Body:
    def __init__(self):
        self.bones, self.cubes = [], []

    def bone(self, name, parent, pivot, rot=(0, 0, 0)):
        self.bones.append((name, parent, list(pivot), list(rot)))

    def bone_pair(self, name, parent_l, parent_r, pivot, rot=(0, 0, 0)):
        x, y, z = pivot
        self.bone(name + '_r', parent_l, (x, y, z), rot)
        self.bone(name + '_l', parent_r, (-x, y, z), (rot[0], -rot[1], -rot[2]))

    def box(self, name, bone, a, b, role='base'):
        self.cubes.append(Cube(name, bone, tuple(a), tuple(b), role))

    def pair(self, name, bone_l, bone_r, a, b, role='base'):
        """Left cube + mirrored right cube sharing one painted net."""
        (ax, ay, az), (bx, by, bz) = a, b
        self.cubes.append(Cube(name + '_r', bone_l, (ax, ay, az), (bx, by, bz), role, uvkey=name))
        self.cubes.append(Cube(name + '_l', bone_r, (-bx, ay, az), (-ax, by, bz), role, uvkey=name))


# --- animation helpers ---------------------------------------------------------------------------------------------

def anim(length, bones, loop=True):
    a = {'animation_length': length, 'bones': {b: {ch: keys(length, fr) for ch, fr in chans.items()} for b, chans in bones.items()}}
    a['loop'] = True if loop else 'hold_on_last_frame'
    return a


def sway(length, amp, axis=1, phase=0.0, n=8, base=0.0):
    """A sine loop on one axis, sampled n times (GeckoLib interpolates linearly between)."""
    out = {}
    for i in range(n + 1):
        v = [0.0, 0.0, 0.0]
        v[axis] = round(base + amp * math.sin(2 * math.pi * (i / n + phase)), 2)
        out[round(length * i / n, 3)] = v
    return out


def spin(length, turns=1):
    return {round(length * i / 4, 3): [0, 90 * i * turns, 0] for i in range(5)}


def mirror(chans):
    """The right-side copy of a left-side channel set: Y and Z rotations flip."""
    out = {}
    for ch, frames in chans.items():
        out[ch] = {t: ([v[0], -v[1], -v[2]] if ch == 'rotation' else ([-v[0], v[1], v[2]] if ch == 'position' else v)) for t, v in frames.items()}
    return out


def both(name, chans):
    return {name + '_r': chans, name + '_l': mirror(chans)}


# --- biped ---------------------------------------------------------------------------------------------------------

def biped(leg=(3, 10), gap=1, torso=(8, 10, 5), head=(8, 7, 7), arm=(3, 12), hunch=0, sink=0, head_fwd=0,
          clothes=('torso', 'legs'), arm_rest=0, chest=True):
    """Two legs, torso, head, two arms (with empty item_r/item_l bones at the hands for held items).
    sink: how far the head sits down between the shoulders. Returns (body, info)."""
    b = Body()
    lw, lh = leg
    tw, th, td = torso
    hw, hh, hd = head
    aw, al = arm
    top = lh + th
    hy0 = top - sink
    hz0 = -hd // 2 - head_fwd
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, lh, 0), (hunch, 0, 0))
    b.bone('head', 'body', (0, top, 0), (-round(hunch * 0.7), 0, 0))
    b.bone_pair('arm', 'body', 'body', (tw // 2 + aw // 2, top - 1, 0), (arm_rest, 0, 0))
    b.bone('item_l', 'arm_l', (-(tw // 2 + aw // 2), top - al + 1, -1))
    b.bone('item_r', 'arm_r', (tw // 2 + aw // 2, top - al + 1, -1))
    b.bone_pair('leg', 'root', 'root', (gap + lw // 2, lh, 0))

    half = lw // 2
    b.pair('leg', 'leg_r', 'leg_l', (gap, 1, -half), (gap + lw, lh, lw - half), 'membrane' if 'legs' in clothes else 'base')
    b.pair('foot', 'leg_r', 'leg_l', (gap, 0, -half - 1), (gap + lw, 1, lw - half), 'dark')
    b.box('torso', 'body', (-tw // 2, lh, -td // 2), (tw - tw // 2, top, td - td // 2), 'membrane' if 'torso' in clothes else 'base')
    if chest:
        b.box('chest', 'body', (-tw // 2 + 1, lh + th // 2, -td // 2 - 1), (tw - tw // 2 - 1, top - 1, -td // 2), 'belly')
    b.box('head', 'head', (-hw // 2, hy0, hz0), (hw - hw // 2, hy0 + hh, hz0 + hd))
    ey = hy0 + max(2, hh * 11 // 20)
    b.pair('eye', 'head', 'head', (1, ey, hz0 - 1), (min(3, hw // 2), ey + 1, hz0), 'eye')
    b.pair('arm', 'arm_r', 'arm_l', (tw - tw // 2, top - al, -aw // 2), (tw - tw // 2 + aw, top, aw - aw // 2))
    b.pair('hand', 'arm_r', 'arm_l', (tw - tw // 2, top - al - 1, -aw // 2), (tw - tw // 2 + aw, top - al, aw - aw // 2), 'dark')
    info = dict(lh=lh, top=top, tw=tw, td=td, hw=hw, hh=hh, hd=hd, hx=hw - hw // 2, hy0=hy0, hy1=hy0 + hh, hz0=hz0, hz1=hz0 + hd,
                ey=ey, shoulder=top - 1, back=td - td // 2, aw=aw, al=al)
    return b, info


def biped_tail(b, info, length=12, width=3, tip_role='base', segments=2):
    y = info['lh'] + 1
    z = info['back']
    seg = length // segments
    parent = 'body'
    for i in range(segments):
        w = max(1, width - i)
        name = 'tail%d' % (i + 1)
        b.bone(name, parent, (0, y + w // 2, z), (-35 if i == 0 else 10, 0, 0))
        b.box(name, name, (-(w // 2), y, z), (w - w // 2, y + w, z + seg))
        parent, z = name, z + seg
    b.box('tail_tip', parent, (-1, y - 1, z), (1, y + 2, z + 3), tip_role)


def biped_wings(b, info, span=16, height=12):
    """Bat wings on the back: a bone bar along the top edge and a membrane hanging under it, swept back."""
    sy, z = info['shoulder'], info['back']
    b.bone_pair('wing', 'body', 'body', (2, sy, z), (0, 25, 15))
    b.pair('wing_bar', 'wing_r', 'wing_l', (2, sy - 1, z), (2 + span, sy + 1, z + 2), 'horn')
    b.pair('wing_skin', 'wing_r', 'wing_l', (3, sy - height, z + 1), (2 + span, sy - 1, z + 2), 'membrane')


def horns(b, info, rise=4, out=2, thick=2, curl=True):
    """A pair of horns from the top corners of the head, curling outward and back."""
    hx, y, z = info['hx'], info['hy1'], info['hz0'] + 2
    b.pair('horn', 'head', 'head', (hx - thick, y, z), (hx, y + rise, z + thick), 'horn')
    if curl:
        b.pair('horn_tip', 'head', 'head', (hx, y + rise - thick, z + thick - 1), (hx + out, y + rise, z + thick + 1), 'horn')


def pointed_ears(b, info, length=4):
    hx, y, z = info['hx'], info['ey'], info['hz0'] + 2
    b.pair('ear', 'head', 'head', (hx, y, z), (hx + length, y + 2, z + 1))
    b.pair('ear_tip', 'head', 'head', (hx + length, y + 1, z), (hx + length + 2, y + 3, z + 1), 'dark')


def tusks(b, info):
    y, z = info['hy0'], info['hz0']
    b.pair('tusk', 'head', 'head', (1, y, z - 1), (2, y + 3, z), 'horn')


def brow(b, info):
    hx = info['hx']
    b.box('brow', 'head', (-hx, info['ey'] + 1, info['hz0'] - 1), (hx, info['ey'] + 2, info['hz0']), 'dark')


def snout(b, info, w=4, h=3, length=4, role='base'):
    y, z = info['hy0'], info['hz0']
    b.box('snout', 'head', (-w // 2, y, z - length), (w - w // 2, y + h, z), role)
    b.box('nostril', 'head', (-w // 2, y + h - 1, z - length - 1), (w - w // 2, y + h, z - length), 'dark')


def biped_anims(info, heavy=False):
    """idle / fly (wings, legs dangling) / attack (right arm overhead swing) / hurt / death (falls on its back)."""
    slow = 1.4 if heavy else 1.0
    return {
        'idle': anim(3.0, {
            'body': {'position': {0: [0, 0, 0], 1.5: [0, 0.4, 0], 3: [0, 0, 0]}},
            'head': {'rotation': sway(3.0, 7)},
            'arm_r': {'rotation': sway(3.0, 3, axis=0)},
            'arm_l': {'rotation': sway(3.0, 3, axis=0, phase=0.5)},
            'tail1': {'rotation': sway(3.0, 12)},
            'tail2': {'rotation': sway(3.0, 16, phase=0.2)},
            'wing_r': {'rotation': sway(3.0, 4, axis=2)},
            'wing_l': {'rotation': sway(3.0, -4, axis=2)},
        }),
        'fly': anim(0.8, {
            'root': {'position': {0: [0, 4, 0], 0.4: [0, 3, 0], 0.8: [0, 4, 0]}},
            **both('wing', {'rotation': {0: [0, 0, 25], 0.4: [0, 0, -30], 0.8: [0, 0, 25]}}),
            'leg_r': {'rotation': {0: [15, 0, 0], 0.8: [15, 0, 0]}},
            'leg_l': {'rotation': {0: [22, 0, 0], 0.8: [22, 0, 0]}},
            'tail1': {'rotation': sway(0.8, 10)},
        }),
        'attack': anim(0.7 * slow, {
            'arm_r': {'rotation': {0: [0, 0, 0], 0.25 * slow: [-140, 0, 0], 0.45 * slow: [-20, 0, 0], 0.7 * slow: [0, 0, 0]}},
            'arm_l': {'rotation': {0: [0, 0, 0], 0.25 * slow: [-25, 0, 0], 0.7 * slow: [0, 0, 0]}},
            'body': {'rotation': {0: [0, 0, 0], 0.25 * slow: [-6, 12, 0], 0.45 * slow: [10, -14, 0], 0.7 * slow: [0, 0, 0]}},
        }, loop=False),
        'hurt': anim(0.4, {
            'body': {'rotation': {0: [0, 0, 0], 0.1: [-10, 0, 0], 0.4: [0, 0, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 0.1: [-15, 0, 0], 0.4: [0, 0, 0]}},
        }, loop=False),
        'death': anim(1.2 * slow, {
            'root': {'rotation': {0: [0, 0, 0], 0.9 * slow: [-88, 0, 0], 1.2 * slow: [-90, 0, 0]},
                     'position': {0: [0, 0, 0], 0.9 * slow: [0, info['back'] + 1, 0]}},
            'tail1': {'rotation': {0: [0, 0, 0], 0.9 * slow: [125, 0, 0]}},
            'tail2': {'rotation': {0: [0, 0, 0], 0.9 * slow: [-10, 0, 0]}},
            **both('wing', {'rotation': {0: [0, 0, 0], 0.9 * slow: [0, -25, -15]}}),  # flat on the ground, not under it
            **both('arm', {'rotation': {0: [0, 0, 0], 0.9 * slow: [-20, 0, 40]}}),
            'head': {'rotation': {0: [0, 0, 0], 0.9 * slow: [-15, 0, 0]}},
        }, loop=False),
    }


# --- quadruped -----------------------------------------------------------------------------------------------------

def quadruped(body=(8, 7, 16), leg=(3, 8), head=(6, 6, 5), muzzle=(4, 3, 4), splay=False, rise=2):
    """Four legs, a body, a head with an upper muzzle and an opening jaw. splay = lizard legs out at the sides."""
    b = Body()
    w, h, l = body
    lw, lh = leg
    hw, hh, hl = head
    mw, mh, ml = muzzle
    cy = lh + h // 2
    hy0 = lh + h - hh // 2 + rise
    hz0 = -l // 2 - hl + 2
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, cy, 0))
    b.bone('head', 'body', (0, hy0 + hh // 2, -l // 2))
    b.bone('jaw', 'head', (0, hy0 + 1, hz0))
    lx = w // 2 + (lw // 2 if splay else -lw // 2)
    fz, bz = -l // 2 + lw // 2 + 1, l // 2 - lw // 2 - 1
    for side, sx in (('r', 1), ('l', -1)):
        b.bone('leg_f_' + side, 'body', (sx * lx, lh, fz))
        b.bone('leg_b_' + side, 'body', (sx * lx, lh, bz))
    b.box('body', 'body', (-w // 2, lh, -l // 2), (w - w // 2, lh + h, l - l // 2))
    x0 = w - w // 2 if splay else w - w // 2 - lw
    for name, z in (('leg_f', fz), ('leg_b', bz)):
        b.pair(name, name + '_r', name + '_l', (x0, 1, z - lw // 2), (x0 + lw, lh + (1 if splay else 0), z - lw // 2 + lw))
        b.pair(name.replace('leg', 'paw'), name + '_r', name + '_l', (x0, 0, z - lw // 2 - 1), (x0 + lw, 1, z - lw // 2 + lw), 'claw')
    b.box('head', 'head', (-hw // 2, hy0, hz0), (hw - hw // 2, hy0 + hh, hz0 + hl))
    jh = max(1, mh // 3)
    b.box('muzzle', 'head', (-mw // 2, hy0 + jh, hz0 - ml), (mw - mw // 2, hy0 + mh, hz0))
    b.box('nose', 'head', (-1, hy0 + mh - 1, hz0 - ml - 1), (1, hy0 + mh, hz0 - ml), 'dark')
    b.box('jaw', 'jaw', (-mw // 2 + (1 if mw > 3 else 0), hy0, hz0 - ml + 1), (mw - mw // 2 - (1 if mw > 3 else 0), hy0 + jh, hz0), 'belly')
    ey = hy0 + hh - 2
    if ey < hy0 + mh:  # the muzzle covers the face: eyes on the sides of the head
        b.pair('eye', 'head', 'head', (hw - hw // 2, ey, hz0 + 1), (hw - hw // 2 + 1, ey + 1, hz0 + 2), 'eye')
    else:
        b.pair('eye', 'head', 'head', (hw // 2 - 2, ey, hz0 - 1), (hw // 2 - 1, ey + 1, hz0), 'eye')
    info = dict(w=w, span=w / 2 + (lw if splay else 0), h=h, l=l, lh=lh, cy=cy, hw=hw, hx=hw - hw // 2, hy0=hy0, hy1=hy0 + hh, hz0=hz0, ey=ey, back=l - l // 2,
                top=lh + h)
    return b, info


def quad_tail(b, info, length=10, width=2, droop=-40, segments=2, taper=1):
    y = info['lh'] + info['h'] - 1 - width
    z = info['back']
    seg = length // segments
    parent = 'body'
    for i in range(segments):
        wd = max(1, width - i * taper)
        name = 'tail%d' % (i + 1)
        b.bone(name, parent, (0, y + wd // 2 + 1, z), (droop if i == 0 else 10, 0, 0))
        b.box(name, name, (-(wd // 2), y, z), (wd - wd // 2, y + wd, z + seg))
        parent, z = name, z + seg


def quad_ears(b, info, h=3):
    hx, y, z = info['hx'], info['hy1'], info['hz0'] + 1
    b.pair('ear', 'head', 'head', (hx - 2, y, z), (hx, y + h, z + 1), 'dark')


def quad_anims(info):
    drop = round(info['span'] - info['cy'], 1)  # lying on its side: the widest part rests on the ground
    return {
        'idle': anim(3.0, {
            'body': {'position': {0: [0, 0, 0], 1.5: [0, 0.3, 0], 3: [0, 0, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 0.8: [3, 9, 0], 1.6: [-2, 0, 0], 2.3: [3, -9, 0], 3: [0, 0, 0]}},
            'jaw': {'rotation': {0: [0, 0, 0], 2.6: [0, 0, 0], 2.8: [8, 0, 0], 3: [0, 0, 0]}},
            'tail1': {'rotation': sway(3.0, 14)},
            'tail2': {'rotation': sway(3.0, 18, phase=0.15)},
            'tail3': {'rotation': sway(3.0, 22, phase=0.3)},
        }),
        'attack': anim(0.7, {
            'body': {'position': {0: [0, 0, 0], 0.2: [0, 0, 1.5], 0.4: [0, 0, -2.5], 0.7: [0, 0, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 0.2: [-15, 0, 0], 0.4: [20, 0, 0], 0.7: [0, 0, 0]}},
            'jaw': {'rotation': {0: [0, 0, 0], 0.2: [38, 0, 0], 0.42: [0, 0, 0]}},
            'leg_f_r': {'rotation': {0: [0, 0, 0], 0.3: [-30, 0, 0], 0.7: [0, 0, 0]}},
            'leg_f_l': {'rotation': {0: [0, 0, 0], 0.35: [-20, 0, 0], 0.7: [0, 0, 0]}},
        }, loop=False),
        'hurt': anim(0.4, {
            'body': {'rotation': {0: [0, 0, 0], 0.1: [0, 0, 6], 0.25: [0, 0, -4], 0.4: [0, 0, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 0.1: [-15, 0, 0], 0.4: [0, 0, 0]}},
            'jaw': {'rotation': {0: [0, 0, 0], 0.1: [20, 0, 0], 0.4: [0, 0, 0]}},
        }, loop=False),
        'death': anim(1.3, {
            'body': {'rotation': {0: [0, 0, 0], 1.0: [0, 0, 84], 1.3: [0, 0, 90]}, 'position': {0: [0, 0, 0], 1.0: [0, drop, 0], 1.3: [0, drop, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 1.0: [10, 0, 0]}},
            'jaw': {'rotation': {0: [0, 0, 0], 1.0: [25, 0, 0]}},
        }, loop=False),
    }


# --- serpent -------------------------------------------------------------------------------------------------------

def serpent(segments=6, length=5, width=4, head=(5, 4, 6), neck=6):
    """A body of tapering segments along +Z on the ground, a neck rising at the front and a head with a jaw."""
    b = Body()
    hw, hh, hl = head
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, width // 2, 0))
    b.bone('neck', 'body', (0, width // 2, 0), (-45, 0, 0))
    b.bone('head', 'neck', (0, width // 2, -neck), (45, 0, 0))
    b.bone('jaw', 'head', (0, 1, -neck))
    b.box('neck', 'neck', (-width // 2, 0, -neck), (width - width // 2, width, 0))
    b.box('head', 'head', (-hw // 2, 1, -neck - hl), (hw - hw // 2, 1 + hh, -neck + 1))
    b.box('jaw', 'jaw', (-hw // 2 + 1, 0, -neck - hl + 1), (hw - hw // 2 - 1, 1, -neck), 'belly')
    b.pair('eye', 'head', 'head', (hw // 2 - 2, hh - 1, -neck - hl - 1), (hw // 2 - 1, hh, -neck - hl), 'eye')
    b.box('tongue', 'jaw', (0, 0, -neck - hl - 2), (1, 1, -neck - hl + 1), 'membrane')
    parent, z = 'body', 0
    for i in range(segments):
        w = max(1, width - (i * width) // (segments + 1))
        name = 'seg%d' % (i + 1)
        b.bone(name, parent, (0, w // 2, z))
        b.box(name, name, (-(w // 2), 0, z), (w - w // 2, w, z + length))
        if i < segments - 1 and i % 2 == 0:
            b.box(name + '_ridge', name, (0, w, z + 1), (1, w + 1, z + length - 1), 'dark')
        parent, z = name, z + length
    b.box('rattle', parent, (0, 0, z), (1, 1, z + 2), 'horn')
    return b, dict(segments=segments, width=width)


def serpent_anims(info):
    n = info['segments']
    sw = {'seg%d' % (i + 1): {'rotation': sway(2.4, 10 + i, phase=-0.12 * i)} for i in range(n)}
    return {
        'idle': anim(2.4, {**sw, 'neck': {'rotation': sway(2.4, 4, axis=0)}, 'head': {'rotation': sway(2.4, 8)},
                           'jaw': {'rotation': {0: [0, 0, 0], 2.0: [0, 0, 0], 2.2: [12, 0, 0], 2.4: [0, 0, 0]}}}),
        'fly': anim(2.4, {**sw, 'body': {'position': {0: [0, 0, 0], 1.2: [0, 1.5, 0], 2.4: [0, 0, 0]}}}),
        'attack': anim(0.6, {
            'neck': {'rotation': {0: [0, 0, 0], 0.2: [-20, 0, 0], 0.38: [18, 0, 0], 0.6: [0, 0, 0]}},
            'head': {'rotation': {0: [0, 0, 0], 0.38: [10, 0, 0], 0.6: [0, 0, 0]}},
            'jaw': {'rotation': {0: [0, 0, 0], 0.2: [22, 0, 0], 0.45: [0, 0, 0]}},
            'body': {'position': {0: [0, 0, 0], 0.38: [0, 0, -3], 0.6: [0, 0, 0]}},
        }, loop=False),
        'hurt': anim(0.4, {'neck': {'rotation': {0: [0, 0, 0], 0.1: [-20, 0, 0], 0.4: [0, 0, 0]}},
                           'jaw': {'rotation': {0: [0, 0, 0], 0.1: [25, 0, 0], 0.4: [0, 0, 0]}}}, loop=False),
        'death': anim(1.3, {
            'neck': {'rotation': {0: [0, 0, 0], 1.0: [45, 0, 0]}},
            'body': {'rotation': {0: [0, 0, 0], 1.3: [0, 0, 160]}, 'position': {0: [0, 0, 0], 1.3: [0, 3, 0]}},
            **{'seg%d' % (i + 1): {'rotation': {0: [0, 0, 0], 1.0: [0, 14 * (-1) ** i, 0]}} for i in range(n)},
        }, loop=False),
    }


# --- spider --------------------------------------------------------------------------------------------------------

def spider(body=(8, 5, 7), abdomen=(10, 8, 11), leg=(12, 9), eyes=8):
    """Cephalothorax + abdomen, fangs, eight two-segment legs fanned forward to back."""
    b = Body()
    w, h, l = body
    aw, ah, al = abdomen
    reach, lh = leg
    y0 = lh - 2
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, y0 + h // 2, 0))
    b.bone('abdomen', 'body', (0, y0 + h // 2, l // 2), (-12, 0, 0))
    b.bone_pair('fang', 'body', 'body', (2, y0 + 1, -l // 2))
    b.box('body', 'body', (-w // 2, y0, -l // 2), (w - w // 2, y0 + h, l - l // 2))
    b.box('abdomen', 'abdomen', (-aw // 2, y0 - 1, l // 2), (aw - aw // 2, y0 - 1 + ah, l // 2 + al))
    b.box('mark', 'abdomen', (-2, y0 - 1 + ah, l // 2 + 3), (2, y0 + ah, l // 2 + al - 3), 'belly')
    b.pair('fang', 'fang_r', 'fang_l', (1, y0 - 2, -l // 2 - 2), (3, y0 + 1, -l // 2), 'horn')
    for i in range(eyes // 2):
        ex, ey = 1 + (i % 2) * 2, y0 + h - 2 - (i // 2) * 2
        b.pair('eye%d' % i, 'body', 'body', (ex - 1 + (i // 2), ey, -l // 2 - 1), (ex + (i // 2), ey + 1, -l // 2), 'eye')
    for i, yaw in enumerate((-50, -18, 18, 45)):
        z = -l // 2 + 1 + i * (l - 2) // 3
        up, low = 'leg%d' % (i + 1), 'knee%d' % (i + 1)
        my, knee = y0 + h // 2, w // 2 + reach // 2
        # Upper segment angled up to the knee, the lower one bent back down to the ground.
        b.bone_pair(up, 'body', 'body', (w // 2, my, z), (0, yaw, 25))
        b.bone_pair(low, up + '_r', up + '_l', (knee, my, z), (0, 0, -100))
        b.pair(up, up + '_r', up + '_l', (w // 2, my - 1, z - 1), (knee, my + 1, z + 1))
        b.pair(low, low + '_r', low + '_l', (knee, my - 1, z - 1), (knee + reach, my, z), 'dark')
    return b, dict(lh=lh)


def spider_anims(info):
    legs = {}
    for i in range(4):
        legs.update(both('leg%d' % (i + 1), {'rotation': sway(1.6, 4, axis=1, phase=0.25 * i)}))
    curl = {}
    for i in range(4):
        curl.update(both('leg%d' % (i + 1), {'rotation': {0: [0, 0, 0], 0.9: [0, 0, 55]}}))
    return {
        'idle': anim(1.6, {**legs, 'abdomen': {'rotation': sway(1.6, 3, axis=0)},
                           **both('fang', {'rotation': {0: [0, 0, 0], 1.2: [0, 0, 0], 1.4: [0, 8, 0], 1.6: [0, 0, 0]}})}),
        'attack': anim(0.6, {
            'body': {'rotation': {0: [0, 0, 0], 0.2: [-22, 0, 0], 0.38: [10, 0, 0], 0.6: [0, 0, 0]},
                     'position': {0: [0, 0, 0], 0.2: [0, 2, 0], 0.38: [0, 0.5, 0], 0.6: [0, 0, 0]}},
            **both('leg1', {'rotation': {0: [0, 0, 0], 0.2: [0, 0, 45], 0.38: [0, 0, -5], 0.6: [0, 0, 0]}}),
            **both('fang', {'rotation': {0: [0, 0, 0], 0.2: [0, -20, 0], 0.38: [0, 15, 0], 0.6: [0, 0, 0]}}),
        }, loop=False),
        'hurt': anim(0.4, {'body': {'rotation': {0: [0, 0, 0], 0.1: [-8, 0, 5], 0.4: [0, 0, 0]}}}, loop=False),
        'death': anim(1.2, {**curl, 'body': {'position': {0: [0, 0, 0], 0.9: [0, -(info['lh'] - 4), 0]}}}, loop=False),
    }


# --- blob (oozes) --------------------------------------------------------------------------------------------------

def blob(base=(14, 6), mid=(11, 4), top=(7, 3), bubbles=6):
    b = Body()
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, 0, 0))
    b.bone('top', 'body', (0, base[1] + mid[1], 0))
    y = 0
    for name, (w, h), bone in (('base', base, 'body'), ('mid', mid, 'body'), ('top', top, 'top')):
        b.box(name, bone, (-w // 2, y, -w // 2), (w - w // 2, y + h, w - w // 2), 'membrane' if name == 'base' else 'base')
        y += h
    spots = [(-base[0] // 2 - 1, 2, -2), (base[0] // 2, 3, 1), (-2, 1, -base[0] // 2 - 1), (1, 4, base[0] // 2), (-mid[0] // 2 - 1, base[1] + 1, 0), (2, base[1] + 2, -mid[0] // 2 - 1)]
    for i, (sx, sy, sz) in enumerate(spots[:bubbles]):
        b.box('bubble%d' % i, 'body', (sx, sy, sz), (sx + 1, sy + 1, sz + 1), 'eye')
    return b, {}


def blob_anims():
    return {
        'idle': anim(2.0, {'body': {'scale': {0: [1, 1, 1], 1: [1.06, 0.92, 1.06], 2: [1, 1, 1]}},
                           'top': {'position': sway(2.0, 0.6, axis=1), 'rotation': sway(2.0, 4, axis=2)}}),
        'attack': anim(0.6, {'body': {'scale': {0: [1, 1, 1], 0.2: [0.88, 1.18, 0.88], 0.38: [1.12, 0.85, 1.35], 0.6: [1, 1, 1]},
                                      'position': {0: [0, 0, 0], 0.38: [0, 0, -3], 0.6: [0, 0, 0]}}}, loop=False),
        'hurt': anim(0.35, {'body': {'scale': {0: [1, 1, 1], 0.1: [1.15, 0.8, 1.15], 0.35: [1, 1, 1]}}}, loop=False),
        'death': anim(1.2, {'body': {'scale': {0: [1, 1, 1], 1.0: [1.5, 0.12, 1.5], 1.2: [1.55, 0.1, 1.55]}}}, loop=False),
    }


# --- floating elemental --------------------------------------------------------------------------------------------

def elemental(torso=(10, 9, 6), head=(6, 6, 6), fist=(4, 5), float_y=6, swirl=3):
    """A torso over a spinning vortex of shrinking rings, heavy fists on short arms, no legs."""
    b = Body()
    tw, th, td = torso
    hw, hh, hd = head
    fw, fh = fist
    ty = float_y + 4 * swirl
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, ty, 0))
    b.bone('head', 'body', (0, ty + th, 0))
    b.bone_pair('arm', 'body', 'body', (tw // 2 + 1, ty + th - 2, 0))
    b.box('torso', 'body', (-tw // 2, ty, -td // 2), (tw - tw // 2, ty + th, td - td // 2))
    b.box('core', 'body', (-2, ty + 3, -td // 2 - 1), (2, ty + 6, -td // 2), 'eye')
    b.box('head', 'head', (-hw // 2, ty + th, -hd // 2), (hw - hw // 2, ty + th + hh, hd - hd // 2))
    b.pair('eye', 'head', 'head', (1, ty + th + hh // 2, -hd // 2 - 1), (2, ty + th + hh // 2 + 1, -hd // 2), 'eye')
    b.pair('shoulder', 'arm_r', 'arm_l', (tw - tw // 2, ty + th - 4, -2), (tw - tw // 2 + 3, ty + th, 2), 'membrane')
    b.pair('fist', 'arm_r', 'arm_l', (tw - tw // 2, ty + th - 4 - fh, -fw // 2), (tw - tw // 2 + fw, ty + th - 4, fw - fw // 2))
    parent, y, w = 'body', ty, tw - 2
    for i in range(swirl):
        name = 'swirl%d' % (i + 1)
        b.bone(name, parent, (0, y, 0))
        b.box(name, name, (-w // 2, y - 4, -w // 2), (w - w // 2, y, w - w // 2), 'membrane' if i % 2 else 'base')
        parent, y, w = name, y - 4, max(2, w - 3)
    return b, dict(swirl=swirl)


def elemental_anims(info):
    def spins(length):
        return {'swirl%d' % (i + 1): {'rotation': spin(length, (-1) ** i * (i + 1))} for i in range(info['swirl'])}
    idle = {**spins(2.0), 'body': {'position': sway(2.0, 0.8, axis=1)},
            'arm_r': {'rotation': sway(2.0, 6, axis=2)}, 'arm_l': {'rotation': sway(2.0, -6, axis=2, phase=0.5)}}
    return {
        'idle': anim(2.0, idle),
        'fly': anim(2.0, idle),
        'attack': anim(0.6, {**spins(0.6), 'arm_r': {'rotation': {0: [0, 0, 0], 0.15: [-30, 0, 0], 0.32: [-95, 0, 0], 0.6: [0, 0, 0]}},
                             'body': {'rotation': {0: [0, 0, 0], 0.32: [8, -15, 0], 0.6: [0, 0, 0]}}}, loop=False),
        'hurt': anim(0.4, {'body': {'rotation': {0: [0, 0, 0], 0.1: [-12, 0, 0], 0.4: [0, 0, 0]}}}, loop=False),
        'death': anim(1.2, {'body': {'scale': {0: [1, 1, 1], 1.0: [0.15, 0.15, 0.15], 1.2: [0.05, 0.05, 0.05]},
                                     'position': {0: [0, 0, 0], 1.0: [0, -8, 0]}}}, loop=False),
    }


# --- bird ----------------------------------------------------------------------------------------------------------

def bird(body=(6, 6, 10), head=(4, 4, 5), leg=4, beak=3, wing=6):
    """Folded wings at the sides (they spread to horizontal in flight), a fan tail, talons."""
    b = Body()
    w, h, l = body
    hw, hh, hl = head
    lh = leg
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, lh + h // 2, 0), (-10, 0, 0))
    b.bone('head', 'body', (0, lh + h, -l // 2 + 1), (10, 0, 0))
    b.bone_pair('wing', 'body', 'body', (w // 2, lh + h, 0))
    b.bone('tail', 'body', (0, lh + h - 2, l // 2))
    b.bone_pair('leg', 'body', 'body', (1, lh, 0))
    b.box('body', 'body', (-w // 2, lh, -l // 2), (w - w // 2, lh + h, l - l // 2))
    hy, hz = lh + h - 2, -l // 2 - hl + 2
    b.box('head', 'head', (-hw // 2, hy, hz), (hw - hw // 2, hy + hh, hz + hl))
    b.box('beak', 'head', (-1, hy + 1, hz - beak), (1, hy + 3, hz), 'horn')
    b.pair('eye', 'head', 'head', (hw - hw // 2, hy + hh - 2, hz + 1), (hw - hw // 2 + 1, hy + hh - 1, hz + 2), 'eye')
    b.pair('wing', 'wing_r', 'wing_l', (w - w // 2, lh + h - wing, -l // 2 + 1), (w - w // 2 + 1, lh + h, l - l // 2))
    b.pair('primaries', 'wing_r', 'wing_l', (w - w // 2, lh + h - wing + 1, l - l // 2), (w - w // 2 + 1, lh + h - 1, l - l // 2 + 4), 'dark')
    b.box('tail', 'tail', (-2, lh + h - 3, l - l // 2), (2, lh + h - 2, l - l // 2 + 5), 'dark')
    b.pair('leg', 'leg_r', 'leg_l', (1, 1, -1), (2, lh, 0), 'horn')
    b.pair('talon', 'leg_r', 'leg_l', (0, 0, -3), (3, 1, 0), 'horn')
    return b, dict(lh=lh, h=h)


def bird_anims(info):
    return {
        'idle': anim(3.0, {
            'head': {'rotation': {0: [0, 0, 0], 0.6: [0, 0, 0], 0.7: [0, 25, 0], 1.6: [0, 25, 0], 1.7: [5, -20, 0], 2.6: [5, -20, 0], 2.7: [0, 0, 0], 3: [0, 0, 0]}},
            'tail': {'rotation': sway(3.0, 4, axis=0)},
            'body': {'position': {0: [0, 0, 0], 1.5: [0, 0.2, 0], 3: [0, 0, 0]}},
        }),
        'fly': anim(0.6, {
            **both('wing', {'rotation': {0: [0, 0, 60], 0.3: [0, 0, 125], 0.6: [0, 0, 60]}}),
            **both('leg', {'rotation': {0: [45, 0, 0], 0.6: [45, 0, 0]}}),
            'body': {'position': {0: [0, 3, 0], 0.3: [0, 4.2, 0], 0.6: [0, 3, 0]}, 'rotation': {0: [10, 0, 0], 0.6: [10, 0, 0]}},
            'tail': {'rotation': {0: [-10, 0, 0], 0.6: [-10, 0, 0]}},
        }),
        'attack': anim(0.5, {
            'head': {'rotation': {0: [0, 0, 0], 0.18: [-20, 0, 0], 0.32: [35, 0, 0], 0.5: [0, 0, 0]}},
            **both('wing', {'rotation': {0: [0, 0, 0], 0.2: [0, 0, 50], 0.5: [0, 0, 0]}}),
        }, loop=False),
        'hurt': anim(0.4, {'body': {'rotation': {0: [0, 0, 0], 0.1: [0, 0, 10], 0.4: [0, 0, 0]}},
                           **both('wing', {'rotation': {0: [0, 0, 0], 0.1: [0, 0, 40], 0.4: [0, 0, 0]}})}, loop=False),
        'death': anim(1.0, {
            'body': {'rotation': {0: [0, 0, 0], 0.8: [20, 0, 8]}, 'position': {0: [0, 0, 0], 0.8: [0, -info['lh'] + 1, 0]}},
            **both('wing', {'rotation': {0: [0, 0, 0], 0.8: [0, 0, 85]}}),
            **both('leg', {'rotation': {0: [0, 0, 0], 0.8: [80, 0, 0]}}),
        }, loop=False),
    }


# --- fish (sharks, whales) -----------------------------------------------------------------------------------------

def fish(body=(8, 7, 16), head=(6, 5, 7), tail=7, fin=5):
    b = Body()
    w, h, l = body
    hw, hh, hl = head
    y0 = 4
    cy = y0 + h // 2
    b.bone('root', None, (0, 0, 0))
    b.bone('body', 'root', (0, cy, 0))
    b.bone('jaw', 'body', (0, y0 + 1, -l // 2))
    b.bone('tail1', 'body', (0, cy, l // 2))
    b.bone('tail2', 'tail1', (0, cy, l // 2 + tail))
    b.bone_pair('fin', 'body', 'body', (w // 2, y0 + 2, -2))
    b.box('body', 'body', (-w // 2, y0, -l // 2), (w - w // 2, y0 + h, l - l // 2))
    b.box('head', 'body', (-hw // 2, y0 + 2, -l // 2 - hl), (hw - hw // 2, y0 + 1 + hh, -l // 2))
    b.box('jaw', 'jaw', (-hw // 2 + 1, y0 - 1, -l // 2 - hl + 1), (hw - hw // 2 - 1, y0 + 1, -l // 2), 'belly')
    b.box('teeth', 'jaw', (-hw // 2 + 1, y0 + 1, -l // 2 - hl + 1), (hw - hw // 2 - 1, y0 + 2, -l // 2 - hl + 2), 'horn')
    b.pair('eye', 'body', 'body', (hw - hw // 2, y0 + hh - 1, -l // 2 - hl + 2), (hw - hw // 2 + 1, y0 + hh, -l // 2 - hl + 3), 'eye')
    b.box('dorsal', 'body', (-1, y0 + h, -2), (1, y0 + h + fin, 3), 'dark')
    b.pair('fin', 'fin_r', 'fin_l', (w - w // 2, y0 + 1, -4), (w - w // 2 + fin, y0 + 2, 0), 'dark')
    b.box('tail1', 'tail1', (-2, cy - 2, l - l // 2), (2, cy + 2, l - l // 2 + tail))
    b.box('fluke', 'tail2', (-1, cy - fin, l - l // 2 + tail), (1, cy + fin + 1, l - l // 2 + tail + 4), 'dark')
    return b, {}


def fish_anims():
    return {
        'idle': anim(2.0, {'tail1': {'rotation': sway(2.0, 12)}, 'tail2': {'rotation': sway(2.0, 20, phase=-0.15)},
                           'body': {'rotation': sway(2.0, -3)}, **both('fin', {'rotation': sway(2.0, 8, axis=2)})}),
        'attack': anim(0.6, {'jaw': {'rotation': {0: [0, 0, 0], 0.2: [9, 0, 0], 0.4: [0, 0, 0]}},
                             'body': {'position': {0: [0, 0, 0], 0.2: [0, 0, 1.5], 0.4: [0, 0, -3], 0.6: [0, 0, 0]},
                                      'rotation': {0: [0, 0, 0], 0.4: [8, 0, 0], 0.6: [0, 0, 0]}}}, loop=False),
        'hurt': anim(0.4, {'body': {'rotation': {0: [0, 0, 0], 0.1: [0, 10, 8], 0.4: [0, 0, 0]}}}, loop=False),
        'death': anim(1.4, {'body': {'rotation': {0: [0, 0, 0], 1.2: [0, 0, 175], 1.4: [0, 0, 180]},
                                      'position': {0: [0, 0, 0], 1.2: [0, 3, 0]}}}, loop=False),
    }


# --- floating spirits (imported ghosts: wraith, specter) ------------------------------------------------------------

def ghost_anims():
    """No legs to stand on: the whole figure hovers and bobs; the arms reach for the attack; it fades into the ground."""
    return {
        'idle': anim(3.0, {'root': {'position': sway(3.0, 1.2, axis=1, base=2.0)},
                           'arm_r': {'rotation': sway(3.0, 6, axis=0)}, 'arm_l': {'rotation': sway(3.0, 6, axis=0, phase=0.5)},
                           'head': {'rotation': sway(3.0, 6)}}),
        'fly': anim(3.0, {'root': {'position': sway(3.0, 1.2, axis=1, base=2.0)}}),
        'attack': anim(0.6, {'root': {'position': {0: [0, 2, 0], 0.3: [0, 2, -3], 0.6: [0, 2, 0]}},
                             'arm_r': {'rotation': {0: [0, 0, 0], 0.15: [-40, 0, 0], 0.3: [-100, 0, 0], 0.6: [0, 0, 0]}},
                             'arm_l': {'rotation': {0: [0, 0, 0], 0.18: [-40, 0, 0], 0.33: [-100, 0, 0], 0.6: [0, 0, 0]}}}, loop=False),
        'hurt': anim(0.4, {'root': {'rotation': {0: [0, 0, 0], 0.1: [-10, 0, 0], 0.4: [0, 0, 0]},
                                    'position': {0: [0, 2, 0], 0.4: [0, 2, 0]}}}, loop=False),
        # Dissipates upward: shrinking toward a rising point never drags the robe through the floor.
        'death': anim(1.4, {'root': {'scale': {0: [1, 1, 1], 1.4: [0.05, 0.05, 0.05]},
                                     'position': {0: [0, 2, 0], 1.4: [0, 10, 0]}}}, loop=False),
    }


# --- any rig (imported models): only root, head and arm_r/arm_l are assumed, and nothing ever goes below the ground --

def generic_anims():
    return {
        'idle': anim(3.0, {'root': {'scale': {0: [1, 1, 1], 1.5: [1, 1.015, 1], 3: [1, 1, 1]}},
                           'head': {'rotation': sway(3.0, 6)},
                           'arm_r': {'rotation': sway(3.0, 3, axis=0)}, 'arm_l': {'rotation': sway(3.0, 3, axis=0, phase=0.5)}}),
        'attack': anim(0.7, {'root': {'position': {0: [0, 0, 0], 0.3: [0, 0.5, -1.5], 0.45: [0, 0.5, -1], 0.7: [0, 0, 0]}},
                             'arm_r': {'rotation': {0: [0, 0, 0], 0.25: [-60, 0, 0], 0.4: [-15, 0, 0], 0.7: [0, 0, 0]}},
                             'head': {'rotation': {0: [0, 0, 0], 0.3: [10, 0, 0], 0.7: [0, 0, 0]}}}, loop=False),
        'hurt': anim(0.4, {'head': {'rotation': {0: [0, 0, 0], 0.1: [-15, 0, 0], 0.4: [0, 0, 0]}},
                           'root': {'position': {0: [0, 0, 0], 0.1: [0, 0, 1], 0.4: [0, 0, 0]}}}, loop=False),
        'death': anim(1.2, {'root': {'scale': {0: [1, 1, 1], 1.0: [1.15, 0.1, 1.15], 1.2: [1.2, 0.05, 1.2]}}}, loop=False),
    }
