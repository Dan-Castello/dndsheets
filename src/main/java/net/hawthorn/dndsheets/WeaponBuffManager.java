package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

/**
 * <p>Timed weapon buffs: a spell that adds damage dice to <em>every</em> weapon hit while it lasts
 * (Divine Favor, Branding Smite). Not to be confused with {@link PaladinSmiteManager}, which is a
 * one-shot rider — this one is spent by rounds, not by hit.</p>
 *
 * <p>Stored on the sheet rather than in an in-memory map for the same reason as conditions: it's
 * character state, it has to survive a reconnect, and the mod has already once had the problem of
 * losing changes that only lived in RAM.</p>
 *
 * <p>Duration ticks down in full rounds, not real ticks, same as the barbarian's Rage: a "1 minute"
 * buff is 10 rounds, and counting it in real seconds would mean nothing at a table where a turn takes
 * however long the player takes to decide.</p>
 */
public class WeaponBuffManager {

	private static final String DICE_KEY = "weaponBuffDice";
	private static final String TYPE_KEY = "weaponBuffType";
	private static final String NAME_KEY = "weaponBuffName";
	private static final String ROUNDS_KEY = "weaponBuffRounds";

	public record Buff(String name, String dice, String damageType) {}

	public static void grant(JsonObject sheet, String name, String dice, String damageType, int rounds) {
		if (sheet == null) return;
		sheet.addProperty(NAME_KEY, name);
		sheet.addProperty(DICE_KEY, dice);
		sheet.addProperty(TYPE_KEY, damageType);
		sheet.addProperty(ROUNDS_KEY, rounds);
	}

	/**
	 * <p>The active buff, or {@code null} if there is none. Unlike {@code PaladinSmiteManager.consumeIfPending}
	 * this does NOT consume anything: a timed buff applies to every hit in the round, and decrementing it
	 * here would limit it to a single hit, which is exactly the other one's mechanic.</p>
	 */
	public static Buff active(JsonObject sheet) {
		if (sheet == null || !sheet.has(DICE_KEY)) return null;
		if (!sheet.has(ROUNDS_KEY) || sheet.get(ROUNDS_KEY).getAsInt() <= 0) return null;
		return new Buff(
			sheet.has(NAME_KEY) ? sheet.get(NAME_KEY).getAsString() : "Buff",
			sheet.get(DICE_KEY).getAsString(),
			sheet.has(TYPE_KEY) ? sheet.get(TYPE_KEY).getAsString() : "force");
	}

	/** Decrements one round and clears the sheet if it expired. Returns true if it just expired. */
	public static boolean tickRound(JsonObject sheet) {
		if (sheet == null || !sheet.has(ROUNDS_KEY)) return false;
		int left = sheet.get(ROUNDS_KEY).getAsInt() - 1;
		if (left > 0) {
			sheet.addProperty(ROUNDS_KEY, left);
			return false;
		}
		clear(sheet);
		return true;
	}

	/** Also called when concentration is lost: these spells require it. */
	public static void clear(JsonObject sheet) {
		if (sheet == null) return;
		sheet.remove(DICE_KEY);
		sheet.remove(TYPE_KEY);
		sheet.remove(NAME_KEY);
		sheet.remove(ROUNDS_KEY);
	}
}
