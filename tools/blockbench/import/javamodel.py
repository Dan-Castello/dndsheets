"""Imports a Forge 1.20.1 entity model written in Java (ModelPart: createBodyLayer / LayerDefinition) as a GeckoLib
creature of this mod: geo.json + one PNG per variant + the shared animations of a body plan (bodies.py), since the
source's animations are Java code that cannot be carried over.

Only models whose licence the survey (tools/models_survey) cleared. Sharing Mojang's humanoid/skeleton box layout is
not a verdict by itself: such a model is evaluated one by one (what the author added, whose texture it is) and the
decision is recorded as `reviewed` in IMPORTS; without it the import stops (check_original). Mojang's own boxes are
never emitted: HumanoidModel.createMesh() contributes pivots only.

  python tools/blockbench/import/javamodel.py            (every entry of IMPORTS)
  python tools/blockbench/import/javamodel.py wraith     (one)

Java -> Bedrock geo (verified with render.py, same conventions as GeckoLib):
- Java part offsets are relative to the parent part and add up; Bedrock pivots are absolute. y_file = 24 - y_java,
  x and z unchanged (the geo file is X-mirrored from Blockbench, exactly as Java's model space is from the world).
- Rotations: radians -> degrees, same X/Y/Z in the file (Java applies them Z*Y*X, like GeckoLib).
- Cubes keep box UV (texOffs), mirror and CubeDeformation (inflate)."""
import json, math, os, re, sys, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, '..', '..', '..'))
ASSETS = os.path.join(REPO, 'src', 'main', 'resources', 'assets', 'dndsheets')
CACHE = os.path.join(HERE, '..', 'out', 'import')
sys.path.insert(0, os.path.join(HERE, '..', 'mobs'))
import bodies as B

GOETY = dict(repo='Polarice3/Goety-2', ref='1.20', author='Polarice3', licence='MIT',
             url='https://github.com/Polarice3/Goety-2',
             model='src/main/java/com/Polarice3/Goety/client/render/model/%s.java',
             texture='src/main/resources/assets/goety/textures/entity/%s')

# name -> source, Java class (+ parent classes whose createMesh() it builds on), {variant: texture}, body plan for the
# animations, bone renames, parts hidden in the pose we keep, and the per-model review when it shares vanilla boxes.
# Evaluated and left out (see reference/creature-model-survey.md): Goety's SkullLord (no monster of the bestiary is a
# floating skull), GraveGolem (4.9 blocks tall at Medium: no size category fits), SquallGolem (the elemental
# archetype already covers it), HauntedArmor and LichMode (no geometry of their own: they draw vanilla armour and the
# player's skin), and the plain Cultist (its fanatics wear the vanilla pillager texture, Mojang's art).
IMPORTS = {
    'necromancer': dict(src=GOETY, cls='NecromancerModel', plan='generic',
                        variants={'necromancer': 'necromancer/necromancer.png', 'frost': 'necromancer/frost_necromancer.png',
                                  'mossy': 'necromancer/mossy_necromancer.png', 'drowned': 'necromancer/drowned_necromancer.png'},
                        rename={'skeleton': 'root'},
                        reviewed='Skeleton proportions, but its own horned crown, pauldrons, loincloth and staff, and '
                                 'Goety-painted textures: original enough, and the licence (MIT) covers it.'),
    'crone': dict(src=GOETY, cls='CroneModel', bases=['CultistModel'], plan='generic',
                  variants={'crone': 'cultist/crone.png'}, rename={},
                  hide=['hat', 'right_arm', 'left_arm', 'clothes', 'leg0', 'leg1'],
                  reviewed='Illager base, but its own tiered witch hat, hunched back, cuffed crossed arms and warty '
                           'nose, and a Goety-painted texture. Hidden: the cultist hood, free arms and robe (unpainted '
                           'on this texture) and the duplicate legs.'),
    'brood_mother': dict(src=GOETY, cls='BroodMotherModel', plan='generic', scale=0.25,
                         variants={'brood_mother': 'servants/spider/brood_mother.png'}, rename={}),
    'web_spider': dict(src=GOETY, cls='WebSpiderModel', plan='generic', scale=0.8,
                       variants={'web_spider': 'servants/spider/web_spider.png'}, rename={}),
    'redstone_golem': dict(src=GOETY, cls='RedstoneGolemModel', plan='generic', scale=0.4,
                           variants={'redstone_golem': 'servants/redstone_golem/redstone_golem.png'}, rename={}),
    'whisperer': dict(src=GOETY, cls='WhispererModel', plan='generic', scale=0.5,
                      variants={'whisperer': 'servants/whisperer/whisperer.png'}, rename={}),
    'warlock': dict(src=GOETY, cls='WarlockModel', bases=['CultistModel'], plan='generic',
                    variants={'warlock': 'cultist/warlock.png'}, rename={}, hide=['right_arm', 'left_arm', 'clothes'],
                    reviewed='Illager geometry plus a hood tip and a mole: small additions, but the texture is '
                             'Goety\'s own. Hidden: the free arms (its texture paints the crossed ones) and the robe '
                             'that duplicates the body.'),
    'wraith': dict(src=GOETY, cls='WraithModel', plan='ghost',
                   variants={'wraith': 'wraith/wraith.png', 'border': 'wraith/border_wraith.png', 'muck': 'wraith/muck_wraith.png'},
                   rename={'Ghost': 'root', 'right_arm': 'arm_r', 'left_arm': 'arm_l'}),
    'specter': dict(src=GOETY, cls='SpecterModel', plan='ghost', variants={'specter': 'specter/specter.png'}, rename={}),
}
# Bone names of the source mapped to the ones bodies.py animates (applied after the per-import renames).
COMMON = {'right_arm': 'arm_r', 'left_arm': 'arm_l', 'rightArm': 'arm_r', 'leftArm': 'arm_l', 'right_leg': 'leg_r',
          'left_leg': 'leg_l', 'rightLeg': 'leg_r', 'leftLeg': 'leg_l', 'Head': 'head', 'Body': 'body', 'tail': 'tail1'}


