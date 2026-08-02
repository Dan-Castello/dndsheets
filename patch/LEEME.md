# Parche: D&D Sheets con vida/hambre/nivel reales

## Qué hace

Modifica `D&D Sheets` (Inkshriek/dndsheets, MIT license, base para el mod "DnD Sheets" de Hawthorn en CurseForge) para que estos campos dejen de ser texto editable a mano y pasen a **reflejar el estado real del jugador**, actualizándose solos mientras la hoja está abierta:

| Campo en la hoja | Fuente real en Minecraft |
|---|---|
| PG actual | `entity.getHealth()` |
| PG máximo | `entity.getMaxHealth()` |
| PG temporal | `entity.getAbsorptionAmount()` (los corazones dorados de Minecraft ya son mecánicamente equivalentes a los PG temporales de D&D — no hacía falta inventar nada) |
| Nivel | `entity.experienceLevel` (el nivel de XP real, era un campo que el propio autor había dejado a medio conectar) |
| Bono de Competencia | calculado automáticamente a partir del nivel, con la fórmula estándar de 5e (2 + (nivel-1)/4) |
| Hambre *(campo nuevo)* | `entity.getFoodData().getFoodLevel()` |

También añadí traducción completa al español (`es_es.json`), ya que no existía.

## Qué NO hace (todavía)

- **Clase, raza, trasfondo, CA, velocidad, dados de golpe, atributos**: siguen siendo manuales, porque no tienen equivalente real en Minecraft vanilla (tu decisión fue no tocar la jugabilidad, así que no hay "clase" ni "raza" nativas que leer).
- **Posiciones exactas de los widgets nuevos** (`nivel`, `hambre`): puse coordenadas de partida cerca de Competencia y Velocidad respectivamente, pero la textura de fondo (`character_sheet.png`) no tiene un hueco dibujado para ellos. Vas a necesitar:
  1. Abrir esa textura en un editor de imágenes.
  2. Dibujar un recuadro/etiqueta donde quieras que aparezcan.
  3. Ajustar las coordenadas `X`/`Y` en el código para que calcen (están marcadas con un comentario `NOTA:` en el archivo).

## Cómo aplicarlo

1. Clona el repo original:
   ```
   git clone https://github.com/Inkshriek/dndsheets.git
   cd dndsheets
   ```
2. Aplica el parche:
   ```
   git apply sync-vida-hambre-nivel.patch
   ```
   (o simplemente reemplaza `CharacterSheetScreen.java` y los dos `.json` de idioma por los que te entregué, están en las mismas rutas relativas dentro de `src/main/`).
3. Compila con Gradle (necesitas JDK 17 y conexión a los repositorios de Forge, cosa que yo no tengo en este entorno):
   ```
   ./gradlew build
   ```
   El `.jar` resultante queda en `build/libs/`.
4. Cópialo a la carpeta `mods` de tu servidor y clientes, junto a Curios, Tinkers, etc.

## Por qué no lo compilé yo mismo

Mi entorno no tiene acceso a los repositorios de Maven de Forge (solo a un set limitado de dominios), así que no puedo bajar las dependencias del MDK ni ejecutar Gradle aquí. Sí verifiqué que las llaves/paréntesis del archivo están balanceados y que el JSON de idioma es válido, pero **te recomiendo compilarlo y probarlo tú antes de llevarlo a la mesa con tu grupo**, por si algún nombre de método cambia entre builds de Forge 1.20.1 (usé la API estándar de `LivingEntity`/`Player`, que no debería haber cambiado, pero nunca está de más confirmar).

## Siguiente paso natural

Con esto, D&D Sheets deja de sentirse como "una ventana aparte" — es un espejo del jugador real. Lo que sigue para que el entorno se sienta más unificado todavía:
- Un resource pack que retexturice `character_sheet.png` para que combine visualmente con Curios/Sophisticated Backpacks (misma paleta, misma tipografía).
- Decidir si quieres que "Clase" también se autocompletar cuando detecte ciertos ítems equipados (ej. si lleva un arco → sugiere "Explorador"), aunque eso ya sería una decisión de diseño, no solo técnica.
