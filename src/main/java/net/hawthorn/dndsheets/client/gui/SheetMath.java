package net.hawthorn.dndsheets.client.gui;

import com.google.gson.JsonObject;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Display arithmetic for the sheet screens: ability modifiers and the flat bonus of a roll expression
 * ("1d20 + $dex + $prof + 2" is dex + proficiency + 2). Only for printing next to a die: the roll itself is always
 * resolved by the server from the same expression.</p>
 */
public final class SheetMath {
	private static final String[] ABILITY_KEYS = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};
	private static final String[] ABBR = {"str", "dex", "con", "int", "wis", "cha"};
	private static final Pattern TERM = Pattern.compile("([+-]?)([^+-]+)");

	private SheetMath() {
	}

	public static int modOf(int score) {
		return Math.floorDiv(score - 10, 2);
	}

	public static String signed(int value) {
		return value >= 0 ? "+" + value : Integer.toString(value);
	}

	public static int score(JsonObject sheet, String key) {
		if (sheet == null || !sheet.has(key)) return 10;
		try {
			return Integer.parseInt(sheet.get(key).getAsString().trim());
		} catch (RuntimeException e) {
			return 10;
		}
	}

	public static int proficiency(int level) {
		return 2 + (Math.max(1, level) - 1) / 4;
	}

	public static int bonusOf(JsonObject sheet, int level, String expression) {
		if (expression == null) return 0;
		String text = expression.toLowerCase(Locale.ROOT).replace(" ", "");
		int proficiency = proficiency(level);
		int total = 0;
		Matcher parts = TERM.matcher(text);
		while (parts.find()) {
			int sign = "-".equals(parts.group(1)) ? -1 : 1;
			String term = parts.group(2);
			int value = 0;
			if (term.matches("\\d*d\\d+")) value = 0; //The die itself.
			else if (term.startsWith("$hprof")) value = proficiency / 2;
			else if (term.startsWith("$prof")) value = proficiency;
			else if (term.startsWith("$")) {
				for (int i = 0; i < 6; i++) if (term.startsWith("$" + ABBR[i])) value = modOf(score(sheet, ABILITY_KEYS[i]));
			} else {
				try {
					value = Integer.parseInt(term);
				} catch (NumberFormatException ignored) {
					value = 0;
				}
			}
			total += sign * value;
		}
		return total;
	}
}
