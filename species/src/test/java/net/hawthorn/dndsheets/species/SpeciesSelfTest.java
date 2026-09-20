package net.hawthorn.dndsheets.species;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.hawthorn.dndsheets.RollIndex;

/**
 * <p>The only logic in this addon with real branches: {@link RaceRegistry#apply} and {@link BackgroundRegistry#apply}
 * are additive (unlike {@code PresetRegistry.applyToSheet}, which overwrites), so switching
 * race or background must undo the previous one before applying the new one, and reapplying the same one must not
 * duplicate anything. Runs standalone, without the Forge runtime — neither touches Minecraft classes.</p>
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

	/** Sheet with all 18 skills without proficiency, each with a non-empty expression — same requirement
	 *  as {@code RollIndex.setSkillProficiency}, which no-ops if the skill doesn't exist yet. */
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
		assertTrue(first.outcome() == RaceRegistry.ApplyOutcome.APPLIED, "the first application should be APPLIED");
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "Elf should add +2 DEX, got " + sheet.get("dexterity"));

		RaceRegistry.ApplyResult second = RaceRegistry.apply(sheet, "elf");
		assertTrue(second.outcome() == RaceRegistry.ApplyOutcome.NO_CHANGE, "reapplying the same race should change nothing");
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "reapplying should not add the bonus twice, got " + sheet.get("dexterity"));
	}

	private static void checkSwapSubtractsPreviousBonus() {
		JsonObject sheet = new JsonObject();
		sheet.addProperty("strength", "10");
		sheet.addProperty("dexterity", "10");

		RaceRegistry.apply(sheet, "half_orc"); //STR +2, CON +1
		assertTrue(sheet.get("strength").getAsString().equals("12"), "Half-Orc should add +2 STR");

		RaceRegistry.apply(sheet, "elf"); //DEX +2 — the Half-Orc +2 STR has to go away
		assertTrue(sheet.get("strength").getAsString().equals("10"), "switching to Elf should subtract the Half-Orc bonus, left " + sheet.get("strength"));
		assertTrue(sheet.get("dexterity").getAsString().equals("12"), "switching to Elf should add its own +2 DEX");
	}

	private static void checkUnknownRace() {
		JsonObject sheet = new JsonObject();
		RaceRegistry.ApplyResult result = RaceRegistry.apply(sheet, "no-existe");
		assertTrue(result.outcome() == RaceRegistry.ApplyOutcome.UNKNOWN_RACE, "an id that doesn't exist must return UNKNOWN_RACE, not blow up");
	}

	private static void checkBackgroundApplyIsIdempotent() {
		JsonObject sheet = sheetWithSkills();

		BackgroundRegistry.ApplyResult first = BackgroundRegistry.apply(sheet, "acolyte");
		assertTrue(first.outcome() == BackgroundRegistry.ApplyOutcome.APPLIED, "the first application should be APPLIED");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "Acolyte should grant proficiency in Insight (10)");
		assertTrue(RollIndex.isSkillProficient(sheet, 8), "Acolyte should grant proficiency in Religion (8)");

		BackgroundRegistry.ApplyResult second = BackgroundRegistry.apply(sheet, "acolyte");
		assertTrue(second.outcome() == BackgroundRegistry.ApplyOutcome.NO_CHANGE, "reapplying the same background should change nothing");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "reapplying should not remove the proficiency");
	}

	private static void checkBackgroundSwapRevokesPreviousSkills() {
		JsonObject sheet = sheetWithSkills();

		BackgroundRegistry.apply(sheet, "criminal"); //Deception(14) + Stealth(3)
		assertTrue(RollIndex.isSkillProficient(sheet, 14), "Criminal should grant proficiency in Deception (14)");
		assertTrue(RollIndex.isSkillProficient(sheet, 3), "Criminal should grant proficiency in Stealth (3)");

		BackgroundRegistry.apply(sheet, "acolyte"); //Insight(10) + Religion(8) — Deception/Stealth have to go away
		assertTrue(!RollIndex.isSkillProficient(sheet, 14), "switching to Acolyte should remove Criminal's Deception");
		assertTrue(!RollIndex.isSkillProficient(sheet, 3), "switching to Acolyte should remove Criminal's Stealth");
		assertTrue(RollIndex.isSkillProficient(sheet, 10), "switching to Acolyte should grant its own Insight");
		assertTrue(RollIndex.isSkillProficient(sheet, 8), "switching to Acolyte should grant its own Religion");
	}

	private static void checkUnknownBackground() {
		JsonObject sheet = sheetWithSkills();
		BackgroundRegistry.ApplyResult result = BackgroundRegistry.apply(sheet, "no-existe");
		assertTrue(result.outcome() == BackgroundRegistry.ApplyOutcome.UNKNOWN_BACKGROUND, "an id that doesn't exist must return UNKNOWN_BACKGROUND, not blow up");
	}

	private static void assertTrue(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
