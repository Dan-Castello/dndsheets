package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <p>Per-class hit die table, in config/dndsheets-common.toml, so it can be edited (or translated, or
 * extended by hand) in bulk without touching code or recompiling.</p>
 */
@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.MOD)
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was deleted —
//233 lines that not a single caller used, not even the addons, which come in through here). An external
//mod calling these methods is exposed to their signature changing without notice. The only thing
//intended for external consumption is the api/event events, which do have real consumers.
public class Config {
	public static final ForgeConfigSpec SPEC;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> HIT_DICE_ENTRIES;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> WEAPON_DAMAGE_ENTRIES;
	private static final ForgeConfigSpec.ConfigValue<List<? extends String>> ENCHANT_BONUS_ENTRIES;
	private static final ForgeConfigSpec.BooleanValue VISION_RULES;
	private static final ForgeConfigSpec.BooleanValue SOLO_MODE;
	private static final ForgeConfigSpec.IntValue CAST_TICKS_PER_LEVEL;
	private static final ForgeConfigSpec.IntValue CAST_TICKS_MAX;
	private static final ForgeConfigSpec.IntValue FEET_PER_BLOCK;
	private static final java.util.EnumMap<Rule, ForgeConfigSpec.BooleanValue> AUTO = new java.util.EnumMap<>(Rule.class);

	/**
	 * <p>Automations the DM can hand back to the table (manual = the mod stops doing it, everything is
	 * rolled/decided by hand from the sheet and the commands). {@code VISION} is the old
	 * {@code visionRules} flag (off by default); the rest default to automatic.</p>
	 */
	public enum Rule { COMBAT, TURNS, DEATH_SAVES, OPPORTUNITY_ATTACKS, VISION }

