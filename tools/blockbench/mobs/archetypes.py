"""Every creature archetype except the dragon (dragon.py): proportions on a shared body plan (bodies.py), a few extras,
and one palette per variant. Each archetype is ONE model + ONE mask; a variant is a palette (see mobkit.py).
Run all:  python tools/blockbench/mobs/archetypes.py
Run some: python tools/blockbench/mobs/archetypes.py goblinoid beast"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from mobkit import Archetype
import bodies as B


def pal(base, dark, belly, horn, membrane, eye):
    """Six hex colours in role order: base, dark, belly, horn, membrane, eye."""
    rgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))
    return dict(zip(('base', 'dark', 'belly', 'horn', 'membrane', 'eye'), map(rgb, (base, dark, belly, horn, membrane, eye))))


def goblinoid():
    b, i = B.biped(leg=(3, 8), torso=(8, 9, 5), head=(8, 7, 7), arm=(3, 10), hunch=12, clothes=('torso', 'legs'))
    B.pointed_ears(b, i, 4)
    b.box('nose', 'head', (-1, i['ey'] - 2, i['hz0'] - 2), (1, i['ey'], i['hz0']))
    return b, B.biped_anims(i), {
        # skin, leather/shade, tunic, teeth+nails, clothes, eyes
        'goblin': pal('7a9a3a', '4a3a26', 'a08a5a', 'e8dcb0', '6a4a2a', 'ffd040'),
        'hobgoblin': pal('c0603a', '3a2a2a', '8a2a2a', 'e8dcb0', '4a4a52', 'ffe060'),
        'bugbear': pal('8a6438', '3e2a1a', 'b08a5a', 'e0d4b0', '5a4028', 'f0e050'),
        'duergar': pal('8a8a90', '3a3a40', '6a5a4a', 'd0c8b8', '4a4448', 'ff7040'),
        'deep_gnome': pal('9a948c', '4a4440', '7a6a5a', 'd8d0c0', '5a5048', 'c0e0ff'),
    }


def brute():
    b, i = B.biped(leg=(5, 10), torso=(14, 12, 8), head=(8, 8, 7), arm=(5, 15), hunch=14, sink=2, head_fwd=2, clothes=('legs',))
    B.tusks(b, i)
    B.brow(b, i)
    b.box('belt', 'body', (-7, 10, -5), (7, 12, -4), 'dark')
    return b, B.biped_anims(i, heavy=True), {
        'orc': pal('6e8a5a', '3a3426', '8a7a5a', 'f0e8c8', '4a3a2a', 'ff4030'),
        'ogre': pal('b89a6a', '5a4430', 'd0b48a', 'e8e0c0', '6a5034', 'ffe080'),
        'troll': pal('5a7a4a', '2e3e2a', '7a8a5a', '4a4a3a', '3a4a3a', 'ffcc30'),
        'minotaur': pal('6a4428', '2e1e12', '8a6040', 'e8e0c8', '4a3020', 'ff3020'),
        'oni': pal('4a6ab0', '1e2a5a', '7a8ac8', 'f0f0e0', '2a2a3a', 'ffd040'),
        'hill_giant': pal('c0a07a', '5a4430', 'd8bc96', 'e8e0c0', '7a5a3a', '8ab0d0'),
        'stone_giant': pal('9a9a94', '4e4e4a', 'b0b0aa', 'd8d8d0', '6a6a64', '60c0ff'),
        'frost_giant': pal('a8c4dc', '506a8a', 'd0e0f0', 'f0f8ff', '6a7a8a', '40a0ff'),
        'fire_giant': pal('4a3a3a', '1a1414', 'c05a2a', 'ff9a3a', '6a2a1a', 'ffb020'),
        'cloud_giant': pal('d8d0e8', '7a7090', 'f0ecf8', 'f8f8ff', '9a90b8', 'a0e0ff'),
    }


def reptilian():
    b, i = B.biped(leg=(3, 9), torso=(8, 10, 5), head=(6, 6, 6), arm=(3, 11), hunch=10, clothes=(), head_fwd=1)
    B.snout(b, i, w=4, h=3, length=4)
    B.biped_tail(b, i, length=12, width=3)
    b.box('crest', 'head', (-1, i['hy1'], i['hz0'] + 1), (1, i['hy1'] + 2, i['hz0'] + 5), 'horn')
    return b, B.biped_anims(i), {
        'kobold': pal('a8502e', '5a2a1a', 'd8a070', 'e8d8a8', '6a3a22', 'ffd040'),
        'lizardfolk': pal('4a7a4a', '24402a', 'b8c08a', 'd0c890', '3a5a3a', 'ffcc20'),
    }


def demon():
    b, i = B.biped(leg=(4, 12), torso=(10, 12, 6), head=(7, 7, 7), arm=(4, 14), hunch=6, clothes=())
    B.horns(b, i, rise=4, out=3)
    B.biped_tail(b, i, length=14, width=2, tip_role='horn', segments=2)
    B.biped_wings(b, i, span=16, height=11)
    b.pair('claw', 'arm_r', 'arm_l', (5, i['shoulder'] - 15, -2), (9, i['shoulder'] - 14, 2), 'claw')
    return b, B.biped_anims(i), {
        'barbed_devil': pal('b03a2a', '5a1a14', 'd8704a', '2a2420', '6a1a1a', 'ffe040'),
        'bearded_devil': pal('8a2a2a', '3a1010', 'b05a4a', '3a3020', '5a2020', 'ffb020'),
        'horned_devil': pal('7a2a1a', '2a0a0a', 'a04a2a', '1a1410', '4a1010', 'ff6020'),
        'bone_devil': pal('d8d0b8', '6a6250', 'f0e8d0', '3a3428', 'a09a80', 'ff3020'),
        'chain_devil': pal('8a7a74', '3a3230', 'a8908a', '5a5a5e', '6a5a54', 'ff4020'),
        'ice_devil': pal('a8d0e8', '4a6a8a', 'e0f0ff', 'f8ffff', '7aa0c8', '40e0ff'),
        'pit_fiend': pal('9a1a1a', '2a0606', 'c04a2a', '1a1010', '3a0a0a', 'ffd020'),
        'erinyes': pal('e0b8a8', '3a2a34', 'f0e0d8', 'e8e0f0', '2a1a24', 'ff4060'),
        'balor': pal('3a2a2a', '0e0808', 'c03a10', 'ff8a20', '5a1a10', 'ffcc20'),
        'imp': pal('d8502a', '6a1a0a', 'f0a070', '2a2018', '9a3018', 'ffe040'),
        'quasit': pal('4a8a3a', '1e3a1a', '8ab05a', 'e0d8a0', '3a6a2a', 'ff4020'),
    }


def undead():
    b, i = B.biped(leg=(3, 12), torso=(8, 11, 4), head=(7, 7, 7), arm=(3, 13), hunch=15, arm_rest=-35, clothes=('torso', 'legs'), chest=False)
    b.box('ribs', 'body', (-3, 16, -3), (3, 20, -2), 'horn')
    b.box('jawbone', 'head', (-2, i['hy0'] - 1, i['hz0']), (2, i['hy0'], i['hz0'] + 3), 'horn')
    return b, B.biped_anims(i), {
        # flesh, shade, (unused belly), bone, rags, eyes
        'zombie': pal('7a9070', '3a4434', '6a7a60', 'd8d0b0', '4a5a7a', 'c03020'),
        'ghoul': pal('8a8098', '3a344a', '7a7088', 'e0d8c8', '4a3a3a', 'ffe040'),
        'ghast': pal('6a7860', '2a3024', '5a6650', 'c8c0a0', '3a3428', 'a0ff40'),
        'wight': pal('a8acb4', '3a3c44', '8a8c94', 'e8e4d8', '2a2a30', '40c0ff'),
        'mummy': pal('d0c098', '6a5a3a', 'b8a880', 'e0d4b0', 'b8a87a', '40ff80'),
        'mummy_lord': pal('c8b48a', '5a4a2a', 'd8b040', 'f0d870', 'a08850', '40ffc0'),
        'vampire_spawn': pal('d8d0d0', '2a1a20', 'c8b8b8', 'f8f8f0', '5a1020', 'ff2030'),
    }


def golem():
    b, i = B.biped(leg=(6, 10), torso=(16, 13, 9), head=(7, 7, 7), arm=(6, 17), sink=2, head_fwd=2, clothes=(), chest=False)
    b.box('rune', 'body', (-2, 16, -6), (2, 20, -5), 'eye')
    b.pair('shoulder_plate', 'arm_r', 'arm_l', (7, i['shoulder'] - 2, -4), (15, i['shoulder'] + 2, 4), 'dark')
    B.brow(b, i)
    return b, B.biped_anims(i, heavy=True), {
        # body, seams, (unused), plates/studs, (unused), rune+eyes
        'clay_golem': pal('a0603a', '5a301a', 'b8784a', '6a3a20', '7a4a2a', 'ffb040'),
        'flesh_golem': pal('c8a090', '5a3a40', 'd8b8a8', '6a6a6a', '8a6a6a', 'c0ff60'),
        'iron_golem': pal('9a9ea6', '4a4e56', 'b8bcc4', '6a6e78', '7a7e86', 'ff6020'),
        'stone_golem': pal('8a867a', '3e3c36', 'a09c90', '5a5850', '6a6860', '60d0ff'),
        'shield_guardian': pal('a08050', '4a3a24', 'c0a070', '6a7480', '806040', '40c0ff'),
        'earth_elemental': pal('7a5a3a', '3a2a1a', '8a6a4a', '5a7a3a', '6a4a2a', 'ffa020'),
    }


def beast():
    b, i = B.quadruped(body=(7, 7, 16), leg=(3, 8), head=(6, 6, 5), muzzle=(4, 3, 4), rise=3)
    B.quad_ears(b, i, 3)
    B.quad_tail(b, i, length=10, width=2, droop=-35, segments=2, taper=0)
    return b, B.quad_anims(i), {
        # fur, back/stripes, underside, claws, (unused), eyes
        'wolf': pal('8a8a8a', '4a4a4e', 'c8c8c0', '3a3430', '6a6a6a', 'f0c040'),
        'dire_wolf': pal('5a5250', '2a2626', '8a8480', '2a2420', '4a4240', 'ffb020'),
        'worg': pal('3a3434', '161212', '5a5050', '1a1410', '2a2424', 'ff3020'),
        'winter_wolf': pal('e0e8f0', '8aa0b8', 'f8fcff', '8a9aa8', 'b8c8d8', '40c0ff'),
        'hell_hound': pal('3a2626', '120a0a', 'c04a1a', 'ff8a30', '5a1a10', 'ffcc20'),
        'death_dog': pal('6a5040', '2a1e16', '8a6a54', '2a2018', '4a382c', 'ff4020'),
        'blink_dog': pal('c8a868', '6a5434', 'e8d8b0', '4a3a28', '9a8048', '60c0ff'),
        'mastiff': pal('a07a4a', '4a3420', 'c8a87a', '3a2a1a', '7a5a38', '2a1a10'),
        'hyena': pal('9a9470', '4a4430', 'c8c4a0', '3a3020', '7a7450', 'e0a020'),
        'jackal': pal('b88a5e', '5a3e22', 'e0c8a0', '3a2a1a', '9a7048', 'e0b030'),
        'lion': pal('d0a454', '8a5a24', 'e8d4a0', '3a2a1a', 'a07a38', '5a3a14'),
        'tiger': pal('d87a2a', '2a1a10', 'f0e8d8', '3a2a1a', 'b05a1a', 'f0d040'),
        'panther': pal('2a2a30', '101014', '3a3a42', '1a1a1a', '222228', 'f0e040'),
        'saber_toothed_tiger': pal('a8743a', '5a3418', 'e8d8b8', 'f0e8d0', '86582c', 'f0c040'),
    }


def reptile():
    b, i = B.quadruped(body=(9, 5, 18), leg=(3, 3), head=(6, 4, 5), muzzle=(4, 3, 8), splay=True, rise=0)
    B.quad_tail(b, i, length=18, width=4, droop=0, segments=3, taper=1)
    for k, z in enumerate((-6, -1, 4)):
        b.box('scute%d' % k, 'body', (-1, i['top'], z), (1, i['top'] + 1, z + 3), 'dark')
    return b, B.quad_anims(i), {
        'crocodile': pal('4a5a34', '22301a', 'b8b080', 'e0d8b8', '3a4a2a', 'e0d040'),
        'lizard': pal('6a8a4a', '34482a', 'c8c890', 'd0c8a0', '5a7a3a', 'ff9020'),
        'giant_lizard': pal('8a6a3a', '44341c', 'd0b880', 'd8c8a0', '6a5030', 'ffcc20'),
        'basilisk': pal('5a6a5a', '26302a', '9aa88a', 'c8c0a0', '4a5a4a', '80ff40'),
    }


def serpent():
    b, i = B.serpent(segments=6, length=5, width=4, head=(5, 4, 6), neck=6)
    return b, B.serpent_anims(i), {
        'snake': pal('6a7a3a', '2e3a1a', 'c8c080', 'd8c8a0', 'c02020', 'ffd020'),
        'constrictor': pal('8a6a3a', '3a2a14', 'd0b880', 'd8c8a0', 'c02020', 'ffa020'),
        'poisonous': pal('3a7a3a', '102a10', 'd0d050', 'd8c8a0', 'e02020', 'ff4020'),
        'flying_snake': pal('3a8ab0', '1a3a5a', 'e0c060', 'f0e8c0', 'e02020', 'ffe040'),
        'guardian_naga': pal('c8a040', '5a4018', 'f0e0a0', 'f8f0c0', 'c02020', '40c0ff'),
        'spirit_naga': pal('3a2a4a', '140a1a', '7a5a8a', 'b0a0c0', 'c02020', 'ff40c0'),
        'purple_worm': pal('6a3a8a', '2a1440', 'a080b0', 'e0d8c0', 'c03050', 'ffe060'),
    }


def spider():
    b, i = B.spider(body=(8, 5, 7), abdomen=(10, 8, 11), leg=(12, 9))
    return b, B.spider_anims(i), {
        # chitin, legs/shade, abdomen mark, fangs, (unused), eyes
        'spider': pal('3a3030', '1a1414', 'c02020', 'e0d0b0', '2a2222', 'ff3020'),
        'wolf_spider': pal('7a5a3a', '3a2a1a', 'c0a070', 'e0d0b0', '5a4028', 'ffb020'),
        'phase_spider': pal('4a5a9a', '1a2040', 'a0e0ff', 'e0f0ff', '3a4a7a', 'a0ffff'),
    }


def ooze():
    b, i = B.blob()
    return b, B.blob_anims(), {
        # body, shade, (unused), (unused), base rim, bubbles/debris
        'gray_ooze': pal('7a7e80', '3a3e40', '8a8e90', '9a9ea0', '5a5e60', 'b0b4b8'),
        'ochre_jelly': pal('c8962a', '6a4a10', 'd8aa40', 'e0c060', 'a07418', 'f0d880'),
        'black_pudding': pal('26222a', '0a080c', '322e36', '3a3640', '18161a', '5a5060'),
        'gelatinous_cube': pal('8ad0b0', '3a7a68', 'a0e0c8', 'c0f0e0', '6ab098', 'e8e0c8'),
    }


def elemental():
    b, i = B.elemental()
    return b, B.elemental_anims(i), {
        # body, shade, (unused), (unused), swirl bands, core+eyes
        'fire': pal('e0601a', '7a1a06', 'f0a030', 'ffe080', 'ffb020', 'fff0a0'),
        'air': pal('c8d8e8', '7a90a8', 'e0ecf8', 'f8ffff', 'a8bcd0', 'ffffff'),
        'water': pal('2a6ab0', '0e2a5a', '4a8ac8', 'a0d0ff', '5aa0e0', 'c0f0ff'),
        'dust_mephit': pal('a89878', '5a4e3a', 'c0b090', 'd8c8a8', '8a7a5a', 'ffe080'),
        'ice_mephit': pal('b8e0f0', '5a8aa8', 'e0f4ff', 'ffffff', '8ac0e0', '40c0ff'),
        'magma_mephit': pal('5a2a1a', '1a0a06', 'c04a10', 'ff8a20', 'e06010', 'ffd040'),
        'steam_mephit': pal('d0d4d8', '7a8088', 'e8ecf0', 'ffffff', 'b0b8c0', 'ff8040'),
        'magmin': pal('3a2a20', '100806', 'e05a10', 'ffb030', 'ff7a10', 'fff080'),
    }


def bird():
    b, i = B.bird()
    return b, B.bird_anims(i), {
        # feathers, flight feathers, chest, beak+talons, (unused), eyes
        'hawk': pal('8a6040', '3a2818', 'e0d0b0', 'e8c040', '6a4a30', 'f0c020'),
        'blood_hawk': pal('8a2a2a', '2a0a0a', 'c06a5a', 'd8b040', '6a2020', 'ff3020'),
        'eagle': pal('5a3a24', '2a1a10', 'f0f0e8', 'f0c030', '4a3020', 'f0c020'),
        'owl': pal('a08060', '4a3424', 'e0d0b8', 'b0a080', '7a6048', 'ffb020'),
        'vulture': pal('3a3030', '161212', 'c09a9a', 'd8c8a0', '2a2424', 'e04020'),
        'roc': pal('c89a5a', '6a4424', 'f0e0c0', 'e8c040', 'a07844', 'ff8020'),
    }


def shark():
    b, i = B.fish()
    return b, B.fish_anims(), {
        'shark': pal('6a7a8a', '343e48', 'e8ecf0', 'f8f8f0', '4a5a6a', '101010'),
        'reef_shark': pal('8a9a8a', '4a5a4a', 'e8ece0', 'f8f8f0', '6a7a6a', '101010'),
        'killer_whale': pal('1a1a20', '08080a', 'f0f0f0', 'f8f8f0', '101014', 'f0f0f0'),
    }


# Warnings of bbmodel_validate that are the design, not a defect (substring of the message).
ACCEPTED = {
    'dragon': ('body and neck1',),  # the neck grows out of the chest
    'brute': ('torso and head',),  # the head sits sunk between the shoulders
    'demon': ('torso and tail1',),
    'reptilian': ('torso and tail1',),
}

ARCHETYPES = dict(goblinoid=goblinoid, brute=brute, reptilian=reptilian, demon=demon, undead=undead, golem=golem,
                  beast=beast, reptile=reptile, serpent=serpent, spider=spider, ooze=ooze, elemental=elemental,
                  bird=bird, shark=shark)
# Bipeds: only the underside counts as belly (no lighter band at the bottom of the legs).
NO_BELLY_BAND = {'goblinoid', 'brute', 'reptilian', 'demon', 'undead', 'golem', 'elemental', 'ooze'}

if __name__ == '__main__':
    for name in sys.argv[1:] or ARCHETYPES:
        body, animations, palettes = ARCHETYPES[name]()
        Archetype(name, body.bones, body.cubes, palettes, animations,
                  belly_roles=() if name in NO_BELLY_BAND else ('base',), size=128).run()
