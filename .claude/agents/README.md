# Subagentes: Modding Minecraft Forge / NeoForge / Java

Set de 3 subagentes para Claude Code, pensados para trabajar en secuencia sobre un mod
de Forge/NeoForge (o cualquier proyecto Java con Gradle que use un stack similar).

## Instalación

- **A nivel de proyecto** (recomendado, queda versionado con el mod):
  copia los `.md` dentro de `<repo-del-mod>/.claude/agents/`
- **A nivel global** (disponible en cualquier proyecto):
  copia los `.md` dentro de `~/.claude/agents/`

Claude Code los detecta automáticamente por su `description` y puede delegarles tareas
solo, o puedes invocarlos explícitamente, p. ej.:

> "Usa el agente `mc-forge-env-setup` para revisar por qué no compila el proyecto"
> "Pide al agente `mc-forge-auditor` una auditoría completa de `src/main/java`"
> "Que el agente `mc-forge-refactorer` aplique los hallazgos críticos del informe"

## Los 3 agentes

| Agente | Rol | Permisos |
|---|---|---|
| `mc-forge-env-setup` | Diagnostica y repara el entorno (JDK, Gradle, mappings, run configs, dependencias) | Lectura + escritura de config/build, sin tocar lógica de negocio |
| `mc-forge-auditor` | Auditoría profunda **de solo lectura**: arquitectura, bugs, rendimiento, seguridad, compatibilidad | Solo lectura (Read/Grep/Glob/Bash de inspección) — nunca edita código |
| `mc-forge-refactorer` | Refactoriza con libertad total apoyándose en el informe de auditoría | Lectura + escritura completa (Edit/Write/MultiEdit) |

## Flujo recomendado

1. **`mc-forge-env-setup`** — deja el proyecto compilando y ejecutable antes de tocar nada más.
2. **`mc-forge-auditor`** — genera un informe estructurado de hallazgos, priorizado por severidad, sin modificar una sola línea.
3. **`mc-forge-refactorer`** — toma ese informe (o instrucciones puntuales) y refactoriza con libertad arquitectónica, preservando los contratos externos del mod (IDs de registro, protocolo de red, claves de traducción, formato de guardado/NBT).

Separar auditoría de refactorización es intencional: el auditor nunca "arregla mientras mira",
así el informe queda limpio y el refactorer trabaja con permisos de escritura explícitos y contexto completo.
