# -*- coding: utf-8 -*-
"""Pasa los nombres de los packs de serie a claves de idioma.

Antes: `"name": "Daga"` — texto fijo en el dato, que ninguna opcion de idioma puede tocar, y que
ademas se hornea en el NBT del ItemStack, asi que un cliente en ingles leia "Daga" para siempre.
Despues: `"name": "content.dndsheets.weapon.dagger"`, que cada cliente resuelve en el suyo.

El ESPANOL sale del nombre que ya habia. El INGLES sale del ID, que en estos packs es el id del SRD
y por tanto ya viene en ingles (`dndsheets:fire_bolt` -> "Fire Bolt"); cuando el nombre actual YA
coincide con ese id se prefiere el nombre tal cual, porque trae la puntuacion buena
("Ammunition, +1" gana a "Ammunition 1"). Lo que no tiene id del que tirar —los nombres de ataque
de monstruo— va en OVERRIDES_EN a mano.

Un pack escrito a mano por un DM NO pasa por aqui y no hace falta tocarlo: `ContentNames.of` deja el
literal como estaba (Minecraft devuelve la clave cruda cuando no existe).

    python tools/content_lang_keys.py          # migra y reescribe en_us/es_es
    python tools/content_lang_keys.py --dry    # solo informa
"""
import json
import io
import os
import re
import sys

DEFAULTS = os.path.join('src', 'main', 'resources', 'dndsheets', 'defaults')
LANG = os.path.join('src', 'main', 'resources', 'assets', 'dndsheets', 'lang')
PREFIX = 'content.dndsheets.'

# presets.json queda FUERA a proposito, y no por pereza: el nombre de un preset no solo se enseña,
# se ESCRIBE en la hoja (`characterClass`, `characterSubclass`), que es un campo de texto que el
# jugador ve y puede editar, y del que ademas SpellSlots.casterFor deduce por subcadena si la clase
# lanza conjuros. Con la clave dentro, el jugador leeria "content.dndsheets.preset.wizard" en la
# casilla Clase de su ficha. Traducir clases pide que la hoja guarde el id (ya lo tiene en
# `appliedPresetId`) y muestre el nombre aparte — otro cambio, no este.
# ponytail: 12 clases y 12 subclases siguen en espanol; el arreglo es desacoplar guardado de display.

# Que fichero aporta que tipo de clave.
TYPES = {
    'weapons.json': 'weapon',
    'spells.json': 'spell',
    'monsters.json': 'monster',
    'items.json': 'item',
    'feats.json': 'feat',
    'traits.json': 'trait',
    'encounters.json': 'encounter',
}

# Minusculas dentro de un titulo ingles, salvo en la primera palabra.
SMALL = {'of', 'the', 'and', 'or', 'in', 'on', 'a', 'an', 'to', 'from', 'with', 'at', 'by', 'for'}

