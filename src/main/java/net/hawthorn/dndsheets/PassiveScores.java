package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * <p>Puntuación pasiva = la misma fórmula que ya tiene el jugador para esa habilidad en la columna
 * "skills" de su hoja, sustituyendo el d20 por un 10 fijo (regla de 5e), en vez de duplicar esa
 * fórmula a mano. El índice 12 (Percepción) es el mismo orden fijo que ya usa {@link RollIndex}.</p>
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
