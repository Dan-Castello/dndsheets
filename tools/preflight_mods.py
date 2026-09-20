# -*- coding: utf-8 -*-
"""Checks the dependencies of the mods in runClient/mods WITHOUT starting the game.

    python tools/preflight_mods.py

Written after a failed startup that cost a whole "start, crash, read the log" cycle:
Citadel was missing (which Ice and Fire and Alex's Mobs ask for) and four mods demanded a newer Forge version
than the environment's. All of that is written in each jar's mods.toml, so it can be known
beforehand. FORGE_VERSION=47.2.0 in the environment to check against another version.

It is approximate on purpose: it only compares the range's lower bound, which is 99% of the real
failures. A "${file.jarVersion}" is a marker that Forge itself resolves at startup, not a failure.
"""
import io
import json
import os, re, sys, zipfile

MODS = 'runClient/mods'
FORGE = os.environ.get('FORGE_VERSION', '47.4.22')

# Read from build.gradle and not copied here: one more copy of the version number is one more copy that
# goes stale without anyone noticing (it already happened, see mods.toml).
VERSION = re.search(r"^version\s*=\s*'([^']+)'", io.open('build.gradle', encoding='utf-8').read(), re.M).group(1)

present = {'forge': FORGE, 'minecraft': '1.20.1', 'dndsheets': VERSION}
deps = []   # (jar, modId, range, mandatory)

for jar in sorted(os.listdir(MODS)):
    if not jar.endswith('.jar'):
        continue
    try:
        z = zipfile.ZipFile(os.path.join(MODS, jar))
        raw = z.read('META-INF/mods.toml').decode('utf-8', 'replace')
    except Exception as e:
        print('  (sin mods.toml) %s' % jar)
        continue
    # jar-in-jar: mods that embed their libraries bring them inside
    for n in z.namelist():
        m = re.match(r'META-INF/jarjar/(.+)\.jar$', n)
        if m:
            present.setdefault(m.group(1).split('-')[0].lower(), '?')
    for m in re.finditer(r'modId\s*=\s*"([^"]+)"\s*\n\s*version\s*=\s*"([^"]+)"', raw):
        present[m.group(1)] = m.group(2)
    # Split by block, not with a [^\[]* : the bracket of versionRange="[47.3,)" cut the block
    # before reading the range, and then EVERYTHING looked satisfied. It was a textbook false green.
    for block in re.split(r'\[\[dependencies\.[^\]]+\]\]', raw)[1:]:
        block = block.split('[[')[0]
        mid = re.search(r'modId\s*=\s*"([^"]+)"', block)
        mand = re.search(r'mandatory\s*=\s*(true|false)', block)
        rng = re.search(r'versionRange\s*=\s*"([^"]*)"', block)
        if mid:
            deps.append((jar, mid.group(1), rng.group(1) if rng else '', mand.group(1) == 'true' if mand else True))
    # the ids the jar itself declares, even if they didn't match the pattern above
    for m in re.finditer(r'^\s*modId\s*=\s*"([^"]+)"', raw, re.M):
        present.setdefault(m.group(1), '?')

def ver(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r'[.\-+]', v)[:4]]

def satisfies(have, rng):
    if not rng or have == '?':
        return True
    m = re.match(r'[\[\(]([^,\]\)]*),?([^,\]\)]*)[\]\)]', rng)
    if not m or not m.group(1):
        return True
    return ver(have) >= ver(m.group(1))


def dev_hostile_mixins(path):
    """Mods whose mixins will NOT be applied in the development client.

    A mod compiled for production references SRG names (f_117950_) and ships a refmap with the
    "searge" table so Forge can translate them to the environment's. Without that table, the @Shadow doesn't
    find the field and the startup dies with MixinApplyError. It happened to Oculus, and this is the only
    way to know without starting.
    """
    out = []
    for jar in sorted(os.listdir(path)):
        if not jar.endswith('.jar'):
            continue
        try:
            z = zipfile.ZipFile(os.path.join(path, jar))
        except Exception:
            continue
        for n in [x for x in z.namelist() if x.endswith('refmap.json')]:
            try:
                d = json.loads(z.read(n).decode('utf-8', 'replace'))
            except Exception:
                continue
            blob = json.dumps(d.get('mappings') or {})
            if re.search(r'[fm]_\d+_', blob) and 'searge' not in (d.get('data') or {}):
                out.append((jar, n))
    return out


missing, old = [], []
for jar, mid, rng, mandatory in deps:
    if not mandatory:
        continue
    if mid not in present:
        missing.append((jar, mid, rng))
    elif not satisfies(present[mid], rng):
        old.append((jar, mid, rng, present[mid]))

print('jars: %d | mods detectados: %d | Forge: %s\n' % (
    len([j for j in os.listdir(MODS) if j.endswith('.jar')]), len(present), FORGE))
if missing:
    print('FALTAN:')
    for jar, mid, rng in missing:
        print('  %-28s pide %s %s' % (jar, mid, rng))
if old:
    print('DEMASIADO VIEJOS:')
    for jar, mid, rng, have in old:
        print('  %-28s pide %s %s, hay %s' % (jar, mid, rng, have))
hostile = dev_hostile_mixins(MODS)
if hostile:
    print('MIXINS THAT DO NOT APPLY IN DEVELOPMENT (starting with this = MixinApplyError):')
    for jar, n in hostile:
        print('  %-28s %s sin tabla searge' % (jar, n))
if not missing and not old and not hostile:
    print('No uncovered mandatory dependencies, and no problematic mixin.')
