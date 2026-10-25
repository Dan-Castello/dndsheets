# DESIGN.md — dndsheets GUI

Authoritative visual and UX reference for every screen in `client/gui` (and the dungeon addon's screens). It documents what already exists; the code is the source of truth, so when they disagree, fix this file or the code, not neither. Look: **a leather-bound tome with aged-brass fittings, drawn with Minecraft's own bevel grammar**.

## 1. Theme
- Dark leather panel (tiled `textures/screens/panel_leather.png`, ~95 % opaque, so the world shows faintly behind it), parchment text, brass accents. Never pure white text: it vibrates on dark leather.
- Panels use Minecraft's bevel (light top/left, dark bottom/right, **2 px** — a 1 px bevel vanishes at GUI scale 2). The D&D feel comes from colour and brass corner pieces, not from breaking that grammar.
- Everything shared lives in `GuiStyle`. Changing it there changes every screen.

## 2. Colour roles (`GuiStyle.java`)
| Role | Constant | Value | Use |
|---|---|---|---|
| Title / primary text | `TITLE_COLOR` | `0xFFE9D8B4` | titles, main labels |
| Secondary text | `SUBTITLE_COLOR` | `0xFFB9A88C` | subtitles, descriptions |
| Muted | `MUTED_COLOR` | `0xFF8C8071` | hints, empty states, disabled text |
| Good | `GOOD_COLOR` | `0xFF7FD68F` | success, healthy HP |
| Bad | `BAD_COLOR` | `0xFFE35A4B` | failure, critical HP |
| Warn | `WARN_COLOR` | `0xFFE0B63F` | caution, middling HP |
| Accent (brass) | `ACCENT_COLOR` | `0xFFC9A227` | active values, markers — "stand out without shouting" |

Private to `GuiStyle` (don't copy them): bevel light `0xFF6B5636`, bevel dark `0xFF0B0906`, edge `0xFF2E2418`, studs `0xFFC9A227`.

**Rule: no hex colours in screens.** Take roles from `GuiStyle`, including the state tokens above. Remaining debt: the HUD overlays (`TurnHudOverlay`, `ResourceHudOverlay`), `SheetAdjustScreen`, `TomeField` and `IconButton` still carry their own literals (the HUDs use a separate palette on purpose; don't merge it without checking in-game).

Reserve colour for meaning: brass = active/important, red/green = state (HP, success/failure), muted = secondary. Don't decorate with colour.

## 3. Typography
- Titles: Cinzel via `GuiStyle.title(Component)`; fall back to the default font for glyphs it lacks.
- Body: the vanilla font. Mixed-case, sentence style, specific verbs ("Add to staff", not "OK").
- Long text must degrade, never overflow silently: `TomeButton` truncates with "…" at rest and scrolls on hover. `TomeButton` also shows the full text as a tooltip when it is truncated. Anything else that can overflow (labels, titles) needs truncation **and** a tooltip with the full text.
- Every visible string has a lang key in all 8 files (`en_us` + 7 `es_*`); no literals in `Component.literal` except data (ids, names from content).

## 4. Components
| Need | Use | Notes |
|---|---|---|
| Button | `TomeButton.of(...)` | the standard row; has hover/disabled states |
| Icon-only button | `IconButton` | **must** have a tooltip |
| Text field | `TomeField` | |
| Cycling option | `DirectionalCycleButton` | left click forward, **right click back**; never hand-write |
| Section divider in a list | `addHeader(...)` / `SectionHeader` | |
| Scrolling list | `ButtonListWidget` via `ListPickerScreen` | |

States every interactive control must show distinctly: **rest, hover, selected/active, disabled** (and keyboard focus where applicable). Hover/selected must read stronger than rest; disabled must still be legible.

## 5. Layout
- Pick a base (invariant 7, `PROJECT_CONTEXT.md`): `ListPickerScreen` (lists/menus), `SmallFormScreen` (short form + Confirm/Cancel/Delete), `FormPanelScreen` (custom panel with `addFieldRow`), `ModalDialogScreen` (fixed dialog). Never extend bare `Screen`. The one justified exception is `ItemPickerScreen` (an icon grid, which no base models); if a second grid screen appears, extract a grid base instead of copying it.
- Constants: `FormPanelScreen.FIELD_WIDTH = 160`, `ROW_HEIGHT = 30`, panel padding 14; `ListPickerScreen.BUTTON_HEIGHT = 20`, `SPACING = 4`, search appears automatically from 14 rows (`AUTO_SEARCH_MIN_ROWS`).
- Never centre in `height/2` without a floor: use `Math.max(44, …)` or the title scrolls off screen.
- Never size a button by subtraction (`WIDE_WIDTH - …` once produced 2 px). A button that doesn't fit beside fields gets its own row.
- A full screen should push extra actions behind a button that opens its own screen (`SheetAdjustScreen` → "Conditions…").
- One accent focus per screen; group related controls; keep margins on the 2/4/14 px rhythm above.

## 6. Depth
- Panel = leather + top-lit gradient + 2 px bevel + brass corners (`GuiStyle.panel`).
- `GuiStyle.ruleOrnate` once under a screen's **title**; `GuiStyle.rule` for internal separators (an ornament repeated per section stops being an ornament).
- No soft shadows (they look dirty at pixel scale); separate with the 1 px outer edge.

## 7. Feedback
- Every DM action on another player confirms in chat; a silent action reads as a broken one.
- Destructive actions (delete, reset) go through `ConfirmScreen`/`ModalDialogScreen` or are undoable.
- Errors say what to do next ("The staff already holds 9 spells" → implies remove one), not just that it failed.
- Transient state (active spell/monster) uses the action bar, not chat spam.
- Empty list → `emptyMessage()` with a reason and, where possible, the next step (`ListPickerScreen` falls back to a generic "Nothing here yet."). A search with no matches shows "No results for …" automatically.

## 8. Keyboard
- Esc always goes back (to `parent`) or closes. Enter confirms a `SmallFormScreen`, and in a `ListPickerScreen` search box it presses the first matching row. Arrow-key navigation of lists is still missing.
- Anything done with the mouse needs a keyboard route where reasonable (keybinds H/P/G/R/X/Z exist; Shift+scroll cycles staff/card).
- Don't steal vanilla keys (E, Q, F, T…).

## 9. Do / Don't
**Do**: reuse a base; copy the nearest existing screen; use `GuiStyle` roles; add a tooltip to icon buttons and truncated text; provide empty states; translate in all 8 lang files; verify in `runClient` at GUI scale 2 **and** 3 and with a long Spanish string.
**Don't**: hardcode colours or bevels; write hand layouts; use absolute white; rely on hover to reveal essential info; rebuild widgets on every click if the screen has a search box (it clears it); add a per-row companion button to a list.

## Review loop
Build → open in `runClient` → screenshot (F2) → fix the three worst problems → repeat (max three rounds). The self-test can't see pixels, so this is the check. Use `/gui-review <path>` for the static checklist.
