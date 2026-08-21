# Atribución de contenido de terceros

## SRD 5.1 — System Reference Document

Parte del contenido incluido en este mod (hechizos, monstruos, y las estadísticas asociadas)
deriva del **System Reference Document 5.1 ("SRD 5.1")** de Wizards of the Coast LLC, publicado
bajo la licencia **Creative Commons Attribution 4.0 International (CC-BY-4.0)**.

> This work includes material taken from the System Reference Document 5.1 ("SRD 5.1") by
> Wizards of the Coast LLC and available at
> https://dnd.wizards.com/resources/systems-reference-document.
> The SRD 5.1 is licensed under the Creative Commons Attribution 4.0 International License,
> available at https://creativecommons.org/licenses/by/4.0/legalcode.

Los datos se importaron a partir de la transcripción JSON del SRD 5.1 mantenida por el proyecto
[5e-bits/5e-database](https://github.com/5e-bits/5e-database), también bajo CC-BY-4.0. Los
nombres se tradujeron al español y las estadísticas se adaptaron a los esquemas de contenido de
este mod (ver `PROJECT_CONTEXT.md`); las adaptaciones y traducciones son obra de este proyecto.

**Qué NO está incluido, y no va a estarlo:** contenido de manuales cerrados de D&D (Xanathar's,
Tasha's, Volo's, monstruos y subclases fuera del SRD). Este mod se distribuye públicamente y
solo puede llevar material redistribuible.

## SRD 5.2 — System Reference Document 5.2

Las **dotes** que se envían (`feats.json`) derivan del **System Reference Document 5.2 ("SRD 5.2")**,
la publicación de 2024 de Wizards of the Coast LLC, también bajo **CC-BY-4.0**.

> This work includes material from the System Reference Document 5.2 ("SRD 5.2") by Wizards of the
> Coast LLC and available at https://www.dndbeyond.com/srd. The SRD 5.2 is licensed under the
> Creative Commons Attribution 4.0 International License, available at
> https://creativecommons.org/licenses/by/4.0/legalcode.

Por qué había que ir a buscarlo: el SRD 5.1 traía **una sola dote** (Luchador), así que durante todo
el desarrollo la elección "mejora de característica o dote" tenía una única alternativa. El SRD 5.2
publicó la lista entera, y de ahí salen las 15 restantes — las de origen, los cuatro estilos de
combate y los siete Dones Épicos.

## Cómo se reproduce la importación

No es prosa: es un comando. Los packs se pueden volver a generar con
[`tools/import_srd.py`](tools/import_srd.py), que lee la transcripción JSON del SRD (5.1 o 5.2)
y la traduce a los esquemas de contenido de este mod.

```
python tools/import_srd.py --kind feat   --from https://raw.githubusercontent.com/5e-bits/5e-database/main/src/2024/en/5e-SRD-Feats.json   --lang tools/lang/srd52_feats_es.json   --into src/main/resources/dndsheets/defaults/feats.json
```

Las traducciones al español viven en `tools/lang/` precisamente para que ese comando dé el archivo
que se envía y no una versión en inglés: la adaptación queda como dato y no como trabajo perdido.
El mismo importador acepta [Open5e](https://open5e.com/) para el contenido **OGL** de la comunidad —
ese material NO se envía con el mod, lo trae cada mesa a su mundo bajo la licencia que le corresponda.

## Archivos afectados

- `src/main/resources/dndsheets/defaults/spells.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/monsters.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/items.json` (SRD 5.1)
- `src/main/resources/dndsheets/defaults/feats.json` (SRD 5.2)
- `tools/lang/srd52_feats_es.json` (traducción de este proyecto del texto del SRD 5.2)
- `test/dndsheets/*/ejemplo.json`

Cualquier ampliación futura de estos packs desde el SRD queda cubierta por esta misma atribución.