# Lo que no se puede derivar de un id: nombres de ataque de monstruo (el JSON no les da id) y los
# pocos ids que estan en espanol. Escrito a mano contra el SRD.
OVERRIDES_EN = {
    # --- ataques y acciones de monstruo ---
    'Aguijon': 'Stinger',
    'Aguijon (mordisco en forma bestial)': 'Stinger (Bite in Beast Form)',
    'Aliento de Fuego': 'Fire Breath',
    'Aplastamiento': 'Slam',
    'Arco Corto': 'Shortbow',
    'Arco Largo': 'Longbow',
    'Arpon': 'Harpoon',
    'Ballesta de Mano': 'Hand Crossbow',
    'Ballesta Ligera': 'Light Crossbow',
    'Ballesta Pesada': 'Heavy Crossbow',
    'Baston': 'Quarterstaff',
    u'Bastón': 'Quarterstaff',
    'Bola de Fuego': 'Fireball',
    'Cascos': 'Hooves',
    'Cimitarra': 'Scimitar',
    'Colmillo': 'Fang',
    'Constrenir': 'Constrict',
    'Contacto': 'Touch',
    'Contacto Putrefacto': 'Rotting Touch',
    'Cornada': 'Gore',
    'Daga': 'Dagger',
    'Dardo Envenenado': 'Poison Dart',
    'Descarga': 'Discharge',
    'Drenar Fuerza': 'Strength Drain',
    'Drenar Sangre': 'Blood Drain',
    'Drenar Vida': 'Life Drain',
    'Embestida': 'Ram',
    'Escudo con Puas': 'Spiked Shield',
    'Espada Corta': 'Shortsword',
    'Espada Larga': 'Longsword',
    'Estoque': 'Rapier',
    'Garra': 'Claw',
    'Garra (mordisco en forma bestial)': 'Claw (Bite in Beast Form)',
    'Garras': 'Claws',
    'Garrote': 'Club',
    'Garrote de Hueso con Puas': 'Spiked Bone Club',
    'Garrote Grande': 'Greatclub',
    'Garrote Pesado': 'Heavy Club',
    'Golpe': 'Slam',
    'Hacha Grande': 'Greataxe',
    'Jabalina': 'Javelin',
    'Lanza': 'Spear',
    'Lucero del Alba': 'Morningstar',
    'Mandoble': 'Greatsword',
    'Martillo de Guerra': 'Warhammer',
    'Maza': 'Mace',
    'Mordisco': 'Bite',
    'Mordiscos': 'Bites',
    'Pica': 'Pike',
    'Pico': 'Beak',
    'Pico de Guerra': 'War Pick',
    'Picos': 'Beaks',
    'Puno': 'Fist',
    'Roca': 'Rock',
    'Seudopodo': 'Pseudopod',
    'Talones': 'Talons',
    'Tentaculos': 'Tentacles',
    'Toque que Consume Vida': 'Life-Draining Touch',
    'Zarpazo': 'Rake',
    # --- ids en espanol ---
    'dndsheets:emboscada_goblin': 'Goblin Ambush',
    'dndsheets:patrulla_hobgoblin': 'Hobgoblin Patrol',
    'dndsheets:manada_lobos': 'Wolf Pack',
    'dndsheets:cripta': 'Crypt Guardians',
    'dndsheets:banda_bandidos': 'Bandit Gang',
    'dndsheets:bandidos': 'Highway Bandits',
    # --- subclases: el id es escueto ("cleric:life") y el nombre del SRD lleva su prefijo ---
    'fighter:champion': 'Champion',
    'wizard:evocation': 'School of Evocation',
    'rogue:thief': 'Thief',
    'cleric:life': 'Life Domain',
    'paladin:devotion': 'Oath of Devotion',
    'barbarian:berserker': 'Path of the Berserker',
    'ranger:hunter': 'Hunter',
    'bard:lore': 'College of Lore',
    'druid:land': 'Circle of the Land',
    'warlock:fiend': 'The Fiend',
    'sorcerer:draconic': 'Draconic Bloodline',
    'monk:open_hand': 'Way of the Open Hand',
    'monje:artes_marciales': 'Martial Arts',
    'picaro:ataque_furtivo': 'Sneak Attack',
}


# El caso inverso: ataques que el pack trae ya en INGLES y a los que les falta el espanol.
OVERRIDES_ES = {
    'Battleaxe': 'Hacha de Batalla',
    'Beard': 'Barba',
    'Chain': 'Cadena',
    'Claw (Fiend Form Only)': u'Garra (solo en forma de diablo)',
    'Claw (Oni Form Only)': u'Garra (solo en forma de oni)',
    'Claws (Hag Form Only)': u'Garras (solo en forma de bruja)',
    'Fork': 'Tridente',
    'Glaive': 'Guja',
    'Horn': 'Cuerno',
    'Horns': 'Cuernos',
    'Hurl Flame': 'Lanzar Llama',
    'Maul': 'Mazo',
    'Paralyzing Touch': 'Contacto Paralizante',
    'Pincer': 'Pinza',
    'Rotting Fist': u'Puno Putrefacto',
    'Shield Bash': 'Golpe de Escudo',
    'Slaying Longbow': 'Arco Largo Matador',
    'Snake Hair': 'Cabellera de Serpientes',
    'Stinger': u'Aguijon',
    'Stomp': 'Pisoton',
    'Sword': 'Espada',
    'Tail': 'Cola',
    'Tail Spike': u'Pua de la Cola',
    'Tail Stinger': u'Aguijon de la Cola',
    'Tentacle': u'Tentaculo',
    'Tusks': 'Colmillos',
    'Unarmed Strike': 'Golpe sin Armas',
    'Whip': 'Latigo',
    'Withering Touch': 'Contacto Marchitador',
}


