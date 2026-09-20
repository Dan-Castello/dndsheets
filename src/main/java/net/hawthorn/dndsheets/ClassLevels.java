package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <p>Multiclassing: a character's levels split across several classes, written on the sheet as
 * {@code "classLevels": {"fighter": 3, "wizard": 2}}.</p>
 *
 * <p><b>Deliberately left for the end of the roadmap</b>, for a concrete reason: it's the only thing
 * that redoes tables that were already fixed level by level ({@link SpellSlots}, the proficiency bonus,
 * max HP). That's why it comes in as <b>an optional field that takes over when present</b> rather than a
 * model change: a sheet without {@code classLevels} — i.e. every sheet that exists today — behaves
 * exactly as before, and the rest of the mod never learns multiclassing exists. The one thing that does
 * change is that, when present, {@code characterLevel} gets rewritten as the sum, so the ~70 call sites
 * that read the total level keep reading a plain number and don't need to learn anything.</p>
 *
 * <p><b>What isn't modeled, and why:</b></p>
 * <ul>
 *   <li><b>Ability score prerequisites</b> (13 in both classes). At the table that's the DM's call before
 *       granting the level, and here the level <em>is granted by the DM</em>: an automatic check could
 *       only get in the way of a table playing with a different rule.</li>
 *   <li><b>The new class's traits</b> aren't granted automatically. Applying its whole preset would be
 *       worse than doing nothing: it would overwrite the six ability scores, hit die, and equipment of
 *       the character that already exists. The DM grants whichever traits apply, which is already what
 *       they do with {@code /dndtraits}.</li>
 *   <li><b>Pact slots don't stack</b> with a caster's. A multiclassed warlock has two separate pools in
 *       5e and this sheet only knows how to carry one, so it carries the caster's. Undershooting is the
 *       safe direction — the opposite would hand out slots the character doesn't have.</li>
 * </ul>
 */
public final class ClassLevels {

	static final String FIELD = "classLevels";

	private ClassLevels() {
	}

	/**
	 * <p>This sheet's per-class levels, in the order they were written. Order matters: the <b>first</b>
	 * one is the class the character started with, and in 5e that's the only one that grants the full
	 * hit die at its level 1.</p>
	 */
	public static Map<String, Integer> of(JsonObject sheet) {
		Map<String, Integer> levels = new LinkedHashMap<>();
		if (sheet == null || !sheet.has(FIELD) || !sheet.get(FIELD).isJsonObject()) return levels;
		JsonObject json = sheet.getAsJsonObject(FIELD);
		for (String classId : json.keySet()) {
			try {
				int level = json.get(classId).getAsInt();
				if (level > 0) levels.put(classId, level);
			} catch (RuntimeException ignored) {
				//An entry that isn't a number is skipped, like any other broken content line: the rest
				//of the split still counts.
			}
		}
		return levels;
	}

	public static boolean isMulticlass(JsonObject sheet) {
		return of(sheet).size() > 1;
	}

	public static int total(Map<String, Integer> levels) {
		int total = 0;
		for (int level : levels.values()) total += level;
		return total;
	}

	/**
	 * <p>A multiclass character's caster level: full-caster levels count whole, plus half-casters'
	 * levels <b>rounded down</b>. Warlock levels don't count — the pact is a separate pool.</p>
	 *
	 * <p>That rounding is the rule's classic trap, which is why it's written separately from
	 * {@link SpellSlots#maxSlots}: a half-caster <em>in a single class</em> rounds up (a level-2 paladin
	 * already casts), while multiclassing rounds down (a paladin 2 contributes 1). Writing both with the
	 * same rounding gives a table that's right half the time, which is the worst kind of bug: it looks
	 * like it works.</p>
	 */
	public static int casterLevel(Map<String, Integer> levels) {
		int caster = 0;
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			switch (SpellSlots.casterFor(entry.getKey())) {
				case FULL -> caster += entry.getValue();
				case HALF -> caster += entry.getValue() / 2;
				default -> { }
			}
		}
		return caster;
	}

	/**
	 * <p>Max HP for a class split: the <b>first</b> class's full die, then half the die + 1 for each
	 * remaining level, each using <em>its own</em> class's die. The Constitution modifier applies once
	 * per level, and every level grants at least 1 HP even with a terrible Constitution — the same rules
	 * as {@code CharacterRules.maxHitPointsFor}, applied die by die instead of with a single one.</p>
	 */
	public static int maxHitPoints(Map<String, Integer> levels, int constitution) {
		if (levels.isEmpty()) return 1;
		int conMod = Math.floorDiv(constitution - 10, 2);
		int maxHp = 0;
		boolean first = true;

		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			int hitDie = Config.hitDieFor(entry.getKey());
			for (int level = 0; level < entry.getValue(); level++) {
				if (first) {
					maxHp += hitDie + conMod;
					first = false;
				} else {
					maxHp += Math.max(1, (hitDie / 2 + 1) + conMod);
				}
			}
		}
		return Math.max(1, maxHp);
	}

	/** How a class split reads on the sheet and in chat: "Fighter 3 / Wizard 2". */
	public static String describe(Map<String, Integer> levels) {
		StringBuilder text = new StringBuilder();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) {
			PresetRegistry.ClassPreset preset = PresetRegistry.get(entry.getKey());
			if (text.length() > 0) text.append(" / ");
			text.append(preset != null ? preset.name() : entry.getKey()).append(' ').append(entry.getValue());
		}
		return text.toString();
	}

	/**
	 * <p>Adds a level in {@code classId} and keeps the sheet consistent: the split, the total level, and
	 * the class text. Doesn't touch HP or slots — their respective owners handle that
	 * ({@code SheetLoader.applyClassHitPoints} and {@link SpellSlots#applyProgression}), which already
	 * know how to read the split.</p>
	 *
	 * <p>If the sheet had no split yet, it's seeded with the class it already had and its current level:
	 * without that, multiclassing a level-5 fighter would turn it into a fighter 0 / wizard 1.</p>
	 */
	public static Map<String, Integer> addLevel(JsonObject sheet, String classId, String currentClassId, int currentLevel) {
		Map<String, Integer> levels = of(sheet);
		if (levels.isEmpty() && currentClassId != null && !currentClassId.isBlank()) {
			levels.put(currentClassId, Math.max(1, currentLevel));
		}
		levels.merge(classId, 1, Integer::sum);

		JsonObject json = new JsonObject();
		for (Map.Entry<String, Integer> entry : levels.entrySet()) json.addProperty(entry.getKey(), entry.getValue());
		sheet.add(FIELD, json);
		sheet.addProperty("characterLevel", String.valueOf(total(levels)));
		sheet.addProperty("characterClass", describe(levels));
		return levels;
	}
}
