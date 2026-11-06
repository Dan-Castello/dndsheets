# Contract: `OnboardingMessage` (red)

Registrado **al final** de `DndsheetsMod.registerNetworkMessages` (invariante 1). Hay que actualizar `NETWORK_SHAPE`, `NETWORK_ORDER` y `NETWORK_WIRE` en `JsonContentSelfTest` y subir la versión del protocolo.

## Formato

```text
writeEnum(action)       // Action: el orden de los valores no cambia nunca; los nuevos van al final (invariante 2)
writeUtf(payload, 8192) // vacío si la acción no lleva carga
writeBoolean(isDm)      // solo lo lee SYNC; en el resto viaja false
```

## Acciones

| Action | Dirección | Payload | El receptor hace | Validación |
|---|---|---|---|---|
| `SYNC` | S→C | JSON `{"welcome":bool,"done":[ids]}` | Sustituye el estado de cliente `OnboardingClient` y refresca la ruta y el menú si están abiertos | JSON inválido → se ignora, el estado anterior sigue |
| `MARK` | C→S | id del hito | Marca el hito si existe y está en la lista de hitos marcables desde el cliente (`sheet_opened`, `menu_opened`, `dm_panel`, `dungeon_import`, `seen_*`; `dungeon_import` porque `DungeonMapImporter.read` solo corre en el cliente) → `SYNC`. Responde con `SYNC` **aunque el hito ya estuviera hecho**: `menu_opened` lo necesita para refrescar `isDm` | Id desconocido o no marcable desde el cliente → se descarta con `LOGGER.debug`; nunca falla |
| `DISMISS_WELCOME` | C→S | — | `welcome=1` → `SYNC` | Idempotente |
| `OPEN_WELCOME` | C→S | — | Responde abriendo la bienvenida (vía `ScreenActionMessage.ONBOARDING_OPEN`) | Permitido siempre |
| `RESET` | C→S | — | Vacía el estado del remitente → `SYNC` | Solo afecta al remitente |

## Garantías

- El cliente **no puede** marcar hitos de acción real (`dice`, `first_attack`, `monster_spawned`, …): solo los marca el servidor donde ocurre la acción. La única excepción es `dungeon_import`: lee un archivo local y no hace nada en el mundo, así que falsearlo no da ventaja. Así la ruta no se puede "falsear" y FR-004 es fiable.
- `isDm` sale de `DndsheetsMod.canActAsDm(player)` en el momento del `SYNC`; se reenvía al entrar y cuando cambia el permiso de operador. Solo decide qué se ve; no da ningún permiso.
- Toda escritura del estado sale de `Onboarding.mark/dismiss/reset(ServerPlayer, …)`, que modifica el NBT y envía el `SYNC`. No hay otra vía de escritura.
