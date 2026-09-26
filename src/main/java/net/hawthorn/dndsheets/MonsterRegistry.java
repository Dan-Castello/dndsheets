package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javax.annotation.Nullable;
import net.hawthorn.dndsheets.compat.PehkuiCompat;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>Monster stat blocks hot-loaded by {@code /dndmonsters load}, in memory (lost on server restart
 * unless the same file is reloaded). Spawned monsters are real vanilla mobs with {@code NoAI:1} and a
 * persistent NBT tag {@code {dndsheets:{monster:"id", currentHp:N}}} that ties them to their stat block
 * and tracks their real D&amp;D health.</p>
 */
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines that not a single caller used, not even the addons, which come in through here).
//An external mod calling these methods is exposed to their signature changing without notice. The only
//thing intended for external consumption is the api/event events, which do have real consumers.
public class MonsterRegistry {
	/**
	 * <p>How a monster LOOKS, using Minecraft's own pieces without bringing in any new model.</p>
	 *
	 * <p>The bestiary uses 41 vanilla models for 330 monsters, and very unevenly distributed: 52
	 * creatures are a vindicator, the 43 dragons among them. To someone playing, that reads as "it's all
	 * the same thing with a different name" — and it's the clearest edge VTTs with sheet and art
	 * libraries have over this.</p>
	 *
	 * <p>Without the ability to ship third-party art (licensing) or invent models, what can actually be
	 * done is use the pieces Minecraft already has: <b>visible equipment</b> (a skeleton with an iron
	 * helmet and sword doesn't read as the same critter as a bare one), <b>baby size</b>, and
	 * <b>glowing</b>. It's free, weighs nothing, and mostly differentiates the 51 humanoids, which is
	 * where it showed the most.</p>
	 *
	 * <p>The real long-term answer, the one that gives a true ecosystem, is in {@code baseEntity}: it
	 * accepts ANY registered entity, including one from another mod. An addon with real dragons plugs in
	 * by writing its id there, without touching this mod.</p>
	 */
	public record Appearance(String mainHand, String offHand, String helmet, String chestplate, String leggings,
			String boots, boolean baby, boolean glowing) {

		static final Appearance DEFAULT = new Appearance(null, null, null, null, null, null, false, false);
		static final Appearance GLOWING = new Appearance(null, null, null, null, null, null, false, true);

		public boolean isDefault() {
			return this == DEFAULT || (mainHand == null && offHand == null && helmet == null && chestplate == null
				&& leggings == null && boots == null && !baby && !glowing);
		}
	}

	public record MonsterAttack(String name, String toHitAbility, String dice, String damageAbility, String damageType, String effectName, String effectDice, int effectTurns) {
		public boolean appliesEffect() { return effectName != null; }
	}
	public record MonsterSpell(String name, String saveAbility, int saveDc, String dice, boolean halfOnSave, String damageType, String effectName, String effectDice, int effectTurns) {
		public boolean appliesEffect() { return effectName != null; }
	}

	/**
	 * @param nonmagicalAffinities same as {@code damageAffinities}, but only against NON-magical attacks.
	 *                             It's the SRD's most common form ("bludgeoning, piercing, and slashing
	 *                             from nonmagical attacks") and affects lycanthropes, demons, devils, and
	 *                             a good chunk of the mid-CR bestiary. The silver/adamantine variants are
	 *                             treated here as simply "nonmagical": the mod doesn't have those
	 *                             materials, and the alternative was ignoring the resistance entirely.
	 * @param damageAffinities damage type → {@code resistant}/{@code vulnerable}/{@code immune}, the same
	 *                         vocabulary as {@code damageAffinities} on a player's sheet (see
	 *                         {@link DamageTypes#multiplierForLabel}). Empty = no affinities, which is how
	 *                         every monster behaved until now.
	 */
	public record MonsterStatBlock(
		String id, String name, String baseEntityId, int ac, int maxHp,
		Map<String, Integer> abilities, int proficiencyBonus,
		List<MonsterAttack> attacks, List<MonsterSpell> spells,
		Map<String, String> damageAffinities, Map<String, String> nonmagicalAffinities,
		CreatureType type, int legendaryResistances, int legendaryActions, int attacksPerTurn,
		//Alongside "type" and not inside "appearance": in 5e, size is a declared property of the
		//creature, just like its type, and the SRD writes it that way. Today only PehkuiCompat reads it to
		//render it, but the day some rule asks about it (grappling a Large creature, a tight space,
		//shoving) the data is already in place and no pack needs migrating. Absent = UNKNOWN = renders as always.
		CreatureSize size,
		Appearance appearance,
		//"ai": true keeps the base entity's AI alive instead of summoning it frozen. It exists for NPC mod
		//entities (EasyNPC and friends), which bring their own goals — patrolling, following the party,
		//staying at their post — and are the reason baseEntityId accepts any installed entity: without
		//this, spawnAt's setNoAi killed them the instant they appeared and left them as decoration. In
		//COMBAT the mod still runs the show: TurnManager.freeze switches that AI off for the duration of
		//the encounter and gives it back when it ends, so the monster resolves its turn with 5e rules
		//(MonsterActionManager.autoAct) rather than vanilla AI. In other words: the AI is for OUTSIDE
		//combat, which is where there used to be nothing.
		boolean keepsOwnAi,
		//"ownClock": true pulls this creature out of the turn ORDER without pulling it out of combat. It
		//still counts toward ending the encounter, still takes damage under every 5e rule, and still
		//stops moving if it gets paralyzed or stunned — the only thing it skips is having to wait for its
		//turn: it acts on its own every 6 seconds, which is how long a 5e round lasts (see
		//OwnClockManager). It's not "no rules," it's "no queue": the action economy is the same, just out
		//of sync.
		boolean ownClock,
		//"flies": true — the creature has a flying speed in its SRD stat block (giant eagle, giant owl,
		//pteranodon...). Only DruidWildShapeManager uses it: transforming into a flying beast grants real
		//flight (not just the model), and reverting takes it away, same as with AC and physical abilities.
		//Absent = false = how the whole bestiary behaved before this.
		boolean flies
	) {
		public int abilityModifier(String key) {
			Integer score = abilities.get(key.toLowerCase(Locale.ROOT));
			return score == null ? 0 : Math.floorDiv(score - 10, 2);
		}
	}

