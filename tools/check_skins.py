# -*- coding: utf-8 -*-
"""Checks the appearance packs against the mods' jars, without starting the game.

    python tools/check_skins.py [mods folder]

By default it looks at runClient/mods-disabled. Point it at the mods folder of the real install to
check them against the versions that are actually used.

It exists because the first version of the packs was written from each mod's documentation and failed
on 44 lines: Ice and Fire's dragons are fire_dragon and not firedragon, and Naturalist has neither
hyena nor owl. That breaks nothing in game —MonsterRegistry.reskin checks the entity before touching
the block— but it leaves the monster with its vanilla model and it only shows by reading the server log.

The list of ids comes from each mod's en_us.json (the entity.<mod>.<id> keys), which is the only
source that ships with the jar itself and doesn't have to be taken on faith.
"""
import io
import json
import os
import re
import sys
import zipfile

SKINS = os.path.join('src', 'main', 'resources', 'dndsheets', 'skins')


def entities_of(jar_path):
    """modid -> set of entity ids that jar declares."""
    found = {}
    try:
        z = zipfile.ZipFile(jar_path)
    except Exception:
        return found
    for name in z.namelist():
        m = re.match(r'assets/([a-z0-9_]+)/lang/en_us\.json$', name)
        if not m:
            continue
        modid = m.group(1)
        try:
            data = json.loads(z.read(name).decode('utf-8', 'replace'))
        except Exception:
            continue
        for key in data:
            e = re.match(r'entity\.%s\.([a-z0-9_]+)$' % modid, key)
            if e:
                found.setdefault(modid, set()).add(e.group(1))
    return found


def main(mods_dir):
    known = {}
    for jar in sorted(os.listdir(mods_dir)):
        if jar.endswith('.jar'):
            for modid, ids in entities_of(os.path.join(mods_dir, jar)).items():
                known.setdefault(modid, set()).update(ids)

    total = bad = 0
    for pack in sorted(os.listdir(SKINS)):
        data = json.load(io.open(os.path.join(SKINS, pack), encoding='utf-8'))
        modid = data['mod']
        if modid not in known:
            print('  %-18s jar not found (cannot check it)' % pack)
            continue
        missing = []
        for monster, entity in data['skins'].items():
            total += 1
            name = entity.split(':', 1)[1]
            if name not in known[modid]:
                missing.append((monster, entity))
        bad += len(missing)
        print('  %-18s %3d lineas, %d sin entidad' % (pack, len(data['skins']), len(missing)))
        for monster, entity in missing:
            print('        %s -> %s  NO EXISTE' % (monster, entity))

    print('\n%d lines checked, %d point to an entity that does not exist.' % (total, bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.path.join('runClient', 'mods-disabled')))
