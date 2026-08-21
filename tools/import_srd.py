# -*- coding: utf-8 -*-
"""Importa contenido de fuera (SRD 5.1/5.2, Open5e, o un pack de otra mesa) a los packs de este mod.

    python tools/import_srd.py --kind feat   --from <url|archivo> --into src/main/resources/dndsheets/defaults/feats.json
    python tools/import_srd.py --kind spell  --from https://api.open5e.com/v1/spells/?limit=50 --dry-run
    python tools/import_srd.py --kind monster --from src/2014/en/5e-SRD-Monsters.json --limit 20

Por que existe: los 330 monstruos y los 87 hechizos que ya vienen se importaron A MANO, una vez, y ese
trabajo no quedo escrito en ninguna parte. Eso convertia cada ampliacion en el mismo trabajo otra vez, y
dejaba a las mesas sin manera de traerse su propio contenido salvo escribiendolo entrada a entrada.

Tres formatos de entrada, detectados por como es el primer registro (nadie tiene que declarar nada):

  * **5e-bits/5e-database** (clave "index"): la transcripcion JSON del SRD, tanto la 5.1 (`src/2014/en`)
    como la 5.2 (`src/2024/en`). Es de donde salio el contenido que ya viene.
  * **Open5e** (clave "slug"): la API de la comunidad, con el contenido OGL de Kobold Press y demas.
    Se le sigue la paginacion (`next`) hasta `--limit`.
  * **Nativo** (clave "id"): un pack que YA esta en el formato de este mod. No es un caso tonto: sirve
    para fusionar el pack de otra mesa sin pisar lo tuyo, que es lo que hace `--into`.

Tres reglas que no se negocian:

  1. **`--into` solo AÑADE.** Un id que ya esta en el pack se salta y se dice. Los packs de este repo
     estan formateados a mano (invariante 10) y una entrada nueva se empalma como texto antes del `]`
     final: los bytes de lo que ya habia no se tocan.
  2. **Lo que no se sabe mapear no se inventa: se salta y se cuenta al final.** Un hechizo cuyo daño no
     aparece en la prosa entra como un hechizo que no hace nada, y eso se descubre en la mesa.
  3. **Los tipos de daño se dejan como vienen.** `DamageTypes.normalize` del mod entiende "fire" igual
     que "fuego", asi que traducirlos aqui seria una segunda tabla que se puede separar de la primera.

`--lang` toma un diccionario `{"dndsheets:alert": {"name": "...", "description": "..."}}` para que la
importacion sea reproducible: lo que se envia en el repo esta en español y se puede volver a generar.
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
    """Un archivo local o una URL. Devuelve la lista de registros, siguiendo la paginacion de Open5e."""
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
            # La paginacion solo se sigue si de verdad hace falta: pedir 3200 monstruos para quedarte con
            # 20 es maleducado con una API gratis. Y solo desde una URL: un volcado guardado en disco lleva
            # dentro su "next", y seguirlo convertia "importa este archivo" en 72 peticiones a internet.
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

# El tipo de dote del SRD 5.2 dice a que nivel se puede coger. No es cosmetico: los Dones Epicos son de
# nivel 19, y ofrecerlos en la mejora del nivel 4 llena la lista de cosas que el servidor va a rechazar.
FEAT_MIN_LEVEL = {'origin': 1, 'fighting-style': 1, 'fighting style': 1, 'general': 4, 'epic boon': 19,
                  'epic-boon': 19, 'boon': 19}

# La Mejora de Caracteristica es una dote en el SRD 5.2, pero en este mod ya ES el recurso que las dotes
# gastan (ver LevelUpManager). Importarla seria ofrecerla como alternativa a si misma.
FEAT_SKIP = {'ability-score-improvement', 'ability_score_improvement'}


def map_feat(record, shape, namespace):
    if shape == 'nativo':
        return record, None
    key = record.get('index') or record.get('slug') or ''
    if key.lower() in FEAT_SKIP:
        return None, 'la Mejora de Caracteristica ya es el recurso que gastan las dotes'
    name = record.get('name')
    description = (record.get('description') or record.get('desc') or '').strip()
    if not name or not description:
        return None, 'sin nombre o sin texto'
    out = {'id': source_id(record, shape, namespace), 'name': name,
           'description': re.sub(r'\s*\n\s*', ' ', description)}
    level = FEAT_MIN_LEVEL.get(str(record.get('type', '')).lower())
    if level is None and record.get('prerequisite'):
        level = 4  # Open5e no dice el tipo; una dote con requisito no es de origen.
    if level and level > 1:
        out['minLevel'] = level
    return out, None


# --------------------------------------------------------------------------- hechizos

# De que caracteristica lanza cada clase. El SRD no lo dice en el hechizo (depende de quien lo lance),
# asi que se toma la primera clase que puede lanzarlo, que es lo que acierta en el 95% de los casos.
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
    # Open5e trae las dos: "level": "4th-level" (para leer) y "level_int": 4. La buena es la segunda.
    level = record.get('level_int', record.get('level'))
    if name is None or level is None:
        return None, 'sin nombre o sin nivel'
    # "desc"/"higher_level" son un parrafo en Open5e y una LISTA de parrafos en 5e-bits.
    prose = flatten(record.get('desc'))
    higher = flatten(record.get('higher_level'))
    out = {'id': source_id(record, shape, namespace), 'name': name, 'level': int(level)}

    ability = 'int'
    for cls in spell_classes(record):
        if cls in CASTING_ABILITY:
            ability = CASTING_ABILITY[cls]
            break
    out['castingAbility'] = ability

    # El dado: 5e-bits lo trae estructurado por nivel de espacio; Open5e solo en la prosa.
    dice = damage_type = None
    upcast = None
    damage = record.get('damage') or {}
    at_slot = damage.get('damage_at_slot_level') or damage.get('damage_at_character_level') or {}
    if at_slot:
        first = min(at_slot, key=lambda k: int(k))
        dice = at_slot[first]
        rest = sorted((int(k) for k in at_slot), key=int)
        # Un truco NO se sube de nivel gastando un espacio: crece con el nivel del personaje, y de eso ya
        # se encarga Spell.atCasterLevel. Escribirle upcastDice seria darle las dos subidas.
        if len(rest) > 1 and int(level) > 0 and 'damage_at_slot_level' in damage:
            step = at_slot[str(rest[1])]
            # "8d6" -> "10d6" con un espacio mas significa +1d6 por nivel. Solo se declara la subida si el
            # dado es el mismo: si cambia (2d8 -> 3d10) no hay un incremento que escribir.
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
        # Regla 2: un hechizo sin golpe reconocible entra como un hechizo que no hace nada.
        return None, 'no se reconoce el daño ni la curacion en el texto'

    if damage_type:
        out['damageType'] = damage_type.lower()
    if upcast:
        out['upcastDice'] = upcast
    if record.get('concentration') in (True, 'yes') or record.get('requires_concentration'):
        out['concentration'] = True

    area = record.get('area_of_effect') or {}
    if area.get('size'):
        # 5 pies = 1 bloque. La forma importa: una esfera nace donde impacta y un cono en el lanzador.
        out['aoeRadius'] = max(1, int(area['size']) // 5)
        shape_name = {'sphere': 'sphere', 'cylinder': 'sphere', 'line': 'line', 'cone': 'cone',
                      'cube': 'sphere'}.get(area.get('type'), 'sphere')
        if shape_name != 'sphere':
            out['aoeShape'] = shape_name
    return out, None


# --------------------------------------------------------------------------- monstruos

# Con que mob de Minecraft se representa cada tipo de criatura. Sale de contar lo que ya usan los 330
# monstruos que vienen (ver monsters.json): esto no inventa un criterio, copia el que ya se aplico.
BASE_ENTITY = {'aberration': 'minecraft:guardian', 'beast': 'minecraft:wolf',
               'celestial': 'minecraft:allay', 'construct': 'minecraft:iron_golem',
               'dragon': 'minecraft:ravager', 'elemental': 'minecraft:vex',
               'fey': 'minecraft:allay', 'fiend': 'minecraft:piglin_brute',
               'giant': 'minecraft:iron_golem', 'humanoid': 'minecraft:zombie',
               'monstrosity': 'minecraft:ravager', 'ooze': 'minecraft:slime',
               'plant': 'minecraft:iron_golem', 'undead': 'minecraft:husk'}


def proficiency_for(cr):
    """Bono de competencia por Valor de Desafio: 2 hasta VD 4, y +1 cada cuatro VD (tabla del DMG)."""
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
        return None, 'sin nombre o sin las seis caracteristicas'

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
                continue  # Multiataque, Aliento recargable descrito en prosa, gritos... no es un golpe.
            dice, damage_type = match.group(1), match.group(2)
        # Con que caracteristica pega: si el texto dice "Ranged" es Destreza, y si no, Fuerza. Lo mismo
        # que hace el SRD al escribir el bono a impactar, solo que aqui se deduce del nombre del ataque.
        ranged = 'ranged' in (action.get('desc') or '').lower()[:60]
        ability = 'dex' if ranged else 'str'
        attacks.append({'name': action.get('name', 'Ataque'), 'toHitAbility': ability,
                        'dice': DICE.search(dice).group(1) if DICE.search(dice) else dice,
                        'damageAbility': ability, 'damageType': damage_type or 'fisico'})
    if not attacks:
        return None, 'ningun ataque con dado reconocible'

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
            # "bludgeoning, piercing, and slashing from nonmagical attacks" es OTRO campo del mod
            # (nonmagicalAffinities) y no un tipo de daño: se deja fuera en vez de inventar una clave.
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
    """Empalma las entradas nuevas como TEXTO antes del `]` final.

    Releer el pack con json.load y volver a escribirlo con json.dump reformatearia las entradas que ya
    estaban, que en este repo estan puestas a mano (invariante 10). Asi el diff son exactamente las
    lineas nuevas."""
    # Una entrada por linea y con espacios dentro de las llaves, que es como estan los packs que ya vienen
    # (items.json, spells.json): asi un pack importado y uno escrito a mano se leen igual.
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
    parser.add_argument('--into', help='pack del mod al que añadir (solo añade, nunca pisa)')
    parser.add_argument('--lang', help='diccionario id -> {name, description} para traducir')
    parser.add_argument('--namespace', default='dndsheets')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--dry-run', action='store_true')
    args = parser.parse_args()

    records = fetch(args.source, args.limit)
    if not records:
        sys.exit('no hay registros en ' + args.source)
    shape = shape_of(records[0])
    if shape is None:
        sys.exit('no reconozco el formato: el primer registro no tiene "index", "slug" ni "id"')

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
            skipped.append('%s: ya esta en el pack' % mapped['id'])
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
        print('añadidas %d entradas a %s' % (len(entries), args.into))


if __name__ == '__main__':
    main()
