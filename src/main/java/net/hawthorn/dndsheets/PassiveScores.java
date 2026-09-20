package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <p>Passive score = the same formula the player already has for that skill in the "skills" column of
 * their sheet, substituting a fixed 10 for the d20 (5e rule), instead of duplicating that formula by
 * hand. Index 12 (Perception) is the same fixed order already used by {@link RollIndex}.</p>
 */
public class PassiveScores {
	private static final int PERCEPTION_SKILL_INDEX = 12;

	public static int passivePerception(JsonObject sheet) {
		return passiveFor(sheet, PERCEPTION_SKILL_INDEX);
	}

	public static int passiveFor(JsonObject sheet, int skillIndex) {
		if (sheet == null || !sheet.has("skills")) return 10;
		JsonArray skills = sheet.getAsJsonArray("skills");
		if (skillIndex >= skills.size()) return 10;

		String expression = skills.get(skillIndex).getAsString().replaceFirst("1d20", "10");
		DiceManager.RollOutcome outcome = DiceManager.roll(sheet, expression);
		return outcome.result() != null ? outcome.result().getValue() : 10;
	}
}
