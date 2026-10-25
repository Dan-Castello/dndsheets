"""Lint for the creature archetypes: checks the INSTALLED assets (what the game loads), posed with the same evaluator
as render.py. Errors exit 1; warnings only print.

  python tools/blockbench/mobs/lint.py [archetype ...] [--no-mcp] [--self-test]

Gates
- mcp: bbmodel_validate on the .bbmodel (outliner, degenerate, floating, interpenetration, mirror). Its errors fail;
  its warnings print unless accepted in archetypes.ACCEPTED.
- keyframes: every animated bone exists; no key outside 0..animation_length.
- loop_seam: a looping clip ends where it starts (no pop when it wraps).
- ground_sink: no clip drops geometry below the rest pose's lowest point by more than SINK_TOLERANCE (death:
  DEATH_SINK).
- detachment: a bone whose cubes touch its parent's at rest never drifts more than DETACH_GAP away during a clip.
- z_fighting: two cubes with the same orientation sharing a face plane, facing the same way, overlapping in area.
- palette: every role present; base/dark and eye/base contrast; variants of one archetype distinguishable.
- size: texture use under 90 %; rest height and width in blocks, reported (a medium creature is ~1-2.5 blocks)."""
import copy, itertools, json, os, sys
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import render as R

DEATH_SINK = 4.0  # a quarter block: vanilla mobs sink further when they tip over
SINK_TOLERANCE = 1.0
DETACH_GAP = 1.0
RATE = 20


class Report:
    def __init__(self, name):
        self.name, self.errors, self.warnings, self.notes = name, [], [], []

    def error(self, gate, msg):
        self.errors.append('%s: %s' % (gate, msg))

    def warn(self, gate, msg):
        self.warnings.append('%s: %s' % (gate, msg))


def aabb(corners):
    return corners.min(0), corners.max(0)


def gap(a, b):
    """Distance between two axis-aligned boxes (0 when they touch or overlap)."""
    d = np.maximum(0, np.maximum(a[0] - b[1], b[0] - a[1]))
    return float(np.linalg.norm(d))


def times(length):
    n = max(1, int(round(length * RATE)))
    return [length * i / n for i in range(n + 1)]


def boxes_by_bone(model, anim=None, t=0.0):
    out = {}
    for bone, _, w in R.posed_cubes(model, anim, t):
        out.setdefault(bone, []).append(aabb(w))
    return out


def solid_parent(model, bone):
    p = model.bones[bone]['parent']
    while p and not model.bones[p]['cubes']:
        p = model.bones[p]['parent']
    return p


def nearest(boxes_a, boxes_b):
    return min(gap(a, b) for a in boxes_a for b in boxes_b)


def check_animations(model, rep):
    rest = R.posed_cubes(model)
    ground = min(w[:, 1].min() for _, _, w in rest)
    rest_boxes = boxes_by_bone(model)
    attached = [b for b in rest_boxes if solid_parent(model, b) and nearest(rest_boxes[b], rest_boxes[solid_parent(model, b)]) <= 0.01]
    for name, a in model.animations.items():
        length = float(a.get('animation_length', 0))
        for bone, chans in a['bones'].items():
            if bone not in model.bones:
                rep.error('keyframes', '%s animates "%s", which the model does not have' % (name, bone))
                continue
            for ch, frames in chans.items():
                if isinstance(frames, dict):
                    bad = [k for k in frames if not -1e-6 <= float(k) <= length + 1e-6]
                    if bad:
                        rep.error('keyframes', '%s %s.%s has keys outside 0..%g: %s' % (name, bone, ch, length, bad))
                    if a.get('loop') is True:
                        v0, v1 = R.sample(frames, 0.0, [0, 0, 0]), R.sample(frames, length, [0, 0, 0])
                        d = np.abs(v0 - v1)
                        if ch == 'rotation':
                            d = np.minimum(d % 360, 360 - d % 360)
                        if d.max() > 0.5:
                            rep.error('loop_seam', '%s %s.%s starts at %s and ends at %s' % (name, bone, ch, v0.round(1).tolist(), v1.round(1).tolist()))
        allowed = DEATH_SINK if name == 'death' else SINK_TOLERANCE
        worst, worst_t, worst_bone = 0.0, 0.0, None
        drift = {}
        for t in times(length):
            posed = R.posed_cubes(model, name, t)
            for bone, _, w in posed:
                sink = ground - w[:, 1].min()
                if sink > worst:
                    worst, worst_t, worst_bone = sink, t, bone
            boxes = {}
            for bone, _, w in posed:
                boxes.setdefault(bone, []).append(aabb(w))
            for b in attached:
                g = nearest(boxes[b], boxes[solid_parent(model, b)])
                if g > drift.get(b, (0, 0))[0]:
                    drift[b] = (g, t)
        if worst > allowed:
            rep.error('ground_sink', '%s sinks "%s" %.1f units below the ground at %.2fs (allowed %.1f)' % (name, worst_bone, worst, worst_t, allowed))
        for b, (g, t) in drift.items():
            if g > DETACH_GAP:
                rep.error('detachment', '%s pulls "%s" %.1f units off "%s" at %.2fs' % (name, b, g, solid_parent(model, b), t))


