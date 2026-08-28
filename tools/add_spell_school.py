#!/usr/bin/env python3
"""Inserta "school" en un pack de hechizos, respetando el formato a mano.

Por que un script y no editar el JSON con json.load/json.dump: la invariante 10 de PROJECT_CONTEXT.md
dice que el contenido esta formateado a mano, compacto, una linea por hechizo. Un round-trip por el
modulo json reflowea el archivo entero y deja un diff ilegible de 87 lineas donde solo cambia un campo.
Esto es una insercion de texto por linea: el resto del archivo sale byte a byte igual.

La tabla es del SRD 5.1 (CC-BY-4.0, ver PROJECT_CONTEXT.md > Atribucion). Se escribe aqui a mano en vez
de descargarse: el build no toca la red, y un pack de contenido no puede depender de que un repo ajeno
siga en pie.

Idempotente: una linea que ya trae "school" se deja como esta.

    python tools/add_spell_school.py src/main/resources/dndsheets/defaults/spells.json
"""
import io
import re
import sys

# Sin ninguna de adivinacion: el pack solo trae conjuros que el motor puede resolver, y las de
# adivinacion del SRD (Detectar Magia, Identificar) son utilidad pura. MagicSchool.DIVINATION existe
# igual, para los packs que escriba un DM.
SCHOOLS = {
    "abjuracion": ["banishment"],
    "conjuracion": [
        "spirit_guardians", "poison_spray", "produce_flame", "call_lightning", "acid_splash",
        "entangle", "black_tentacles", "cloudkill", "insect_plague", "incendiary_cloud",
        "wall_of_thorns", "flaming_sphere", "faithful_hound",
    ],
    "encantamiento": [
        "vicious_mockery", "charm_person", "hideous_laughter", "sleep", "hold_person",
        "dominate_beast", "dominate_person", "hold_monster", "dominate_monster", "feeblemind",
    ],
    "ilusion": ["hypnotic_pattern", "phantasmal_killer", "weird", "fear"],
    "nigromancia": [
        "toll_the_dead", "chill_touch", "inflict_wounds", "blindness_deafness", "vampiric_touch",
        "blight", "circle_of_death", "eyebite", "harm", "finger_of_death", "false_life",
    ],
    "transmutacion": ["heat_metal", "disintegrate", "flesh_to_stone", "regenerate"],
}
# Evocacion es el resto: es la escuela mayoritaria del pack (los conjuros de dano directo y los de
# curacion), asi que listarla entera seria repetir cincuenta ids para nada.
DEFAULT_SCHOOL = "evocacion"

BY_ID = {spell_id: school for school, ids in SCHOOLS.items() for spell_id in ids}

LEVEL = re.compile(r'("level":\s*\d+,)')
SPELL_ID = re.compile(r'"id":\s*"[^":]*:?([a-z0-9_]+)"')


def patch(path):
    lines = io.open(path, encoding="utf-8").read().split("\n")
    out, touched = [], 0
    for line in lines:
        found = SPELL_ID.search(line)
        if not found or '"school"' in line or not LEVEL.search(line):
            out.append(line)
            continue
        school = BY_ID.get(found.group(1), DEFAULT_SCHOOL)
        out.append(LEVEL.sub(r'\1 "school": "%s",' % school, line, count=1))
        touched += 1
    io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(out))
    print("%s: %d hechizos" % (path, touched))


if __name__ == "__main__":
    for target in sys.argv[1:] or ["src/main/resources/dndsheets/defaults/spells.json"]:
        patch(target)
