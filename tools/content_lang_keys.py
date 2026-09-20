# -*- coding: utf-8 -*-
"""Turns the names in the shipped packs into language keys.

Before: `"name": "Daga"` — fixed text in the data, which no language option can touch, and which
is also baked into the ItemStack's NBT, so an English client read "Daga" forever.
After: `"name": "content.dndsheets.weapon.dagger"`, which each client resolves in its own language.

The SPANISH comes from the name that was already there. The ENGLISH comes from the ID, which in these packs is the SRD id
and therefore is already in English (`dndsheets:fire_bolt` -> "Fire Bolt"); when the current name ALREADY
matches that id the name is preferred as is, because it carries the right punctuation
("Ammunition, +1" beats "Ammunition 1"). What has no id to draw from —monster attack names—
goes in OVERRIDES_EN by hand.

A pack written by hand by a DM does NOT go through here and needs no touching: `ContentNames.of` leaves the
literal as it was (Minecraft returns the raw key when it does not exist).

    python tools/content_lang_keys.py          # migrates and rewrites en_us/es_es
    python tools/content_lang_keys.py --dry    # only reports
"""
import json
import io
import os
import re
import sys

DEFAULTS = os.path.join('src', 'main', 'resources', 'dndsheets', 'defaults')
LANG = os.path.join('src', 'main', 'resources', 'assets', 'dndsheets', 'lang')
PREFIX = 'content.dndsheets.'

# presets.json is deliberately LEFT OUT, and not out of laziness: a preset's name is not only displayed,
# it is WRITTEN to the sheet (`characterClass`, `characterSubclass`), which is a text field the
# player sees and can edit, and from which SpellSlots.casterFor also deduces by substring whether the class
# casts spells. With the key inside, the player would read "content.dndsheets.preset.wizard" in the
# Class box of their sheet — decoupling storage (id) from display (key) would be another change, not this one.
#
# Instead, the 12 classes and 12 subclasses were translated to literal English text directly
# in presets.json (without a `content.dndsheets.*` key): SpellSlots.casterFor/castingAbilityFor,
# Config.hitDieFor and WeaponDefault.allowsClass already checked both the English and the
# Spanish substring before this change, so translating the literal breaks none of that — and the sheets
# already saved keep their old Spanish text, which those same matchers still recognize.

# Which file contributes which kind of key.
TYPES = {
    'weapons.json': 'weapon',
    'spells.json': 'spell',
    'monsters.json': 'monster',
    'items.json': 'item',
    'feats.json': 'feat',
    'traits.json': 'trait',
    'encounters.json': 'encounter',
}

# Lowercase inside an English title, except for the first word.
SMALL = {'of', 'the', 'and', 'or', 'in', 'on', 'a', 'an', 'to', 'from', 'with', 'at', 'by', 'for'}

# What cannot be derived from an id: monster attack names (the JSON gives them no id) and the
# few ids that are in Spanish. Written by hand against the SRD.
OVERRIDES_EN = {
    # --- monster attacks and actions ---
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
    # --- subclasses: the id is terse ("cleric:life") and the SRD name carries its prefix ---
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


# The inverse case: attacks the pack already ships in ENGLISH and that lack the Spanish.
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
    """English from the id, unless the current name is ALREADY that English, better punctuated."""
    if identifier and identifier in OVERRIDES_EN:
        return OVERRIDES_EN[identifier]
    if current in OVERRIDES_EN:
        return OVERRIDES_EN[current]
    # With no id to draw from (monster attacks): it is either in OVERRIDES_EN or stays as it was, and
    # main() lists it so someone can translate it. English is never invented from the Spanish.
    if not identifier:
        return current
    guess = title_en(slug(identifier).split('.')[-1])

    # The id groups the variants with a numeric suffix (armor_1, armor_2): the SRD writes them
    # "Armor, +1". Without this it came out "Armor 1", which is nobody's name.
    bonus = re.match(r'^(.*) ([123])$', guess)
    if bonus:
        guess = '%s, +%s' % (bonus.group(1), bonus.group(2))

    # If the existing name STARTS with the same word as the guess, it is already in English and
    # better written too: it carries the punctuation and nuances the id cannot carry
    # ("Ammunition, +1, +2, or +3" beats "Ammunition"; "Carpet of Flying (3 ft. x 5 ft.)" beats
    # "Carpet of Flying 3x5"; "Belt of Cloud Giant Strength" beats the id's order, meant
    # for grouping variants and not for reading). A Spanish name does not share its first word with
    # its English id —"Escudo Animado" against "Animated Shield"—, so that one falls to the guess.
    def first_word(text):
        words = re.findall(r'[A-Za-z0-9]+', text)
        return words[0].lower() if words else ''
    if current and first_word(current) == first_word(guess):
        return current
    return guess


def collect(pack, kind, entries, unresolved):
    """Walks a pack and returns [(object, field, key, es, en)] without touching anything yet."""
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
        # Attacks have no id: the key comes from the Spanish name, which is stable and unique
        # within the pack, and is also SHARED between monsters (twenty of them carry "Cimitarra").
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
        # Substitutions grouped by ENTRY, not by loose value: two different objects can
        # share a name ("Pocion de Curacion" is potion_of_healing and potion_of_healing_common) and with
        # a global map the second would take the first one's key. Within an entry the name
        # is unique. The same value in different entries —"Cimitarra", which twenty
        # monsters carry— does share a key on purpose: it is a single language entry.
        by_entry = {}
        for obj, field, key, spanish, english in found:
            es_new.setdefault(key, spanish)
            en_new.setdefault(key, english)
            by_entry.setdefault(id(obj), []).append((obj[field], key))
        total += len(found)
        print('%-16s %4d nombres' % (filename, len(found)))
        if dry:
            continue

        # TEXT substitution, not a round-trip through json.dump: invariant 10 of PROJECT_CONTEXT.md
        # says these packs are hand-formatted (compact, one entry per line) and a dump
        # reflows the whole file — 14,000 lines of diff where only 1,351 values change. Same
        # criterion, and for the same reason, as tools/add_spell_school.py.
        raw = io.open(path, encoding='utf-8').read()

        # Each entry starts at its "id" and runs to the next one's "id", so that stretch of
        # text contains its name and those of its attacks and nothing else. It works the same with packs of one
        # entry per line (weapons, items) and with multiline ones (monsters), without reformatting anything.
        cuts = [m.start() for m in re.finditer(r'"id"\s*:\s*"', raw)]
        assert len(cuts) == len(pack), ('%s: %d entries but %d "id" fields in the text — there are nested ids '
                                          'and splitting by entry does not work here'
                                          % (filename, len(pack), len(cuts)))
        cuts.append(len(raw))

        chunks = []
        for n, entry in enumerate(pack):
            chunk = raw[cuts[n]:cuts[n + 1]]
            for value, key in by_entry.get(id(entry), []) + sum(
                    (by_entry.get(id(a), []) for group in ('attacks', 'abilities_special')
                     for a in entry.get(group, [])), []):
                # "name" is also the appliesEffect field, which is NOT migrated (there the value is the id
                # of a condition and the engine compares it). That is why it is anchored on the exact value and not on
                # the field: a "poisoned" never matches a content name.
                before = '"name": %s' % json.dumps(value, ensure_ascii=False)
                assert before in chunk, '%s: could not find %s in %s' % (filename, before, entry.get('id'))
                chunk = chunk.replace(before, '"name": "%s"' % key)
            chunks.append(chunk)
        io.open(path, 'w', encoding='utf-8').write(raw[:cuts[0]] + ''.join(chunks))

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
