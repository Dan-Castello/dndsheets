package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * <p>Últimas tiradas de la partida, en memoria: el desglose completo que {@link DiceManager.RollOutcome}
 * ya calcula (el mismo texto que se ve en el chat), pero sin perderse en el scroll. Enganchado en el único
 * punto de entrada de toda tirada, {@link DiceManager#roll} — no hace falta interceptar cada uno de sus
 * llamadores por separado.</p>
 *
 * <p><b>ponytail:</b> sin quién tira cuando el llamador no pasa una hoja con {@code characterName} (un
 * monstruo, casi siempre, invocado con un {@code JsonObject} vacío) — se guarda como {@code "?"} en vez de
 * nada. Pasarle la identidad real a {@code DiceManager.roll} pediría tocar las docenas de sitios que ya lo
 * llaman hoy; el desglose de la tirada en sí (lo que más se pierde sin log) ya queda capturado igual.
 * Subir a esto cuando alguien lo eche de menos en una mesa de verdad.</p>
 *
 * <p>Solo en memoria, mismo criterio que {@link TurnManager}: es un historial de la SESIÓN, no del
 * personaje, así que no hace falta que sobreviva a un reinicio del servidor.</p>
 */
public final class RollLog {
	private RollLog() {}

	public record Entry(String actor, String formatted, long timestampMillis) {}

	private static final int MAX_ENTRIES = 200;
	private static final Deque<Entry> entries = new ArrayDeque<>();

	public static synchronized void record(JsonObject sheet, DiceManager.RollOutcome outcome) {
		if (outcome.result() == null || outcome.formatted() == null) return; //Tirada fallida: nada que anotar.
		String actor = sheet != null && sheet.has("characterName") ? sheet.get("characterName").getAsString() : "?";
		entries.addLast(new Entry(actor, outcome.formatted(), System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) entries.removeFirst();
	}

	/** Las últimas tiradas, más reciente primero. */
	public static synchronized List<Entry> recent() {
		List<Entry> copy = new ArrayList<>(entries);
		Collections.reverse(copy);
		return copy;
	}
}
