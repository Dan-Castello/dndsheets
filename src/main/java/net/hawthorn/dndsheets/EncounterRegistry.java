package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <p>Encuentros: un grupo de monstruos con nombre, guardado antes de la sesión y soltado entero de una
 * vez. Es el bucle de preparación de un DM, que hasta ahora no existía — se invocaba monstruo a monstruo,
 * lo cual va bien para enseñar el mod y fatal para una noche de juego con cuatro combates preparados.</p>
 *
 * <p><b>No trae ninguna regla nueva.</b> Invoca con {@link MonsterRegistry#spawnAt} y ahí se acaba: la
 * iniciativa la sigue arrancando el primer golpe ({@code CombatManager.autoStartCombatIfNeeded}, que ya
 * recoge a todo el que esté en el radio), así que un encuentro no tiene que saber nada de turnos. Lo único
 * que aporta es <em>a la vez y donde tú digas</em>.</p>
 *
 * <p><b>La composición se escribe como texto</b> ({@code "dndsheets:goblin x4"}) y no como un objeto con
 * campos. Es el mismo formato en el JSON y en la casilla del creador in-game, así que hay un solo parser y
 * una sola sintaxis que aprender; con objetos habría dos formas de decir lo mismo y una conversión entre
 * ellas. Sin {@code xN} es uno.</p>
 */
public class EncounterRegistry {

	/** Cuánto se separan los monstruos entre sí al soltarlos, en bloques. */
	private static final double RING_SPACING = 1.6;

	public record Member(String monsterId, int count) {}

	public record Encounter(String id, String name, List<Member> members) {
		public int total() {
			int total = 0;
			for (Member member : members) total += member.count();
			return total;
		}
	}

	private static final NamedRegistry<Encounter> REGISTRY = new NamedRegistry<>("encuentro", Encounter::id);

	public static void register(Encounter encounter) {
		REGISTRY.register(encounter);
	}

	public static Encounter get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	private static final JsonRegistryLoader<Encounter> LOADER =
		new JsonRegistryLoader<>("encuentro", EncounterRegistry::parse, EncounterRegistry::register);

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static int loadJson(JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static Encounter parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;

		List<Member> members = new ArrayList<>();
		if (json.has("monsters")) {
			JsonArray array = json.getAsJsonArray("monsters");
			for (JsonElement element : array) {
				Member member = parseMember(element.getAsString());
				if (member != null) members.add(member);
			}
		}
		return new Encounter(id, name, members);
	}

	/**
	 * <p>{@code "dndsheets:goblin x4"} → cuatro goblins. Sin la cola, uno.</p>
	 *
	 * <p>Una línea que no se entiende devuelve {@code null} y se salta, en vez de reventar el encuentro
	 * entero: es la misma decisión que ya toma {@link JsonRegistryLoader} con cada entrada de un archivo —
	 * perder un monstruo de una emboscada es recuperable, perder el archivo del DM entero no.</p>
	 */
	static Member parseMember(String text) {
		if (text == null) return null;
		String trimmed = text.trim();
		if (trimmed.isEmpty()) return null;

		int split = trimmed.lastIndexOf(" x");
		if (split < 0) return new Member(trimmed, 1);

		try {
			int count = Integer.parseInt(trimmed.substring(split + 2).trim());
			//Cero o negativo no es "ninguno", es una errata: un encuentro con una línea que no invoca nada
			//se lee como un encuentro roto, así que se trata como uno solo y el DM ve lo que escribió.
			return new Member(trimmed.substring(0, split).trim(), Math.max(1, count));
		} catch (NumberFormatException e) {
			//"dragón x viejo" no es una cuenta: el nombre se queda entero.
			return new Member(trimmed, 1);
		}
	}

	/** Cómo se lee un encuentro en el chat y en las listas: "Goblin x4, Lobo x2". */
	public static String describe(Encounter encounter) {
		StringBuilder text = new StringBuilder();
		for (Member member : encounter.members()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(member.monsterId());
			if (text.length() > 0) text.append(", ");
			text.append(block != null ? block.name() : member.monsterId());
			if (member.count() > 1) text.append(" x").append(member.count());
		}
		return text.length() == 0 ? "(vacío)" : text.toString();
	}

	/**
	 * <p>Suelta el encuentro entero alrededor de {@code center}, repartido en círculo para que no salgan
	 * todos apilados en el mismo bloque —que es lo que hace {@code /dndmonsters spawn} con una cantidad, y
	 * deja un montón imposible de señalar con la vara.</p>
	 *
	 * @return cuántos se invocaron de verdad; menos que el total significa que algún id no existe.
	 */
	public static int spawn(ServerLevel level, Vec3 center, Encounter encounter) {
		int total = encounter.total();
		double radius = total <= 1 ? 0 : RING_SPACING * total / (2 * Math.PI);
		int spawned = 0;
		int placed = 0;

		for (Member member : encounter.members()) {
			for (int i = 0; i < member.count(); i++) {
				double angle = 2 * Math.PI * placed / Math.max(1, total);
				placed++;
				Entity entity = MonsterRegistry.spawnAt(level,
					center.x + Math.cos(angle) * radius,
					center.y,
					center.z + Math.sin(angle) * radius,
					member.monsterId());
				if (entity == null) continue;
				CombatFx.monsterSpawn(entity);
				spawned++;
			}
		}
		return spawned;
	}
}
