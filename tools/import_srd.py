# -*- coding: utf-8 -*-
"""Imports outside content (SRD 5.1/5.2, Open5e, or another table's pack) into this mod's packs.

    python tools/import_srd.py --kind feat   --from <url|archivo> --into src/main/resources/dndsheets/defaults/feats.json
    python tools/import_srd.py --kind spell  --from https://api.open5e.com/v1/spells/?limit=50 --dry-run
    python tools/import_srd.py --kind monster --from src/2014/en/5e-SRD-Monsters.json --limit 20

Why it exists: the 330 monsters and 87 spells that already ship were imported BY HAND, once, and that
work was written down nowhere. That turned every expansion into the same work again, and
left tables with no way to bring their own content other than writing it entry by entry.

Three input formats, detected by what the first record looks like (nobody has to declare anything):

  * **5e-bits/5e-database** ("index" key): the JSON transcription of the SRD, both 5.1 (`src/2014/en`)
    and 5.2 (`src/2024/en`). It is where the content that already ships came from.
  * **Open5e** ("slug" key): the community API, with Kobold Press's OGL content and more.
    Its pagination (`next`) is followed up to `--limit`.
  * **Native** ("id" key): a pack that is ALREADY in this mod's format. It is not a silly case: it serves
    to merge another table's pack without overwriting yours, which is what `--into` does.

Three rules that are not negotiable:

  1. **`--into` only ADDS.** An id that is already in the pack is skipped and reported. This repo's packs
     are hand-formatted (invariant 10) and a new entry is spliced in as text before the final `]`:
     the bytes of what was already there are not touched.
  2. **What cannot be mapped is not invented: it is skipped and counted at the end.** A spell whose damage does not
     appear in the prose gets in as a spell that does nothing, and that is discovered at the table.
  3. **Damage types are left as they come.** The mod's `DamageTypes.normalize` understands "fire" the same
     as "fuego", so translating them here would be a second table that can drift from the first.

`--lang` takes a dictionary `{"dndsheets:alert": {"name": "...", "description": "..."}}` so the
import is reproducible: whatever is translated in the repo can be generated again.
"""
import argparse
import json
import os
import re
import sys
import unicodedata
import urllib.request

UA = {'User-Agent': 'dndsheets-import/1.0 (+https://github.com/Dan-Castello/dndsheets)'}


def fetch(source, limit):
    """A local file or a URL. Returns the list of records, following Open5e's pagination."""
    records = []
    while source:
        remote = bool(re.match(r'https?://', source))
        if remote:
            with urllib.request.urlopen(urllib.request.Request(source, headers=UA), timeout=120) as r:
                data = json.loads(r.read().decode('utf-8'))
        else:
            with open(source, encoding='utf-8') as f:
                data = json.load(f)
        if isinstance(data, dict) and 'results' in data:
            records.extend(data['results'])
            # Pagination is only followed if it is really needed: asking for 3200 monsters to keep
            # 20 is rude to a free API. And only from a URL: a dump saved on disk carries
            # its own "next", and following it turned "import this file" into 72 internet requests.
            source = data.get('next') if (remote and (limit is None or len(records) < limit)) else None
        else:
            records.extend(data if isinstance(data, list) else [data])
            source = None
    return records[:limit] if limit else records


def slugify(text):
    stripped = unicodedata.normalize('NFD', text).encode('ascii', 'ignore').decode('ascii')
    return re.sub(r'[^a-z0-9]+', '_', stripped.lower()).strip('_')


def shape_of(record):
    if 'index' in record:
        return '5e-bits'
    if 'slug' in record:
        return 'open5e'
    if 'id' in record:
        return 'nativo'
    return None


def source_id(record, shape, namespace):
    if shape == 'nativo':
        return record['id']
    return '%s:%s' % (namespace, slugify(record.get('index') or record.get('slug')))


# --------------------------------------------------------------------------- dotes

# The SRD 5.2 feat type says at which level it can be taken. It is not cosmetic: Epic Boons are
# level 19, and offering them at the level 4 improvement fills the list with things the server will reject.
FEAT_MIN_LEVEL = {'origin': 1, 'fighting-style': 1, 'fighting style': 1, 'general': 4, 'epic boon': 19,
                  'epic-boon': 19, 'boon': 19}

# Ability Score Improvement is a feat in SRD 5.2, but in this mod it already IS the resource feats
# spend (see LevelUpManager). Importing it would be offering it as an alternative to itself.
FEAT_SKIP = {'ability-score-improvement', 'ability_score_improvement'}


