package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * <p>Character rules that don't depend on Minecraft: whose each sheet is, which one its owner has
 * equipped, and the max HP that comes out of class/level/Constitution. Kept separate from
 * {@link SheetLoader} because the latter resolves {@code FMLPaths.GAMEDIR} on initialization and
 * therefore can't even be loaded outside a running Forge instance — and these are exactly the
 * branch-heavy rules worth being able to pin down in {@code JsonContentSelfTest}.</p>
 *
 * <p>Everything here is pure functions over the sheet map: there's no own state that could fall out of
 * sync with {@code SheetLoader}'s.</p>
 */
final class CharacterRules {

	private CharacterRules() {}

	/**
	 * <p>Max HP by class, level and Constitution, with the SRD's average rule: full hit die at level 1,
	 * and half the die + 1 (plus the Constitution modifier) per subsequent level, with a minimum of 1 HP
	 * per level even with dismal Constitution.</p>
	 */
	static int maxHitPointsFor(JsonObject sheet, int level) {
		int con = intField(sheet, "constitution", 10);
		//With a level split, the split rules: each class contributes its own hit die, and the class text
		//("Fighter 3 / Wizard 2") is no longer usable to derive a single one — Config.hitDieFor would
		//grab whichever it found first inside the phrase.
		if (ClassLevels.isMulticlass(sheet)) return ClassLevels.maxHitPoints(ClassLevels.of(sheet), con);
		int hitDie = Config.hitDieFor(sheet != null && sheet.has("characterClass") ? sheet.get("characterClass").getAsString() : "");
		int conMod = Math.floorDiv(con - 10, 2);

		int maxHp = hitDie + conMod;
		for (int lvl = 2; lvl <= Math.max(1, level); lvl++) {
			maxHp += Math.max(1, (hitDie / 2 + 1) + conMod);
		}
		return Math.max(1, maxHp);
	}

	/**
	 * <p>Character level for a sheet with no player behind it. The {@code Player} version falls back to
	 * real Minecraft XP when the DM didn't set a level; an NPC sheet has no XP to fall back on, so it
	 * starts at 1 — in 5e no character is level 0.</p>
	 */
	/**
	 * <p>Proficiency bonus by level: +2 from 1 to 4, +3 from 5 to 8, +4 from 9 to 12, +5 from 13 to 16 and
	 * +6 from 17 to 20. This is the 5e table, and it's not a minor detail — it enters every attack roll,
	 * every save DC and every proficient check.</p>
	 *
	 * <p>Previously nobody computed it: the sheet started with a fixed "2" and stayed there, so a level 20
	 * character attacked with the bonus of a level 1 one. The sheet also already rendered the field in
	 * amber and non-editable, i.e. marked as "fills itself in" — it promised a calculation that didn't
	 * exist.</p>
	 */
	static int proficiencyBonusFor(int level) {
		return 2 + (Math.min(20, Math.max(1, level)) - 1) / 4;
	}

	/**
	 * <p>Barbarian Rage damage bonus: +2 up to level 8, +3 from 9 to 15 and +4 from 16 onward.</p>
	 *
	 * <p>It used to be fixed at +2 and marked as a simplification. A barbarian is the class with the fewest
	 * buttons: its progression <em>is</em> this number, so freezing it left a level 20 barbarian hitting
	 * exactly like a level 1 one except for the weapon's dice.</p>
	 */
	static int rageDamageBonusFor(int level) {
		if (level >= 16) return 4;
		if (level >= 9) return 3;
		return 2;
	}

	/**
	 * <p>Bardic Inspiration die: d6, upgrading to d8/d10/d12 at levels 5, 10 and 15.</p>
	 *
	 * <p>This was also fixed, with the same consequence: the resource that defines the class never
	 * improved.</p>
	 */
	static String bardicInspirationDieFor(int level) {
		if (level >= 15) return "1d12";
		if (level >= 10) return "1d10";
		if (level >= 5) return "1d8";
		return "1d6";
	}

	static int levelOf(JsonObject sheet) {
		if (sheet != null && sheet.has("characterLevel")) return Math.max(1, sheet.get("characterLevel").getAsInt());
		return 1;
	}

