# -*- coding: utf-8 -*-
"""Comprueba las dependencias de los mods de runClient/mods SIN arrancar el juego.

    python tools/preflight_mods.py

Escrito despues de un arranque fallido que costo un ciclo entero de "arranca, crashea, lee el log":
faltaba Citadel (que piden Ice and Fire y Alexs Mobs) y cuatro mods exigian una version de Forge mas
nueva que la del entorno. Todo eso esta escrito en el mods.toml de cada jar, o sea que se puede saber
antes. FORGE_VERSION=47.2.0 en el entorno para comprobar contra otra version.

Es aproximado a proposito: solo compara el limite inferior del rango, que es el 99% de los fallos
reales. Un "${file.jarVersion}" es un marcador que el propio Forge resuelve al arrancar, no un fallo.
"""
import json
import os, re, sys, zipfile

MODS = 'runClient/mods'
FORGE = os.environ.get('FORGE_VERSION', '47.4.22')

present = {'forge': FORGE, 'minecraft': '1.20.1', 'dndsheets': '1.0.2'}
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
    # jar-in-jar: los mods que embeben sus librerias las traen dentro
    for n in z.namelist():
        m = re.match(r'META-INF/jarjar/(.+)\.jar$', n)
        if m:
            present.setdefault(m.group(1).split('-')[0].lower(), '?')
    for m in re.finditer(r'modId\s*=\s*"([^"]+)"\s*\n\s*version\s*=\s*"([^"]+)"', raw):
        present[m.group(1)] = m.group(2)
    # Trocear por bloque, no con un [^\[]* : el corchete de versionRange="[47.3,)" cortaba el bloque
    # antes de leer el rango, y entonces TODO parecia satisfecho. Era un falso verde de manual.
    for block in re.split(r'\[\[dependencies\.[^\]]+\]\]', raw)[1:]:
        block = block.split('[[')[0]
        mid = re.search(r'modId\s*=\s*"([^"]+)"', block)
        mand = re.search(r'mandatory\s*=\s*(true|false)', block)
        rng = re.search(r'versionRange\s*=\s*"([^"]*)"', block)
        if mid:
            deps.append((jar, mid.group(1), rng.group(1) if rng else '', mand.group(1) == 'true' if mand else True))
    # los ids que el propio jar declara, aunque no casaran con el patron de arriba
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
    """Mods cuyos mixins NO se van a aplicar en el cliente de desarrollo.

    Un mod compilado para produccion referencia los nombres SRG (f_117950_) y trae un refmap con la
    tabla "searge" para que Forge los traduzca a los del entorno. Sin esa tabla, el @Shadow no
    encuentra el campo y el arranque muere con MixinApplyError. Le paso a Oculus, y esta es la unica
    forma de saberlo sin arrancar.
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
    print('MIXINS QUE NO APLICAN EN DESARROLLO (arrancar con esto = MixinApplyError):')
    for jar, n in hostile:
        print('  %-28s %s sin tabla searge' % (jar, n))
if not missing and not old and not hostile:
    print('Sin dependencias obligatorias sin cubrir, y ningun mixin problematico.')