def map_feat(record, shape, namespace):
    if shape == 'nativo':
        return record, None
    key = record.get('index') or record.get('slug') or ''
    if key.lower() in FEAT_SKIP:
        return None, 'Ability Score Improvement is already the resource feats spend'
    name = record.get('name')
    description = (record.get('description') or record.get('desc') or '').strip()
    if not name or not description:
        return None, 'no name or no text'
    out = {'id': source_id(record, shape, namespace), 'name': name,
           'description': re.sub(r'\s*\n\s*', ' ', description)}
    level = FEAT_MIN_LEVEL.get(str(record.get('type', '')).lower())
    if level is None and record.get('prerequisite'):
        level = 4  # Open5e does not give the type; a feat with a prerequisite is not an origin one.
    if level and level > 1:
        out['minLevel'] = level
    return out, None


# --------------------------------------------------------------------------- hechizos

# Which ability each class casts with. The SRD does not say it on the spell (it depends on who casts it),
# so the first class that can cast it is taken, which is right in 95% of cases.
CASTING_ABILITY = {'wizard': 'int', 'artificer': 'int',
                   'cleric': 'wis', 'druid': 'wis', 'ranger': 'wis',
                   'bard': 'cha', 'sorcerer': 'cha', 'warlock': 'cha', 'paladin': 'cha'}

ABILITY_KEY = {'str': 'str', 'strength': 'str', 'dex': 'dex', 'dexterity': 'dex',
               'con': 'con', 'constitution': 'con', 'int': 'int', 'intelligence': 'int',
               'wis': 'wis', 'wisdom': 'wis', 'cha': 'cha', 'charisma': 'cha'}

DICE = re.compile(r'(\d+d\d+)')


def flatten(value):
    if value is None:
        return ''
    return value if isinstance(value, str) else ' '.join(str(x) for x in value)


def spell_classes(record):
    raw = record.get('classes') or record.get('dnd_class') or ''
    if isinstance(raw, list):
        names = [c.get('name', c) if isinstance(c, dict) else c for c in raw]
    else:
        names = str(raw).split(',')
    return [str(n).strip().lower() for n in names if str(n).strip()]