	static {
		ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
		builder.comment(
			"Hit die per class, used to compute actual max HP from class + level + constitution.",
			"Format: one entry per line like \"name:die\" (e.g. \"fighter:10\").",
			"Matching is by substring and case-insensitive against the sheet's 'Class & Level' field.",
			"Add whatever class names you use at your table here (in any language) without touching the code."
		);
		HIT_DICE_ENTRIES = builder.defineList("hitDice", defaultHitDice(), Config::isValidEntry);

		builder.comment(
			"Default damage for each weapon, used to preload the Attacks tab with the weapons",
			"the player is carrying in their inventory, and for the automatic roll when hitting an armor stand.",
			"Format: one entry per line like \"item_id;die;ability\" (e.g. \"minecraft:iron_sword;1d6;str\").",
			"Ability is str or dex. Each player can override their own roll by editing the entry on their sheet.",
			"",
			"Also supports custom weapons (daggers, spears, darts...) on ANY base item:",
			"give the item an NBT tag {dndsheets:{weapon:\"your_id\"}} (via /give, a loot table with",
			"set_nbt, etc.) and use that same \"your_id\" as the key here, e.g. \"dndsheets:dagger;1d4;dex\".",
			"That tag takes priority over the base item's id, so you can hand them out as loot."
		);
		WEAPON_DAMAGE_ENTRIES = builder.defineList("weaponDamage", defaultWeaponDamage(), Config::isValidWeaponEntry);

		builder.comment(
			"Damage bonus per weapon enchantment level, added as a flat number to the roll",
			"(equivalent to the +1/+2/+3 of a magic weapon in 5e), not as extra dice.",
			"Format: one entry per line like \"enchantment_id;bonus_per_level\" (e.g. \"minecraft:sharpness;1\").",
			"A Sharpness III with bonus 1 adds +3. Add any other enchantment here that you want to count."
		);
		ENCHANT_BONUS_ENTRIES = builder.defineList("enchantmentDamageBonus", defaultEnchantBonus(), Config::isValidEnchantEntry);

		builder.comment(
			"Vision rules (VisionManager): in darkness — Minecraft light level below 4 — a character",
			"without darkvision becomes blinded, with everything that means in 5e: attacks with",
			"disadvantage and is attacked with advantage. Whoever has the trait sees as in dim light. Holding",
			"a torch or lantern in hand counts as bright light.",
			"",
			"Off by default ON PURPOSE: it's the most intrusive rule in the mod, because it changes how",
			"Minecraft is played outside the table (mining at night, entering a cave). It's toggled on and",
			"off live with /dndvision, which writes here."
		);
		VISION_RULES = builder.define("visionRules", false);

		builder.comment(
			"Spell casting time, in ticks (20 ticks = 1 second).",
			"",
			"A spell with a casting time doesn't go off instantly: the caster builds up particles of its",
			"school, the action bar shows progress, and taking damage forces a Constitution save to avoid",
			"losing it (the spell slot is spent regardless, as with Counterspell). This is the only thing in",
			"this mod that makes casting TAKE real time.",
			"",
			"Computed as spell level x castTicksPerLevel, capped by castTicksMax. Cantrips are always",
			"instant. A spell can set its own \"castTicks\" in its JSON, which then takes priority",
			"(this is how a Shield or a Counterspell is written, since they need to go off immediately).",
			"",
			"castTicksPerLevel = 0 restores the always-instant casting of old for the whole table."
		);
		CAST_TICKS_PER_LEVEL = builder.defineInRange("castTicksPerLevel", 3, 0, 40);
		CAST_TICKS_MAX = builder.defineInRange("castTicksMax", 20, 0, 200);

		builder.comment(
			"Solo mode (no DM): when active, ANY connected player can do everything that currently requires",
			"an operator — summoning monsters, controlling turns by hand, applying a class preset to",
			"themselves, toggling table rules, and even applying conditions or adjusting ANOTHER player's",
			"sheet. There's no new hierarchy or 'party leader': in solo mode everyone is equally trusted",
			"among themselves, the same level of trust already required to share the same Minecraft world.",
			"",
			"Toggled on and off live with /dndsolo, same as visionRules with /dndvision — but THAT command",
			"requires permission level 4 (server owner/console), not 2: it decides WHO has full",
			"administrative power over other players, so it demands the same access already needed to",
			"appoint operators, not the level 2 that this very flag renders irrelevant the moment it's turned on."
		);
		SOLO_MODE = builder.define("soloMode", false);

		builder.comment(
			"Automation switches (the DM's Rules menu). true = the mod resolves it on its own; false = manual,",
			"the mod doesn't intervene and the table does it by hand.",
			"autoCombat: attack/damage rolls, AC and resistances applied on hit (and hits starting combat).",
			"autoTurns: combat starting on its own when someone is hit (the DM can still start it by hand).",
			"autoDeathSaves: reaching 0 HP puts a player in the downed/death-save state instead of dying.",
			"autoOpportunityAttacks: leaving an enemy's reach on your turn provokes an attack."
		);
		for (Rule rule : Rule.values()) {
			if (rule == Rule.VISION) AUTO.put(rule, VISION_RULES);
			else AUTO.put(rule, builder.define(rule == Rule.OPPORTUNITY_ATTACKS ? "autoOpportunityAttacks"
				: rule == Rule.DEATH_SAVES ? "autoDeathSaves" : rule == Rule.TURNS ? "autoTurns" : "autoCombat", true));
		}
		builder.comment("Feet each block counts for: distances, movement budget and the movement HUD. 5 = the usual grid.");
		FEET_PER_BLOCK = builder.defineInRange("feetPerBlock", 5, 1, 30);

		SPEC = builder.build();
	}

	private static List<String> defaultHitDice() {
		return List.of(
			"barbarian:12", "bárbaro:12", "barbaro:12",
			"fighter:10", "guerrero:10",
			"paladin:10", "paladín:10",
			"ranger:10", "explorador:10",
			"bard:8", "bardo:8",
			"cleric:8", "clérigo:8", "clerigo:8",
			"druid:8", "druida:8",
			"monk:8", "monje:8",
			"rogue:8", "pícaro:8", "picaro:8",
			"warlock:8", "brujo:8",
			"sorcerer:6", "hechicero:6",
			"wizard:6", "mago:6"
		);
	}

	/** Whether vision rules are active. See {@link net.hawthorn.dndsheets.VisionManager}. */
	public static boolean visionRules() {
		return VISION_RULES.get();
	}

	/** Turns them on or off and writes it to the toml, so it survives a restart. */
	public static void setVisionRules(boolean enabled) {
		VISION_RULES.set(enabled);
		VISION_RULES.save();
	}

	/** The three editable lists of the toml, for the in-game editor (see {@code BrowseActionMessage.RULES}). */
	public enum Table { HIT_DICE, WEAPON_DAMAGE, ENCHANT_BONUS }

