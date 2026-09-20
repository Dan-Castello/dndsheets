#!/usr/bin/env python3
"""Inserts "school" into a spell pack, respecting the hand-written format.

Why a script and not editing the JSON with json.load/json.dump: invariant 10 of PROJECT_CONTEXT.md
says the content is hand-formatted, compact, one line per spell. A round-trip through the
json module reflows the whole file and leaves an unreadable 87-line diff where only one field changes.
This is a per-line text insertion: the rest of the file comes out byte for byte the same.

The table is from SRD 5.1 (CC-BY-4.0, see PROJECT_CONTEXT.md > Attribution). It is written here by hand instead
of being downloaded: the build doesn't touch the network, and a content pack can't depend on someone else's repo
staying up.

Idempotent: a line that already has "school" is left as it is.

    python tools/add_spell_school.py src/main/resources/dndsheets/defaults/spells.json
"""
import io
import re
import sys

# With no divination at all: the pack only ships spells the engine can resolve, and the SRD's
# divination ones (Detect Magic, Identify) are pure utility. MagicSchool.DIVINATION exists
# anyway, for packs a DM writes.
SCHOOLS = {
    "abjuration": ["banishment"],
    "conjuration": [
        "spirit_guardians", "poison_spray", "produce_flame", "call_lightning", "acid_splash",
        "entangle", "black_tentacles", "cloudkill", "insect_plague", "incendiary_cloud",
        "wall_of_thorns", "flaming_sphere", "faithful_hound",
    ],
    "enchantment": [
        "vicious_mockery", "charm_person", "hideous_laughter", "sleep", "hold_person",
        "dominate_beast", "dominate_person", "hold_monster", "dominate_monster", "feeblemind",
    ],
    "illusion": ["hypnotic_pattern", "phantasmal_killer", "weird", "fear"],
    "necromancy": [
        "toll_the_dead", "chill_touch", "inflict_wounds", "blindness_deafness", "vampiric_touch",
        "blight", "circle_of_death", "eyebite", "harm", "finger_of_death", "false_life",
    ],
    "transmutation": ["heat_metal", "disintegrate", "flesh_to_stone", "regenerate"],
}
# Evocation is the rest: it is the pack's majority school (the direct-damage spells and the healing
# ones), so listing it in full would be repeating fifty ids for nothing.
DEFAULT_SCHOOL = "evocation"

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
