package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Cuánto pesa un encuentro para ESTE grupo: el presupuesto de PX del DMG (umbrales por nivel,
 * multiplicador por cantidad de monstruos) reducido a la única palabra que un DM mira mientras arma una
 * pelea —"Media", "Mortal"—. Sin esto, {@link EncounterRegistry} deja componer un grupo pero no dice nada
 * sobre si es un combate o una masacre, que es justo la pregunta del diseño de encuentros.</p>
 *
 * <p><b>La VD se estima, no se lee.</b> El bestiario del mod no trae campo {@code cr} —son 330 bloques, y
 * los que crea el DM in-game no tendrían ninguno— así que se deduce del propio bloque con la tabla de
 * "crear un monstruo" del DMG: PG y CA dan la VD defensiva, el daño por asalto la ofensiva, y la VD es la
 * media de las dos. Cae a un escalón de la VD publicada en el SRD para el bestiario de ejemplo, que para
 * elegir entre "media" y "difícil" sobra.</p>
 */
public class EncounterBudget {

	//Los escalones de VD en orden: 0, 1/8, 1/4, 1/2, 1, 2, 3... Todas las tablas de abajo comparten este
	//índice, así que una sola posición sirve para PX, PG, daño y CA esperada.
	private static final int[] CR_XP = {10, 25, 50, 100, 200, 450, 700, 1100, 1800, 2300, 2900, 3900, 5000,
		5900, 7200, 8400, 10000, 11500, 13000, 15000, 18000, 20000, 22000, 25000, 33000, 41000, 50000,
		62000, 75000, 90000, 105000, 120000, 135000, 155000};
	/** PG máximos de cada escalón (VD defensiva). */
	private static final int[] HP_MAX = {6, 35, 49, 70, 85, 100, 115, 130, 145, 160, 175, 190, 205, 220, 235,
		250, 265, 280, 295, 310, 325, 340, 355, 400, 445, 490, 535, 580, 625, 670, 715, 760, 805, 850};
	/** Daño por asalto máximo de cada escalón (VD ofensiva). */
	private static final int[] DAMAGE_MAX = {1, 3, 5, 8, 14, 20, 26, 32, 38, 44, 50, 56, 62, 68, 74, 80, 86,
		92, 98, 104, 110, 116, 122, 140, 158, 176, 194, 212, 230, 248, 266, 284, 302, 320};
	/** CA que se espera en cada escalón: dos puntos por encima o por debajo mueven la VD un escalón. */
	private static final int[] EXPECTED_AC = {13, 13, 13, 13, 13, 13, 13, 14, 15, 15, 15, 16, 16, 17, 17, 17,
		18, 18, 18, 18, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19, 19};

	/** Umbrales de PX por personaje y nivel (1..20): fácil, media, difícil, mortal. */
	private static final int[][] THRESHOLDS = {
		{25, 50, 75, 100}, {50, 100, 150, 200}, {75, 150, 225, 400}, {125, 250, 375, 500},
		{250, 500, 750, 1100}, {300, 600, 900, 1400}, {350, 750, 1100, 1700}, {450, 900, 1400, 2100},
		{550, 1100, 1600, 2400}, {600, 1200, 1900, 2800}, {800, 1600, 2400, 3600}, {1000, 2000, 3000, 4500},
		{1100, 2200, 3400, 5100}, {1250, 2500, 3800, 5700}, {1400, 2800, 4300, 6400}, {1600, 3200, 4800, 7200},
		{2000, 3900, 5900, 8800}, {2100, 4200, 6300, 9500}, {2400, 4900, 7300, 10900}, {2800, 5700, 8500, 12700}};

	/** Multiplicador por cantidad de monstruos; un grupo pequeño o grande sube o baja una fila (DMG). */
	private static final double[] MULTIPLIERS = {1, 1.5, 2, 2.5, 3, 4};

	/** Las cinco palabras del veredicto, de menos a más: clave de idioma sin prefijo. */
	public static final String[] RATINGS = {"trivial", "facil", "media", "dificil", "mortal"};

	private EncounterBudget() {}

	/** PX del monstruo según la VD estimada de su bloque. Un id que no existe vale 0: no suma al presupuesto. */
	public static int xp(String monsterId) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(monsterId);
		return block == null ? 0 : xp(block);
	}

	public static int xp(MonsterRegistry.MonsterStatBlock block) {
		int defensive = step(HP_MAX, block.maxHp());
		//La CA ajusta el escalón que ya dieron los PG, no uno propio: en el DMG es un modificador, no una
		//tercera VD que promediar.
		defensive = clamp(defensive + (block.ac() - EXPECTED_AC[defensive]) / 2);
		int offensive = step(DAMAGE_MAX, damagePerRound(block));
		return CR_XP[clamp(Math.round((defensive + offensive) / 2f))];
	}

	/**
	 * <p>El mejor ataque por el número de ataques del turno, o el mejor conjuro si pega más. No suma
	 * ataques distintos ni cuenta acciones legendarias: el DMG pide "el mayor daño que puede hacer en un
	 * asalto", y un bloque del mod repite su mejor ataque en vez de combinar dos.</p>
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
		//Hacia abajo, como todas las medias de 5e: un ataque de 1d6+2 se publica como "5 (1d6+2)", no 5,5 —
		//y redondeando hacia arriba el goblin se salta un escalón entero de VD.
		return (int) Math.floor(best);
	}

	/** Media de una expresión "2d6+3". Lo que no se entiende vale 0, igual que el resto del mod al parsear. */
	static double averageDice(String expression) {
		if (expression == null || expression.isBlank()) return 0;
		double total = 0;
		//Se parte conservando el signo para poder restar: "1d8-1" son un término de 1d8 y otro de -1.
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

	/** Los cuatro umbrales del grupo (fácil, media, difícil, mortal), sumando los de cada personaje. */
	public static int[] thresholds(List<Integer> partyLevels) {
		int[] total = new int[4];
		for (int level : partyLevels) {
			int[] row = THRESHOLDS[Math.min(THRESHOLDS.length, Math.max(1, level)) - 1];
			for (int i = 0; i < 4; i++) total[i] += row[i];
		}
		return total;
	}

	/** Niveles de los jugadores conectados con hoja cargada: el grupo real contra el que se mide la pelea. */
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
		if (partySize > 0 && partySize < 3) row++;     //Menos de tres: el mismo grupo pega más fuerte.
		else if (partySize > 5) row--;                 //Seis o más: se lo reparten.
		return MULTIPLIERS[Math.min(MULTIPLIERS.length - 1, Math.max(0, row))];
	}

	/**
	 * <p>El veredicto: índice en {@link #RATINGS}. Sin grupo conectado (umbrales a cero) no hay contra qué
	 * medir y devuelve -1 — el llamador enseña la composición sin palabra en vez de mentir con "mortal".</p>
	 */
	public static int rate(int totalXp, int monsterCount, int partySize, int[] thresholds) {
		if (thresholds[3] <= 0) return -1;
		double adjusted = totalXp * multiplier(monsterCount, partySize);
		for (int i = 3; i >= 0; i--) {
			if (adjusted >= thresholds[i]) return i + 1;
		}
		return 0;
	}

	/** Veredicto de un encuentro guardado, medido contra los jugadores conectados. */
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
