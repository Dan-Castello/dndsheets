package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * <p>Traits (class passives/features) hot-loaded by {@code /dndtraits load}, in memory
 * (same as {@link SpellRegistry}/{@link MonsterRegistry}: lost on restart unless the same file is
 * reloaded). A class preset grants them automatically by id (the {@code "traits"} field in its JSON, see
 * {@link PresetRegistry}); {@code /dndtraits grant} grants them by hand.</p>
 *
 * <p>Two effects for now, both "a die that scales by level" — {@link LevelDice} is the same shape for
 * both, only the JSON field read and where it's consumed differ:</p>
 * <ul>
 *   <li>{@code unarmedDiceByLevel}: replaces the bare-handed strike with a real 5e roll (the monk's
 *   Martial Arts) — see {@link #unarmedProfileFor}, consumed by {@link CombatManager}.</li>
 *   <li>{@code sneakAttackDiceByLevel}: extra dice that get ADDED to the damage roll when the attack was
 *   made with advantage (the rogue's Sneak Attack) — see {@link #sneakAttackDiceFor}, also consumed by
 *   {@link CombatManager}. Deliberate simplification: 5e also allows it with an adjacent ally and no
 *   disadvantage; here only real advantage counts, there's no notion of "adjacent ally" in the engine.</li>
 * </ul>
 * <p>Adding a new effect type is the same pattern: one more field here, one more branch where it's
 * consumed — no generic rules engine needed for this.</p>
 */
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines that not a single caller used, addons included, which come in through here).
//An external mod calling these methods risks their signature changing without notice. The only
//thing meant for external consumption is the api/event events, which do have real consumers.
public class TraitRegistry {
	public record LevelDice(int level, String dice) {}
	public record Trait(String id, String name, String unarmedAbility, List<LevelDice> unarmedDiceByLevel, List<LevelDice> sneakAttackDiceByLevel) {}
	public record UnarmedProfile(String dice, String ability) {}

	private static final NamedRegistry<Trait> REGISTRY = new NamedRegistry<>("trait", Trait::id);

	public static void register(Trait trait) {
		REGISTRY.register(trait);
	}

	@Nullable
	public static Trait get(String id) {
		return REGISTRY.get(canonical(id));
	}

	//Trait ids written into sheets when they were Spanish ("monje:artes_marciales") still resolve.
	private static String canonical(String id) {
		return switch (id) {
			case "monje:artes_marciales" -> "monk:martial_arts";
			case "picaro:ataque_furtivo" -> "rogue:sneak_attack";
			default -> id;
		};
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Public: used by TraitCommand (/dndtraits load) and by DndPaths to preload all the folder's .json
	//files on server startup, without DndPaths having to depend on the command layer.
	private static final JsonRegistryLoader<Trait> LOADER = new JsonRegistryLoader<>("trait", TraitRegistry::parse, TraitRegistry::register);

	/** Loads from an already-parsed JSON (datapack or another mod's jar) — see ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	public static Trait parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String unarmedAbility = json.has("unarmedAbility") ? json.get("unarmedAbility").getAsString().toLowerCase(Locale.ROOT) : "str";

		List<LevelDice> unarmedTiers = parseLevelDice(json, "unarmedDiceByLevel");
		List<LevelDice> sneakAttackTiers = parseLevelDice(json, "sneakAttackDiceByLevel");

		return new Trait(id, name, unarmedAbility, unarmedTiers, sneakAttackTiers);
	}

	private static List<LevelDice> parseLevelDice(JsonObject json, String field) {
		List<LevelDice> tiers = new ArrayList<>();
		if (!json.has(field)) return tiers;

		for (JsonElement el : json.getAsJsonArray(field)) {
			JsonObject tier = el.getAsJsonObject();
			tiers.add(new LevelDice(tier.has("level") ? tier.get("level").getAsInt() : 1, tier.get("dice").getAsString()));
		}
		tiers.sort((a, b) -> b.level() - a.level()); //Descending: the first level that "fits" is the highest applicable one.
		return tiers;
	}

	//--- Sheet: list of granted trait ids in "traits" (see SheetLoader.validateSheet) ---

	//Adds the trait if it wasn't already there; used both when applying a preset (PresetRegistry.applyToSheet)
	//and by /dndtraits grant, to avoid duplicate entries if granted twice by mistake.
	public static void grant(JsonObject sheet, String traitId) {
		if (!sheet.has("traits")) sheet.add("traits", new JsonArray());
		JsonArray granted = sheet.getAsJsonArray("traits");
		for (JsonElement el : granted) {
			if (canonical(el.getAsString()).equals(canonical(traitId))) return;
		}
		granted.add(traitId);
	}

	//Used by PresetRegistry.applyToSheet when switching presets: without this, switching from "monk" to
	//"wizard" left Martial Arts granted forever, since grant() only knows how to add, never remove.
	public static void revoke(JsonObject sheet, String traitId) {
		if (!sheet.has("traits")) return;
		JsonArray granted = sheet.getAsJsonArray("traits");
		JsonArray kept = new JsonArray();
		for (JsonElement el : granted) {
			if (!canonical(el.getAsString()).equals(canonical(traitId))) kept.add(el);
		}
		sheet.add("traits", kept);
	}

	//Bare-handed strike: walks the granted traits looking for one that defines a die by level (e.g.
	//Martial Arts); returns the highest applicable level, or null if none of the granted traits touch this
	//(normal unmodified Minecraft behavior for punching).
	@Nullable
	public static UnarmedProfile unarmedProfileFor(JsonObject sheet, int level) {
		if (sheet == null || !sheet.has("traits")) return null;

		for (JsonElement el : sheet.getAsJsonArray("traits")) {
			Trait trait = REGISTRY.get(canonical(el.getAsString()));
			if (trait == null || trait.unarmedDiceByLevel().isEmpty()) continue;

			for (LevelDice tier : trait.unarmedDiceByLevel()) {
				if (level >= tier.level()) return new UnarmedProfile(tier.dice(), trait.unarmedAbility());
			}
		}
		return null;
	}

	//Sneak Attack: extra die added to the damage roll (not replacing it) when the attack was made with
	//advantage. Null if none of the granted traits define it, or if the character hasn't yet reached the
	//level of the table's first entry.
	@Nullable
	public static String sneakAttackDiceFor(JsonObject sheet, int level) {
		if (sheet == null || !sheet.has("traits")) return null;

		for (JsonElement el : sheet.getAsJsonArray("traits")) {
			Trait trait = REGISTRY.get(canonical(el.getAsString()));
			if (trait == null || trait.sneakAttackDiceByLevel().isEmpty()) continue;

			for (LevelDice tier : trait.sneakAttackDiceByLevel()) {
				if (level >= tier.level()) return tier.dice();
			}
		}
		return null;
	}
}
