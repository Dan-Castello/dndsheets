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
 * <p>Encounters: a named group of monsters, saved before the session and dropped in all at once. It's the
 * DM prep loop that didn't exist until now — monsters were summoned one at a time, which works fine for
 * showing off the mod and is terrible for a game night with four fights prepared.</p>
 *
 * <p><b>It brings no new rule.</b> It summons with {@link MonsterRegistry#spawnAt} and that's the end of
 * it: initiative still kicks off on the first hit ({@code CombatManager.autoStartCombatIfNeeded}, which
 * already picks up everyone in range), so an encounter doesn't need to know anything about turns. The only
 * thing it adds is <em>all at once and wherever you say</em>.</p>
 *
 * <p><b>The composition is written as text</b> ({@code "dndsheets:goblin x4"}) rather than an object with
 * fields. It's the same format in the JSON and in the in-game creator's field, so there's a single parser
 * and a single syntax to learn; with objects there would be two ways to say the same thing and a
 * conversion between them. With no {@code xN} it means one.</p>
 */
public class EncounterRegistry {

	/** How far apart the monsters are spaced from each other when dropped in, in blocks. */
	private static final double RING_SPACING = 1.6;

	public record Member(String monsterId, int count) {}

	public record Encounter(String id, String name, List<Member> members) {
		public int total() {
			int total = 0;
			for (Member member : members) total += member.count();
			return total;
		}
	}

	private static final NamedRegistry<Encounter> REGISTRY = new NamedRegistry<>("encounter", Encounter::id);

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
		new JsonRegistryLoader<>("encounter", EncounterRegistry::parse, EncounterRegistry::register);

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
	 * <p>{@code "dndsheets:goblin x4"} → four goblins. Without the suffix, one.</p>
	 *
	 * <p>A line that isn't understood returns {@code null} and is skipped, instead of blowing up the whole
	 * encounter: it's the same call {@link JsonRegistryLoader} already makes for each entry in a file —
	 * losing one monster from an ambush is recoverable, losing the DM's entire file isn't.</p>
	 */
	static Member parseMember(String text) {
		if (text == null) return null;
		String trimmed = text.trim();
		if (trimmed.isEmpty()) return null;

		int split = trimmed.lastIndexOf(" x");
		if (split < 0) return new Member(trimmed, 1);

		try {
			int count = Integer.parseInt(trimmed.substring(split + 2).trim());
			//Zero or negative isn't "none," it's a typo: an encounter with a line that summons nothing
			//reads as a broken encounter, so it's treated as one, and the DM sees what they wrote.
			return new Member(trimmed.substring(0, split).trim(), Math.max(1, count));
		} catch (NumberFormatException e) {
			//"dragon x old" isn't a count: the name stays whole.
			return new Member(trimmed, 1);
		}
	}

	/** How an encounter reads in chat and in lists: "Goblin x4, Wolf x2". */
	public static String describe(Encounter encounter) {
		StringBuilder text = new StringBuilder();
		for (Member member : encounter.members()) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.get(member.monsterId());
			if (text.length() > 0) text.append(", ");
			text.append(ContentNames.plain(block != null ? block.name() : member.monsterId()));
			if (member.count() > 1) text.append(" x").append(member.count());
		}
		return text.length() == 0 ? "(empty)" : text.toString();
	}

	/**
	 * <p>Drops the entire encounter around {@code center}, spread in a circle so they don't all come out
	 * stacked on the same block — which is what {@code /dndmonsters spawn} does with a quantity, leaving a
	 * pile impossible to target with the wand.</p>
	 *
	 * @return how many were actually summoned; fewer than the total means some id doesn't exist.
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
