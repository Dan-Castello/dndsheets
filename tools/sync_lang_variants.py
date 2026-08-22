# -*- coding: utf-8 -*-
"""Copia es_es.json a las demas variantes de espanol que ofrece Minecraft.

Minecraft NO tiene cadena de respaldo por region: carga en_us y encima el idioma EXACTO que este
elegido. Un jugador con "Espanol (Mexico)" recibe es_mx de vanilla y, del mod, en_us — el juego se ve
en espanol y el mod en ingles, que es justo el sintoma por el que existe este script. No hay forma de
declarar "es_* usa es_es": la unica salida es que el archivo este con cada nombre.

Correlo despues de tocar es_es.json. JsonContentSelfTest.checkLanguageFiles falla si no lo hiciste.
"""
import io, os, shutil

LANG = os.path.join("src", "main", "resources", "assets", "dndsheets", "lang")
# Las 6 restantes de las 7 que trae 1.20.1. "esan" queda fuera a proposito: es asturiano, otro idioma.
VARIANTS = ["es_ar", "es_cl", "es_ec", "es_mx", "es_uy", "es_ve"]

source = os.path.join(LANG, "es_es.json")
for name in VARIANTS:
    shutil.copyfile(source, os.path.join(LANG, name + ".json"))
print("es_es.json copiado a:", ", ".join(VARIANTS))
