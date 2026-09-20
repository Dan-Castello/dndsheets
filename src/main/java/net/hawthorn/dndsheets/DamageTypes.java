package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.Set;

/**
 * <p>Resistances/vulnerabilities/immunities per damage type, stored on the player's sheet
 * as {@code damageAffinities: {"fire":"resistant", ...}} (see {@code /dndsheet damagetype}). It only
 * protects/hurts the player who has it — monsters and armor stands don't carry this layer.</p>
 *
 * <p>Barbarian Rage ({@link BarbarianRageManager}) is added in here instead of being written as one more
 * entry in "damageAffinities": it's temporary and shouldn't survive a server restart or show up as
 * something the DM "set by hand" with {@code /dndsheet damagetype}.</p>
 */
public class DamageTypes {
	private static final Set<String> PHYSICAL_TYPES = Set.of("physical", "slashing", "piercing", "bludgeoning");

	/**
	 * <p>The fourteen 5e damage types as they're written in this mod: no accents, lowercase, and in
	 * English. It lives here rather than in the command that suggests them because it's the same list
	 * that decides whether a resistance applies — two copies drifting apart would mean a resistance the
	 * DM sees suggested that then does nothing.</p>
	 */
	public static final String[] CANONICAL = {
		"physical", "slashing", "piercing", "bludgeoning", "fire", "cold", "lightning",
		"acid", "poison", "psychic", "radiant", "necrotic", "force", "thunder"
	};

	//The legacy Spanish names (sheets, packs and saves written before the English switch) map to the canonical ones.
	private static final Map<String, String> LEGACY_SPANISH = Map.ofEntries(
		Map.entry("fisico", "physical"), Map.entry("cortante", "slashing"),
		Map.entry("perforante", "piercing"), Map.entry("contundente", "bludgeoning"),
		Map.entry("fuego", "fire"), Map.entry("frio", "cold"), Map.entry("rayo", "lightning"),
		Map.entry("acido", "acid"), Map.entry("veneno", "poison"), Map.entry("psiquico", "psychic"),
		Map.entry("radiante", "radiant"), Map.entry("necrotico", "necrotic"),
		Map.entry("fuerza", "force"), Map.entry("trueno", "thunder"));

	/**
	 * <p><b>A damage type written any way, always the same.</b> A damage type isn't a label that gets
	 * printed: it's a KEY compared against the resistances on the sheet and the monster block
	 * ({@code damageAffinities}). That's why {@code "Fire"}, {@code "fuego"} (legacy) and {@code "fire "} have
	 * to produce the same string — otherwise a pack imported in English slips straight through a
	 * character's fire resistance with nothing flagging it, which is the worst way to fail: a number
	 * still comes out, and it comes out wrong.</p>
	 *
	 * <p>A type that isn't in the table is <b>not discarded</b>, it's returned normalized. A table that
	 * invents "bleed" still has its type, and has it the same on both ends of the comparison, which is
	 * the only thing needed for its homebrew resistance to work.</p>
	 */
	public static String normalize(String raw) {
		if (raw == null) return "physical";
		String stripped = java.text.Normalizer.normalize(raw.trim().toLowerCase(java.util.Locale.ROOT),
				java.text.Normalizer.Form.NFD)
			.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
			.replaceAll("[\\s_-]", "");
		if (stripped.isEmpty()) return "physical";
		return LEGACY_SPANISH.getOrDefault(stripped, stripped);
	}

	public static double multiplierFor(Entity target, JsonObject sheet, String damageType) {
		double multiplier = sheetMultiplierFor(sheet, damageType);
		if (target instanceof ServerPlayer player && BarbarianRageManager.isRaging(player) && PHYSICAL_TYPES.contains(damageType)) {
			multiplier = Math.min(multiplier, 0.5); //Already immune/resistant some other way doesn't get worse; normal/vulnerable does drop to resistant.
		}
		return multiplier;
	}

	private static double sheetMultiplierFor(JsonObject sheet, String damageType) {
		if (sheet == null || damageType == null || !sheet.has("damageAffinities")) return 1.0;
		JsonObject affinities = sheet.getAsJsonObject("damageAffinities");
		String key = normalize(damageType);
		//Keys on a sheet handwritten before this existed may carry accents or capitals.
		for (String written : affinities.keySet()) {
			if (normalize(written).equals(key)) return multiplierForLabel(affinities.get(written).getAsString());
		}
		return 1.0;
	}

	/**
	 * <p>Translates a declared affinity ({@code resistant}/{@code vulnerable}/{@code immune}) to its
	 * multiplier. Public because monster blocks store theirs in their own map, not a JSON sheet:
	 * without this, the same vocabulary would end up parsed in two places that could disagree.</p>
	 */
	public static double multiplierForLabel(String affinity) {
		if (affinity == null) return 1.0;
		return switch (affinity.toLowerCase(java.util.Locale.ROOT)) {
			case "resistant" -> 0.5;
			case "vulnerable" -> 2.0;
			case "immune" -> 0.0;
			default -> 1.0;
		};
	}

	public static int applyMultiplier(int amount, double multiplier) {
		return (int) Math.floor(amount * multiplier);
	}
}
