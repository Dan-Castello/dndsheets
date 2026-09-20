
// https://mvnrepository.com/artifact/com.bernardomg.tabletop/dice
package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import io.github.tfriedrichs.dicebot.result.DiceResult;
import io.github.tfriedrichs.dicebot.result.DiceResultPrettyPrinter;
import io.github.tfriedrichs.dicebot.expression.DiceExpression;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines that not a single caller used, not even the addons, which come in through here).
//An external mod calling these methods is exposed to their signature changing without notice. The only
//thing intended for external consumption is the api/event events, which do have real consumers.
public class DiceManager {

	//Engine behind ALL of the mod's rolls: these Pattern/Logger objects used to be compiled/re-fetched on
	//every roll (every hit, every skill check), so they're cached once as static fields.
	private static final Logger LOGGER = LogManager.getLogger(DndsheetsMod.MODID);
	//The pretty-printer puts the dice of a group IN BRACKETS, comma-separated when there's more than
	//one: "1d1" comes out as "1 = 1[1]", but "2d6 + 1" comes out as "5 = 4[1, 3] + 1". The old pattern was
	//\[(\d+)] — a single number — so as soon as there were two dice it did NOT match and added zero. That
	//meant a critical hit doubled NOTHING on any multi-die weapon: greatsword and maul (2d6), and the 2d8/2d10
	//monster attacks, hit on a critical exactly the same as on a normal hit.
	private static final Pattern BRACKETED_DIE_PATTERN = Pattern.compile("\\[([\\d,\\s]+)]");
	private static final Pattern ABSURD_DICE_COUNT_PATTERN = Pattern.compile("(\\d+)d\\d");
	private static final Pattern DICE_NOTATION_PATTERN = Pattern.compile("\\d*d\\d+");
	private static final Pattern BRACKETED_VALUE_PATTERN = Pattern.compile("\\[[^\\]]*]");

	/**
	 * @param result the rolled outcome, or null if the expression failed to parse/roll.
	 * @param formatted a pretty-printed string with the dice notation (e.g. "1d10") shown inside
	 *                  the brackets instead of the individual rolled values, or null if {@code result} is null.
	 */
	public record RollOutcome(DiceResult result, String formatted) {}

	public enum Advantage { NORMAL, ADVANTAGE, DISADVANTAGE }

	public static Advantage advantageFromLabel(String label) {
		if ("advantage".equalsIgnoreCase(label) || "ventaja".equalsIgnoreCase(label)) return Advantage.ADVANTAGE;
		if ("disadvantage".equalsIgnoreCase(label) || "desventaja".equalsIgnoreCase(label)) return Advantage.DISADVANTAGE;
		return Advantage.NORMAL;
	}

	/**
	 * <p>Combines advantage/disadvantage from several sources at once (the DM's
	 * {@code /dndsheet advantage}, the attacker's conditions, the target's). In 5e they don't stack:
	 * no matter how many there are on each side, one of each is enough to cancel out and leave a normal roll.</p>
	 */
	public static Advantage combineAdvantage(Advantage... sources) {
		boolean advantage = false;
		boolean disadvantage = false;
		for (Advantage source : sources) {
			if (source == Advantage.ADVANTAGE) advantage = true;
			if (source == Advantage.DISADVANTAGE) disadvantage = true;
		}
		if (advantage == disadvantage) return Advantage.NORMAL;
		return advantage ? Advantage.ADVANTAGE : Advantage.DISADVANTAGE;
	}

	/**
	 * @param outcome the (possibly advantage/disadvantage-adjusted) roll, formatted for chat.
	 * @param criticalHit true on a natural 20: auto-hits, and damage should be rolled via {@link #rollDamage} with {@code critical=true}.
	 * @param criticalMiss true on a natural 1: auto-misses regardless of the total.
	 */
	public record AttackRoll(RollOutcome outcome, boolean criticalHit, boolean criticalMiss) {}

