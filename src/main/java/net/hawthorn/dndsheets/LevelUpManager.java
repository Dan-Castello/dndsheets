package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Locale;

/**
 * <p>Subir de nivel: lo que cambia solo, lo que hay que elegir, y contarlo.</p>
 *
 * <p>Casi todo lo que da un nivel en 5e ya se <b>derivaba</b> del campo {@code characterLevel} — puntos de
 * golpe máximos, bono de competencia, espacios de conjuro, los dados de Ataque Furtivo y Artes Marciales.
 * Lo que faltaba era lo único que no se puede derivar porque es una <b>decisión</b>: la Mejora de Puntuación
 * de Característica de los niveles 4, 8, 12, 16 y 19. Sin ella, un personaje de nivel 20 peleaba con las
 * características de uno de nivel 1, que es el número que más se nota de todos.</p>
 *
 * <p>Y faltaba <b>decirlo</b>. {@code /dndsheet setlevel 5} cambiaba media hoja en silencio: los PG máximos,
 * la competencia y los espacios se movían sin una línea en el chat. Subir de nivel es de los pocos momentos
 * de una campaña que la mesa celebra, y era el que menos se veía.</p>
 *
 * <p>Las mejoras pendientes se anotan <b>en la hoja</b> ({@code pendingAbilityImprovements}) y no en memoria:
 * así sobreviven a cerrar la pantalla, a desconectarse y a un reinicio del servidor, y así subir de golpe
 * del nivel 1 al 8 concede las dos que corresponden en vez de perder una. Es también lo que hace segura la
 * pantalla del cliente: el servidor solo aplica una mejora si de verdad quedaba alguna pendiente.</p>
 */
public class LevelUpManager {

	public static final int MAX_LEVEL = 20;
	/** Tope de 5e para una característica subida por mejoras. */
	public static final int MAX_ABILITY = 20;
	private static final String PENDING = "pendingAbilityImprovements";

	private static final String[] ABILITIES = {"strength", "dexterity", "constitution", "intelligence", "wisdom", "charisma"};

	/** Los niveles que conceden Mejora de Puntuación de Característica en 5e. */
	static boolean isImprovementLevel(int level) {
		return level == 4 || level == 8 || level == 12 || level == 16 || level == 19;
	}

	/** Cuántas mejoras se ganan al pasar de {@code from} a {@code to}. Bajar de nivel no quita ninguna. */
	static int improvementsBetween(int from, int to) {
		int count = 0;
		for (int level = from + 1; level <= to; level++) {
			if (isImprovementLevel(level)) count++;
		}
		return count;
	}

	public static int pendingOf(JsonObject sheet) {
		return sheet != null && sheet.has(PENDING) ? Math.max(0, sheet.get(PENDING).getAsInt()) : 0;
	}

	/**
	 * <p>Anota las mejoras que concede el salto de nivel. Se llama desde el ÚNICO sitio que cambia el nivel
	 * ({@code SheetCommand.applyLevel}), así que da igual si el cambio vino del comando o del Panel de DM.</p>
	 */
	public static void grantImprovementsFor(JsonObject sheet, int fromLevel, int toLevel) {
		int granted = improvementsBetween(fromLevel, toLevel);
		if (granted > 0) sheet.addProperty(PENDING, pendingOf(sheet) + granted);
	}

