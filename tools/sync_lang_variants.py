# -*- coding: utf-8 -*-
"""Copies es_es.json to the other Spanish variants Minecraft offers.

Minecraft has NO regional fallback chain: it loads en_us and on top of it the EXACT language that is
chosen. A player with "Español (México)" gets es_mx from vanilla and, from the mod, en_us — the game shows
in Spanish and the mod in English, which is exactly the symptom this script exists for. There is no way to
declare "es_* uses es_es": the only way out is for the file to exist under each name.

Run it after touching es_es.json. JsonContentSelfTest.checkLanguageFiles fails if you didn't.
"""
import io, os, re, shutil

LANG = os.path.join("src", "main", "resources", "assets", "dndsheets", "lang")
# The list does NOT live here: it lives in JsonContentSelfTest.SPANISH_VARIANTS, which is what fails the build if
# a copy is missing. Two lists you have to remember to touch at the same time are exactly the failure this
# script exists to not repeat.
TEST = os.path.join("src", "test", "java", "net", "hawthorn", "dndsheets", "JsonContentSelfTest.java")
DECL = re.search(r"SPANISH_VARIANTS = \{([^}]*)\}", io.open(TEST, encoding="utf-8").read())
if DECL is None:
    raise SystemExit("could not find SPANISH_VARIANTS in " + TEST)
VARIANTS = re.findall(r'"([a-z_]+)"', DECL.group(1))

source = os.path.join(LANG, "es_es.json")
for name in VARIANTS:
    shutil.copyfile(source, os.path.join(LANG, name + ".json"))
print("es_es.json copied to:", ", ".join(VARIANTS))
