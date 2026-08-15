package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

/**
 * <p>Buffs de arma con duración: un hechizo que suma dados de daño a <em>cada</em> golpe con arma mientras
 * dura (Favor Divino, Castigo Marcador). No confundir con {@link PaladinSmiteManager}, que es un rider de
 * un solo uso — este se gasta por asaltos, no por golpe.</p>
 *
 * <p>Se guarda en la hoja y no en un mapa en memoria por la misma razón que las condiciones: es estado del
 * personaje, tiene que sobrevivir a una reconexión, y el mod ya tuvo una vez el problema de perder cambios
 * que solo vivían en RAM.</p>
 *
 * <p>La duración se descuenta en asaltos completos, no en ticks reales, igual que la Furia del bárbaro:
 * un buff de "1 minuto" son 10 asaltos, y contarlo en segundos reales no significaría nada en una mesa
 * donde un turno tarda lo que tarde el jugador en decidir.</p>
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
	 * <p>El buff activo, o {@code null} si no hay. A diferencia de {@code PaladinSmiteManager.consumeIfPending}
	 * esto NO consume nada: un buff de duración se aplica a todos los golpes del asalto, y descontarlo aquí
	 * lo dejaría en un solo golpe, que es justo la mecánica del otro.</p>
	 */
	public static Buff active(JsonObject sheet) {
		if (sheet == null || !sheet.has(DICE_KEY)) return null;
		if (!sheet.has(ROUNDS_KEY) || sheet.get(ROUNDS_KEY).getAsInt() <= 0) return null;
		return new Buff(
			sheet.has(NAME_KEY) ? sheet.get(NAME_KEY).getAsString() : "Buff",
			sheet.get(DICE_KEY).getAsString(),
			sheet.has(TYPE_KEY) ? sheet.get(TYPE_KEY).getAsString() : "fuerza");
	}

	/** Descuenta un asalto y limpia la hoja si expiró. Devuelve true si acaba de expirar. */
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

	/** Se llama también al perder la concentración: estos hechizos la requieren. */
	public static void clear(JsonObject sheet) {
		if (sheet == null) return;
		sheet.remove(DICE_KEY);
		sheet.remove(TYPE_KEY);
		sheet.remove(NAME_KEY);
		sheet.remove(ROUNDS_KEY);
	}
}