	/**
	 * <p><b>How many spells they can have prepared.</b> In 5e: their class's spellcasting ability modifier
	 * + their level, minimum 1. Cantrips don't count (they're at-will, not prepared).</p>
	 *
	 * <p>Returns 0 for someone who casts nothing, and that's what turns off the whole rule: with no
	 * casting class there's no list to prepare, so everything they know can be cast the same as before
	 * this existed. Same shape as the rest of the mod — nothing fires off a guess.</p>
	 */
	static int preparedLimitFor(JsonObject sheet) {
		if (sheet == null || !sheet.has("characterClass")) return 0;
		String ability = SpellSlots.castingAbilityFor(sheet.get("characterClass").getAsString());
		if (ability == null) return 0;

		int score = intField(sheet, abilityFieldFor(ability), 10);
		return Math.max(1, Math.floorDiv(score - 10, 2) + levelOf(sheet));
	}

	//Abilities are stored as a string on the sheet, and an old sheet can have anything at all in there.
	private static int intField(JsonObject sheet, String key, int fallback) {
		if (sheet == null || !sheet.has(key)) return fallback;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			return fallback;
		}
	}

	/**
	 * <p>Owner of a sheet, or {@code null} if it belongs to nobody (an NPC). With no {@code ownerUuid}
	 * field —every sheet predating characters existing— the owner is the id itself, because back then a
	 * sheet's id <em>was</em> its player's UUID. That fallback is what makes it unnecessary to migrate
	 * anything on disk.</p>
	 */
	@Nullable
	static String ownerOf(String characterId, JsonObject sheet) {
		if (sheet != null && sheet.has("ownerUuid")) {
			String owner = sheet.get("ownerUuid").getAsString();
			return owner.isEmpty() ? null : owner;
		}
		return characterId;
	}

	/** Ids of that player's characters, in stable order. */
	static List<String> ownedBy(Map<String, JsonObject> sheets, String playerUuid) {
		List<String> owned = new ArrayList<>();
		for (Map.Entry<String, JsonObject> entry : sheets.entrySet()) {
			if (playerUuid.equals(ownerOf(entry.getKey(), entry.getValue()))) owned.add(entry.getKey());
		}
		Collections.sort(owned);
		return owned;
	}

	/**
	 * <p>Player → active character binding, derived from each sheet's {@code active} field instead of
	 * stored as a separate index: an index can fall out of sync with the sheets and leave someone unable
	 * to play, whereas the field inside the sheet itself can't contradict itself.</p>
	 *
	 * <p>If a player ended up with two sheets marked active (manual JSON edit), the one with the lower id
	 * wins. What matters is that the tie-break is deterministic, not which one wins: without sorting, the
	 * player would find themselves with a different character depending on the boot order.</p>
	 */
	static Map<String, String> buildActive(Map<String, JsonObject> sheets) {
		Map<String, String> active = new HashMap<>();
		List<String> ids = new ArrayList<>(sheets.keySet());
		Collections.sort(ids);
		for (String characterId : ids) {
			JsonObject sheet = sheets.get(characterId);
			if (sheet == null || !sheet.has("active") || !sheet.get("active").getAsBoolean()) continue;
			String owner = ownerOf(characterId, sheet);
			if (owner == null) continue; //NPC: no player has it equipped.
			active.putIfAbsent(owner, characterId);
		}
		return active;
	}

	/**
	 * <p>Resolves what the player typed —a <b>name</b> or an id— to the character's id.</p>
	 *
	 * <p>Ids are derived from the UUID ({@code 380df991-...-2}), so asking someone to type one to switch
	 * characters is asking them to copy a string that means nothing to them. The name is what the person
	 * knows, so that's what's accepted; the id is still valid because it's what shows up in messages and
	 * in the file name.</p>
	 *
	 * <p>Deliberate order: <b>exact id, exact name, and only then unique prefix</b>. A character sharing a
	 * name with another's id has to be selectable, and a prefix can never beat an exact match — typing
	 * "Ana" with both an Ana and an Anabel present must resolve to Ana, not an ambiguity error.</p>
	 *
	 * @return the id, or {@code null} if it's not recognized or if there's more than one candidate
	 *         (ambiguous is as much a "no" as not finding it: picking one would pick wrong half the time).
	 */
	@Nullable
	static String resolveCharacter(Map<String, JsonObject> sheets, List<String> candidateIds, String query) {
		if (query == null) return null;
		String needle = query.trim();
		if (needle.isEmpty()) return null;

		for (String id : candidateIds) {
			if (id.equals(needle)) return id;
		}

		//"Name [id]": what autocomplete suggests when TWO characters share a name, and the only honest way
		//to choose between them. A character actually named "Bruno [el Bravo]" won't match any id, so it
		//falls through to the normal matching below without doing anything strange.
		String bracketed = idInsideBrackets(candidateIds, needle);
		if (bracketed != null) return bracketed;

		String exact = null;
		int exactCount = 0;
		String prefix = null;
		int prefixCount = 0;
		for (String id : candidateIds) {
			String name = nameOf(sheets.get(id));
			if (name == null) continue;
			if (name.equalsIgnoreCase(needle)) {
				exact = id;
				exactCount++;
			} else if (name.toLowerCase(Locale.ROOT).startsWith(needle.toLowerCase(Locale.ROOT))) {
				prefix = id;
				prefixCount++;
			}
		}
		if (exactCount == 1) return exact;
		if (exactCount > 1) return null;
		return prefixCount == 1 ? prefix : null;
	}

	@Nullable
	private static String idInsideBrackets(List<String> candidateIds, String needle) {
		int open = needle.lastIndexOf('[');
		if (open <= 0 || !needle.endsWith("]")) return null;
		String inner = needle.substring(open + 1, needle.length() - 1).trim();
		for (String id : candidateIds) {
			if (id.equals(inner)) return id;
		}
		return null;
	}

	/**
	 * <p>How a character is offered to the player: just its plain name, or {@code Name [id]} if
	 * <b>another</b> of the candidates shares the name.</p>
	 *
	 * <p>The id only shows up where it's actually needed. Before, the name was always suggested, and two
	 * same-named characters produced two identical suggestions that couldn't even be resolved: autocomplete
	 * offered an option the command itself would later reject as ambiguous. A suggestion that doesn't work
	 * is worse than no suggestion at all.</p>
	 */
	static String suggestionLabelFor(Map<String, JsonObject> sheets, List<String> candidateIds, String characterId) {
		String name = nameOf(sheets.get(characterId));
		if (name == null || name.isBlank()) return characterId;
		for (String other : candidateIds) {
			if (other.equals(characterId)) continue;
			if (name.equalsIgnoreCase(nameOf(sheets.get(other)))) return name + " [" + characterId + "]";
		}
		return name;
	}

	/** Name of a sheet, or null if it doesn't have one: not all of them carry it, and comparing against null is worse than skipping it. */
	@Nullable
	static String nameOf(JsonObject sheet) {
		return sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : null;
	}

	/**
	 * <p>Which character a player should end up wearing when the one they had stops existing: the same
	 * one if it's still there, otherwise the first one left, and {@code null} if none are left.</p>
	 *
	 * <p>Exists as a separate function because of the bug it cost: {@code activeCharacterOf} <b>doesn't
	 * distinguish</b> "has this one equipped" from "has none equipped", because with no binding it returns
	 * the player's own UUID — the sheet id from before characters existed. Asking that function whether
	 * the player still has a character answered yes as soon as a file with that id existed, even if it
	 * wasn't equipped. The right question is asked against the explicit binding, and now it can be checked
	 * without a running game.</p>
	 *
	 * @param boundId the character registered as active, or {@code null} if none is registered.
	 */
	@Nullable
	static String characterToWearAfter(Set<String> existingIds, String boundId, List<String> ownedIds) {
		if (boundId != null && existingIds.contains(boundId)) return boundId;
		for (String id : ownedIds) {
			if (existingIds.contains(id)) return id;
		}
		return null;
	}

	/**
	 * Id for one more character of that player. Derived from their UUID, so it's unique across players
	 * without needing a global counter, and still a valid file name on any system.
	 */
	static String nextCharacterId(Set<String> existing, String playerUuid) {
		for (int n = 2; ; n++) {
			String candidate = playerUuid + "-" + n;
			if (!existing.contains(candidate)) return candidate;
		}
	}

	/**
	 * <p>The sheet field for an ability written as {@code "str"} or as {@code "strength"}. Returns null
	 * for anything else: a feat from a pack that says {@code "force"} must not write into a made-up
	 * field or, worse, one that happens to exist by coincidence.</p>
	 *
	 * <p>Exists because content uses the SRD's short keys and the sheet uses the long names. The
	 * conversion used to be written by hand in {@code PresetRegistry.applyToSheet}, six lines in a row; by
	 * the second thing that grants abilities —feats— that was already twelve.</p>
	 */
	@Nullable
	static String abilityFieldFor(String ability) {
		if (ability == null) return null;
		return switch (ability.toLowerCase(Locale.ROOT)) {
			case "str", "strength" -> "strength";
			case "dex", "dexterity" -> "dexterity";
			case "con", "constitution" -> "constitution";
			case "int", "intelligence" -> "intelligence";
			case "wis", "wisdom" -> "wisdom";
			case "cha", "charisma" -> "charisma";
			default -> null;
		};
	}

	/** How far whoever has darkvision sees in it, per the SRD: 60 feet. */
	private static final int SRD_DARKVISION_FEET = 60;

	/**
	 * SRD races with darkvision. "elf" also covers "half-elf" and "orc" covers "half-orc" — both halves
	 * have it, so the substring match gives the correct answer without repeating the entry. English names
	 * are included too because a table might have its race list in any language.
	 */
	private static final List<String> DARKVISION_RACES =
		List.of("enano", "dwarf", "elfo", "elf", "gnomo", "gnome", "orco", "orc", "tiefling", "drow");

	/**
	 * <p>Darkvision of the character on this sheet, in feet; zero means they don't have it.</p>
	 *
	 * <p>Two sources, in this order: the sheet's {@code darkvision} field if it's set —the escape hatch
	 * for a homebrew race, a trait, or an item that grants it— and if not, the race. The table is the
	 * SRD's: dwarf, elf (and therefore half-elf), gnome, half-orc and tiefling see 60 feet; human, halfling
	 * and dragonborn see nothing.</p>
	 *
	 * <p><b>An unrecognized race grants no trait</b>, and that's deliberate: this mod's races are free text
	 * that a pack can replace entirely ({@link CharacterOptionsRegistry}), so there's no way here to tell
	 * "race that can't see" from "race I don't know". When in doubt, nothing is granted — no rule should
	 * fire based on a guess, which is the same decision already made with {@link CreatureType} — and the
	 * sheet field is the explicit correction. The comparison strips accents and matches by substring so
	 * "Elfo", "elfo del bosque" and "Elf" are all the same.</p>
	 */
	static int darkvisionFeetFor(JsonObject sheet) {
		int explicit = intField(sheet, "darkvision", -1);
		if (explicit >= 0) return explicit;
		return darkvisionFeetFor(sheet != null && sheet.has("characterRace")
			? sheet.get("characterRace").getAsString() : null);
	}

	static int darkvisionFeetFor(String race) {
		if (race == null || race.isBlank()) return 0;
		String normalized = java.text.Normalizer.normalize(race, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
		for (String seer : DARKVISION_RACES) if (normalized.contains(seer)) return SRD_DARKVISION_FEET;
		return 0;
	}

	/**
	 * <p>Id for an NPC sheet, readable and valid as a file name. Accents are decomposed and stripped
	 * BEFORE filtering characters: without that, "Capitán" produced {@code npc-capit-n}, because "á"
	 * doesn't fall within {@code [a-z0-9]} and turned into a separator. In a mod with Spanish content that
	 * affects most names, not some rare edge case.</p>
	 */
	static String npcIdFor(Set<String> existing, String characterName) {
		String withoutAccents = characterName == null ? "" : java.text.Normalizer
			.normalize(characterName, java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{M}+", "");
		String slug = withoutAccents.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "-")
			.replaceAll("(^-|-$)", "");
		if (slug.isEmpty()) slug = "pnj"; //A name entirely in non-Latin characters must not produce an empty id.
		String candidate = "npc-" + slug;
		for (int n = 2; existing.contains(candidate); n++) candidate = "npc-" + slug + "-" + n;
		return candidate;
	}
}
