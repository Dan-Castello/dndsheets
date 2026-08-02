# Reskins: armas personalizadas y monstruos

Esto es un **resource pack aparte** (no un mod, no va en `dndsheets/`) — se instala como cualquier
resource pack de Minecraft: `.minecraft/resourcepacks/` (cliente) o `world/resourcepacks/`
(servidor con recursos forzados), y se activa desde Opciones → Paquetes de Recursos. Formato
1.20.1 (`pack_format: 15`).

No incluye ninguna textura real (`.png`) — los `.json` de acá son plantillas funcionales, la
imagen la tenés que dibujar/conseguir vos y ponerla en la ruta que se indica.

## Armas: `customModelData`

Sirve para que un arma personalizada (id `dndsheets:...`) no comparta la textura de su ítem base
de Minecraft — p. ej. que una "Daga" no se vea como una espada de hierro sin más.

1. En tu `weapons.json` real (no en esta plantilla), poné un `"customModelData": N` único en el
   arma que querés reskinear — ver `templates/weapons.json`, entrada `dndsheets:enchanted_rapier`
   (usa `100001` sobre el ítem base `minecraft:iron_sword`).
2. Este resource pack ya trae el patrón completo para ESE ejemplo exacto:
   - `assets/minecraft/models/item/iron_sword.json` — parchea el modelo del ítem base vanilla
     (`minecraft:iron_sword`) para que, cuando el `CustomModelData` de la etiqueta NBT sea
     `100001`, use OTRO modelo en vez del de espada de hierro normal.
   - `assets/dndsheets/models/item/enchanted_rapier.json` — el modelo "propio" al que apunta ese
     override, con su propia textura.
3. Falta un solo archivo real: la textura en sí, en
   `assets/dndsheets/textures/item/enchanted_rapier.png` (16×16, formato item estándar de
   Minecraft). Sin ella, el ítem se ve invisible/roto — este pack no trae ninguna imagen.

### Para tu propia arma
Copiá el patrón: un override nuevo dentro de `overrides` en el modelo del MISMO ítem base
(`assets/minecraft/models/item/<baseItem>.json` — si tenés varias armas custom sobre el mismo
ítem base, van TODAS en el mismo array `overrides`, una por `customModelData`), un modelo nuevo en
`assets/dndsheets/models/item/<tu_arma>.json`, y su textura en
`assets/dndsheets/textures/item/<tu_arma>.png`.

## Monstruos

**No hace falta `customModelData` ni ningún campo nuevo** — `MonsterStatBlock.baseEntity` ya es un
tipo de entidad vanilla real (zombie, esqueleto, araña...), así que reskinear ese tipo de entidad
ENTERO es un resource pack de toda la vida, sin tocar el mod:

- Reemplazá `assets/minecraft/textures/entity/zombie/zombie.png` (o el mob base que uses) por tu
  propia textura, y CUALQUIER monstruo `dndsheets` que use `"baseEntity": "minecraft:zombie"` se
  ve con esa textura — incluidos los zombies vanilla normales del mundo, ya que es la MISMA
  entidad, sin distinción por instancia.

**Límite real**: si querés que DOS monstruos que comparten el mismo `baseEntity` (p. ej. un goblin
y un hobgoblin, ambos zombies) se vean DISTINTOS entre sí, un resource pack vanilla no alcanza —
necesitarías un renderer de entidad a medida (código, no JSON), o dar a cada uno un `baseEntity`
vanilla distinto para poder reskinearlos por separado (p. ej. goblin = zombie, hobgoblin =
husk/zombie villager).