	//Attack roll (always "1d20 + ..."): with advantage/disadvantage the whole expression is rolled twice
	//and the higher/lower total is kept, which is equivalent to comparing only the d20 since the
	//modifier doesn't change between rolls. The critical is detected by looking at the first die
	//actually rolled within the chosen roll (see firstDieValue).
	public static AttackRoll rollAttack(JsonObject sheet, String expression, Advantage advantage) {
		return toAttackRoll(sheet, rollWithAdvantage(sheet, expression, advantage));
	}

	/**
	 * <p>The "roll twice, keep higher/lower" of advantage/disadvantage, without the critical wrapper from
	 * {@link #rollAttack} — the same 5e mechanic for ANY d20 (attack, save, ability check), split out
	 * from here so an ability check (which has no automatic critical in the SRD) can use it without
	 * inheriting {@code criticalHit}/{@code criticalMiss}, which only mean something on an attack. See
	 * {@code VisionManager}/{@code RollAnnouncerProcedure} for the first use outside of attacks:
	 * disadvantage on Perception from dim light.</p>
	 */
	public static RollOutcome rollWithAdvantage(JsonObject sheet, String expression, Advantage advantage) {
		//It has to HAVE a die. The library doesn't reject an expression it doesn't understand: it still
		//produces a number, and that number went in as if it were the d20 — a misspelled die in a content
		//pack gave an AUTOMATIC CRITICAL every time that number landed on 20 (relevant only to rollAttack,
		//but the "there's an actual die" filter applies the same to any d20). It's filtered with the same
		//pattern the parser itself already uses, so what counts as "a die" here and what gets rolled
		//afterward can't drift apart.
		if (!DICE_NOTATION_PATTERN.matcher(expression.toLowerCase()).find()) {
			return roll(sheet, expression);
		}

		RollOutcome first = roll(sheet, expression);
		if (advantage == Advantage.NORMAL) return first;

		RollOutcome second = roll(sheet, expression);
		if (first.result() == null) return second;
		if (second.result() == null) return first;

		boolean keepFirst = advantage == Advantage.ADVANTAGE
			? first.result().getValue() >= second.result().getValue()
			: first.result().getValue() <= second.result().getValue();
		RollOutcome kept = keepFirst ? first : second;
		RollOutcome discarded = keepFirst ? second : first;
		String label = advantage == Advantage.ADVANTAGE ? "advantage" : "disadvantage";
		return new RollOutcome(kept.result(), kept.formatted() + " (" + label + ", discarding " + discarded.formatted() + ")");
	}

	private static AttackRoll toAttackRoll(JsonObject sheet, RollOutcome outcome) {
		if (outcome.result() == null) return new AttackRoll(outcome, false, false);
		int natural = firstDieValue(outcome.result());
		//An attack roll is ALWAYS 1d20, so the natural die can only fall between 1 and 20. Outside that
		//range there was no d20: the dice library doesn't reject an expression it doesn't understand, it
		//still produces a number, and that number went in here as if it were the die. A misspelled die in
		//a content pack gave an AUTOMATIC CRITICAL every time that number came out 20 or higher.
		if (natural < 1 || natural > 20) return new AttackRoll(outcome, false, false);
		//A natural 1 is still a miss no matter what: widening the critical range doesn't narrow the miss
		//range, and nothing in 5e does.
		return new AttackRoll(outcome, natural >= criticalFrom(sheet), natural == 1);
	}

	/**
	 * <p>From which natural die this sheet crits. 20 unless something lowers it: today only the Fighter's
	 * Champion subclass (19), which is the SRD subclass trait this engine can support without inventing anything.</p>
	 *
	 * <p>It's read from the sheet and not the subclass so the engine doesn't have to know what a subclass
	 * is: the rule lives in one place and whoever grants it just writes a number, the same way the
	 * warlock's pact does. The lower bound is a safety net, not a rule: a 2 written into a JSON would turn
	 * every attack into a critical, and that would be discovered in the middle of a fight.</p>
	 */
	static int criticalFrom(JsonObject sheet) {
		if (sheet == null || !sheet.has("criticalFrom")) return 20;
		try {
			return Math.min(20, Math.max(15, Integer.parseInt(sheet.get("criticalFrom").getAsString())));
		} catch (RuntimeException e) {
			return 20;
		}
	}

