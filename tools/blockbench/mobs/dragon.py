"""Dragon archetype: quadruped with a long neck, tail and two membrane wings. One model, ten colour variants (the
chromatic and metallic dragons of the SRD); wyrmling/young/adult/ancient are the same model at different Pehkui scales.
Faces -Z. Run: python tools/blockbench/mobs/dragon.py"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mobkit import Archetype, Cube, keys

BONES = [  # name, parent, pivot, rotation
    ('root', None, [0, 0, 0], [0, 0, 0]),
    ('body', 'root', [0, 16, 0], [0, 0, 0]),
    ('neck1', 'body', [0, 20, -8], [-30, 0, 0]),
    ('neck2', 'neck1', [0, 24, -13], [20, 0, 0]),
    ('head', 'neck2', [0, 27, -17], [0, 0, 0]),
    ('jaw', 'head', [0, 25, -17], [0, 0, 0]),
    ('tail1', 'body', [0, 17, 10], [0, 0, 0]),
    ('tail2', 'tail1', [0, 16, 18], [0, 0, 0]),
    ('tail3', 'tail2', [0, 16, 25], [0, 0, 0]),
    ('wing_r', 'body', [6, 22, -3], [0, 0, 0]),
    ('wing_l', 'body', [-6, 22, -3], [0, 0, 0]),
    ('leg_f_r', 'body', [6, 14, -6], [0, 0, 0]),
    ('leg_f_l', 'body', [-6, 14, -6], [0, 0, 0]),
    ('leg_b_r', 'body', [6, 15, 7], [0, 0, 0]),
    ('leg_b_l', 'body', [-6, 15, 7], [0, 0, 0]),
]

cubes = []


def add(name, bone, a, b, role='base'):
    cubes.append(Cube(name, bone, tuple(a), tuple(b), role))


def pair(name, bone_l, bone_r, a, b, role='base'):
    """Left cube + mirrored right cube sharing one painted net."""
    ax, ay, az = a
    bx, by, bz = b
    cl = Cube(name + '_r', bone_l, (ax, ay, az), (bx, by, bz), role, uvkey=name)
    cr = Cube(name + '_l', bone_r, (-bx, ay, az), (-ax, by, bz), role, uvkey=name)
    cubes.extend([cl, cr])


# body, spine, neck, head
add('body', 'body', (-5, 12, -9), (5, 22, 11))
for i, z in enumerate((-7, -3, 1, 5)):
    add('spine_%d' % i, 'body', (-1, 22, z), (1, 24, z + 2), 'horn')
add('neck1', 'neck1', (-3, 19, -12), (3, 25, -6))
add('neck2', 'neck2', (-2, 23, -17), (2, 27, -12))
add('head', 'head', (-3, 25, -24), (3, 30, -16))
add('snout', 'head', (-2, 25, -28), (2, 28, -24))
add('jaw', 'jaw', (-2, 23, -27), (2, 25, -17))
pair('horn', 'head', 'head', (2, 30, -18), (3, 34, -16), 'horn')
pair('horn_tip', 'head', 'head', (2, 33, -16), (3, 35, -14), 'horn')
pair('eye', 'head', 'head', (3, 27, -22), (4, 28, -21), 'eye')
add('brow', 'head', (-3, 29, -25), (3, 30, -24), 'dark')
# tail
add('tail1', 'tail1', (-3, 14, 11), (3, 20, 18))
add('tail2', 'tail2', (-2, 14, 18), (2, 19, 25))
add('tail3', 'tail3', (-1, 14, 25), (1, 18, 33))
add('tail_tip', 'tail3', (-1, 14, 33), (1, 17, 37), 'horn')
# legs (front pair, back pair)
pair('thigh_f', 'leg_f_r', 'leg_f_l', (4, 8, -8), (8, 16, -4))
pair('shin_f', 'leg_f_r', 'leg_f_l', (4, 2, -7), (7, 8, -4))
pair('foot_f', 'leg_f_r', 'leg_f_l', (3, 0, -10), (8, 2, -3), 'claw')
pair('thigh_b', 'leg_b_r', 'leg_b_l', (4, 8, 5), (9, 17, 10))
pair('shin_b', 'leg_b_r', 'leg_b_l', (5, 2, 6), (8, 8, 9))
pair('foot_b', 'leg_b_r', 'leg_b_l', (4, 0, 3), (9, 2, 10), 'claw')
# wings: arm, forearm, three membrane panels and their struts
pair('arm', 'wing_r', 'wing_l', (5, 21, -4), (18, 23, -2))
pair('forearm', 'wing_r', 'wing_l', (18, 21, -4), (28, 23, -2))
pair('wing_claw', 'wing_r', 'wing_l', (28, 21, -5), (30, 23, -2), 'claw')
pair('membrane_a', 'wing_r', 'wing_l', (5, 21, -2), (14, 22, 10), 'membrane')
pair('membrane_b', 'wing_r', 'wing_l', (14, 21, -2), (22, 22, 7), 'membrane')
pair('membrane_c', 'wing_r', 'wing_l', (22, 21, -2), (28, 22, 3), 'membrane')
pair('strut_a', 'wing_r', 'wing_l', (13, 22, -2), (14, 23, 11), 'horn')
pair('strut_b', 'wing_r', 'wing_l', (21, 22, -2), (22, 23, 8), 'horn')
pair('strut_c', 'wing_r', 'wing_l', (27, 22, -2), (28, 23, 4), 'horn')

# base, dark, belly, horn, membrane, eye
PALETTES = {
    'red': dict(base=(168, 36, 34), dark=(104, 18, 24), belly=(232, 172, 92), horn=(232, 222, 190), membrane=(146, 44, 54), eye=(255, 224, 64)),
    'blue': dict(base=(42, 92, 192), dark=(24, 52, 128), belly=(172, 202, 232), horn=(232, 232, 222), membrane=(52, 82, 152), eye=(255, 255, 124)),
    'green': dict(base=(52, 132, 52), dark=(28, 84, 34), belly=(192, 202, 112), horn=(222, 216, 172), membrane=(62, 102, 52), eye=(224, 255, 84)),
    'black': dict(base=(42, 42, 50), dark=(18, 18, 24), belly=(104, 112, 102), horn=(162, 162, 152), membrane=(52, 52, 62), eye=(124, 255, 144)),
    'white': dict(base=(226, 232, 242), dark=(168, 180, 200), belly=(250, 250, 255), horn=(200, 212, 222), membrane=(190, 202, 226), eye=(120, 200, 255)),
    'brass': dict(base=(192, 152, 62), dark=(134, 100, 34), belly=(232, 207, 142), horn=(240, 226, 172), membrane=(166, 122, 52), eye=(255, 242, 152)),
    'bronze': dict(base=(152, 106, 56), dark=(98, 64, 30), belly=(202, 172, 112), horn=(212, 192, 152), membrane=(126, 90, 50), eye=(122, 222, 162)),
    'copper': dict(base=(192, 102, 62), dark=(130, 60, 34), belly=(226, 162, 112), horn=(226, 202, 172), membrane=(142, 92, 72), eye=(142, 255, 222)),
    'gold': dict(base=(226, 186, 52), dark=(164, 124, 20), belly=(250, 232, 142), horn=(255, 246, 202), membrane=(206, 162, 42), eye=(255, 255, 255)),
    'silver': dict(base=(192, 197, 207), dark=(130, 135, 150), belly=(236, 240, 246), horn=(226, 230, 236), membrane=(172, 177, 197), eye=(152, 232, 255)),
}


def anim(length, bones, loop=True):
    a = {'animation_length': length, 'bones': {b: {ch: keys(length, fr) for ch, fr in chans.items()} for b, chans in bones.items()}}
    a['loop'] = True if loop else 'hold_on_last_frame'
    return a


def flap(sign):
    return {'rotation': {0: [0, 0, 28 * sign], 0.5: [0, 0, -34 * sign], 1.0: [0, 0, 28 * sign]}}


ANIMATIONS = {
    'idle': anim(4.0, {
        'body': {'position': {0: [0, 0, 0], 2: [0, 0.5, 0], 4: [0, 0, 0]}},
        'neck1': {'rotation': {0: [0, 0, 0], 2: [3, 0, 0], 4: [0, 0, 0]}},
        'head': {'rotation': {0: [0, 0, 0], 1: [0, 5, 0], 2: [-2, 0, 0], 3: [0, -5, 0], 4: [0, 0, 0]}},
        'tail1': {'rotation': {0: [0, 0, 0], 1: [0, 7, 0], 2: [0, 0, 0], 3: [0, -7, 0], 4: [0, 0, 0]}},
        'tail2': {'rotation': {0: [0, 0, 0], 1: [0, 10, 0], 2: [0, 0, 0], 3: [0, -10, 0], 4: [0, 0, 0]}},
        'tail3': {'rotation': {0: [0, 0, 0], 1: [0, 14, 0], 2: [0, 0, 0], 3: [0, -14, 0], 4: [0, 0, 0]}},
        'wing_r': {'rotation': {0: [0, 0, 0], 2: [0, 0, 4], 4: [0, 0, 0]}},
        'wing_l': {'rotation': {0: [0, 0, 0], 2: [0, 0, -4], 4: [0, 0, 0]}},
    }),
    'fly': anim(1.0, {
        'body': {'position': {0: [0, 4, 0], 0.5: [0, 2.8, 0], 1.0: [0, 4, 0]}},  # hovers: the dangling legs clear the floor
        'wing_r': flap(1), 'wing_l': flap(-1),
        'leg_f_r': {'rotation': {0: [35, 0, 0], 1: [35, 0, 0]}}, 'leg_f_l': {'rotation': {0: [35, 0, 0], 1: [35, 0, 0]}},
        'leg_b_r': {'rotation': {0: [30, 0, 0], 1: [30, 0, 0]}}, 'leg_b_l': {'rotation': {0: [30, 0, 0], 1: [30, 0, 0]}},
        'tail1': {'rotation': {0: [0, 0, 0], 0.5: [0, 6, 0], 1: [0, 0, 0]}},
        'tail3': {'rotation': {0: [0, 0, 0], 0.5: [0, -12, 0], 1: [0, 0, 0]}},
    }),
    'attack': anim(0.8, {
        'neck1': {'rotation': {0: [0, 0, 0], 0.2: [-14, 0, 0], 0.45: [22, 0, 0], 0.8: [0, 0, 0]}},
        'head': {'rotation': {0: [0, 0, 0], 0.2: [-10, 0, 0], 0.45: [18, 0, 0], 0.8: [0, 0, 0]}},
        'jaw': {'rotation': {0: [0, 0, 0], 0.2: [38, 0, 0], 0.45: [6, 0, 0], 0.8: [0, 0, 0]}},
        'body': {'position': {0: [0, 0, 0], 0.2: [0, 0, 1.5], 0.45: [0, 0, -2], 0.8: [0, 0, 0]}},
        'wing_r': {'rotation': {0: [0, 0, 0], 0.2: [0, 0, 20], 0.8: [0, 0, 0]}},
        'wing_l': {'rotation': {0: [0, 0, 0], 0.2: [0, 0, -20], 0.8: [0, 0, 0]}},
    }, loop=False),
    'hurt': anim(0.4, {
        'body': {'rotation': {0: [0, 0, 0], 0.1: [0, 0, 6], 0.25: [0, 0, -4], 0.4: [0, 0, 0]}},
        'head': {'rotation': {0: [0, 0, 0], 0.1: [-14, 0, 0], 0.4: [0, 0, 0]}},
        'jaw': {'rotation': {0: [0, 0, 0], 0.1: [20, 0, 0], 0.4: [0, 0, 0]}},
    }, loop=False),
    'death': anim(1.4, {
        'body': {'rotation': {0: [0, 0, 0], 1.0: [0, 0, 84], 1.4: [0, 0, 90]}, 'position': {0: [0, 0, 0], 0.6: [0, -1, 0], 1.0: [0, -8.5, 0], 1.4: [0, -10, 0]}},
        'head': {'rotation': {0: [0, 0, 0], 1.0: [10, 0, 0]}},
        'jaw': {'rotation': {0: [0, 0, 0], 1.0: [30, 0, 0]}},
        # Rolled onto its -X side: that wing folds up over the body instead of going through the floor.
        'wing_r': {'rotation': {0: [0, 0, 0], 1.0: [0, 0, -50]}}, 'wing_l': {'rotation': {0: [0, 0, 0], 1.0: [0, 0, -110]}},
    }, loop=False),
}

if __name__ == '__main__':
    Archetype('dragon', BONES, cubes, PALETTES, ANIMATIONS).run()