	/**
	 * <p>Creature type of a world monster, or {@link CreatureType#UNKNOWN} if it has none (a
	 * compatibility mob, a generic NPC, or a pack written before this field existed).</p>
	 */
	//--- Legendary Resistance: uses left for THIS specific monster, not its species. They live in its NBT
	//tag alongside HP, same as everything else that's per-instance: two dragons with the same id spend
	//theirs separately, and Minecraft already saves and loads that compartment on its own.

	private static final String LEGENDARY_LEFT = "legendaryLeft";

	/**
	 * <p>Legendary Resistance uses left. With the tag not set yet (a monster summoned before the rule
	 * existed, or freshly spawned) it returns its block's own: the default value is "has them all," not
	 * "has none."</p>
	 */
	public static int legendaryResistancesLeft(Entity entity) {
		MonsterStatBlock block = statBlockOf(entity);
		if (block == null || block.legendaryResistances() <= 0) return 0;
		CompoundTag tag = entity.getPersistentData().getCompound("dndsheets");
		return tag.contains(LEGENDARY_LEFT) ? Math.max(0, tag.getInt(LEGENDARY_LEFT)) : block.legendaryResistances();
	}

	/** Spends one. Returns false if it had none left. */
	public static boolean spendLegendaryResistance(Entity entity) {
		int left = legendaryResistancesLeft(entity);
		if (left <= 0) return false;
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets");
		tag.putInt(LEGENDARY_LEFT, left - 1);
		data.put("dndsheets", tag);
		return true;
	}

	public static CreatureType typeOf(Entity entity) {
		//A player is humanoid, and this isn't a minor detail: without it, Hold Person wouldn't work
		//on a PC — the spell's most common use at the table — because a player has no stat block to pull
		//a type from. Every playable SRD race is humanoid.
		if (entity instanceof Player) return CreatureType.HUMANOID;
		MonsterStatBlock block = statBlockOf(entity);
		return block != null ? block.type() : CreatureType.UNKNOWN;
	}

	private static final NamedRegistry<MonsterStatBlock> REGISTRY = new NamedRegistry<>("monster", MonsterStatBlock::id);

	public static void register(MonsterStatBlock block) {
		REGISTRY.register(block);
	}

	/** Overwrites without warning: see {@link NamedRegistry#replace}. Used by SummonManager on every summon. */
	public static void replace(MonsterStatBlock block) {
		REGISTRY.replace(block);
	}

	@Nullable
	public static MonsterStatBlock get(String id) {
		return REGISTRY.get(id);
	}