def fetch(src, path):
    os.makedirs(CACHE, exist_ok=True)
    local = os.path.join(CACHE, src['repo'].replace('/', '_') + '_' + path.replace('/', '_'))
    if not os.path.exists(local):
        url = 'https://raw.githubusercontent.com/%s/%s/%s' % (src['repo'], src['ref'], path)
        data = urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': 'dndsheets-import'}), timeout=60).read()
        open(local, 'wb').write(data)
    return local


# --- Java parsing ----------------------------------------------------------------------------------------------------

def num(expr):
    e = expr.strip()
    e = re.sub(r'(\d)[fFdD]\b', r'\1', e)
    e = e.replace('(float)', '').replace('Mth.PI', 'math.pi').replace('Math.PI', 'math.pi').replace('((float)Math.PI)', 'math.pi')
    return float(eval(e, {'__builtins__': {}, 'math': math}))


def split_args(s):
    out, depth, cur = [], 0, ''
    for ch in s:
        if ch == ',' and depth == 0:
            out.append(cur.strip())
            cur = ''
            continue
        depth += ch == '('
        depth -= ch == ')'
        cur += ch
    if cur.strip():
        out.append(cur.strip())
    return out


def call_args(text, start):
    """text[start] is '(': returns (inside, index after the closing paren)."""
    depth = 0
    for i in range(start, len(text)):
        depth += text[i] == '('
        depth -= text[i] == ')'
        if depth == 0:
            return text[start + 1:i], i + 1
    raise ValueError('unbalanced parentheses')


def parse_cubes(builder):
    cubes, u, v, mirror = [], 0, 0, False
    for m in re.finditer(r'\.(texOffs|addBox|mirror)\(', builder):
        inside, _ = call_args(builder, m.end() - 1)
        args = split_args(inside)
        if m.group(1) == 'texOffs':
            u, v = int(num(args[0])), int(num(args[1]))
        elif m.group(1) == 'mirror':
            mirror = not args or args[0] == 'true'
        else:
            if args and args[0].startswith('"'):
                args = args[1:]
            x, y, z, w, h, d = (num(a) for a in args[:6])
            inflate, mir = 0.0, mirror
            for extra in args[6:]:
                if 'CubeDeformation' in extra:
                    vals = re.findall(r'-?[\d.]+', extra.split('CubeDeformation', 1)[1])
                    inflate = float(vals[0]) if vals else 0.0
                elif extra in ('true', 'false'):
                    mir = extra == 'true'
            cubes.append(dict(box=(x, y, z, w, h, d), uv=(u, v), mirror=mir, inflate=inflate))
    return cubes