	/**
	 * @param amount final damage to apply (dice doubled on {@code critical}, modifier counted once).
	 */
	public record DamageResult(int amount, String formatted) {}

	public static DamageResult rollDamage(JsonObject sheet, String expression, boolean critical) {
		RollOutcome outcome = roll(sheet, expression);
		if (outcome.result() == null) return new DamageResult(0, null);
		if (!critical) return new DamageResult(outcome.result().getValue(), outcome.formatted());

		int diceSum = sumDiceValues(outcome.result());
		return new DamageResult(outcome.result().getValue() + diceSum, outcome.formatted() + " CRITICAL! (+" + diceSum + ")");
	}

	//The library's pretty-printer shows every rolled die in brackets (e.g. "[15] + 3 = 18"). The first
	//bracket always corresponds to the d20 in an attack roll ("1d20 + ..."), so it's enough to read it to
	//know whether it came up natural 20/1, without walking DiceResult's internal tree.
	private static int firstDieValue(DiceResult result) {
		Matcher m = BRACKETED_DIE_PATTERN.matcher(new DiceResultPrettyPrinter().prettyPrint(result));
		if (!m.find()) return -1;
		//The first of the group: an attack roll is always 1d20, but now the group can carry several
		//numbers, and the natural that decides critical/miss is the first one.
		return Integer.parseInt(m.group(1).split(",")[0].trim());
	}

	//Sums every die rolled (every bracket), so only the dice portion of a damage roll can be doubled on a
	//critical without also doubling the flat modifier.
	private static int sumDiceValues(DiceResult result) {
		Matcher m = BRACKETED_DIE_PATTERN.matcher(new DiceResultPrettyPrinter().prettyPrint(result));
		int sum = 0;
		while (m.find()) {
			for (String die : m.group(1).split(",")) sum += Integer.parseInt(die.trim());
		}
		return sum;
	}