	/**
	 * <p>Changes ONLY the model of an already-registered monster, leaving its rules intact. Used by
	 * {@link MonsterSkins} so a dragon can become another mod's dragon without touching its stat block.</p>
	 *
	 * <p>It checks that the entity exists before changing anything, and that check is the whole safety
	 * net for the idea: a wrong id in an appearance pack leaves the vanilla model as it was instead of
	 * degrading a monster that worked. Without it, a typo would turn a vindicator into a zombie.</p>
	 *
	 * @return {@code true} if it was applied.
	 */
	public static boolean reskin(String id, String entityId) {
		MonsterStatBlock block = REGISTRY.get(id);
		if (block == null) return false;
		ResourceLocation loc = ResourceLocation.tryParse(entityId);
		if (loc == null || !ForgeRegistries.ENTITY_TYPES.containsKey(loc)) return false;

		REGISTRY.replace(new MonsterStatBlock(block.id(), block.name(), entityId, block.ac(), block.maxHp(),
			block.abilities(), block.proficiencyBonus(), block.attacks(), block.spells(), block.damageAffinities(),
			block.nonmagicalAffinities(), block.type(), block.legendaryResistances(), block.legendaryActions(),
			block.attacksPerTurn(), block.size(), block.appearance(), block.keepsOwnAi(), block.ownClock(), block.flies()));
		return true;
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Public: used by MonsterCommand (/dndmonsters load) and by DndPaths to solo-preload every .json in
	//the folder at server startup, without DndPaths having to depend on the command layer. It used to be
	//that a malformed monster halfway through the file aborted
	//the rest (visible only as a load WARN, invisible to the DM in chat) — JsonRegistryLoader now skips
	//per element, not per file.
	private static final JsonRegistryLoader<MonsterStatBlock> LOADER = new JsonRegistryLoader<>("monster", MonsterRegistry::parse, MonsterRegistry::register);

	/** Loads from an already-parsed JSON (a datapack or another mod's jar) — see ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	private static Appearance parseAppearance(JsonObject json) {
		if (json == null) return Appearance.DEFAULT;
		return new Appearance(
			str(json, "mainHand"), str(json, "offHand"), str(json, "helmet"), str(json, "chestplate"),
			str(json, "leggings"), str(json, "boots"),
			json.has("baby") && json.get("baby").getAsBoolean(),
			json.has("glowing") && json.get("glowing").getAsBoolean());
	}

	@Nullable
	private static String str(JsonObject json, String key) {
		return json.has(key) ? json.get(key).getAsString() : null;
	}

	public static MonsterStatBlock parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		String baseEntity = json.has("baseEntity") ? json.get("baseEntity").getAsString() : "minecraft:zombie";
		int ac = json.has("ac") ? json.get("ac").getAsInt() : 10;
		int hp = json.has("hp") ? json.get("hp").getAsInt() : 1;
		int prof = json.has("proficiencyBonus") ? json.get("proficiencyBonus").getAsInt() : 2;
		boolean keepsOwnAi = json.has("ai") && json.get("ai").getAsBoolean();
		boolean ownClock = json.has("ownClock") && json.get("ownClock").getAsBoolean();
		boolean flies = json.has("flies") && json.get("flies").getAsBoolean();

		Map<String, Integer> abilities = new LinkedHashMap<>();
		JsonObject abilitiesJson = json.has("abilities") ? json.getAsJsonObject("abilities") : null;
		for (String key : Combatant.ABILITIES) {
			abilities.put(key, abilitiesJson != null && abilitiesJson.has(key) ? abilitiesJson.get(key).getAsInt() : 10);
		}

		List<MonsterAttack> attacks = new ArrayList<>();
		if (json.has("attacks")) {
			for (JsonElement el : json.getAsJsonArray("attacks")) {
				attacks.add(parseAttack(el.getAsJsonObject()));
			}
		}

		List<MonsterSpell> spells = new ArrayList<>();
		if (json.has("abilities_special")) {
			for (JsonElement el : json.getAsJsonArray("abilities_special")) {
				JsonObject s = el.getAsJsonObject();
				JsonObject effect = s.has("appliesEffect") ? s.getAsJsonObject("appliesEffect") : null;
				spells.add(new MonsterSpell(
					s.get("name").getAsString(),
					s.has("saveAbility") ? s.get("saveAbility").getAsString().toLowerCase(Locale.ROOT) : "dex",
					s.has("saveDc") ? s.get("saveDc").getAsInt() : 10,
					s.get("dice").getAsString(),
					!s.has("halfOnSave") || s.get("halfOnSave").getAsBoolean(),
					s.has("damageType") ? DamageTypes.normalize(s.get("damageType").getAsString()) : "physical",
					effect != null ? effect.get("name").getAsString() : null,
					effect != null ? effect.get("dice").getAsString() : null,
					effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0
				));
			}
		}

		//Optional: a monster without them behaves exactly as before, with no resistances.
		Map<String, String> damageAffinities = readAffinities(json, "damageAffinities");
		Map<String, String> nonmagicalAffinities = readAffinities(json, "nonmagicalAffinities");

		//Also optional: a pack written before this field existed still loads fine, with UNKNOWN, and the
		//only thing it loses is access to the rules that ask about type.
		CreatureType type = CreatureType.parse(json.has("type") ? json.get("type").getAsString() : null);
		//Absent = 0 = not a boss. That's the correct default: Legendary Resistance belongs to a good
		//dozen SRD creatures, not the entire bestiary.
		int legendaryResistances = json.has("legendaryResistances") ? Math.max(0, json.get("legendaryResistances").getAsInt()) : 0;
		//How many legendary actions it can spend per round (3 in almost all of the SRD). Absent = 0 = acts
		//only on its own turn, like any other monster.
		int legendaryActions = json.has("legendaryActions") ? Math.max(0, json.get("legendaryActions").getAsInt()) : 0;
		//Multiattack: how many attacks it makes on ITS turn. 1 by default, which is how the whole bestiary
		//used to behave. The cap of 6 isn't a 5e rule, it's a safety net: an absurd number in a JSON
		//(deliberate or a typo) turns a turn into a burst of chat messages nobody can read.
		int attacksPerTurn = json.has("multiattack") ? Math.max(1, Math.min(6, json.get("multiattack").getAsInt())) : 1;
		//Just as optional as "type," and for the same reason: a pack from before this field loads with
		//UNKNOWN, which is exactly the size it used to render as before the field existed.
		CreatureSize size = CreatureSize.parse(json.has("size") ? json.get("size").getAsString() : null);
		Appearance appearance = parseAppearance(json.has("appearance") ? json.getAsJsonObject("appearance") : null);

		return new MonsterStatBlock(id, name, baseEntity, ac, hp, abilities, prof, attacks, spells, damageAffinities, nonmagicalAffinities, type, legendaryResistances, legendaryActions, attacksPerTurn, size, appearance, keepsOwnAi, ownClock, flies);
	}

	private static Map<String, String> readAffinities(JsonObject json, String field) {
		Map<String, String> result = new HashMap<>();
		if (!json.has(field)) return result;
		JsonObject affinities = json.getAsJsonObject(field);
		for (String type : affinities.keySet()) {
			//The KEY is normalized the same way as the hit's damage type that will look it up: a block
			//written in English ("fire") has to match the damage that arrives ("fire") or the resistance
			//doesn't exist.
			result.put(DamageTypes.normalize(type), affinities.get(type).getAsString().toLowerCase(Locale.ROOT));
		}
		return result;
	}

	//Extracted from parse() so it's also used by the custom attack a DM adds live to an already-summoned
	//monster (see addCustomAttack) — same format, one loose "attacks" object.
	private static MonsterAttack parseAttack(JsonObject a) {
		JsonObject effect = a.has("appliesEffect") ? a.getAsJsonObject("appliesEffect") : null;
		return new MonsterAttack(
			a.get("name").getAsString(),
			a.has("toHitAbility") ? a.get("toHitAbility").getAsString().toLowerCase(Locale.ROOT) : "str",
			a.get("dice").getAsString(),
			a.has("damageAbility") ? a.get("damageAbility").getAsString().toLowerCase(Locale.ROOT) : "str",
			a.has("damageType") ? DamageTypes.normalize(a.get("damageType").getAsString()) : "physical",
			effect != null ? effect.get("name").getAsString() : null,
			effect != null ? effect.get("dice").getAsString() : null,
			effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0
		);
	}

	//Public: used by the in-game content creator to save a summoned monster (usually a generic NPC
	//already built up with live attacks, see client.gui.MonsterTemplateSaveScreen) as a reusable JSON
	//template — same field names parse() expects, so loading it back works the same as any other monster pack.
	public static JsonObject toJson(MonsterStatBlock block) {
		JsonObject json = new JsonObject();
		json.addProperty("id", block.id());
		json.addProperty("name", block.name());
		if (block.type() != CreatureType.UNKNOWN) json.addProperty("type", block.type().label());
		if (block.size() != CreatureSize.UNKNOWN) json.addProperty("size", block.size().label());
		if (block.legendaryResistances() > 0) json.addProperty("legendaryResistances", block.legendaryResistances());
		if (block.legendaryActions() > 0) json.addProperty("legendaryActions", block.legendaryActions());
		if (block.attacksPerTurn() > 1) json.addProperty("multiattack", block.attacksPerTurn());
		json.addProperty("baseEntity", block.baseEntityId());
		if (block.keepsOwnAi()) json.addProperty("ai", true);
		if (block.ownClock()) json.addProperty("ownClock", true);
		json.addProperty("ac", block.ac());
		json.addProperty("hp", block.maxHp());
		json.addProperty("proficiencyBonus", block.proficiencyBonus());

		JsonObject abilities = new JsonObject();
		for (Map.Entry<String, Integer> entry : block.abilities().entrySet()) abilities.addProperty(entry.getKey(), entry.getValue());
		json.add("abilities", abilities);

		if (!block.attacks().isEmpty()) {
			JsonArray attacks = new JsonArray();
			for (MonsterAttack attack : block.attacks()) attacks.add(attackToJson(attack));
			json.add("attacks", attacks);
		}

		//Omitted if empty, to avoid cluttering every saved monster with an object that says nothing:
		//parse() already treats "no field" and "empty" the same way.
		writeAffinities(json, "damageAffinities", block.damageAffinities());
		writeAffinities(json, "nonmagicalAffinities", block.nonmagicalAffinities());
		writeAppearance(json, block.appearance());
		return json;
	}

	//Omitted if empty, to avoid cluttering every saved monster with an object that says nothing:
	//parse() already treats "no field" and "empty" the same way.
	private static void writeAffinities(JsonObject json, String field, Map<String, String> affinities) {
		if (affinities.isEmpty()) return;
		JsonObject out = new JsonObject();
		for (Map.Entry<String, String> entry : affinities.entrySet()) out.addProperty(entry.getKey(), entry.getValue());
		json.add(field, out);
	}

	private static void writeAppearance(JsonObject json, Appearance look) {
		if (look == null || look.isDefault()) return;
		JsonObject out = new JsonObject();
		if (look.mainHand() != null) out.addProperty("mainHand", look.mainHand());
		if (look.offHand() != null) out.addProperty("offHand", look.offHand());
		if (look.helmet() != null) out.addProperty("helmet", look.helmet());
		if (look.chestplate() != null) out.addProperty("chestplate", look.chestplate());
		if (look.leggings() != null) out.addProperty("leggings", look.leggings());
		if (look.boots() != null) out.addProperty("boots", look.boots());
		if (look.baby()) out.addProperty("baby", true);
		if (look.glowing()) out.addProperty("glowing", true);
		json.add("appearance", out);
	}

	private static JsonObject attackToJson(MonsterAttack attack) {
		JsonObject a = new JsonObject();
		a.addProperty("name", attack.name());
		a.addProperty("toHitAbility", attack.toHitAbility());
		a.addProperty("dice", attack.dice());
		a.addProperty("damageAbility", attack.damageAbility());
		a.addProperty("damageType", attack.damageType());
		return a;
	}

	//--- Persistent NBT tag of the spawned mob (Entity#getPersistentData, saved and loaded by Minecraft itself) ---

	public static void tagAsMonster(Entity entity, String monsterId, int currentHp) {
		CompoundTag tag = new CompoundTag();
		tag.putString("monster", monsterId);
		tag.putInt("currentHp", currentHp);
		entity.getPersistentData().put("dndsheets", tag);
	}

	/** Whether this creature plays outside the turn order. See {@code OwnClockManager}. */
	public static boolean isOffClock(Entity entity) {
		MonsterStatBlock block = statBlockOf(entity);
		return block != null && block.ownClock();
	}

	@Nullable
	public static String monsterIdOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		CompoundTag tag = data.getCompound("dndsheets");
		return tag.contains("monster") ? tag.getString("monster") : null;
	}