def check_z_fighting(model, rep):
    mats = R.bone_matrices(model)
    cubes = []
    for name, b in model.bones.items():
        M = mats[name]
        for i, c in enumerate(b['cubes']):
            cubes.append(('%s#%d' % (name, i), M, c))
    for (na, Ma, ca), (nb, Mb, cb) in itertools.combinations(cubes, 2):
        if not np.allclose(Ma[:3, :3], Mb[:3, :3], atol=1e-4):
            continue
        Rinv = Ma[:3, :3].T
        def box(M, c):
            pts = R.CORNERS * c['size'] + c['lo']
            w = (M @ np.c_[pts, np.ones(8)].T).T[:, :3] @ Rinv.T
            return w.min(0), w.max(0)
        (alo, ahi), (blo, bhi) = box(Ma, ca), box(Mb, cb)
        for ax in range(3):
            others = [k for k in range(3) if k != ax]
            overlap = all(min(ahi[k], bhi[k]) - max(alo[k], blo[k]) > 1e-3 for k in others)
            if not overlap:
                continue
            for side, (pa, pb) in (('min', (alo[ax], blo[ax])), ('max', (ahi[ax], bhi[ax]))):
                if abs(pa - pb) < 1e-3:
                    rep.error('z_fighting', '%s and %s share their %s%s face plane' % (na, nb, side, 'xyz'[ax]))


def check_palettes(model, rep):
    bases = {}
    for v, pal in model.palettes.items():
        missing = [r for r in R.ROLES if r not in pal]
        if missing:
            rep.error('palette', '%s lacks %s' % (v, missing))
            continue
        c = {r: np.array(pal[r], float) for r in R.ROLES}
        if np.linalg.norm(c['base'] - c['dark']) < 30:
            rep.warn('palette', '%s: base and dark are too close to read the shading' % v)
        if np.linalg.norm(c['eye'] - c['base']) < 60:
            rep.warn('palette', '%s: the eyes do not stand out from the base colour' % v)
        bases[v] = c['base']
    for (a, ca), (b, cb) in itertools.combinations(bases.items(), 2):
        if np.linalg.norm(ca - cb) < 18:
            rep.warn('palette', '%s and %s have nearly the same base colour' % (a, b))


def check_size(model, rep):
    used = float((model.mask[..., 3] > 0).mean())
    if used > 0.9:
        rep.warn('size', 'texture %d%% full' % round(used * 100))
    pts = np.vstack([w for _, _, w in R.posed_cubes(model)])
    lo, hi = pts.min(0), pts.max(0)
    rep.notes.append('%.1f x %.1f x %.1f blocks (w x h x l), texture %d%% used' % ((hi[0] - lo[0]) / 16, (hi[1] - lo[1]) / 16, (hi[2] - lo[2]) / 16, round(used * 100)))