	private static ForgeConfigSpec.ConfigValue<List<? extends String>> valueOf(Table table) {
		return switch (table) {
			case HIT_DICE -> HIT_DICE_ENTRIES;
			case WEAPON_DAMAGE -> WEAPON_DAMAGE_ENTRIES;
			case ENCHANT_BONUS -> ENCHANT_BONUS_ENTRIES;
		};
	}

	public static List<String> entries(Table table) {
		return new java.util.ArrayList<>(valueOf(table).get());
	}

	/** Same validators the toml uses, so a bad entry from the editor is refused instead of saved. */
	public static boolean isValid(Table table, String entry) {
		return switch (table) {
			case HIT_DICE -> isValidEntry(entry);
			case WEAPON_DAMAGE -> isValidWeaponEntry(entry) && entry.split(";")[1].trim().matches("\\d+d\\d+");
			case ENCHANT_BONUS -> isValidEnchantEntry(entry);
		};
	}

	/** Replaces the list, writes the toml and applies it now (the file watcher would only do it later). */
	public static void setEntries(Table table, List<String> entries) {
		valueOf(table).set(entries);
		valueOf(table).save();
		reload();
		syncTo(net.minecraftforge.network.PacketDistributor.ALL.noArg());
	}

	/**
	 * <p>The three tables are read on the CLIENT too (the Attacks tab preloads the weapons in the inventory), and
	 * on a dedicated server the client's own toml has nothing to do with the server's. So the server pushes them:
	 * on login and after every edit. The client applies them in memory only; its toml file is never touched.</p>
	 */
	public static void syncTo(net.minecraftforge.network.PacketDistributor.PacketTarget target) {
		for (Table table : Table.values()) {
			DndsheetsMod.PACKET_HANDLER.send(target, new net.hawthorn.dndsheets.network.BrowseListMessage(
				net.hawthorn.dndsheets.network.BrowseListMessage.Kind.CONFIG_SYNC, entries(table), List.of(), table.name()));
		}
	}

	//What the server last pushed, per table. Kept APART from the ConfigValues: Forge's toml handler autosaves on
	//set(), so writing the server's tables into them would overwrite this client's own file with someone else's.
	private static final java.util.EnumMap<Table, List<String>> REMOTE = new java.util.EnumMap<>(Table.class);

	private static List<? extends String> effective(Table table) {
		List<String> remote = REMOTE.get(table);
		return remote != null ? remote : valueOf(table).get();
	}

	/** Client side of {@link #syncTo}: in memory only; the toml on disk is never touched. */
	public static void applyRemote(String tableName, List<String> entries) {
		try {
			REMOTE.put(Table.valueOf(tableName), new java.util.ArrayList<>(entries));
			reload();
		} catch (IllegalArgumentException ignored) {
			//Unknown table: a mismatched server, nothing to apply.
		}
	}

	/** On leaving a server the client goes back to its own tables (singleplayer, another server). */
	public static void clearRemote() {
		REMOTE.clear();
		reload();
	}