	public static RollOutcome roll(JsonObject sheet, String expression) {
		LOGGER.log(Level.INFO, "Initial parse: " + expression);
		expression = expression.toLowerCase();
		try {
			int score, modifier;
			if (sheet.has("strength") && expression.contains("$str")) {
				score = Integer.parseInt(sheet.get("strength").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$str", String.valueOf(modifier));
			}
			if (sheet.has("dexterity") && expression.contains("$dex")) {
				score = Integer.parseInt(sheet.get("dexterity").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$dex", String.valueOf(modifier));
			}
			if (sheet.has("constitution") && expression.contains("$con")) {
				score = Integer.parseInt(sheet.get("constitution").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$con", String.valueOf(modifier));
			}
			if (sheet.has("intelligence") && expression.contains("$int")) {
				score = Integer.parseInt(sheet.get("intelligence").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$int", String.valueOf(modifier));
			}
			if (sheet.has("wisdom") && expression.contains("$wis")) {
				score = Integer.parseInt(sheet.get("wisdom").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$wis", String.valueOf(modifier));
			}
			if (sheet.has("charisma") && expression.contains("$cha")) {
				score = Integer.parseInt(sheet.get("charisma").getAsString());
				modifier = (int)Math.floor((double) (score - 10) / 2);
				expression = expression.replace("$cha", String.valueOf(modifier));
			}
			if (sheet.has("proficiencyBonus") && expression.contains("$prof")) {
				expression = expression.replace("$prof", sheet.get("proficiencyBonus").getAsString());
			}
			if (sheet.has("proficiencyBonus") && expression.contains("$hprof")) {
				modifier = Integer.parseInt(sheet.get("proficiencyBonus").getAsString()) / 2;
				expression = expression.replace("$hprof", String.valueOf(modifier));
			}
			LOGGER.log(Level.INFO, "Final roll: " + expression);
			if (hasAbsurdDiceCount(expression)) {
				LOGGER.log(Level.INFO, "Roll rejected, dice count too large: " + expression);
				return new RollOutcome(null, null);
			}
			expression = wrapDiceTermsInParens(expression);
			DiceExpression ex = DiceExpression.parse(expression);
			DiceResult result = ex.roll();
			RollOutcome outcome = new RollOutcome(result, prettyPrintWithNotation(result, expression));
			RollLog.record(sheet, outcome);
			return outcome;
		} catch (Throwable e) {
			//Throwable, not just Exception: an absurd dice count in a content JSON (weapon, spell,
			//monster, trait) can make the dice library allocate unbounded memory and throw
			//OutOfMemoryError, which is an Error, not an Exception — a catch (Exception e) wouldn't
			//catch it, and that would crash the entire server thread. hasAbsurdDiceCount already cuts
			//off the common case before reaching here; this catch is the fallback for any other rare
			//library failure.
			LOGGER.log(Level.INFO, "Some roll turned up an error, so it will be ignored.");
			return new RollOutcome(null, null);
		}

	}

	//Defensive ceiling: an expression like "999999999d6" isn't a syntax error (it parses fine), but it
	//makes the dice library allocate one result per die and exhaust memory. Any "Nd..." with N above the
	//ceiling is rejected before attempting to roll it, instead of relying solely on the catch above.
	private static final long MAX_DICE_COUNT = 10_000;

	private static boolean hasAbsurdDiceCount(String expression) {
		Matcher m = ABSURD_DICE_COUNT_PATTERN.matcher(expression);
		while (m.find()) {
			try {
				if (Long.parseLong(m.group(1)) > MAX_DICE_COUNT) return true;
			} catch (NumberFormatException e) {
				return true; //Doesn't even fit in a long: definitely absurd.
			}
		}
		return false;
	}

	//Workaround for a precedence bug in the third-party dice library (io.github.tfriedrichs:dicebot,
	//see build.gradle): its grammar only lets a dice group with an explicit count ("1d4") appear as the
	//FIRST term of the whole expression — any that comes after a +/-/*// fails to parse (the grammar
	//gives it lower precedence than addition/subtraction) and the entire roll fails silently, falling
	//through to the catch below with a null result (see README, "Known Bugs" section: "1d20 + 1d4" didn't
	//roll correctly). Wrapping each dice group in parentheses sidesteps this without touching the
	//library: inside parentheses precedence resets, so "1d8 + 1d4" is sent as "1d8 + (1d4)" and parses
	//normally. It doesn't reorder anything — DICE_NOTATION_PATTERN still finds the groups in the same
	//order in the text, so prettyPrintWithNotation (below) isn't affected.
	private static String wrapDiceTermsInParens(String expression) {
		return DICE_NOTATION_PATTERN.matcher(expression).replaceAll("($0)");
	}

	//ponytail: assumes the dice rolls appear in the same order, left to right, in the expression's text
	//and in the result tree. Holds for this sheet's simple expressions (e.g. "1d10 + 3"); if parentheses
	//that reorder terms are ever supported, revisit this.
	private static String prettyPrintWithNotation(DiceResult result, String substitutedExpression) {
		String pretty = new DiceResultPrettyPrinter().prettyPrint(result);
		Matcher diceMatcher = DICE_NOTATION_PATTERN.matcher(substitutedExpression);
		Matcher bracketMatcher = BRACKETED_VALUE_PATTERN.matcher(pretty);

		StringBuilder out = new StringBuilder();
		int lastEnd = 0;
		while (bracketMatcher.find()) {
			out.append(pretty, lastEnd, bracketMatcher.start());
			String notation = diceMatcher.find() ? diceMatcher.group() : bracketMatcher.group().replaceAll("[\\[\\]]", "");
			out.append('[').append(notation).append(']');
			lastEnd = bracketMatcher.end();
		}
		out.append(pretty.substring(lastEnd));
		return out.toString();
	}
}