def parse_pose(expr):
    expr = expr.strip()
    if expr.startswith('PartPose.ZERO'):
        return (0, 0, 0), (0, 0, 0)
    m = re.match(r'PartPose\.(offset|offsetAndRotation|rotation)\(', expr)
    inside, _ = call_args(expr, m.end() - 1)
    a = [num(x) for x in split_args(inside)]
    if m.group(1) == 'offset':
        return tuple(a[:3]), (0, 0, 0)
    if m.group(1) == 'rotation':
        return (0, 0, 0), tuple(a[:3])
    return tuple(a[:3]), tuple(a[3:6])


def method_body(java, name):
    """The text of `static ... name(...) { ... }`."""
    m = re.search(r'static[^;{]*\b%s\s*\(' % re.escape(name), java)
    if not m:
        return None
    start = java.index('{', m.end())
    depth = 0
    for i in range(start, len(java)):
        depth += java[i] == '{'
        depth -= java[i] == '}'
        if depth == 0:
            return java[start:i + 1]
    raise ValueError('unbalanced braces in ' + name)


# HumanoidModel.createMesh(): only the PIVOTS of its seven parts, never Mojang's boxes. Every model imported from that
# family replaces those parts with its own (a part left as a bare pivot simply draws nothing).
HUMANOID_PIVOTS = [('head', (0, 0, 0)), ('hat', (0, 0, 0)), ('body', (0, 0, 0)), ('right_arm', (-5, 2, 0)),
                   ('left_arm', (5, 2, 0)), ('right_leg', (-1.9, 12, 0)), ('left_leg', (1.9, 12, 0))]


class Mesh:
    """MeshDefinition as Java builds it: addOrReplaceChild REPLACES a same-named child (subtree included)."""
    def __init__(self):
        self.nodes = {0: dict(name=None, parent=None, offset=(0, 0, 0), rotation=(0, 0, 0), cubes=[], children=[])}
        self.next = 1

    def child(self, parent, name):
        return next((c for c in self.nodes[parent]['children'] if self.nodes[c]['name'] == name), None)

    def add(self, parent, name, offset, rotation, cubes):
        old = self.child(parent, name)
        node = self.next
        self.next += 1
        self.nodes[node] = dict(name=name, parent=parent, offset=offset, rotation=rotation, cubes=cubes, children=[])
        siblings = self.nodes[parent]['children']
        if old is None:
            siblings.append(node)
        else:
            siblings[siblings.index(old)] = node
        return node

    def flatten(self, hide=()):
        out = []
        def walk(n, parent_name):
            for c in self.nodes[n]['children']:
                d = self.nodes[c]
                if parent_name is None and d['name'] in hide:  # top-level parts only: Crone's hat lives under its head
                    continue
                out.append((d['name'], parent_name, d['offset'], d['rotation'], d['cubes']))
                walk(c, d['name'])
        walk(0, None)
        return out


def apply(mesh, body, sources):
    """Runs one method body's part statements on the mesh, in source order. sources: [java text] of the class and its
    parents, searched for a createMesh() the body calls."""
    var = {}
    events = []
    for m in re.finditer(r'(?:(?:MeshDefinition|PartDefinition)\s+(\w+)\s*=\s*)?([\w.]+)\.createMesh\(|'
                         r'(?:PartDefinition\s+(\w+)\s*=\s*)?(\w+)\.getRoot\(\)|'
                         r'(?:PartDefinition\s+(\w+)\s*=\s*)?(\w+)\.getChild\(\s*"(\w+)"\s*\)|'
                         r'(?:PartDefinition\s+(\w+)\s*=\s*)?(\w+)\.addOrReplaceChild\(|'
                         r'(?:MeshDefinition\s+(\w+)\s*=\s*)?\bcreateMesh\(\s*\)', body):
        events.append(m)
    for m in events:
        g = m.groups()
        if g[1] is not None:  # X.createMesh(...)
            if g[1] == 'HumanoidModel':
                for name, pivot in HUMANOID_PIVOTS:
                    mesh.add(0, name, pivot, (0, 0, 0), [])
            else:
                run_create_mesh(mesh, sources)
        elif g[3] is not None:  # x.getRoot()
            if g[2]:
                var[g[2]] = 0
        elif g[5] is not None:  # x.getChild("name")
            node = mesh.child(var.get(g[5], 0), g[6])
            if g[4] and node is not None:
                var[g[4]] = node
        elif g[8] is not None:  # x.addOrReplaceChild(...)
            inside, _ = call_args(body, m.end() - 1)
            args = split_args(inside)
            offset, rotation = parse_pose(args[2])
            node = mesh.add(var.get(g[8], 0), args[0].strip('"'), offset, rotation, parse_cubes(args[1]))
            if g[7]:
                var[g[7]] = node
        else:  # bare createMesh(): the class's own, or a parent's
            run_create_mesh(mesh, sources)