	@Mod.EventBusSubscriber
	public static class Sync {
		@SubscribeEvent
		public static void onLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
			if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
				syncTo(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player));
			}
		}
	}

	/** Whether {@code rule} is automatic. Every automation goes through here. */
	public static boolean auto(Rule rule) {
		return AUTO.get(rule).get();
	}

	public static void setAuto(Rule rule, boolean enabled) {
		AUTO.get(rule).set(enabled);
		AUTO.get(rule).save();
	}

	/** Feet per block, the single conversion for distance, movement budget and HUD. */
	public static int feetPerBlock() {
		return FEET_PER_BLOCK.get();
	}

	public static void setFeetPerBlock(int feet) {
		FEET_PER_BLOCK.set(Math.max(1, Math.min(30, feet)));
		FEET_PER_BLOCK.save();
	}

	public static void setCastTicksPerLevel(int ticks) {
		CAST_TICKS_PER_LEVEL.set(Math.max(0, Math.min(40, ticks)));
		CAST_TICKS_PER_LEVEL.save();
	}

	/** Casting ticks per spell level; 0 = everything instant. See {@code SpellRegistry.Spell#castTicksAt}. */
	public static int castTicksPerLevel() {
		return CAST_TICKS_PER_LEVEL.get();
	}

	/** Cap on casting time, in ticks. See {@code SpellRegistry.Spell#castTicksAt}. */
	public static int castTicksMax() {
		return CAST_TICKS_MAX.get();
	}

	/** Whether solo mode (no DM) is active. See {@link net.hawthorn.dndsheets.DndsheetsMod#canActAsDm}. */
	public static boolean soloMode() {
		return SOLO_MODE.get();
	}

	/** Same pattern as {@link #setVisionRules} — toggles it and writes it to the toml. */
	public static void setSoloMode(boolean enabled) {
		SOLO_MODE.set(enabled);
		SOLO_MODE.save();
	}

	/**
	 * <p>How much monsters are scaled, read from <b>Minecraft's own difficulty</b> ({@code /difficulty}).
	 * This used to be a separate setting with its own {@code /dnddifficulty} command, and there were two
	 * dials for the same question: you could have the world on Hard and the mod's monsters on Easy, with
	 * nothing warning about the contradiction. Now there's just one, the one the player already knows and
	 * already uses, and it raises or lowers both at once.</p>
	 *
	 * <p>PEACEFUL counts as the lowest rung instead of disabling anything: in vanilla no hostiles spawn,
	 * but this mod's monsters are summoned by the DM on purpose, and making them disappear would break
	 * their scene. They stay, softened.</p>
	 *
	 * <p>Without a server (a call from the client) it returns 1.0, the same as Normal: it's a
	 * multiplier, not a rule, and erring toward "unscaled" doesn't throw any numbers off.</p>
	 */
	private static double difficultyMultiplier() {
		net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
		if (server == null) return 1.0;
		return switch (server.getWorldData().getDifficulty()) {
			case PEACEFUL, EASY -> 0.75;
			case HARD -> 1.5;
			default -> 1.0;
		};
	}

	/**
	 * <p>Max HP of a monster, scaled by difficulty. The single point that applies the multiplier —
	 * use it anywhere that computes how much HP a monster has (spawn and {@code Combatant.maxHp()}),
	 * never read {@code block.maxHp()} directly for that, or they get out of sync: the monster spawns
	 * with less HP than its own bar says it has.</p>
	 */
	public static int scaleMonsterMaxHp(int rawMaxHp) {
		return Math.max(1, (int) Math.round(rawMaxHp * difficultyMultiplier()));
	}

	/** Same multiplier as {@link #scaleMonsterMaxHp}, applied to the damage a monster DEALS. */
	public static int scaleMonsterDamage(int rawDamage) {
		return Math.max(0, (int) Math.round(rawDamage * difficultyMultiplier()));
	}

	private static boolean isValidEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(":");
		if (parts.length != 2) return false;
		try {
			Integer.parseInt(parts[1].trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	private static List<String> defaultWeaponDamage() {
		List<String> entries = new java.util.ArrayList<>();
		for (String tier : new String[]{"wooden", "stone", "iron", "golden", "diamond", "netherite"}) {
			entries.add("minecraft:" + tier + "_sword;1d6;str");
			entries.add("minecraft:" + tier + "_axe;1d8;str");
		}
		entries.add("minecraft:bow;1d8;dex");
		entries.add("minecraft:crossbow;1d8;dex");
		entries.add("minecraft:trident;1d8;str");

		//Example custom weapons (see the NBT tag in the comment above). Delete them or change
		//the die freely; they're just a starting point for daggers/spears/darts handed out as loot.
		entries.add("dndsheets:dagger;1d4;dex");
		entries.add("dndsheets:spear;1d6;str");
		entries.add("dndsheets:dart;1d4;dex");
		return entries;
	}

	private static boolean isValidWeaponEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(";");
		if (parts.length != 3) return false;
		String ability = parts[2].trim().toLowerCase(Locale.ROOT);
		return ability.equals("str") || ability.equals("dex");
	}

	private static List<String> defaultEnchantBonus() {
		return List.of(
			"minecraft:sharpness;1",
			"minecraft:smite;1",
			"minecraft:bane_of_arthropods;1",
			"minecraft:power;1",
			"minecraft:impaling;1"
		);
	}

	private static boolean isValidEnchantEntry(Object entry) {
		if (!(entry instanceof String s)) return false;
		String[] parts = s.split(";");
		if (parts.length != 2) return false;
		try {
			Integer.parseInt(parts[1].trim());
			return true;
		} catch (NumberFormatException e) {
			return false;
		}
	}

	//"hands" is "one" (default), "two" (truly two-handed, see CombatManager.blockedByOffhand) or
	//"versatile" (1d8/1d10 like a longsword: more damage with both hands free). "versatileDice" only
	//matters if hands=="versatile" — see CombatManager, which decides which to use by checking whether
	//the other hand is empty. "classes": list of substrings (lowercase) compared against characterClass,
	//same pattern hitDieFor already uses — empty/null means "any class can use it" (the default case).
	public record WeaponDefault(String dice, String ability, String damageType, String hands, String versatileDice, List<String> classes) {
		public boolean isVersatile() {
			return "versatile".equals(hands) && versatileDice != null;
		}

		public boolean allowsClass(String characterClass) {
			if (classes == null || classes.isEmpty()) return true;
			if (characterClass == null) return false;
			String normalized = characterClass.toLowerCase(Locale.ROOT);
			for (String allowed : classes) if (normalized.contains(allowed)) return true;
			return false;
		}
	}
	//customModelData: optional, so a resource pack can reskin a custom weapon by number instead of
	//sharing the base item's texture — null means "no custom model" (the usual behavior).
	public record WeaponGiveInfo(String displayName, String baseItemId, Integer customModelData) {}

	//Seeded with the default values and non-empty: until Forge loads the toml, hitDieFor returned d8
	//for EVERY class, so anything deriving HP before that load (or outside the game, like the
	//self-test) got numbers for a class that doesn't exist. The toml overwrites it entirely on load,
	//so seeding it changes nothing the DM configures.
	private static Map<String, Integer> hitDiceByClass = parseHitDice(defaultHitDice());

	private static Map<String, Integer> parseHitDice(List<String> entries) {
		Map<String, Integer> parsed = new LinkedHashMap<>();
		for (String entry : entries) {
			String[] parts = entry.split(":");
			try {
				parsed.put(parts[0].trim().toLowerCase(Locale.ROOT), Integer.parseInt(parts[1].trim()));
			} catch (RuntimeException ignored) {
				//Invalid entries are already filtered out by isValidEntry(), but just in case.
			}
		}
		return parsed;
	}
	private static Map<String, WeaponDefault> weaponDamageByItem = new LinkedHashMap<>();
	private static Map<String, Integer> enchantBonusPerLevel = new LinkedHashMap<>();

	//Weapons hot-loaded by /dndweapons load (see WeaponCommand), not by the toml. They take priority
	//over weaponDamageByItem in case a weapon pack wants to override an id already defined there.
	private static final Map<String, WeaponDefault> jsonWeapons = new LinkedHashMap<>();
	private static final Map<String, WeaponGiveInfo> jsonWeaponGiveInfo = new LinkedHashMap<>();

	/**
	 * <p>Registers (or overwrites) a weapon in memory, typically from a JSON loaded with
	 * {@code /dndweapons load}. Not saved to the toml: it's lost on server restart unless
	 * the same file is loaded again.</p>
	 *
	 * <p>Audit item F22: of the 5 positional overloads that used to exist (up to 10 String parameters),
	 * only this 10-parameter one had real callers (see {@link #loadFile}) — the rest were removed.
	 * The other caller was the {@code DndSheetsApi.registerWeapon} facade, deleted because it had none
	 * of its own in turn.</p>
	 */
	public static void registerWeapon(String id, String dice, String ability, String damageType, String hands, String versatileDice, List<String> classes, String displayName, String baseItemId, Integer customModelData) {
		List<String> normalizedClasses = new java.util.ArrayList<>();
		for (String c : classes) normalizedClasses.add(c.toLowerCase(Locale.ROOT));
		jsonWeapons.put(id, new WeaponDefault(dice, ability.toLowerCase(Locale.ROOT), DamageTypes.normalize(damageType), hands.toLowerCase(Locale.ROOT), versatileDice, normalizedClasses));
		jsonWeaponGiveInfo.put(id, new WeaponGiveInfo(displayName, baseItemId, customModelData));
	}

	public static WeaponGiveInfo giveInfoFor(String weaponId) {
		return jsonWeaponGiveInfo.get(weaponId);
	}

	public static java.util.Set<String> loadedWeaponIds() {
		java.util.Set<String> ids = new java.util.LinkedHashSet<>(jsonWeapons.keySet());
		ids.addAll(weaponDamageByItem.keySet());
		return ids;
	}

	//Only the custom weapons loaded via JSON (with their own name and base item), for the creative
	//tab: the ones in weaponDamageByItem are already real Minecraft items, no need to repeat them there.
	public static java.util.Set<String> customWeaponIds() {
		return jsonWeaponGiveInfo.keySet();
	}

	//Public: used by the in-game content creator to delete a weapon created in-game (see
	//ContentPackFile). Not a NamedRegistry like the other *Registry classes (see the loadFile note
	//below), so it needs its own remove over the two maps instead of delegating to NamedRegistry.remove.
	public static boolean removeWeapon(String id) {
		boolean removed = jsonWeapons.remove(id) != null;
		jsonWeaponGiveInfo.remove(id);
		return removed;
	}

	//Public: used by WeaponCommand (/dndweapons load) and by DndPaths to solo-preload all the
	//.json files in the folder at server startup, without DndPaths having to depend on the command
	//layer. Doesn't use JsonRegistryLoader like the other *Registry classes: it validates
	//several required fields at once and calls registerWeapon with positional parameters instead of a
	//parse()/register() pair on its own registry.
	public static int loadFile(Path file) throws IOException {
		return loadJson(JsonParser.parseString(Files.readString(file)), file.getFileName().toString(), id -> { });
	}

	/**
	 * <p>Loads weapons from an already-parsed JSON, from a world file or another mod's jar (see
	 * {@code ContentDatapackLoader}). Accepts either an array of weapons or a single loose one, which
	 * follows the datapack convention: one file, one entry.</p>
	 *
	 * <p>Weapons don't go through {@code JsonRegistryLoader} because they validate three required fields
	 * at once and register with positional parameters instead of a parse/register pair — see that
	 * class's comment. That's why this method duplicates its shape instead of reusing it.</p>
	 */
	public static int loadJson(JsonElement root, String source, java.util.function.Consumer<String> onId) {
		JsonArray weapons;
		if (root.isJsonArray()) {
			weapons = root.getAsJsonArray();
		} else {
			weapons = new JsonArray();
			weapons.add(root);
		}
		int count = 0;
		int index = 0;
		for (JsonElement element : weapons) {
			index++;
			try {
				JsonObject weapon = element.getAsJsonObject();
				if (!weapon.has("id") || !weapon.has("dice") || !weapon.has("ability")) {
					DndsheetsMod.LOGGER.warn("Skipping weapon #{} in {}: missing \"id\", \"dice\" or \"ability\".", index, source);
					continue;
				}

				String id = weapon.get("id").getAsString();
				String dice = weapon.get("dice").getAsString();
				String ability = weapon.get("ability").getAsString();
				String name = weapon.has("name") ? weapon.get("name").getAsString() : id;
				String baseItem = weapon.has("item") ? weapon.get("item").getAsString() : "minecraft:stick";
				String damageType = weapon.has("damageType") ? weapon.get("damageType").getAsString() : "physical";
				String hands = weapon.has("hands") ? weapon.get("hands").getAsString() : "one";
				String versatileDice = weapon.has("versatileDice") ? weapon.get("versatileDice").getAsString() : null;

				//Optional: which classes can use it (substrings compared against the sheet's "Class & Level",
				//same pattern as hitDieFor) — without this field (the default case) any class
				//can use the weapon, same as before.
				List<String> classes = new java.util.ArrayList<>();
				if (weapon.has("classes")) {
					for (JsonElement el : weapon.getAsJsonArray("classes")) classes.add(el.getAsString());
				}

				Integer customModelData = weapon.has("customModelData") ? weapon.get("customModelData").getAsInt() : null;

				registerWeapon(id, dice, ability, damageType, hands, versatileDice, classes, name, baseItem, customModelData);
				onId.accept(id);
				count++;
			} catch (RuntimeException e) {
				DndsheetsMod.LOGGER.warn("Skipping weapon #{} in {}: {}", index, source, e.toString());
			}
		}
		return count;
	}

	//If the id is directly a real Minecraft item (e.g. "minecraft:bow"), it's given as-is, without an
	//NBT tag. If it's a custom id (e.g. "dndsheets:dagger"), it's tagged onto the base item
	//configured (via /dndweapons load) so the rest of the system recognizes it as that weapon.
	//Public: also used by WeaponCommand (/dndweapons give), the creative tab
	//(DndsheetsModCreativeTab) and PresetManager (a preset's starting weapon).
	public static ItemStack buildWeaponStack(String weaponId, int count) {
		ResourceLocation directLoc = ResourceLocation.tryParse(weaponId);
		Item directItem = directLoc != null ? ForgeRegistries.ITEMS.getValue(directLoc) : null;
		if (directItem != null && directItem != Items.AIR) {
			return new ItemStack(directItem, count);
		}

		WeaponGiveInfo giveInfo = giveInfoFor(weaponId);
		Item baseItem = Items.STICK;
		if (giveInfo != null) {
			ResourceLocation baseLoc = ResourceLocation.tryParse(giveInfo.baseItemId());
			Item resolved = baseLoc != null ? ForgeRegistries.ITEMS.getValue(baseLoc) : null;
			if (resolved != null) baseItem = resolved;
		}

		ItemStack stack = new ItemStack(baseItem, count);
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("weapon", weaponId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		if (giveInfo != null) {
			stack.setHoverName(ContentNames.of(giveInfo.displayName()));
			//Resource pack reskin: a custom model in assets/minecraft/models/item/<baseItem>.json can
			//map this number to a different model/texture, without the weapon having to share the one
			//belonging to the base item that represents it (e.g. a "Dagger" that no longer looks like an iron sword).
			if (giveInfo.customModelData() != null) stack.getOrCreateTag().putInt("CustomModelData", giveInfo.customModelData());
		}
		addHandsLore(stack, weaponDefaultFor(weaponId));
		return stack;
	}

	//For "identifying one- and two-handed weapons since some have bonuses" (playtesting feedback): a
	//line of lore visible in the item's tooltip, not just a value in the JSON that only the code reads.
	//Weapons with "hands":"one" (the default case, almost all of them) carry no extra lore —
	//there's nothing special to flag.
	private static void addHandsLore(ItemStack stack, WeaponDefault weaponDefault) {
		if (weaponDefault == null) return;

		Component text = switch (weaponDefault.hands()) {
			case "two" -> Component.translatable("chat.dndsheets.weapon.lore_two_handed");
			case "versatile" -> weaponDefault.isVersatile()
				? Component.translatable("chat.dndsheets.weapon.lore_versatile", weaponDefault.dice(), weaponDefault.versatileDice())
				: null;
			default -> null;
		};
		if (text == null) return;

		net.minecraft.nbt.ListTag lore = new net.minecraft.nbt.ListTag();
		lore.add(net.minecraft.nbt.StringTag.valueOf(Component.Serializer.toJson(text.copy().withStyle(ChatFormatting.GRAY))));
		stack.getOrCreateTagElement("display").put("Lore", lore);
	}

	/**
	 * @param characterClass free text from the sheet's "Class &amp; Level" field.
	 * @return the hit die size (6, 8, 10, 12...) for the first configured class name found
	 * as a substring of {@code characterClass}, or 8 (the most common in 5e) if none match.
	 */
	public static int hitDieFor(String characterClass) {
		if (characterClass == null) return 8;
		String normalized = characterClass.toLowerCase(Locale.ROOT);
		for (Map.Entry<String, Integer> entry : hitDiceByClass.entrySet()) {
			if (normalized.contains(entry.getKey())) return entry.getValue();
		}
		return 8;
	}

	/**
	 * @param itemId registry name of the held item, e.g. "minecraft:iron_sword".
	 * @return the configured default damage die + ability for that item, or null if it isn't a recognized weapon.
	 */
	@Nullable
	public static WeaponDefault weaponDefaultFor(String itemId) {
		if (itemId == null) return null;
		WeaponDefault fromJson = jsonWeapons.get(itemId);
		if (fromJson != null) return fromJson;
		return weaponDamageByItem.get(itemId);
	}

	/**
	 * <p>Compatibility with weapons from OTHER mods (Tinkers' Construct and any other) without needing a
	 * per-item JSON: if nobody registered this id by hand (neither JSON nor .toml) but the item already
	 * declares real attack damage via the vanilla {@code ATTACK_DAMAGE} attribute — the same one that
	 * makes the tooltip say "X Attack Damage" and that lets normal Minecraft combat already deal more
	 * damage with it — it's approximated as a real 5e weapon with THAT damage, instead of always being
	 * treated as an unconfigured weapon.</p>
	 *
	 * <p>Read from the item's real INSTANCE, not from a fixed value per id: Tinkers' Construct tools
	 * store their stats via NBT, different for every forged tool, and that attribute already reflects
	 * them without this mod having to know anything about Tinkers' Construct (or any other mod) in
	 * particular — any item from any mod that participates in normal vanilla combat already exposes this
	 * same attribute; it's the mechanism Minecraft uses for modded combat to work at all.</p>
	 *
	 * <p><b>Deliberate simplifications</b>: it's expressed as a SINGLE "1dX" die with the same average as
	 * the item's real damage (X = 2×damage-1, e.g. +6 real damage → 1d11, average 6) instead of a fixed
	 * number with no variance — it's still a real roll, with its own variance, and it doubles on a
	 * critical hit like any other die. It's not an exact conversion (there's no single "correct die" for
	 * a real Minecraft number), but it preserves the item's average power as balanced by the mod that
	 * adds it. Always Strength and physical damage, no versatility or special damage type; a hand-written
	 * registration (JSON or .toml) for a specific item still overrides this (see {@link #weaponDefaultFor},
	 * which is checked first) — for example, to treat a modded dagger as Dexterity instead of Strength.</p>
	 */
	@Nullable
	public static WeaponDefault autoDetectWeapon(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		double bonus = 0;
		for (AttributeModifier modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
			if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) bonus += modifier.getAmount();
		}
		int average = (int) Math.round(bonus);
		if (average <= 0) return null; //Doesn't even declare more damage than bare hands: don't treat it as a weapon.
		int sides = Math.max(1, 2 * average - 1);
		return new WeaponDefault("1d" + sides, "str", "physical", "one", null, List.of());
	}

	/**
	 * @return the item's custom weapon id from its {@code {dndsheets:{weapon:"..."}}} NBT tag if it has
	 * one (lets any base item be reskinned into a dagger/lanza/dardo/etc. for loot purposes), otherwise
	 * its plain Minecraft registry id (e.g. "minecraft:iron_sword").
	 */
	public static String weaponIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag != null && tag.contains("dndsheets")) {
			CompoundTag dndTag = tag.getCompound("dndsheets");
			if (dndTag.contains("weapon")) return dndTag.getString("weapon");
		}
		return ForgeRegistries.ITEMS.getKey(stack.getItem()).toString();
	}

	/**
	 * @param enchantId registry name of the enchantment, e.g. "minecraft:sharpness".
	 * @return the configured flat damage bonus per level of that enchantment, or null if it isn't configured.
	 */
	@Nullable
	public static Integer enchantBonusPerLevelFor(String enchantId) {
		if (enchantId == null) return null;
		return enchantBonusPerLevel.get(enchantId);
	}

	private static void reload() {
		List<String> configured = new java.util.ArrayList<>();
		for (String entry : effective(Table.HIT_DICE)) configured.add(entry);
		hitDiceByClass = parseHitDice(configured);

		Map<String, WeaponDefault> parsedWeapons = new LinkedHashMap<>();
		for (String entry : effective(Table.WEAPON_DAMAGE)) {
			String[] parts = entry.split(";");
			if (parts.length != 3) continue;
			parsedWeapons.put(parts[0].trim(), new WeaponDefault(parts[1].trim(), parts[2].trim().toLowerCase(Locale.ROOT), "physical", "one", null, List.of()));
		}
		weaponDamageByItem = parsedWeapons;

		Map<String, Integer> parsedEnchants = new LinkedHashMap<>();
		for (String entry : effective(Table.ENCHANT_BONUS)) {
			String[] parts = entry.split(";");
			if (parts.length != 2) continue;
			try {
				parsedEnchants.put(parts[0].trim(), Integer.parseInt(parts[1].trim()));
			} catch (NumberFormatException ignored) {
				//Invalid entries are already filtered out by isValidEnchantEntry(), but just in case.
			}
		}
		enchantBonusPerLevel = parsedEnchants;
	}

	@SubscribeEvent
	public static void onLoad(ModConfigEvent.Loading event) {
		reload();
	}

	@SubscribeEvent
	public static void onReload(ModConfigEvent.Reloading event) {
		reload();
	}
}
