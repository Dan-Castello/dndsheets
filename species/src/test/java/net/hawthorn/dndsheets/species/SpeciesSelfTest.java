package net.hawthorn.dndsheets.species;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.hawthorn.dndsheets.RollIndex;

/**
 * <p>Lo único con ramas reales de este addon: {@link RaceRegistry#apply} y {@link BackgroundRegistry#apply}
 * son aditivos (a diferencia de {@code PresetRegistry.applyToSheet}, que sobreescribe), así que cambiar de
 * raza o trasfondo tiene que deshacer lo anterior antes de aplicar lo nuevo, y reaplicar lo mismo no debe
 * duplicar nada. Corre de pie, sin runtime de Forge — ninguna de las dos toca clases de Minecraft.</p>
 */
public class SpeciesSelfTest {
	public static void main(String[] args) {
		checkApplyIsIdempotent();
		checkSwapSubtractsPreviousBonus();
		checkUnknownRace();
		checkBackgroundApplyIsIdempotent();
		checkBackgroundSwapRevokesPreviousSkills();
		checkUnknownBackground();
		System.out.println("SpeciesSelfTest: OK.");
	}

	/** Sheet con las 18 habilidades sin competencia, cada una con una expresión no vacía — mismo requisito
	 *  que {@code RollIndex.setSkillProficiency}, que no-opea si la habilidad no existe todavía. */
	private static JsonObject sheetWithSkills() {
		JsonObject sheet = new JsonObject();
		JsonArray skills = new JsonArray();
		for (int i = 0; i < RollIndex.SKILL_COUNT; i++) skills.add(new JsonPrimitive("1d20"));
		sheet.add("skills", skills);
		return sheet;
	}

	private static void checkApplyIsIdempotent() {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("dexterity", "10");

		RaceRegistry.ApplyResult first = RaceRegistry.apply(sheet, "elf");
		assertTrue(first.outcome() == RaceRegistry.ApplyOutcome.APPLIED, "la primera aplicación debería ser APPLIED");
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "Elfo debería sumar +2 DEX, dio " + sheet.get("dexterity"));

		RaceRegistry.ApplyResult second = RaceRegistry.apply(sheet, "elf");
		assertTrue(second.outcome() == RaceRegistry.ApplyOutcome.NO_CHANGE, "reaplicar la misma raza no debería tocar nada");
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "reaplicar no debería sumar el bono dos veces, dio " + sheet.get("dexterity"));
	}

	private static void checkSwapSubtractsPreviousBonus() {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("strength", "10");
		sheet.addProperty("dexterity", "10");

		RaceRegistry.apply(sheet, "half_orc"); //STR +2, CON +1
		assertTrue(sheet.get("strength").getAsString().equals("12"), "Semiorco debería sumar +2 STR");

		RaceRegistry.apply(sheet, "elf"); //DEX +2 — el +2 STR de Semiorco tiene que desaparecer
		assertTrue(sheet.get("strength").getAsString().equals("10"), "cambiar a Elfo debería restar el bono de Semiorco, quedó " + sheet.get("strength"));
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "cambiar a Elfo debería sumar su propio +2 DEX");
	}

	private static void checkUnknownRace() {
		JsonObject sheet = new JsonObject();
		RaceRegistry.ApplyResult result = RaceRegistry.apply(sheet, "no-existe");
		assertTrue(result.outcome() == RaceRegistry.ApplyOutcome.UNKNOWN_RACE, "un id que no existe tiene que devolver UNKNOWN_RACE, no reventar");
	}

	private static void checkBackgroundApplyIsIdempotent() {
		JsonObject sheet = sheetWithSkills();

		BackgroundRegistry.ApplyResult first = BackgroundRegistry.apply(sheet, "acolyte");
		assertTrue(first.outcome() == BackgroundRegistry.ApplyOutcome.APPLIED, "la primera aplicación debería ser APPLIED");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "Acólito debería dar competencia en Perspicacia (10)");
		assertTrue(RollIndex.isSkillProficient(sheet, 8), "Acólito debería dar competencia en Religión (8)");

		BackgroundRegistry.ApplyResult second = BackgroundRegistry.apply(sheet, "acolyte");
		assertTrue(second.outcome() == BackgroundRegistry.ApplyOutcome.NO_CHANGE, "reaplicar el mismo trasfondo no debería tocar nada");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "reaplicar no debería quitar la competencia");
	}

	private static void checkBackgroundSwapRevokesPreviousSkills() {
		JsonObject sheet = sheetWithSkills();

		BackgroundRegistry.apply(sheet, "criminal"); //Engaño(14) + Sigilo(3)
		assertTrue(RollIndex.isSkillProficient(sheet, 14), "Criminal debería dar competencia en Engaño (14)");
		assertTrue(RollIndex.isSkillProficient(sheet, 3), "Criminal debería dar competencia en Sigilo (3)");

		BackgroundRegistry.apply(sheet, "acolyte"); //Perspicacia(10) + Religión(8) — Engaño/Sigilo tienen que desaparecer
		assertTrue(!RollIndex.isSkillProficient(sheet, 14), "cambiar a Acólito debería quitar Engaño de Criminal");
		assertTrue(!RollIndex.isSkillProficient(sheet, 3), "cambiar a Acólito debería quitar Sigilo de Criminal");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "cambiar a Acólito debería dar su propia Perspicacia");
		assertTrue(RollIndex.isSkillProficient(sheet, 8), "cambiar a Acólito debería dar su propia Religión");
	}

	private static void checkUnknownBackground() {
		JsonObject sheet = sheetWithSkills();
		BackgroundRegistry.ApplyResult result = BackgroundRegistry.apply(sheet, "no-existe");
		assertTrue(result.outcome() == BackgroundRegistry.ApplyOutcome.UNKNOWN_BACKGROUND, "un id que no existe tiene que devolver UNKNOWN_BACKGROUND, no reventar");
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
