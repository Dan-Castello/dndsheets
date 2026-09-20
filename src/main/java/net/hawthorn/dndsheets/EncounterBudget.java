package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>How heavy an encounter is for THIS party: the DMG's XP budget (per-level thresholds, multiplier by
 * monster count) reduced to the one word a DM actually looks at while building a fight —"Medium",
 * "Deadly"—. Without this, {@link EncounterRegistry} lets you assemble a group but says nothing about
 * whether it's a fight or a massacre, which is exactly the question encounter design asks.</p>
 *
 * <p><b>CR is estimated, not read.</b> The mod's bestiary carries no {@code cr} field — there are 330
 * blocks, and the ones a DM creates in-game would have none — so it's derived from the block itself using
 * the DMG's "creating a monster" table: HP and AC give the defensive CR, damage per round the offensive
 * one, and CR is the average of the two. It lands within one step of the CR published in the SRD for the
 * sample bestiary, which is plenty for choosing between "medium" and "hard."</p>
 */
public class EncounterBudget {

	//The CR steps in order: 0, 1/8, 1/4, 1/2, 1, 2, 3... Every table below shares this index, so a single
	//position covers XP, HP, damage, and expected AC.
	private static final int[] CR_XP = {10, 25, 50, 100, 200, 450, 700, 1100, 1800, 2300, 2900, 3900, 5000,
		5900, 7200, 8400, 10000, 11500, 13000, 15000, 18000, 20000, 22000, 25000, 33000, 41000, 50000,
		62000, 75000, 90000, 105000, 120000, 135000, 155000};
	/** Max HP of each step (defensive CR). */
	private static final int[] HP_MAX = {6, 35, 49, 70, 85, 100, 115, 130, 145, 160, 175, 190, 205, 220, 235,
		250, 265, 280, 295, 310, 325, 340, 355, 400, 445, 490, 535, 580, 625, 670, 715, 760, 805, 850};
	/** Max damage per round of each step (offensive CR). */
	private static final int[] DAMAGE_MAX = {1, 3, 5, 8, 14, 20, 26, 32, 38, 44, 50, 56, 62, 68, 74, 80, 86,
		92, 98, 104, 110, 116, 122, 140, 158, 176, 194, 212, 230, 248, 266, 284, 302, 320};
	/** AC expected at each step: two points above or below shifts CR by one step. */
	private static final int[] EXPECTED_AC = {13, 13, 13, 13, 13, 13, 13, 14, 15, 15, 15, 16, 16, 17, 17, 17,
		18, 18, 18, 18, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19};

	/** XP thresholds per character and level (1..20): easy, medium, hard, deadly. */
	private static final int[][] THRESHOLDS = {
		{25, 50, 75, 100}, {50, 100, 150, 200}, {75, 150, 225, 400}, {125, 250, 375, 500},
		{250, 500, 750, 1100}, {300, 600, 900, 1400}, {350, 750, 1100, 1700}, {450, 900, 1400, 2100},
		{550, 1100, 1600, 2400}, {600, 1200, 1900, 2800}, {800, 1600, 2400, 3600}, {1000, 2000, 3000, 4500},
		{1100, 2200, 3400, 5100}, {1250, 2500, 3800, 5700}, {1400, 2800, 4300, 6400}, {1600, 3200, 4800, 7200},
		{2000, 3900, 5900, 8800}, {2100, 4200, 6300, 9500}, {2400, 4900, 7300, 10900}, {2800, 5700, 8500, 12700}};

	/** Multiplier by monster count; a small or large party moves it up or down a row (DMG). */
	private static final double[] MULTIPLIERS = {1, 1.5, 2, 2.5, 3, 4};

	/** The five verdict words, from lowest to highest: a language key with no prefix. */
	public static final String[] RATINGS = {"trivial", "easy", "medium", "hard", "deadly"};

	private EncounterBudget() {}

