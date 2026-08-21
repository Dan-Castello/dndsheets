
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


//Interno: no forma parte de la API pública versionada del mod (ver net.hawthorn.dndsheets.api.DndSheetsApi
//y su API_VERSION). Un mod externo que llame estos métodos directo en vez de a través de la fachada se
//expone a que cambien de firma sin aviso.
public class DiceManager {

	//Motor de TODAS las tiradas del mod: estos Pattern/Logger se compilaban/re-obtenían en cada tirada
	//(cada golpe, cada tirada de habilidad), así que se cachean una sola vez como campos estáticos.
	private static final Logger LOGGER = LogManager.getLogger(DndsheetsMod.MODID);
	private static final Pattern BRACKETED_DIE_PATTERN = Pattern.compile("\\[(\\d+)]");
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
		if ("ventaja".equalsIgnoreCase(label)) return Advantage.ADVANTAGE;
		if ("desventaja".equalsIgnoreCase(label)) return Advantage.DISADVANTAGE;
		return Advantage.NORMAL;
	}

	/**
	 * <p>Junta ventaja/desventaja de varias fuentes a la vez (el {@code /dndsheet advantage} del DM, las
	 * condiciones del atacante, las del objetivo). En 5e no se acumulan: por muchas de cada lado que haya,
	 * basta una de cada para que se anulen entre sí y quede una tirada normal.</p>
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

	//Tirada de ataque (siempre "1d20 + ..."): con ventaja/desventaja se tira dos veces la expresión
	//completa y se queda con el total mayor/menor, que es equivalente a comparar solo el d20 porque el
	//modificador no cambia entre una tirada y otra. El crítico se detecta mirando el primer dado
	//realmente tirado dentro de la tirada elegida (ver firstDieValue).
	public static AttackRoll rollAttack(JsonObject sheet, String expression, Advantage advantage) {
		RollOutcome first = roll(sheet, expression);
		if (advantage == Advantage.NORMAL) return toAttackRoll(sheet, first);

		RollOutcome second = roll(sheet, expression);
		if (first.result() == null) return toAttackRoll(sheet, second);
		if (second.result() == null) return toAttackRoll(sheet, first);

		boolean keepFirst = advantage == Advantage.ADVANTAGE
			? first.result().getValue() >= second.result().getValue()
			: first.result().getValue() <= second.result().getValue();
		RollOutcome kept = keepFirst ? first : second;
		RollOutcome discarded = keepFirst ? second : first;
		String label = advantage == Advantage.ADVANTAGE ? "ventaja" : "desventaja";
		return toAttackRoll(sheet, new RollOutcome(kept.result(), kept.formatted() + " (" + label + ", se descarta " + discarded.formatted() + ")"));
	}

	private static AttackRoll toAttackRoll(JsonObject sheet, RollOutcome outcome) {
		if (outcome.result() == null) return new AttackRoll(outcome, false, false);
		int natural = firstDieValue(outcome.result());
		//El 1 natural sigue siendo pifia pase lo que pase: ampliar el rango de crítico no estrecha el de
		//fallo, y en 5e no hay nada que lo haga.
		return new AttackRoll(outcome, natural >= criticalFrom(sheet), natural == 1);
	}

	/**
	 * <p>Desde qué dado natural critica esta ficha. 20 salvo que algo lo baje: hoy solo el Campeón del
	 * guerrero (19), que es el rasgo de subclase del SRD que este motor puede sostener sin inventar nada.</p>
	 *
	 * <p>Se lee de la hoja y no de la subclase para que el motor no tenga que saber qué es una subclase: la
	 * regla vive en un sitio y quien la conceda solo escribe un número, igual que hace el pacto del brujo.
	 * El tope inferior es un cortafuegos, no una regla: un 2 escrito en un JSON convertiría cada ataque en
	 * crítico, y eso se descubriría en mitad de un combate.</p>
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
		return new DamageResult(outcome.result().getValue() + diceSum, outcome.formatted() + " ¡CRÍTICO! (+" + diceSum + ")");
	}

	//El pretty-printer de la librería muestra cada dado tirado entre corchetes (p.ej. "[15] + 3 = 18").
	//El primer corchete siempre corresponde al d20 en una tirada de ataque ("1d20 + ..."), así que basta
	//con leerlo para saber si salió natural 20/1, sin recorrer el árbol interno de DiceResult.
	private static int firstDieValue(DiceResult result) {
		Matcher m = BRACKETED_DIE_PATTERN.matcher(new DiceResultPrettyPrinter().prettyPrint(result));
		return m.find() ? Integer.parseInt(m.group(1)) : -1;
	}

	//Suma todos los dados tirados (todos los corchetes), para poder doblar solo la parte de dados de una
	//tirada de daño en un crítico sin doblar también el modificador plano.
	private static int sumDiceValues(DiceResult result) {
		Matcher m = BRACKETED_DIE_PATTERN.matcher(new DiceResultPrettyPrinter().prettyPrint(result));
		int sum = 0;
		while (m.find()) sum += Integer.parseInt(m.group(1));
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
			return new RollOutcome(result, prettyPrintWithNotation(result, expression));
		} catch (Throwable e) {
			//Throwable, no solo Exception: un conteo de dados absurdo en un JSON de contenido (arma,
			//hechizo, monstruo, rasgo) puede hacer que la librería de dados reserve memoria sin límite y
			//tire OutOfMemoryError, que es un Error, no una Exception — un catch (Exception e) no lo
			//atrapaba, y eso tumbaba el hilo del servidor entero. hasAbsurdDiceCount ya corta el caso común
			//antes de llegar aquí; este catch es el respaldo para cualquier otro fallo raro de la librería.
			LOGGER.log(Level.INFO, "Some roll turned up an error, so it will be ignored.");
			return new RollOutcome(null, null);
		}

	}

	//Techo defensivo: una expresión como "999999999d6" no es un error de sintaxis (parsea bien), pero hace
	//que la librería de dados reserve un resultado por cada dado y agote la memoria. Cualquier "Nd..." con
	//N por encima del techo se rechaza antes de intentar tirarlo, en vez de confiar solo en el catch de arriba.
	private static final long MAX_DICE_COUNT = 10_000;

	private static boolean hasAbsurdDiceCount(String expression) {
		Matcher m = ABSURD_DICE_COUNT_PATTERN.matcher(expression);
		while (m.find()) {
			try {
				if (Long.parseLong(m.group(1)) > MAX_DICE_COUNT) return true;
			} catch (NumberFormatException e) {
				return true; //Ni siquiera cabe en un long: seguro que es absurdo.
			}
		}
		return false;
	}

	//Workaround de un bug de precedencia en la librería de dados de terceros (io.github.tfriedrichs:dicebot,
	//ver build.gradle): su gramática solo deja que un grupo de dados con conteo explícito ("1d4") aparezca
	//como el PRIMER término de toda la expresión — cualquiera que venga después de un +/-/*// no consigue
	//parsear (la gramática le da menos precedencia que a la suma/resta) y la tirada entera falla en
	//silencio, cae al catch de abajo con un resultado nulo (ver README, sección "Known Bugs": "1d20 + 1d4"
	//no tiraba bien). Encerrar cada grupo de dados entre paréntesis lo esquiva sin tocar la librería: dentro
	//de un paréntesis la precedencia se reinicia, así que "1d8 + 1d4" se manda como "1d8 + (1d4)" y parsea
	//normal. No reordena nada — DICE_NOTATION_PATTERN sigue encontrando los grupos en el mismo orden en el
	//texto, así que prettyPrintWithNotation (más abajo) no se ve afectado.
	private static String wrapDiceTermsInParens(String expression) {
		return DICE_NOTATION_PATTERN.matcher(expression).replaceAll("($0)");
	}

	//ponytail: asume que las tiradas de dado aparecen en el mismo orden, de izquierda a derecha, en el
	//texto de la expresión y en el árbol de resultados. Vale para las expresiones simples de esta hoja
	//(p.ej. "1d10 + 3"); si algún día se admiten paréntesis que reordenen los términos, revisar esto.
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