def map_spell(record, shape, namespace):
    if shape == 'nativo':
        return record, None
    name = record.get('name')
    # Open5e brings both: "level": "4th-level" (to read) and "level_int": 4. The good one is the second.
    level = record.get('level_int', record.get('level'))
    if name is None or level is None:
        return None, 'no name or no level'
    # "desc"/"higher_level" are a paragraph in Open5e and a LIST of paragraphs in 5e-bits.
    prose = flatten(record.get('desc'))
    higher = flatten(record.get('higher_level'))
    out = {'id': source_id(record, shape, namespace), 'name': name, 'level': int(level)}

    ability = 'int'
    for cls in spell_classes(record):
        if cls in CASTING_ABILITY:
            ability = CASTING_ABILITY[cls]
            break
    out['castingAbility'] = ability

    # The die: 5e-bits brings it structured by slot level; Open5e only in the prose.
    dice = damage_type = None
    upcast = None
    damage = record.get('damage') or {}
    at_slot = damage.get('damage_at_slot_level') or damage.get('damage_at_character_level') or {}
    if at_slot:
        first = min(at_slot, key=lambda k: int(k))
        dice = at_slot[first]
        rest = sorted((int(k) for k in at_slot), key=int)
        # A cantrip does NOT scale by spending a slot: it grows with the character level, and Spell.atCasterLevel
        # already takes care of that. Writing upcastDice for it would give it both increases.
        if len(rest) > 1 and int(level) > 0 and 'damage_at_slot_level' in damage:
            step = at_slot[str(rest[1])]
            # "8d6" -> "10d6" with one more slot means +1d6 per level. The increase is only declared if the
            # die is the same: if it changes (2d8 -> 3d10) there is no increment to write.
            a, b = DICE.match(dice), DICE.match(step)
            if a and b and a.group(1).split('d')[1] == b.group(1).split('d')[1]:
                delta = int(b.group(1).split('d')[0]) - int(a.group(1).split('d')[0])
                if delta > 0:
                    upcast = '%dd%s' % (delta, a.group(1).split('d')[1])
        damage_type = (damage.get('damage_type') or {}).get('name')
    else:
        match = re.search(r'(\d+d\d+)\s+(\w+)\s+damage', prose)
        if match:
            dice, damage_type = match.group(1), match.group(2)
            step = re.search(r'increases by (\d+d\d+)', higher)
            if step:
                upcast = step.group(1)

    heal = record.get('heal_at_slot_level') or {}
    dc = record.get('dc') or {}
    save_ability = ABILITY_KEY.get(str((dc.get('dc_type') or {}).get('index', '')).lower())
    if not save_ability:
        match = re.search(r'(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma) saving throw', prose)
        if match:
            save_ability = ABILITY_KEY[match.group(1).lower()]

    if heal:
        out['mode'] = 'heal'
        out['dice'] = heal[min(heal, key=lambda k: int(k))]
    elif save_ability and dice:
        out['mode'] = 'save'
        out['saveAbility'] = save_ability
        out['dice'] = dice
        if dc.get('dc_success') == 'half' or 'half as much' in prose:
            out['halfOnSave'] = True
    elif dice and ('spell attack' in prose or 'ranged spell attack' in prose or record.get('attack_type')):
        out['mode'] = 'attack'
        out['dice'] = dice
    else:
        # Rule 2: a spell with no recognizable hit gets in as a spell that does nothing.
        return None, 'neither the damage nor the healing is recognized in the text'

    if damage_type:
        out['damageType'] = damage_type.lower()
    if upcast:
        out['upcastDice'] = upcast
    if record.get('concentration') in (True, 'yes') or record.get('requires_concentration'):
        out['concentration'] = True

    area = record.get('area_of_effect') or {}
    if area.get('size'):
        # 5 feet = 1 block. The shape matters: a sphere starts where it hits and a cone at the caster.
        out['aoeRadius'] = max(1, int(area['size']) // 5)
        shape_name = {'sphere': 'sphere', 'cylinder': 'sphere', 'line': 'line', 'cone': 'cone',
                      'cube': 'sphere'}.get(area.get('type'), 'sphere')
        if shape_name != 'sphere':
            out['aoeShape'] = shape_name
    return out, None


# --------------------------------------------------------------------------- monstruos

# Which Minecraft mob represents each creature type. It comes from counting what the 330 monsters
# that ship already use (see monsters.json): this invents no criterion, it copies the one already applied.
BASE_ENTITY = {'aberration': 'minecraft:guardian', 'beast': 'minecraft:wolf',
               'celestial': 'minecraft:allay', 'construct': 'minecraft:iron_golem',
               'dragon': 'minecraft:ravager', 'elemental': 'minecraft:vex',
               'fey': 'minecraft:allay', 'fiend': 'minecraft:piglin_brute',
               'giant': 'minecraft:iron_golem', 'humanoid': 'minecraft:zombie',
               'monstrosity': 'minecraft:ravager', 'ooze': 'minecraft:slime',
               'plant': 'minecraft:iron_golem', 'undead': 'minecraft:husk'}


def proficiency_for(cr):
    """Proficiency bonus by Challenge Rating: 2 up to CR 4, and +1 every four CR (DMG table)."""
    try:
        cr = float(cr)
    except (TypeError, ValueError):
        return 2
    return max(2, 2 + int((cr - 1) // 4)) if cr >= 5 else 2


def ability_scores(record):
    scores = {}
    for short, long in (('str', 'strength'), ('dex', 'dexterity'), ('con', 'constitution'),
                        ('int', 'intelligence'), ('wis', 'wisdom'), ('cha', 'charisma')):
        value = record.get(long, record.get(short))
        if value is None:
            return None
        scores[short] = int(value)
    return scores


def armor_class(record):
    ac = record.get('armor_class')
    if isinstance(ac, list):  # 5e-bits 2024: [{"type": "natural", "value": 15}]
        return int(ac[0].get('value', 10)) if ac else 10
    try:
        return int(ac)
    except (TypeError, ValueError):
        return 10


def map_monster(record, shape, namespace):
    if shape == 'nativo':
        return record, None
    name = record.get('name')
    scores = ability_scores(record)
    if not name or not scores:
        return None, 'no name or missing the six ability scores'

    creature_type = str(record.get('type') or '').lower()
    attacks = []
    for action in record.get('actions') or []:
        dice = None
        for dmg in action.get('damage') or []:
            dice = dmg.get('damage_dice')
            damage_type = ((dmg.get('damage_type') or {}).get('name') or '').lower()
            break
        else:
            damage_type = ''
        if not dice:
            match = re.search(r'(\d+d\d+)[^.]*?\b(\w+) damage', action.get('desc') or '')
            if not match:
                continue  # Multiattack, a rechargeable Breath described in prose, shrieks... it is not a hit.
            dice, damage_type = match.group(1), match.group(2)
        # Which ability it hits with: if the text says "Ranged" it is Dexterity, and if not, Strength. The same
        # as the SRD does when writing the to-hit bonus, only here it is deduced from the attack name.
        ranged = 'ranged' in (action.get('desc') or '').lower()[:60]
        ability = 'dex' if ranged else 'str'
        attacks.append({'name': action.get('name', 'Ataque'), 'toHitAbility': ability,
                        'dice': DICE.search(dice).group(1) if DICE.search(dice) else dice,
                        'damageAbility': ability, 'damageType': damage_type or 'fisico'})
    if not attacks:
        return None, 'no attack with a recognizable die'

    out = {'id': source_id(record, shape, namespace), 'name': name,
           'type': creature_type or 'humanoide',
           'baseEntity': BASE_ENTITY.get(creature_type, 'minecraft:zombie'),
           'ac': armor_class(record), 'hp': int(record.get('hit_points') or 1),
           'abilities': scores,
           'proficiencyBonus': proficiency_for(record.get('challenge_rating') or record.get('cr')),
           'attacks': attacks}

    affinities = {}
    for field, label in (('damage_resistances', 'resistant'), ('damage_immunities', 'immune'),
                         ('damage_vulnerabilities', 'vulnerable')):
        raw = record.get(field) or []
        entries = raw if isinstance(raw, list) else [x.strip() for x in raw.split(',')]
        for entry in entries:
            entry = str(entry).strip().lower()
            # "bludgeoning, piercing, and slashing from nonmagical attacks" is ANOTHER mod field
            # (nonmagicalAffinities) and not a damage type: it is left out instead of inventing a key.
            if not entry or ' ' in entry:
                continue
            affinities[entry] = label
    if affinities:
        out['damageAffinities'] = affinities
    if (record.get('legendary_actions') or []):
        out['legendaryActions'] = 3
    return out, None


MAPPERS = {'feat': map_feat, 'spell': map_spell, 'monster': map_monster}


# --------------------------------------------------------------------------- salida

def existing_ids(path):
    if not os.path.exists(path):
        return set()
    with open(path, encoding='utf-8') as f:
        return {entry['id'] for entry in json.load(f) if 'id' in entry}


def append_to_pack(path, entries):
    """Splices the new entries in as TEXT before the final `]`.

    Re-reading the pack with json.load and writing it back with json.dump would reformat the entries that
    were already there, which in this repo are hand-placed (invariant 10). This way the diff is exactly the
    new lines."""
    # One entry per line and with spaces inside the braces, which is how the packs that already ship are
    # (items.json, spells.json): this way an imported pack and a hand-written one read the same.
    lines = ['  { ' + json.dumps(e, ensure_ascii=False)[1:-1].strip() + ' }' for e in entries]
    if not os.path.exists(path):
        text = '[\n' + ',\n'.join(lines) + '\n]\n'
    else:
        with open(path, encoding='utf-8') as f:
            text = f.read()
        close = text.rindex(']')
        head = text[:close].rstrip()
        if not head.endswith('['):
            head += ','
        text = head + '\n' + ',\n'.join(lines) + '\n' + text[close:]
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--kind', required=True, choices=sorted(MAPPERS))
    parser.add_argument('--from', dest='source', required=True, help='URL o archivo JSON')
    parser.add_argument('--into', help='mod pack to add to (only adds, never overwrites)')
    parser.add_argument('--lang', help='diccionario id -> {name, description} para traducir')
    parser.add_argument('--namespace', default='dndsheets')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    records = fetch(args.source, args.limit)
    if not records:
        sys.exit('no records in ' + args.source)
    shape = shape_of(records[0])
    if shape is None:
        sys.exit('unrecognized format: the first record has no "index", "slug" or "id"')

    translations = {}
    if args.lang:
        with open(args.lang, encoding='utf-8') as f:
            translations = json.load(f)

    have = existing_ids(args.into) if args.into else set()
    mapper = MAPPERS[args.kind]
    entries, skipped = [], []
    for record in records:
        mapped, why = mapper(record, shape, args.namespace)
        if mapped is None:
            skipped.append('%s: %s' % (record.get('name', '?'), why))
            continue
        if mapped['id'] in have:
            skipped.append('%s: already in the pack' % mapped['id'])
            continue
        mapped.update(translations.get(mapped['id'], {}))
        have.add(mapped['id'])
        entries.append(mapped)

    print('formato %s, %d registros -> %d entradas, %d saltadas' %
          (shape, len(records), len(entries), len(skipped)))
    for line in skipped:
        print('  saltada  ' + line)

    if args.dry_run or not args.into:
        print(json.dumps(entries, ensure_ascii=False, indent=2))
        return
    if entries:
        append_to_pack(args.into, entries)
        print('added %d entries to %s' % (len(entries), args.into))


if __name__ == '__main__':
    main()