	/** Monster's XP by its block's estimated CR. A nonexistent id is worth 0: it adds nothing to the budget. */
	public static int xp(String monsterId) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		return block == null ? 0 : xp(block);
	}

	public static int xp(MonsterRegistry.MonsterStatBlock block) {
		int defensive = step(HP_MAX, block.maxHp());
		//AC adjusts the step HP already gave, not a step of its own: in the DMG it's a modifier, not a
		//third CR to average in.
		defensive = clamp(defensive + (block.ac() - EXPECTED_AC[defensive]) / 2);
		int offensive = step(DAMAGE_MAX, damagePerRound(block));
		return CR_XP[clamp(Math.round((defensive + offensive) / 2f))];
	}

	/**
	 * <p>The best attack times the number of attacks per turn, or the best spell if it hits harder. It
	 * doesn't add up different attacks or count legendary actions: the DMG asks for "the most damage it
	 * can deal in one round," and a mod block just repeats its best attack instead of combining two.</p>
	 */
	static int damagePerRound(MonsterRegistry.MonsterStatBlock block) {
		double best = 0;
		for (MonsterRegistry.MonsterAttack attack : block.attacks()) {
			double damage = averageDice(attack.dice()) + block.abilityModifier(attack.damageAbility());
			best = Math.max(best, Math.max(0, damage) * Math.max(1, block.attacksPerTurn()));
		}
		for (MonsterRegistry.MonsterSpell spell : block.spells()) {
			best = Math.max(best, averageDice(spell.dice()));
		}
		//Rounded down, like every 5e average: a 1d6+2 attack is published as "5 (1d6+2)," not 5.5 — and
		//rounding up would bump the goblin a whole CR step.
		return (int) Math.floor(best);
	}

	/** Average of an expression like "2d6+3". Anything not understood is worth 0, same as the rest of the mod when parsing. */
	static double averageDice(String expression) {
		if (expression == null || expression.isBlank()) return 0;
		double total = 0;
		//Split while keeping the sign so subtraction works: "1d8-1" is one 1d8 term and one -1 term.
		for (String term : expression.replace("-", "+-").replace(" ", "").split("\\+")) {
			if (term.isEmpty()) continue;
			boolean negative = term.startsWith("-");
			if (negative) term = term.substring(1);
			double value;
			try {
				int d = term.indexOf('d');
				if (d < 0) {
					value = Integer.parseInt(term);
				} else {
					int count = d == 0 ? 1 : Integer.parseInt(term.substring(0, d));
					value = count * (Integer.parseInt(term.substring(d + 1)) + 1) / 2.0;
				}
			} catch (NumberFormatException e) {
				continue;
			}
			total += negative ? -value : value;
		}
		return total;
	}

	/** The party's four thresholds (easy, medium, hard, deadly), summing each character's own. */
	public static int[] thresholds(List<Integer> partyLevels) {
		int[] total = new int[4];
		for (int level : partyLevels) {
			int[] row = THRESHOLDS[Math.min(THRESHOLDS.length, Math.max(1, level)) - 1];
			for (int i = 0; i < 4; i++) total[i] += row[i];
		}
		return total;
	}

	/** Levels of the connected players with a loaded sheet: the real party the fight is measured against. */
	public static List<Integer> partyLevels(MinecraftServer server) {
		List<Integer> levels = new ArrayList<>();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			if (sheet == null) continue;
			levels.add(SheetLoader.characterLevelOf(sheet));
		}
		return levels;
	}

	public static double multiplier(int monsterCount, int partySize) {
		int row = monsterCount <= 1 ? 0 : monsterCount == 2 ? 1 : monsterCount <= 6 ? 2
			: monsterCount <= 10 ? 3 : monsterCount <= 14 ? 4 : 5;
		if (partySize > 0 && partySize < 3) row++;     //Fewer than three: the same monsters hit harder relative to them.
		else if (partySize > 5) row--;                 //Six or more: they split it among themselves.
		return MULTIPLIERS[Math.min(MULTIPLIERS.length - 1, Math.max(0, row))];
	}

	/**
	 * <p>The verdict: an index into {@link #RATINGS}. With no party connected (thresholds at zero) there's
	 * nothing to measure against and it returns -1 — the caller shows the composition with no word instead
	 * of lying with "deadly."</p>
	 */
	public static int rate(int totalXp, int monsterCount, int partySize, int[] thresholds) {
		if (thresholds[3] <= 0) return -1;
		double adjusted = totalXp * multiplier(monsterCount, partySize);
		for (int i = 3; i >= 0; i--) {
			if (adjusted >= thresholds[i]) return i + 1;
		}
		return 0;
	}

	/** Verdict of a saved encounter, measured against the connected players. */
	public static int rate(EncounterRegistry.Encounter encounter, MinecraftServer server) {
		int total = 0;
		for (EncounterRegistry.Member member : encounter.members()) total += xp(member.monsterId()) * member.count();
		List<Integer> levels = partyLevels(server);
		return rate(total, encounter.total(), levels.size(), thresholds(levels));
	}

	private static int step(int[] table, int value) {
		for (int i = 0; i < table.length; i++) {
			if (value <= table[i]) return i;
		}
		return table.length - 1;
	}

	private static int clamp(int crIndex) {
		return Math.min(CR_XP.length - 1, Math.max(0, crIndex));
	}
}