def check_mcp(name, rep, client, accepted, self_test=False):
    path = os.path.join(R.HERE, name + '.bbmodel')
    if not os.path.exists(path):
        rep.warn('mcp', 'no %s.bbmodel to validate' % name)
        return
    # Joints overlap on purpose (a limb starts inside its socket); only deep pass-throughs are worth a look.
    res = client.call('bbmodel_validate', {'file': path, 'self_test': self_test, 'interpenetration_depth': 2.5})
    text = '\n'.join(c.get('text', '') for c in res.get('result', {}).get('content', []))
    try:
        data = json.loads(text)
    except ValueError:
        rep.error('mcp', 'bbmodel_validate did not answer: %s' % text[:200])
        return
    for gate in data.get('gates', []):
        for v in gate.get('violations', []):
            msg = v.get('message', '')
            if any(a in msg for a in accepted):
                continue
            (rep.error if gate.get('severity') == 'error' else rep.warn)('mcp/' + gate['id'], msg)
    for d in data.get('geckolib', {}).get('diagnostics', []):
        if d.get('check_id') == 'geckolib_modid':
            continue  # only the GeckoLib plugin's export dialog uses it
        (rep.error if d.get('severity') == 'error' else rep.warn)('mcp/geckolib', d.get('message', ''))
    if self_test:
        for st in data.get('self_test', []) or []:
            if not st.get('caught', True):
                rep.warn('mcp/self_test', 'gate %s does not discriminate on this model' % st.get('gate'))


def lint(name, client=None, accepted=(), self_test=False, model=None):
    model = model or R.Model(name)
    rep = Report(name)
    check_animations(model, rep)
    check_z_fighting(model, rep)
    check_palettes(model, rep)
    check_size(model, rep)
    if client:
        check_mcp(name, rep, client, accepted, self_test)
    return rep


def planted_defects(name):
    """--self-test: plant one defect per gate into a copy and confirm the gate catches it."""
    base = R.Model(name)
    results = []

    def caught(gate, mutate):
        m = copy.deepcopy(base)
        mutate(m)
        rep = lint(name, model=m)
        results.append((gate, any(e.startswith(gate) for e in rep.errors)))

    clip = next(iter(base.animations))
    rest = boxes_by_bone(base)
    bone = next(b for b in rest if solid_parent(base, b) and nearest(rest[b], rest[solid_parent(base, b)]) <= 0.01)
    length = float(base.animations[clip]['animation_length'])

    def late_key(m):
        m.animations[clip]['bones'].setdefault(bone, {})['rotation'] = {'0': [0, 0, 0], str(length + 1): [0, 0, 0]}

    def open_loop(m):
        a = m.animations.setdefault('idle', {'animation_length': 2.0, 'bones': {}})
        a['loop'] = True
        a['bones'].setdefault(bone, {})['rotation'] = {'0': [0, 0, 0], str(a['animation_length']): [0, 30, 0]}

    def sink(m):
        root = next(b for b in m.bones if not m.bones[b]['parent'])
        m.animations['attack']['bones'][root] = {'position': {'0': [0, 0, 0], '0.1': [0, -10, 0]}}

    def detach(m):
        m.animations['attack']['bones'][bone] = {'position': {'0': [0, 0, 0], '0.1': [40, 0, 0]}}

    def twin(m):
        c = m.bones[bone]['cubes'][0]
        m.bones[bone]['cubes'].append(dict(c, size=c['size'] * np.array([1, 0.5, 1])))

    caught('keyframes', late_key)
    caught('loop_seam', open_loop)
    caught('ground_sink', sink)
    caught('detachment', detach)
    caught('z_fighting', twin)
    return results


def main(argv):
    names = [a for a in argv if not a.startswith('--')] or R.all_models()
    use_mcp = '--no-mcp' not in argv
    self_test = '--self-test' in argv
    try:
        import archetypes
        accepted = getattr(archetypes, 'ACCEPTED', {})
    except Exception:
        accepted = {}
    client = None
    if use_mcp:
        import bbmcp
        client = bbmcp.Client()
    failed = False
    try:
        for name in names:
            rep = lint(name, client, accepted.get(name, ()), self_test and name == names[0])
            status = 'FAIL' if rep.errors else 'ok'
            print('%-10s %s  %s' % (name, status, '; '.join(rep.notes)))
            for e in rep.errors:
                print('   ERROR', e)
            for w in rep.warnings:
                print('   warn ', w)
            failed |= bool(rep.errors)
            if self_test and name == names[0]:
                for gate, ok in planted_defects(name):
                    print('   self-test %-12s %s' % (gate, 'caught' if ok else 'MISSED'))
                    failed |= not ok
    finally:
        if client:
            client.close()
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
