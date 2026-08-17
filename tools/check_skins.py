# -*- coding: utf-8 -*-
"""Comprueba los packs de aspecto contra los jars de los mods, sin arrancar el juego.

    python tools/check_skins.py [carpeta de mods]

Por defecto mira runClient/mods-disabled. Apuntalo a la carpeta mods de la instalacion real para
comprobarlos contra las versiones que de verdad se usan.

Existe porque la primera version de los packs se escribio desde la documentacion de cada mod y fallo
en 44 lineas: los dragones de Ice and Fire son fire_dragon y no firedragon, y Naturalist no tiene ni
hiena ni buho. Eso no rompe nada en juego —MonsterRegistry.reskin comprueba la entidad antes de tocar
el bloque— pero deja al monstruo con su modelo vanilla y solo se ve leyendo el log del servidor.

La lista de ids sale del en_us.json de cada mod (las claves entity.<mod>.<id>), que es la unica
fuente que viene con el propio jar y no hay que creerse.
"""
import io
import json
import os
import re
import sys
import zipfile

SKINS = os.path.join('src', 'main', 'resources', 'dndsheets', 'skins')


def entities_of(jar_path):
    """modid -> conjunto de ids de entidad que declara ese jar."""
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
            print('  %-18s no encuentro el jar (no puedo comprobarlo)' % pack)
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

    print('\n%d lineas comprobadas, %d apuntan a una entidad que no existe.' % (total, bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else os.path.join('runClient', 'mods-disabled')))