def slug(identifier):
    """`dndsheets:fire_bolt` -> `fire_bolt`; `fighter:champion` -> `fighter.champion`."""
    return identifier.replace('dndsheets:', '').replace(':', '.')


def title_en(text):
    words = [w for w in re.split(r'[_\s]+', text) if w]
    out = []
    for i, w in enumerate(words):
        low = w.lower()
        out.append(low if (i > 0 and low in SMALL) else (w[0].upper() + w[1:] if w else w))
    return ' '.join(out)


def english_for(identifier, current):
    """Ingles del id, salvo que el nombre actual YA sea ese ingles mejor puntuado."""
    if identifier and identifier in OVERRIDES_EN:
        return OVERRIDES_EN[identifier]
    if current in OVERRIDES_EN:
        return OVERRIDES_EN[current]
    # Sin id del que tirar (ataques de monstruo): o esta en OVERRIDES_EN o se queda como estaba, y
    # main() lo lista para que alguien lo traduzca. Nunca se inventa un ingles a partir del espanol.
    if not identifier:
        return current
    guess = title_en(slug(identifier).split('.')[-1])

    # El id agrupa las variantes con un sufijo numerico (armor_1, armor_2): el SRD las escribe
    # "Armor, +1". Sin esto salia "Armor 1", que no es el nombre de nada.
    bonus = re.match(r'^(.*) ([123])$', guess)
    if bonus:
        guess = '%s, +%s' % (bonus.group(1), bonus.group(2))

    # Si el nombre que ya hay EMPIEZA por la misma palabra que la conjetura, ya esta en ingles y
    # ademas mejor escrito: trae la puntuacion y los matices que el id no puede llevar
    # ("Ammunition, +1, +2, or +3" gana a "Ammunition"; "Carpet of Flying (3 ft. x 5 ft.)" gana a
    # "Carpet of Flying 3x5"; "Belt of Cloud Giant Strength" gana al orden del id, pensado para
    # agrupar variantes y no para leerse). Un nombre en espanol no comparte la primera palabra con
    # su id ingles —"Escudo Animado" contra "Animated Shield"—, asi que ese cae a la conjetura.
    def first_word(text):
        words = re.findall(r'[A-Za-z0-9]+', text)
        return words[0].lower() if words else ''
    if current and first_word(current) == first_word(guess):
        return current
    return guess


def collect(pack, kind, entries, unresolved):
    """Recorre un pack y devuelve [(objeto, campo, clave, es, en)] sin tocar nada todavia."""
    found = []

    def add(obj, field, key, identifier):
        current = obj[field]
        if current.startswith(PREFIX):
            return  # ya migrado
        english = english_for(identifier, current)
        if identifier is None:
            unresolved.append(current)
        spanish = OVERRIDES_ES.get(current, current)
        found.append((obj, field, key, spanish, english))

    for entry in pack:
        identifier = entry.get('id', '')
        base = PREFIX + kind + '.' + slug(identifier)
        if 'name' in entry:
            add(entry, 'name', base, identifier)
        # Los ataques no tienen id: la clave sale del nombre en espanol, que es estable y unico
        # dentro del pack, y ademas se COMPARTE entre monstruos (veinte de ellos llevan "Cimitarra").
        for group in ('attacks', 'abilities_special'):
            for action in entry.get(group, []):
                if 'name' not in action:
                    continue
                key = PREFIX + 'attack.' + re.sub(r'[^a-z0-9]+', '_',
                                                  action['name'].lower()).strip('_')
                add(action, 'name', key, None)
        entries.append(entry)
    return found