def run_create_mesh(mesh, sources):
    for java in sources:
        body = method_body(java, 'createMesh')
        if body:
            return apply(mesh, body, sources[sources.index(java) + 1:])
    raise ValueError('createMesh() not found in the class or its listed bases')


def parse(java, bases=(), hide=()):
    """[(name, parent, offset, rotation_rad, cubes)] depth-first, plus the texture size."""
    body = method_body(java, 'createBodyLayer')
    tex = re.search(r'LayerDefinition\.create\(\s*[\w().]+\s*,\s*(\d+)\s*,\s*(\d+)\s*\)', body)
    mesh = Mesh()
    apply(mesh, body, [java] + list(bases))
    return mesh.flatten(hide), (int(tex.group(1)), int(tex.group(2)))


# --- checks and conversion -------------------------------------------------------------------------------------------

VANILLA_HUMANOID = {((-4, -8, -4, 8, 8, 8), (0, 0)), ((-4, 0, -2, 8, 12, 4), (16, 16)), ((-4, -12, -2, 8, 12, 4), (16, 16)),
                    ((-1, -2, -1, 2, 12, 2), (40, 16)), ((-3, -2, -2, 4, 12, 4), (40, 16)), ((-2, 0, -2, 4, 12, 4), (0, 16)),
                    ((-4, -10, -4, 8, 10, 8), (0, 0)), ((-1, -1, -6, 2, 4, 2), (24, 0)), ((-4, 0, -3, 8, 12, 6), (16, 20)),
                    ((-2, 0, -2, 4, 12, 4), (0, 22))}


def check_original(parts):
    """How many boxes repeat Mojang's humanoid / skeleton / illager boxes at their own texture offsets, out of how many.
    Sharing that base is not a verdict by itself (what counts is what the author added and whose texture it is): a model
    with two or more such boxes needs a recorded per-model review ('reviewed' in IMPORTS) before it is imported."""
    boxes = [c for _, _, _, _, cubes in parts for c in cubes]
    hits = sum(1 for c in boxes if (tuple(round(x, 2) for x in c['box']), c['uv']) in VANILLA_HUMANOID)
    return hits, len(boxes)


def to_geo(name, parts, tex_size, rename):
    absolute, bones = {}, []
    names = {}
    for pname, parent, off, rot, cubes in parts:
        base = absolute[parent] if parent else (0.0, 0.0, 0.0)
        pivot = tuple(base[i] + off[i] for i in range(3))
        absolute[pname] = pivot
        new = rename.get(pname) or COMMON.get(pname) or pname
        names[pname] = new
        bone = {'name': new, 'pivot': [round(pivot[0], 4), round(24 - pivot[1], 4), round(pivot[2], 4)]}
        if parent:
            bone['parent'] = names[parent]
        if any(rot):
            bone['rotation'] = [round(math.degrees(r), 4) for r in rot]
        geo_cubes = []
        for c in cubes:
            x, y, z, w, h, d = c['box']
            cube = {'origin': [round(pivot[0] + x, 4), round(24 - (pivot[1] + y + h), 4), round(pivot[2] + z, 4)],
                    'size': [w, h, d], 'uv': list(c['uv'])}
            if c['inflate']:
                cube['inflate'] = c['inflate']
            if c['mirror']:
                cube['mirror'] = True
            geo_cubes.append(cube)
        if geo_cubes:
            bone['cubes'] = geo_cubes
        bones.append(bone)
    roots = [b for b in bones if 'parent' not in b]
    if len(roots) > 1 or roots[0]['name'] != 'root':
        if any(b['name'] == 'root' for b in bones):
            raise ValueError('a non-top bone is already called root')
        for b in roots:
            b['parent'] = 'root'
        bones.insert(0, {'name': 'root', 'pivot': [0, 0, 0]})
    return {'format_version': '1.12.0', 'minecraft:geometry': [{
        'description': {'identifier': 'geometry.' + name, 'texture_width': tex_size[0], 'texture_height': tex_size[1],
                        'visible_bounds_width': 3, 'visible_bounds_height': 3.5, 'visible_bounds_offset': [0, 1.25, 0]},
        'bones': bones}]}


