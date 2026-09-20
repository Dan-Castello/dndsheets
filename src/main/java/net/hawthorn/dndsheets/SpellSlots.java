package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * <p>Spell slots by spell level, which is how they work in 5e: a level-3 spell spends a
 * level-3-or-higher slot, not just "a slot" period.</p>
 *
 * <p>The sheet used to carry a single flat pool ({@code spellSlotsMax}/{@code spellSlotsCurrent}), set
 * once by the class preset and never scaled. That broke two things at once: a Fireball cost the same as
 * a Magic Missile, and a level-10 wizard had the same slots as a level-1 one.</p>
 *
 * <p><b>The old totals are still kept</b> ({@link #syncTotals}) as the sum of the new table. This isn't
 * debt: the HUD, the Spellbook, the sheet summary, and {@code /dndsheet} only show "how many do I have
 * left", and that question still has the same answer. Changing all of those at once too would have made
 * the change much bigger without improving anything visible.</p>
 *
 * <p>Pure class, nothing Minecraft-specific, so the tables can be checked in the self-test.</p>
 *
 * <p>Tables from the SRD 5.1 (CC-BY-4.0); the attribution the license requires lives in PROJECT_CONTEXT.md.</p>
 */
public final class SpellSlots {

	/** Highest spell level that exists. Index 0 is unused: cantrips don't spend a slot. */
	public static final int MAX_SPELL_LEVEL = 9;

	public enum Caster { NONE, FULL, HALF, PACT }

	//One digit per spell level, starting at 1. FULL[character level] — index 0 is left empty so level 1
	//is FULL[1] and nothing needs subtracting on every use.
	private static final String[] FULL = {
		"", "2", "3", "42", "43", "432", "433", "4331", "4332", "43331", "43332",
		"433321", "433321", "4333211", "4333211", "43332111", "43332111",
		"433321111", "433331111", "433332111", "433332211",
	};

	//Warlock's Pact Magic: few slots, ALL the same level, and they recover on a short rest. That's why
	//it's a separate table and not a case of FULL — it isn't "fewer slots", it's a different resource.
	private static final int[] PACT_COUNT = {0, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4};
	private static final int[] PACT_LEVEL = {0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5};

	private SpellSlots() {
	}

	/**
	 * <p>What kind of caster a class is. Compared against both the preset's English id AND its displayed
	 * name, because the sheet stores the translated name in {@code characterClass} ("Mago", not "wizard")
	 * and {@code Config.hitDieFor} already suffers the same issue. By substring and lowercased, same as that one.</p>
	 */
	public static Caster casterFor(String characterClass) {
		if (characterClass == null) return Caster.NONE;
		String c = characterClass.toLowerCase(Locale.ROOT);
		if (contains(c, "warlock", "brujo")) return Caster.PACT;
		if (contains(c, "paladin", "paladín", "ranger", "explorador")) return Caster.HALF;
		if (contains(c, "wizard", "mago", "cleric", "clérigo", "clerigo", "bard", "bardo",
				"druid", "druida", "sorcerer", "hechicero")) return Caster.FULL;
		return Caster.NONE;
	}

	/**
	 * <p>Which ability score a class casts with, in short key form ({@code "int"}/{@code "wis"}/{@code
	 * "cha"}), or {@code null} if that class doesn't cast anything. Sibling of {@link #casterFor} with the
	 * same tolerance: by substring, lowercased, against both the English id and the translated name,
	 * because the sheet stores in {@code characterClass} whatever is displayed ("Mago", not "wizard").</p>
	 *
	 * <p>Used by the prepared-spells limit ({@code CharacterRules.preparedLimitFor}). The spell carries its
	 * own {@code castingAbility} for RESOLVING itself, which is a different question: how many you can
	 * prepare depends on your class, not on what each one is rolled with.</p>
	 */
	public static String castingAbilityFor(String characterClass) {
		if (characterClass == null) return null;
		String c = characterClass.toLowerCase(Locale.ROOT);
		if (contains(c, "wizard", "mago")) return "int";
		if (contains(c, "cleric", "clérigo", "clerigo", "druid", "druida", "ranger", "explorador")) return "wis";
		if (contains(c, "bard", "bardo", "sorcerer", "hechicero", "warlock", "brujo", "paladin", "paladín")) return "cha";
		return null;
	}

	private static boolean contains(String haystack, String... needles) {
		for (String needle : needles) {
			if (haystack.contains(needle)) return true;
		}
		return false;
	}

	/**
	 * <p>Writes a new max and adjusts what's left: newly gained slots come in <b>full</b> and ones already
	 * spent stay spent. Shared by the single-class path and the multiclass path, which only differ in
	 * which table they read.</p>
	 */
	private static void applySlots(JsonObject sheet, int[] max) {
		int[] before = readSlots(sheet, "spellSlotsMaxByLevel");
		int[] current = currentSlots(sheet);

		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			int gained = Math.max(0, max[level] - before[level]);
			current[level] = Math.min(max[level], current[level] + gained);
		}

		writeSlots(sheet, "spellSlotsMaxByLevel", max);
		writeSlots(sheet, "spellSlotsByLevel", current);
		syncTotals(sheet);
	}

	/**
	 * <p>Max slots per spell level. The index is the spell level (1..9); 0 always stays zero because
	 * cantrips are at-will.</p>
	 */
	public static int[] maxSlots(Caster caster, int characterLevel) {
		int level = Math.min(20, Math.max(1, characterLevel));
		int[] slots = new int[MAX_SPELL_LEVEL + 1];

		switch (caster) {
			case FULL -> fill(slots, FULL[level]);
			//A half-caster is exactly a full caster at half level, rounded up — checked level by level
			//against the SRD table before relying on it, because "half" with the rounding backward shifts
			//the whole progression by one level. And it casts nothing until level 2, which is the only
			//point where the rule isn't the division.
			case HALF -> { if (level >= 2) fill(slots, FULL[(level + 1) / 2]); }
			case PACT -> slots[PACT_LEVEL[level]] = PACT_COUNT[level];
			case NONE -> { }
		}
		return slots;
	}

	private static void fill(int[] slots, String row) {
		for (int i = 0; i < row.length(); i++) slots[i + 1] = row.charAt(i) - '0';
	}

	/**
	 * <p>Spends a slot for a spell of level {@code spellLevel}, taking <b>the lowest one that works</b>.
	 * Returns the level of the slot spent, 0 if it was a cantrip (spends nothing), and -1 if none were left.</p>
	 *
	 * <p>The lowest, not the exact one, because in 5e you can cast with a higher slot, and spending the
	 * highest one available when a lower one would do wastes the expensive resource.</p>
	 */
	public static int spend(JsonObject sheet, int spellLevel) {
		return spend(sheet, spellLevel, 0);
	}

	/**
	 * <p>Same thing, but never going below {@code minSlotLevel}: this is what allows <b>deliberately
	 * upcasting</b> (Fireball with a 5th-level slot deals more damage, see {@code Spell.upcastTo}).
	 * Returning the level spent instead of a boolean is exactly what makes that rule possible: the caster
	 * needs to know which slot it actually went out with, not just that it went out.</p>
	 *
	 * <p>If the requested level is exhausted it keeps going up instead of failing. This is the generous
	 * reading: whoever asks "spend a 3rd-level one on me" is asking for <i>at least</i> 3rd level, and
	 * denying the cast while a 4th-level one is free would be a no on a technicality.</p>
	 */
	public static int spend(JsonObject sheet, int spellLevel, int minSlotLevel) {
		if (spellLevel <= 0) return 0; //Cantrip: at-will, spends nothing.
		int[] current = currentSlots(sheet);
		for (int level = Math.max(spellLevel, minSlotLevel); level <= MAX_SPELL_LEVEL; level++) {
			if (current[level] > 0) {
				current[level]--;
				writeSlots(sheet, "spellSlotsByLevel", current);
				syncTotals(sheet);
				return level;
			}
		}
		return -1;
	}

	/**
	 * <p>Minimal client patch after touching the slots: <b>the per-level table and the total derived from
	 * it</b>.</p>
	 *
	 * <p>Exists because sending only the total left the client with a stale table: the Spellbook shows one
	 * column per level and decides from them which levels can be chosen, so with only the total the
	 * columns stayed frozen and the selector offered levels already spent. Whoever sends a short patch has
	 * to send EVERYTHING that changed, and since slots are per-level that's two fields.</p>
	 */
	public static JsonObject clientPatch(JsonObject sheet) {
		JsonObject patch = new JsonObject();
		//Careful: in a patch, a null value means "delete this key" on the client's sheet (see
		//SheetLoader.applyClientDelta), so an absent field is omitted instead of sent empty.
		//All FOUR fields, not just the ones that change on spend. The max is DERIVED (class and level, see
		//applyProgression) and gets recomputed server-side every time the sheet is saved, without the
		//client knowing: sending only the current value left the client stuck with an old max forever and
		//the HUD ended up showing "Spells: 4/2" — more than fit — while the server had 4/7. Reported
		//exactly like this while playing.
		for (String field : new String[]{"spellSlotsByLevel", "spellSlotsMaxByLevel", "spellSlotsCurrent", "spellSlotsMax"}) {
			if (sheet.has(field)) patch.add(field, sheet.get(field));
		}
		return patch;
	}

	/** Is there any slot left to cast this with? Same rule as {@link #spend}, without spending. */
	public static boolean hasSlotFor(JsonObject sheet, int spellLevel) {
		if (spellLevel <= 0) return true;
		int[] current = currentSlots(sheet);
		for (int level = spellLevel; level <= MAX_SPELL_LEVEL; level++) {
			if (current[level] > 0) return true;
		}
		return false;
	}

	/**
	 * <p>Restores spent slots until exhausting a <b>budget of summed levels</b>, not a number of slots:
	 * this is how the wizard's Arcane Recovery works (you recover slots whose levels add up to half your
	 * level, none above 5th). Returns how many it restored.</p>
	 *
	 * <p>Takes the highest ones that fit first, which is what anyone at the table would choose: for the
	 * same budget, a level-3 slot is worth more than three level-1 ones. This rule couldn't be written
	 * until the per-level table existed — with a single flat pool there's no "which level" to restore.</p>
	 */
	public static int restoreBudget(JsonObject sheet, int levelBudget, int maxLevel) {
		int[] max = maxSlotsOf(sheet);
		int[] current = currentSlots(sheet);
		int budget = levelBudget;
		int restored = 0;

		for (int level = Math.min(maxLevel, MAX_SPELL_LEVEL); level >= 1; level--) {
			while (budget >= level && current[level] < max[level]) {
				current[level]++;
				budget -= level;
				restored++;
			}
		}

		if (restored > 0) {
			writeSlots(sheet, "spellSlotsByLevel", current);
			syncTotals(sheet);
		}
		return restored;
	}

	/** Rest: slots return to their max. */
	public static void restoreAll(JsonObject sheet) {
		writeSlots(sheet, "spellSlotsByLevel", maxSlotsOf(sheet));
		syncTotals(sheet);
	}

	/**
	 * <p>Recomputes the max from class and level, and adjusts what's left so it never exceeds the new max.
	 * On leveling up, newly gained slots come in <b>full</b>: in 5e they're gained upon finishing the long
	 * rest that levels you up, so granting them empty would require another rest to use them.</p>
	 */
	public static void applyProgression(JsonObject sheet, String characterClass, int characterLevel) {
		//A multiclass character doesn't read its table by class name: it reads it by caster level, which
		//comes from the level split (see ClassLevels.casterLevel). And the table it reads is ALWAYS the
		//full-caster one — that's the 5e rule, not an approximation: the half-caster contribution was
		//already accounted for at half value when summed.
		if (ClassLevels.isMulticlass(sheet)) {
			java.util.Map<String, Integer> mix = ClassLevels.of(sheet);
			int casterLevel = ClassLevels.casterLevel(mix);
			if (casterLevel > 0) {
				applySlots(sheet, maxSlots(Caster.FULL, casterLevel));
				return;
			}
			//With no caster level, Pact Magic might still be there: a fighter/warlock contributes nothing
			//to the table above and yet casts. If both were present the one above wins, because this sheet
			//only knows how to carry one pool, and falling short is the safe direction.
			for (java.util.Map.Entry<String, Integer> entry : mix.entrySet()) {
				if (casterFor(entry.getKey()) == Caster.PACT) {
					applySlots(sheet, maxSlots(Caster.PACT, entry.getValue()));
					return;
				}
			}
			migrateFlatPool(sheet);
			return;
		}

		Caster caster = casterFor(characterClass);
		if (caster == Caster.NONE) {
			//A non-caster class carries no progression, but that does NOT mean "no slots": the DM may have
			//set them by hand (a fighter with an item, a homebrew class). Recomputing to zero would erase
			//that configuration on the first sync.
			migrateFlatPool(sheet);
			return;
		}

		applySlots(sheet, maxSlots(caster, characterLevel));
	}

	/**
	 * <p>Sheets predating the table: they only carry the single flat pool. Without this, {@link
	 * #hasSlotFor} would see them empty and the character couldn't cast anything despite their sheet
	 * saying they have slots left.</p>
	 *
	 * <p>They're placed as <b>level 1</b> slots. This is the conservative reading: the pool didn't say
	 * what level they were, and spreading them upward would give them power they never had. For a caster
	 * class this doesn't even get used — the progression recomputes its real table.</p>
	 */
	private static void migrateFlatPool(JsonObject sheet) {
		if (sheet == null || sheet.has("spellSlotsMaxByLevel")) return;
		int flatMax = sheet.has("spellSlotsMax") ? sheet.get("spellSlotsMax").getAsInt() : 0;
		if (flatMax <= 0) return;
		int flatCurrent = sheet.has("spellSlotsCurrent") ? sheet.get("spellSlotsCurrent").getAsInt() : 0;

		int[] max = new int[MAX_SPELL_LEVEL + 1];
		int[] current = new int[MAX_SPELL_LEVEL + 1];
		max[1] = flatMax;
		current[1] = Math.max(0, Math.min(flatMax, flatCurrent));
		writeSlots(sheet, "spellSlotsMaxByLevel", max);
		writeSlots(sheet, "spellSlotsByLevel", current);
		syncTotals(sheet);
	}

	/** Slots set by hand by the DM ({@code /dndsheet setslots} and the DM Panel), all level 1. */
	public static void setFlat(JsonObject sheet, int max, int current) {
		int[] maxSlots = new int[MAX_SPELL_LEVEL + 1];
		int[] currentSlots = new int[MAX_SPELL_LEVEL + 1];
		maxSlots[1] = Math.max(0, max);
		currentSlots[1] = Math.max(0, Math.min(max, current));
		writeSlots(sheet, "spellSlotsMaxByLevel", maxSlots);
		writeSlots(sheet, "spellSlotsByLevel", currentSlots);
		syncTotals(sheet);
	}

	public static int[] maxSlotsOf(JsonObject sheet) {
		return readSlots(sheet, "spellSlotsMaxByLevel");
	}

	public static int[] currentSlots(JsonObject sheet) {
		return readSlots(sheet, "spellSlotsByLevel");
	}

	/**
	 * <p>Keeps {@code spellSlotsMax}/{@code spellSlotsCurrent} as the sum of the table. Everything that
	 * only shows "how many do I have left" (HUD, Spellbook, sheet summary, {@code /dndsheet}) keeps
	 * reading those two unchanged.</p>
	 */
	public static void syncTotals(JsonObject sheet) {
		sheet.addProperty("spellSlotsMax", total(maxSlotsOf(sheet)));
		sheet.addProperty("spellSlotsCurrent", total(currentSlots(sheet)));
	}

	public static int total(int[] slots) {
		int sum = 0;
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) sum += slots[level];
		return sum;
	}

	private static int[] readSlots(JsonObject sheet, String key) {
		int[] slots = new int[MAX_SPELL_LEVEL + 1];
		if (sheet == null || !sheet.has(key) || !sheet.get(key).isJsonObject()) return slots;
		JsonObject stored = sheet.getAsJsonObject(key);
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			String name = String.valueOf(level);
			if (stored.has(name)) {
				try {
					slots[level] = Math.max(0, stored.get(name).getAsInt());
				} catch (RuntimeException ignored) {
					//A sheet edited by hand shouldn't block play: that level just stays at zero.
				}
			}
		}
		return slots;
	}

	private static void writeSlots(JsonObject sheet, String key, int[] slots) {
		JsonObject stored = new JsonObject();
		//Only the levels with slots: a fighter's sheet doesn't need nine zeros, and this way it's clear at
		//a glance what it actually has when opening the .json.
		for (int level = 1; level <= MAX_SPELL_LEVEL; level++) {
			if (slots[level] > 0) stored.addProperty(String.valueOf(level), slots[level]);
		}
		sheet.add(key, stored);
	}
}