	@Nullable
	/**
	 * <p>The name to show for this creature: the one someone gave it (name tag, anvil, {@code /data}) if any,
	 * otherwise the stat block's. Spawning already sets a custom name — the stat block's own translation key —
	 * so "has a custom name" isn't enough: only a name that ISN'T that key counts as the table's own.</p>
	 */
	public static String displayNameOf(Entity entity, MonsterStatBlock block) {
		net.minecraft.network.chat.Component custom = entity.getCustomName();
		if (custom == null) return block.name();
		boolean defaultName = custom.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc
			&& tc.getKey().equals(block.name());
		return defaultName ? block.name() : custom.getString();
	}

	public static MonsterStatBlock statBlockOf(Entity entity) {
		String id = monsterIdOf(entity);
		return id == null ? null : get(id);
	}

	public static int currentHpOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return 0;
		return data.getCompound("dndsheets").getInt("currentHp");
	}

	public static void setCurrentHp(Entity entity, int hp) {
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Empty if it didn't exist; tagAsMonster already sets it.
		tag.putInt("currentHp", hp);
		data.put("dndsheets", tag);
	}

	//--- Per-instance custom attacks: so an already-summoned monster (its species
	//shared among everything with its id) can be edited LIVE without touching JSON or restarting the server.
	//They are stored apart from the shared stat block, in the summoned mob's own NBT tag. Deliberate
	//simplification: no "appliesEffect" (poison, etc.) on custom ones, just attack+damage; if an effect is
	//needed, the whole monster is edited/loaded via JSON as before.

	//Called on every monster action (automatic turn, opportunity attack, or the DM opening its
	//menu), so the parsed JSON is cached by entity UUID instead of being reparsed each time; invalidated
	//only in the two places that actually change the NBT (saveCustomAttacks/clearCustomAttacks).
	//Keyed by UUID and NOT by entity.getId(): an entity's numeric id gets RECYCLED when the
	//entity dies or is unloaded, so a new monster could inherit a dead one's id and with it
	//the previous one's custom attacks. It wasn't just a memory leak: it returned another monster's data.
	//It is also evicted on death (see TurnManager.onMonsterDeath) so it doesn't grow without bound.
	private static final Map<java.util.UUID, List<MonsterAttack>> customAttacksCache = new HashMap<>();

	public static List<MonsterAttack> customAttacksOf(Entity entity) {
		List<MonsterAttack> cached = customAttacksCache.get(entity.getUUID());
		if (cached != null) return cached;

		CompoundTag data = entity.getPersistentData();
		List<MonsterAttack> result;
		if (!data.contains("dndsheets")) {
			result = List.of();
		} else {
			CompoundTag tag = data.getCompound("dndsheets");
			if (!tag.contains("customAttacks")) {
				result = List.of();
			} else {
				result = new ArrayList<>();
				for (JsonElement el : JsonParser.parseString(tag.getString("customAttacks")).getAsJsonArray()) {
					result.add(parseAttack(el.getAsJsonObject()));
				}
			}
		}
		customAttacksCache.put(entity.getUUID(), result);
		return result;
	}

	public static void addCustomAttack(Entity entity, MonsterAttack attack) {
		List<MonsterAttack> current = new ArrayList<>(customAttacksOf(entity));
		current.removeIf(existing -> existing.name().equalsIgnoreCase(attack.name())); //Replaces any existing attack with that name.
		current.add(attack);
		saveCustomAttacks(entity, current);
	}

	public static boolean removeCustomAttack(Entity entity, String name) {
		List<MonsterAttack> current = new ArrayList<>(customAttacksOf(entity));
		boolean removed = current.removeIf(existing -> existing.name().equalsIgnoreCase(name));
		if (removed) saveCustomAttacks(entity, current);
		return removed;
	}

	/** Evicts the cache entry of a monster that has just died. Called from TurnManager.onMonsterDeath. */
	public static void forgetCustomAttacks(Entity entity) {
		customAttacksCache.remove(entity.getUUID());
	}

	public static void clearCustomAttacks(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (data.contains("dndsheets")) data.getCompound("dndsheets").remove("customAttacks");
		customAttacksCache.remove(entity.getUUID());
	}

	private static void saveCustomAttacks(Entity entity, List<MonsterAttack> attacks) {
		JsonArray array = new JsonArray();
		for (MonsterAttack attack : attacks) array.add(attackToJson(attack));

		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Empty if it didn't exist; only happens if the target was not a tagged monster.
		tag.putString("customAttacks", array.toString());
		data.put("dndsheets", tag);

		customAttacksCache.put(entity.getUUID(), List.copyOf(attacks)); //Refresh the cache with what we already have in memory, instead of invalidating and reparsing the JSON we just wrote.
	}

	//--- DM Rod: any item tagged {dndsheets:{dmtool:true}} (same pattern as custom weapons) ---

	public static boolean isDmTool(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("dmtool");
	}

	//--- Movement Rod: same pattern as the DM Rod, but for repositioning an already-summoned monster
	//without going through its attack menu (see MonsterActionManager.onSelectMonsterToMove) ---

	public static boolean isMoveTool(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("movetool");
	}

	//--- Summon card: any item tagged {dndsheets:{monsterSpawn:"id"}} (used like a vanilla spawn egg) ---

	@Nullable
	public static String monsterSpawnIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		return dndTag.contains("monsterSpawn") ? dndTag.getString("monsterSpawn") : null;
	}

	//For the creative tab (DndsheetsModCreativeTab): one relabeled spawn egg per loaded monster.
	public static ItemStack buildSpawnCard(String monsterId) {
		MonsterStatBlock block = get(monsterId);
		ItemStack stack = ItemLook.SUMMON_CARD.applyTo(
			new ItemStack(net.hawthorn.dndsheets.init.DndsheetsModItems.TOKEN.get()));
		CompoundTag dndTag = new CompoundTag();
		dndTag.putString("monsterSpawn", monsterId);
		stack.getOrCreateTag().put("dndsheets", dndTag);
		//"Invocar: " ("Summon: ") used to be hand-written here. The fixed-text lint didn't catch it because it looks for a lowercase letter
		//followed by a space and this one has a colon in between; see checkChatMessagesAreTranslatable, which
		//now also forbids any literal inside a setHoverName.
		stack.setHoverName(Component.translatable("chat.dndsheets.monster.summon_card_name",
			ContentNames.of(block != null ? block.name() : monsterId)));
		return stack;
	}

	//--- Generic NPC: a blank stat block registered on the fly, with no JSON involved, so
	//the DM has a base to fill in live with /dndmonsters attack add (see MonsterCommand). Each
	//summon is registered under its own id, so two generic NPCs never share a stat
	//block even if they have the same name.

	private static int genericCounter = 0;

	public static Entity spawnGeneric(ServerLevel level, double x, double y, double z, String name, String baseEntityId, int ac, int hp) {
		String id = "dndsheets:npc_" + (++genericCounter);
		Map<String, Integer> abilities = new LinkedHashMap<>();
		for (String key : Combatant.ABILITIES) abilities.put(key, 10);

		register(new MonsterStatBlock(id, name, baseEntityId, Math.max(0, ac), Math.max(1, hp), abilities, 2, new ArrayList<>(), new ArrayList<>(), new HashMap<>(), new HashMap<>(), CreatureType.UNKNOWN, 0, 0, 1, CreatureSize.UNKNOWN, Appearance.DEFAULT, false, false, false));
		return spawnAt(level, x, y, z, id);
	}

	/**
	 * <p>Summons a loaded monster's base mob at the given position, just like
	 * {@code /dndmonsters spawn} does (and the summon card in the creative tab): with no AI, with its name
	 * visible, and with the persistent NBT tag that ties it to its stat block.</p>
	 * @return the summoned entity, or null if the monster doesn't exist or its base item is not valid.
	 */
	public static Entity spawnAt(ServerLevel level, double x, double y, double z, String monsterId) {
		return spawnAt(level, x, y, z, monsterId, null);
	}

	/**
	 * @param configure runs on the freshly created entity BEFORE it enters the turn order.
	 *                  It exists because some state decides how it enters: a summon is tagged with its
	 *                  owner, and {@code addLateMonster} reads that tag to know whether it is an enemy or an ally.
	 *                  Tagging it afterwards put it into the initiative as an enemy, and then the combat never
	 *                  ended for as long as it lasted.
	 */
	@Nullable
	public static Entity spawnAt(ServerLevel level, double x, double y, double z, String monsterId,
			java.util.function.Consumer<Entity> configure) {
		MonsterStatBlock block = get(monsterId);
		if (block == null) return null;

		ResourceLocation entityLoc = ResourceLocation.tryParse(block.baseEntityId());
		EntityType<?> type = entityLoc != null ? ForgeRegistries.ENTITY_TYPES.getValue(entityLoc) : null;
		if (type == null) {
			//An unknown id is almost always an addon pointing at an entity from ANOTHER mod that isn't
			//installed. Returning null left the DM with a command that did nothing and no explanation. A
			//zombie with the right name and stats is a playable token; nothing is not.
			DndsheetsMod.LOGGER.warn("dndsheets: monster \"{}\" asks for entity \"{}\", which does not exist (is the mod that provides it missing?). Using a zombie.",
				monsterId, block.baseEntityId());
			type = EntityType.ZOMBIE;
		}

		Entity entity = type.create(level);
		if (entity == null) return null;

		entity.moveTo(x, y, z, 0, 0);
		entity.setCustomName(ContentNames.of(block.name()));
		entity.setCustomNameVisible(true);
		//Frozen unless the block asks otherwise with "ai": true; see keepsOwnAi. A boss with its own
		//clock needs it switched on by definition: it moves on its own throughout the combat.
		if (entity instanceof Mob mob) mob.setNoAi(!block.keepsOwnAi() && !block.ownClock());
		applyLooks(entity, block);
		//Difficulty scaling, same method as Combatant.MonsterCombatant.maxHp(), so the monster
		//is born with the HP its own bar is going to announce, instead of appearing "already hit" on easy or
		//"overhealed" on hard.
		tagAsMonster(entity, monsterId, Config.scaleMonsterMaxHp(block.maxHp()));
		if (configure != null) configure.accept(entity);

		level.addFreshEntity(entity);

		//Without this, NoAI + yaw 0 makes every summoned monster always face north.
		//They are oriented toward the nearest player so it's clear who they are threatening.
		Player nearest = level.getNearestPlayer(entity, 30);
		if (nearest != null) faceTarget(entity, nearest);

		//If summoned in the middle of a combat already under way, it joins the turn order right away; otherwise it
		//would be uncontrollable and could cause the combat to be considered over while it was still alive.
		TurnManager.addLateMonster(level, entity, block.name());

		return entity;
	}

	/**
	 * <p>Dresses the monster with whatever its {@code appearance} block says. Everything touched here is vanilla:
	 * visible equipment, baby size and glow.</p>
	 *
	 * <p>Equipment drop chances are set to 0. A tabletop monster is a token, not a source of loot:
	 * if the helmet you gave it to tell it apart from its three siblings falls to the ground when it is
	 * killed, you've turned a visual decision into a reward the DM hadn't handed out.</p>
	 */
	/**
	 * <p>Attaches a stat block to a creature that <b>already exists</b>. From that moment on
	 * {@code Combatant.of} resolves it like any bestiary monster: AC, HP, resistances,
	 * attacks, conditions and its own turn, because the link is the same persistent NBT tag that
	 * {@link #spawnAt} writes.</p>
	 *
	 * <p>It exists so the creature can be built wherever it is best built (an NPC mod provides skin, pose,
	 * dialogue and goals that this mod lacks) and then declared here for what it is. For that reason it is
	 * <b>conservative</b> about what the creature already came with:</p>
	 * <ul>
	 *   <li><b>HP do</b> get set to the block's. Inheriting the 20 of the villager it was a second ago
	 *       for a 52-HP captain preserves nothing; it's a badly applied block.</li>
	 *   <li><b>The name does not</b>, if it already had its own: whoever built it gave it the name they wanted, and that
	 *       is more specific than the species name.</li>
	 *   <li><b>Equipment, only the slots the block declares</b> ({@code equip} ignores empty ones), so
	 *       a block that only says "sword" gives it the sword and leaves its armor alone.</li>
	 *   <li><b>The AI is left untouched.</b> If it could patrol, it still can; turn mode switches it off for the
	 *       duration of combat and gives it back when it ends (see {@code TurnManager.freeze}).</li>
	 * </ul>
	 */
	public static void applyStatBlock(Entity target, MonsterStatBlock block) {
		//Same difficulty scaling as spawnAt; see the comment there.
		int scaledMaxHp = Config.scaleMonsterMaxHp(block.maxHp());
		tagAsMonster(target, block.id(), scaledMaxHp);
		if (target instanceof net.minecraft.world.entity.LivingEntity living
				&& living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH) != null) {
			living.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(scaledMaxHp);
			living.setHealth(scaledMaxHp);
		}
		if (!target.hasCustomName()) {
			target.setCustomName(ContentNames.of(block.name()));
			target.setCustomNameVisible(true);
		}
		applyLooks(target, block);
	}

	/**
	 * <p>Everything that decides how a creature LOOKS, in a single place: equipment, baby size, glow and size. It takes
	 * the whole block rather than a bare {@link Appearance} precisely because of size, which lives alongside the type and not
	 * inside the appearance (see the record), so there are no two calls someone could make halfway from a
	 * third place.</p>
	 */
	private static void applyLooks(Entity entity, MonsterStatBlock block) {
		//BEFORE the early return below, not after: a Gargantuan with no equipment, no baby flag and no glow
		//has the DEFAULT appearance (the most common case in the bestiary), so with the order reversed it
		//would go unscaled, which is exactly the majority of monsters this field exists to fix.
		PehkuiCompat.applySize(entity, block.size());

		Appearance look = block.appearance();
		if (look == null || look.isDefault()) return;
		if (look.glowing()) entity.setGlowingTag(true);
		if (look.baby()) {
			//Zombie is NOT an AgeableMob (the undead don't grow up), so both branches are needed.
			if (entity instanceof net.minecraft.world.entity.monster.Zombie zombie) zombie.setBaby(true);
			else if (entity instanceof net.minecraft.world.entity.AgeableMob ageable) ageable.setBaby(true);
		}
		if (!(entity instanceof Mob mob)) return;
		equip(mob, net.minecraft.world.entity.EquipmentSlot.MAINHAND, look.mainHand());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.OFFHAND, look.offHand());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.HEAD, look.helmet());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.CHEST, look.chestplate());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.LEGS, look.leggings());
		equip(mob, net.minecraft.world.entity.EquipmentSlot.FEET, look.boots());
	}

	private static void equip(Mob mob, net.minecraft.world.entity.EquipmentSlot slot, String itemId) {
		if (itemId == null || itemId.isBlank()) return;
		ResourceLocation loc = ResourceLocation.tryParse(itemId);
		net.minecraft.world.item.Item item = loc != null ? ForgeRegistries.ITEMS.getValue(loc) : null;
		if (item == null) {
			//Warn and carry on: a nonexistent item must not stop the monster from appearing.
			DndsheetsMod.LOGGER.warn("dndsheets: \"{}\" is not a known item; not equipping that slot.", itemId);
			return;
		}
		mob.setItemSlot(slot, new net.minecraft.world.item.ItemStack(item));
		mob.setDropChance(slot, 0.0f);
	}

	//Public: CombatManager calls it every time a player hits a monster, so it turns to look at them
	//instead of staring at whoever was closest when it was summoned (or at the ground, if the target's
	//feet position is used instead of its eyes).
	public static void faceTarget(Entity monster, Entity target) {
		monster.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());
	}
}