def animations(plan, geo):
    bones = {b['name'] for b in geo['minecraft:geometry'][0]['bones']}
    if plan == 'ghost':
        anims = B.ghost_anims()
    elif plan == 'generic':
        anims = B.generic_anims()
    else:
        raise ValueError(plan)
    return {k: dict(a, bones={b: ch for b, ch in a['bones'].items() if b in bones}) for k, a in anims.items()}


def install(name, spec):
    src = spec['src']
    java = open(fetch(src, src['model'] % spec['cls']), encoding='utf-8').read()
    bases = [open(fetch(src, src['model'] % b), encoding='utf-8').read() for b in spec.get('bases', ())]
    parts, tex_size = parse(java, bases, spec.get('hide', ()))
    hits, total = check_original(parts)
    if hits >= 2 and not spec.get('reviewed'):
        raise SystemExit('%s: %d of %d boxes repeat vanilla humanoid boxes. Review the model by hand (what it adds, whose '
                         'texture it is) and record the decision as reviewed=... in IMPORTS.' % (name, hits, total))
    geo = to_geo(name, parts, tex_size, spec['rename'])
    for d in ('geo/entity', 'animations/entity', 'textures/entity/' + name):
        os.makedirs(os.path.join(ASSETS, d), exist_ok=True)
    json.dump(geo, open(os.path.join(ASSETS, 'geo', 'entity', name + '.geo.json'), 'w', newline='\n'), indent=1)
    json.dump({'format_version': '1.8.0', 'animations': animations(spec['plan'], geo)},
              open(os.path.join(ASSETS, 'animations', 'entity', name + '.animation.json'), 'w', newline='\n'), indent=1)
    tex_dir = os.path.join(ASSETS, 'textures', 'entity', name)
    for f in os.listdir(tex_dir):
        os.remove(os.path.join(tex_dir, f))
    credits = []
    for variant, path in spec['variants'].items():
        data = open(fetch(src, src['texture'] % path), 'rb').read()
        open(os.path.join(tex_dir, variant + '.png'), 'wb').write(data)
        credits.append('textures/entity/%s/%s.png' % (name, variant))
    credits += ['geo/entity/%s.geo.json' % name]
    update_credits(name, credits, src, spec['cls'])
    scales_path = os.path.join(ASSETS, 'creatures', 'scales.json')  # not under geo/: GeckoLib loads every json there
    scales = json.load(open(scales_path, encoding='utf-8')) if os.path.exists(scales_path) else {}
    scales.pop(name, None)
    if spec.get('scale', 1.0) != 1.0:
        scales[name] = spec['scale']
    os.makedirs(os.path.dirname(scales_path), exist_ok=True)
    json.dump(dict(sorted(scales.items())), open(scales_path, 'w', newline=chr(10)), indent=1)
    print('%s: %d bones, %d cubes, %d variants (%dx%d) from %s %s' % (
        name, len(geo['minecraft:geometry'][0]['bones']), sum(len(b.get('cubes', [])) for b in geo['minecraft:geometry'][0]['bones']),
        len(spec['variants']), tex_size[0], tex_size[1], src['repo'], spec['cls']))


def update_credits(name, paths, src, cls):
    path = os.path.join(ASSETS, 'CREDITS.txt')
    lines = [l for l in open(path, encoding='utf-8').read().splitlines()
             if not (l.startswith('textures/entity/%s/' % name) or l.startswith('geo/entity/%s.geo.json' % name))]
    for p in paths:
        lines.append('%s | %s %s (converted by tools/blockbench/import/javamodel.py) | %s | %s | %s' % (
            p, src['repo'], cls, src['author'], src['licence'], src['url']))
    open(path, 'w', encoding='utf-8', newline='\n').write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    for n in sys.argv[1:] or IMPORTS:
        install(n, IMPORTS[n])