	/**
	 * <p>Sube un nivel y cuenta qué ha cambiado. Lo dispara el DM: en una mesa, quien reparte los niveles es
	 * quien lleva la partida — dejarlo en manos del jugador convertiría el nivel en un botón.</p>
	 */
	public static void levelUp(ServerPlayer target) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null) return;

		//characterLevelOf(sheet) —el explícito— y NO la sobrecarga con el jugador, que cae al nivel de XP de
		//Minecraft cuando la hoja no tiene nivel puesto. Ese fallback está bien para MOSTRAR un número
		//mientras el DM no fije uno, y es veneno para decidir el siguiente: un jugador que había picado
		//piedra hasta el nivel de XP 25 no podía subir de nivel nunca ("ya estás en el 20"), y uno con XP 7
		//saltaba de golpe al 8 la primera vez, con su Mejora de Característica incluida, por haber minado.
		//Un personaje al que nadie ha subido de nivel es de nivel 1, mine lo que mine.
		int before = Math.max(1, SheetLoader.characterLevelOf(sheet));
		if (before >= MAX_LEVEL) {
			target.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.at_max", MAX_LEVEL).withStyle(ChatFormatting.GRAY));
			return;
		}

		int hpBefore = intOf(sheet, "maxHp");
		int profBefore = intOf(sheet, "proficiencyBonus");
		int slotsBefore = intOf(sheet, "spellSlotsMax");

		//Un solo camino para cambiar de nivel: el mismo que usan el comando y el Panel de DM, que ya
		//re-deriva PG máximos, competencia y espacios. Duplicar esa derivación aquí sería la tercera copia.
		net.hawthorn.dndsheets.command.SheetCommand.applyLevel(target, before + 1);

		JsonObject updated = SheetLoader.getServerSheet(target.getStringUUID());
		String name = SheetLoader.characterNameOf(updated, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.announce", name, before + 1).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		reportGain(target, "chat.dndsheets.levelup.hp", hpBefore, intOf(updated, "maxHp"));
		reportGain(target, "chat.dndsheets.levelup.proficiency", profBefore, intOf(updated, "proficiencyBonus"));
		reportGain(target, "chat.dndsheets.levelup.slots", slotsBefore, intOf(updated, "spellSlotsMax"));

		if (pendingOf(updated) > 0) openImprovementScreen(target, pendingOf(updated));
	}

	//Solo se anuncia lo que de verdad subió: una lista donde tres de cuatro líneas dicen "sigue igual" hace
	//que la que sí cambió pase desapercibida.
	private static void reportGain(ServerPlayer target, String key, int before, int after) {
		if (after > before) {
			target.sendSystemMessage(Component.translatable(key, before, after).withStyle(ChatFormatting.GREEN));
		}
	}

	public static void openImprovementScreen(ServerPlayer target, int pending) {
		target.sendSystemMessage(Component.translatable("chat.dndsheets.levelup.improvement_pending", pending).withStyle(ChatFormatting.GOLD));
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.ScreenActionMessage(net.hawthorn.dndsheets.network.ScreenActionMessage.Action.ABILITY_IMPROVEMENT_OPEN));
	}

	/**
	 * <p>Aplica una mejora: +2 a una característica, o +1 a dos distintas. Devuelve false si no había ninguna
	 * pendiente o si la elección no es válida.</p>
	 *
	 * <p>Se valida <b>en el servidor</b> y no en la pantalla: un cliente puede mandar el mensaje que quiera,
	 * y "solo si te tocaba" es justo la clase de comprobación que no puede vivir donde el jugador manda.</p>
	 */
	public static boolean applyImprovement(ServerPlayer target, String firstAbility, String secondAbility) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || pendingOf(sheet) <= 0) return false;

		String first = normalize(firstAbility);
		String second = normalize(secondAbility);
		if (first == null) return false;
		//+2 a una, o +1 a DOS DISTINTAS: repetir la misma característica dos veces sería un +2 disfrazado
		//que además se salta el tope de una en una.
		if (second != null && second.equals(first)) return false;

		if (second == null) {
			if (!raise(sheet, first, 2)) return false;
		} else {
			//Las dos o ninguna: aplicar una y fallar la otra dejaría media mejora gastada.
			if (scoreOf(sheet, first) >= MAX_ABILITY || scoreOf(sheet, second) >= MAX_ABILITY) return false;
			raise(sheet, first, 1);
			raise(sheet, second, 1);
		}

		sheet.addProperty(PENDING, pendingOf(sheet) - 1);
		//La Constitución cambia los PG máximos, así que hay que volver a derivarlos: sin esto, subir CON
		//daba el modificador nuevo a todo menos a lo que la Constitución sirve para dar.
		SheetLoader.applyClassHitPoints(target, sheet);
		SheetLoader.saveServer(sheet, target.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.SheetClientMessage(sheet.toString().getBytes()));

		String name = SheetLoader.characterNameOf(sheet, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.improved", name,
			second == null ? label(first) + " +2" : label(first) + " +1, " + label(second) + " +1").withStyle(ChatFormatting.GREEN));
		if (pendingOf(sheet) > 0) openImprovementScreen(target, pendingOf(sheet));
		return true;
	}

	/**
	 * <p>Coger una dote <b>en vez de</b> la mejora de característica. Gasta la misma pendiente y por el mismo
	 * sitio: si fueran dos recursos distintos, un nivel 4 daría las dos cosas y la elección dejaría de serlo.</p>
	 *
	 * <p>Se valida en el servidor, igual que la mejora y por lo mismo: "solo si te tocaba" y "solo una vez"
	 * son comprobaciones que no pueden vivir donde el jugador manda.</p>
	 */
	public static boolean applyFeat(ServerPlayer target, String featId) {
		JsonObject sheet = SheetLoader.getServerSheet(target.getStringUUID());
		if (sheet == null || pendingOf(sheet) <= 0) return false;
		if (!FeatRegistry.grant(sheet, featId, MAX_ABILITY, SheetLoader.characterLevelOf(sheet))) return false;

		sheet.addProperty(PENDING, pendingOf(sheet) - 1);
		//Igual que la mejora: una dote que suba Constitución tiene que volver a derivar los PG máximos.
		SheetLoader.applyClassHitPoints(target, sheet);
		SheetLoader.saveServer(sheet, target.getStringUUID());
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> target),
			new net.hawthorn.dndsheets.network.SheetClientMessage(sheet.toString().getBytes()));

		String name = SheetLoader.characterNameOf(sheet, target);
		ChatFeedback.broadcast(target, Component.translatable("chat.dndsheets.levelup.feat_taken", name,
			FeatRegistry.get(featId).name()).withStyle(ChatFormatting.GREEN));
		if (pendingOf(sheet) > 0) openImprovementScreen(target, pendingOf(sheet));
		return true;
	}

	private static boolean raise(JsonObject sheet, String ability, int amount) {
		int score = scoreOf(sheet, ability);
		if (score >= MAX_ABILITY) return false;
		sheet.addProperty(ability, String.valueOf(Math.min(MAX_ABILITY, score + amount)));
		return true;
	}

	private static int scoreOf(JsonObject sheet, String ability) {
		if (!sheet.has(ability)) return 10;
		try {
			return Integer.parseInt(sheet.get(ability).getAsString());
		} catch (RuntimeException e) {
			//Una hoja vieja puede tener ahí cualquier cosa, incluso un objeto: 10 es la puntuación por
			//defecto de 5e y lo mismo que asume el resto del mod.
			return 10;
		}
	}

	//Solo se aceptan las seis: cualquier otra cosa que llegue por el cable no es una característica.
	private static String normalize(String raw) {
		if (raw == null || raw.isEmpty()) return null;
		String lower = raw.toLowerCase(Locale.ROOT);
		for (String ability : ABILITIES) {
			if (ability.equals(lower)) return ability;
		}
		return null;
	}

	private static String label(String ability) {
		return switch (ability) {
			case "strength" -> "Fuerza";
			case "dexterity" -> "Destreza";
			case "constitution" -> "Constitución";
			case "intelligence" -> "Inteligencia";
			case "wisdom" -> "Sabiduría";
			default -> "Carisma";
		};
	}

	private static int intOf(JsonObject sheet, String key) {
		if (sheet == null || !sheet.has(key)) return 0;
		try {
			return Integer.parseInt(sheet.get(key).getAsString());
		} catch (RuntimeException e) {
			return 0;
		}
	}
}