def main():
    dry = '--dry' in sys.argv
    es_new, en_new = {}, {}
    unresolved = []
    total = 0

    for filename, kind in sorted(TYPES.items()):
        path = os.path.join(DEFAULTS, filename)
        pack = json.load(io.open(path, encoding='utf-8'))
        found = collect(pack, kind, [], unresolved)
        # Las sustituciones agrupadas por ENTRADA, no por valor suelto: dos objetos distintos pueden
        # llamarse igual ("Pocion de Curacion" es potion_of_healing y potion_of_healing_common) y con
        # un mapa global el segundo se llevaria la clave del primero. Dentro de una entrada el nombre
        # si es unico. Un mismo valor en entradas distintas —"Cimitarra", que llevan veinte
        # monstruos— si comparte clave a proposito: es una sola entrada de idioma.
        por_entrada = {}
        for obj, field, key, spanish, english in found:
            es_new.setdefault(key, spanish)
            en_new.setdefault(key, english)
            por_entrada.setdefault(id(obj), []).append((obj[field], key))
        total += len(found)
        print('%-16s %4d nombres' % (filename, len(found)))
        if dry:
            continue

        # Sustitucion de TEXTO, no round-trip por json.dump: la invariante 10 de PROJECT_CONTEXT.md
        # dice que estos packs estan formateados a mano (compactos, una entrada por linea) y un dump
        # reflowea el fichero entero — 14.000 lineas de diff donde solo cambian 1.351 valores. Mismo
        # criterio, y por el mismo motivo, que tools/add_spell_school.py.
        raw = io.open(path, encoding='utf-8').read()

        # Cada entrada empieza por su "id" y llega hasta el "id" de la siguiente, asi que ese tramo de
        # texto contiene su nombre y los de sus ataques y nada mas. Sirve igual con los packs de una
        # entrada por linea (armas, objetos) y con los multilinea (monstruos), sin reformatear nada.
        cortes = [m.start() for m in re.finditer(r'"id"\s*:\s*"', raw)]
        assert len(cortes) == len(pack), ('%s: %d entradas pero %d campos "id" en el texto — hay ids '
                                          'anidados y el troceado por entrada no vale aqui'
                                          % (filename, len(pack), len(cortes)))
        cortes.append(len(raw))

        trozos = []
        for n, entry in enumerate(pack):
            trozo = raw[cortes[n]:cortes[n + 1]]
            for valor, key in por_entrada.get(id(entry), []) + sum(
                    (por_entrada.get(id(a), []) for group in ('attacks', 'abilities_special')
                     for a in entry.get(group, [])), []):
                # "name" es tambien el campo de appliesEffect, que NO se migra (ahi el valor es el id
                # de una condicion y el motor lo compara). Por eso se ancla en el valor exacto y no en
                # el campo: un "envenenado" nunca coincide con un nombre de contenido.
                antes = '"name": %s' % json.dumps(valor, ensure_ascii=False)
                assert antes in trozo, '%s: no encuentro %s en %s' % (filename, antes, entry.get('id'))
                trozo = trozo.replace(antes, '"name": "%s"' % key)
            trozos.append(trozo)
        io.open(path, 'w', encoding='utf-8').write(raw[:cortes[0]] + ''.join(trozos))

    missing = sorted(set(n for n in unresolved
                         if n not in OVERRIDES_EN and n not in OVERRIDES_ES))
    if missing:
        print('\nSIN INGLES (anadelos a OVERRIDES_EN): %d' % len(missing))
        for name in missing:
            print('   %s' % name)

    print('\n%d nombres -> %d claves' % (total, len(es_new)))
    if dry:
        return 0 if not missing else 1

    for code, values in (('en_us', en_new), ('es_es', es_new)):
        path = os.path.join(LANG, code + '.json')
        lang = json.load(io.open(path, encoding='utf-8'))
        lang.update(values)
        with io.open(path, 'w', encoding='utf-8') as handle:
            json.dump(lang, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write('\n')
        print('%s: %d claves' % (code, len(lang)))
    return 0 if not missing else 1


if __name__ == '__main__':
    sys.exit(main())
