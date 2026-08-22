# -*- coding: utf-8 -*-
"""Copia es_es.json a las demas variantes de espanol que ofrece Minecraft.

Minecraft NO tiene cadena de respaldo por region: carga en_us y encima el idioma EXACTO que este
elegido. Un jugador con "Espanol (Mexico)" recibe es_mx de vanilla y, del mod, en_us — el juego se ve
en espanol y el mod en ingles, que es justo el sintoma por el que existe este script. No hay forma de
declarar "es_* usa es_es": la unica salida es que el archivo este con cada nombre.

Correlo despues de tocar es_es.json. JsonContentSelfTest.checkLanguageFiles falla si no lo hiciste.
"""
import io, os, re, shutil

LANG = os.path.join("src", "main", "resources", "assets", "dndsheets", "lang")
# La lista NO vive aqui: vive en JsonContentSelfTest.SPANISH_VARIANTS, que es quien falla el build si
# falta una copia. Dos listas que hay que acordarse de tocar a la vez son exactamente el fallo que este
# script existe para no repetir.
TEST = os.path.join("src", "test", "java", "net", "hawthorn", "dndsheets", "JsonContentSelfTest.java")
DECL = re.search(r"SPANISH_VARIANTS = \{([^}]*)\}", io.open(TEST, encoding="utf-8").read())
if DECL is None:
    raise SystemExit("no encuentro SPANISH_VARIANTS en " + TEST)
VARIANTS = re.findall(r'"([a-z_]+)"', DECL.group(1))

source = os.path.join(LANG, "es_es.json")
for name in VARIANTS:
    shutil.copyfile(source, os.path.join(LANG, name + ".json"))
print("es_es.json copiado a:", ", ".join(VARIANTS))
