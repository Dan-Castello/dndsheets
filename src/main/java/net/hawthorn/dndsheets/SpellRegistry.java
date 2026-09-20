package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * <p>Spells hot-loaded by {@code /dndspells load}, in memory (same as
 * {@link MonsterRegistry}: lost on restart unless the same file is reloaded).</p>
 *
 * <p>A spell is resolved with the SAME mechanic that already exists in the mod, only the source of the
 * stats changes: {@code mode:"attack"} = attack roll (1d20 + casting ability + caster's proficiency)
 * against the target's real AC, same as a weapon or a monster attack; {@code
 * mode:"save"} = the target rolls their own save against the caster's DC (8 + proficiency +
 * casting ability), same as a monster spell.</p>
 */
//No stability contract: this mod doesn't publish a versioned API (the DndSheetsApi facade was
//deleted — 233 lines that not a single caller used, addons included, which come in through here).
//An external mod calling these methods risks their signature changing without notice. The only
//thing meant for external consumption is the api/event events, which do have real consumers.
public class SpellRegistry {
	public record Spell(
		String id, String name, int level, String mode,
		String castingAbility, String saveAbility, String dice, boolean halfOnSave, String damageType,
		boolean concentration, int aoeRadius, String aoeShape, String summonEntityId, boolean followsCasterFlag,
		String effectName, String effectDice, int effectTurns, String upcastDice,
		java.util.Set<CreatureType> affectsTypes, java.util.Set<CreatureType> immuneTypes, MagicSchool school,
		int declaredCastTicks
	) {
		/**
		 * <p>Area shape: {@code sphere} (default), {@code line}, or {@code cone}. The difference isn't
		 * cosmetic — a sphere originates at the impact point, while a line and a cone originate at the
		 * CASTER and extend toward where they're looking. Treating a cone as a radius would hit everything
		 * behind the caster, which is exactly why Lightning Bolt and Cone of Cold couldn't be imported
		 * until now.</p>
		 */
		public boolean originatesAtCaster() { return "line".equals(aoeShape) || "cone".equals(aoeShape) || isZone(); }

		/**
		 * <p>Persistent zone: not resolved on cast, it gets placed and damages whoever starts their turn
		 * inside it for several rounds (see {@link ZoneManager}). What defines it is PERSISTENCE, not
		 * shape: a Wall of Fire and a Moonbeam are the same capability with different geometry.</p>
		 *
		 * <p>{@code aoeShape:"wall"} still implies a zone for compatibility: walls existed before
		 * persistence was its own field, and a pack that already had them written must keep working
		 * untouched.</p>
		 */
		public boolean isZone() { return "zone".equals(mode) || "wall".equals(aoeShape); }

		/** The zone re-centers on the caster every round (Spirit Guardians). */
		public boolean followsCaster() { return followsCasterFlag; }

		/**
		 * <p>Modes that act on the caster themself and need no target in front: {@code buff} (extra dice on
		 * every weapon hit for the duration, see {@link WeaponBuffManager}) and {@code temphp} (temporary
		 * hit points, see {@link Combatant#grantTemporaryHp}).</p>
		 */
		public boolean isSelfTargeted() { return "buff".equals(mode) || "temphp".equals(mode); }

		/** A summon that joins initiative and attacks on its own on its turns — see {@link SummonManager}. */
		public boolean isSummon() { return "summon".equals(mode); }
		//Same pattern as MonsterRegistry.MonsterAttack/MonsterSpell: a concentration spell
		//(Spirit Guardians, Moonbeam...) can leave a status effect running for the duration of
		//concentration (see ConcentrationManager/TurnManager.applyEffect), which reverts only if
		//concentration is lost — this didn't exist before, "losing concentration" just rolled the die.
		public boolean appliesEffect() { return effectName != null; }

		/**
		 * <p>Does this spell do anything to a creature of this type? Hold Person only affects humanoids;
		 * Blight does nothing to undead or constructs. Until now both affected anything, which is the
		 * difference between a spell and its name.</p>
		 *
		 * <p><b>An unknown type is never filtered out.</b> The restriction only applies when what's in
		 * front is actually known: a mob from another mod with no stat block keeps behaving as always,
		 * instead of becoming immune to half the spell list for being unclassified. Same rule as in
		 * {@link CreatureType}: nothing triggers — or gets blocked — from a guess.</p>
		 */
		public boolean affects(CreatureType type) {
			if (type == CreatureType.UNKNOWN) return true;
			if (!affectsTypes.isEmpty() && !affectsTypes.contains(type)) return false;
			return !immuneTypes.contains(type);
		}

		/** Does casting this with a higher slot gain anything? See {@link #upcastTo}. */
		public boolean scalesWithSlot() { return upcastDice != null && !upcastDice.isEmpty(); }

		/**
		 * <p><b>Upcasting.</b> Returns the same spell resolved with a slot of level
		 * {@code slotLevel}: extra {@code upcastDice} for each level above its own (Fireball,
		 * 8d6 base and +1d6 per level, comes out to 10d6 with a 5th-level slot).</p>
		 *
		 * <p>Returns a COPY instead of a bare die string on purpose: {@code dice} is read from eight
		 * different places (attack, save, healing, temp HP, weapon buff, zone, summon, and twinning), and
		 * passing all of them a new parameter would have meant eight changed signatures for the same idea.
		 * This way upcasting works for every hit mode, including ones that don't exist yet.</p>
		 *
		 * <p>The name carries the level used because it goes straight to chat: without that, two Fireballs
		 * with different damage read as a mod bug rather than the decision it was.</p>
		 */
		/**
		 * <p><b>Cantrips that grow with the caster.</b> A damage cantrip gains a die at character levels
		 * 5, 11, and 17: a level-10 wizard's Fire Bolt does 2d10, not 1d10.</p>
		 *
		 * <p>This is the counterpart to {@link #upcastTo} for the one thing that can't be upcast by
		 * spending a slot. Without it, a caster's at-will attack stayed stuck at level-1 damage while
		 * everything else scaled — the same flaw the proficiency bonus used to have, on the attack a
		 * caster uses more times per session than any other.</p>
		 *
		 * <p>Nothing needs to be declared in the JSON: the progression is the same for every SRD damage
		 * cantrip, so it's derived from the level instead of being repeated eleven times by hand.</p>
		 */
		public Spell atCasterLevel(int characterLevel) {
			if (level != 0 || dice == null || "0".equals(dice.trim())) return this;

			int dice5eCount = 1 + (characterLevel >= 5 ? 1 : 0) + (characterLevel >= 11 ? 1 : 0) + (characterLevel >= 17 ? 1 : 0);
			if (dice5eCount == 1) return this;

			return new Spell(id, name, level, mode, castingAbility, saveAbility, repeatDice(dice, dice5eCount),
				halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId, followsCasterFlag,
				effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes, school, declaredCastTicks);
		}

		/**
		 * <p><b>How long this spell takes to go off</b>, in ticks, resolved against the table's
		 * configuration. A {@code castTicks} written in the JSON always wins; if absent, it's derived from
		 * the spell's level.</p>
		 *
		 * <p>A cantrip is instantaneous by definition: it's a caster's at-will attack and can't cost more
		 * than a weapon hit. And {@code perLevel} at 0 returns the same old instantaneous cast for
		 * everyone, which is what makes this function truly optional (invariant 9).</p>
		 *
		 * <p>It's pure arithmetic and lives here, in the record, for the same reason as {@link #upcastTo}
		 * and {@link #atCasterLevel}: the self-test can reach it without a Forge instance running.</p>
		 */
		public int castTicksAt(int perLevel, int max) {
			if (declaredCastTicks >= 0) return declaredCastTicks;
			if (level <= 0 || perLevel <= 0) return 0;
			return Math.max(0, Math.min(level * perLevel, max));
		}

		public Spell upcastTo(int slotLevel) {
			int extraLevels = slotLevel - level;
			if (extraLevels <= 0 || !scalesWithSlot()) return this;

			String added = repeatDice(upcastDice, extraLevels);
			//"0" is the default die for a spell that deals no damage at all; adding to it in front leaves a
			//"0 + 2d6" that still rolls fine but reads in chat like a typo.
			String scaled = "0".equals(dice.trim()) ? added : dice + " + " + added;
			return new Spell(id, name + " (nv. " + slotLevel + ")", level, mode, castingAbility, saveAbility,
				scaled, halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId,
				followsCasterFlag, effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes, school, declaredCastTicks);
		}
	}

	//A "1d6" repeated 3 times gets merged into "3d6" instead of chained: it rolls the same, but chat shows
	//one roll instead of three addends of the same die. Whatever doesn't fit the NdM shape (Magic Missile
	//adds "1d4 + 1" per level, dart by dart) is repeated as-is, which is still correct even if it reads longer.
	private static final java.util.regex.Pattern SIMPLE_DICE = java.util.regex.Pattern.compile("(\\d*)d(\\d+)");

	static String repeatDice(String dice, int times) {
		java.util.regex.Matcher m = SIMPLE_DICE.matcher(dice.trim());
		if (m.matches()) {
			int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
			return (count * times) + "d" + m.group(2);
		}
		StringBuilder sb = new StringBuilder(dice);
		for (int i = 1; i < times; i++) sb.append(" + ").append(dice);
		return sb.toString();
	}

	private static final NamedRegistry<Spell> REGISTRY = new NamedRegistry<>("spell", Spell::id);

	public static void register(Spell spell) {
		REGISTRY.register(spell);
	}

	@Nullable
	public static Spell get(String id) {
		return REGISTRY.get(id);
	}

	public static Set<String> ids() {
		return REGISTRY.ids();
	}

	public static boolean remove(String id) {
		return REGISTRY.remove(id);
	}

	//Public: used by SpellCommand (/dndspells load) and by DndPaths to preload all the folder's .json
	//files on server startup, without DndPaths having to depend on the command layer.
	private static final JsonRegistryLoader<Spell> LOADER = new JsonRegistryLoader<>("spell", SpellRegistry::parse, SpellRegistry::register);

	/** Loads from an already-parsed JSON (datapack or another mod's jar) — see ContentDatapackLoader. */
	public static int loadJson(com.google.gson.JsonElement root, String source, java.util.function.Consumer<String> onId) {
		return LOADER.loadJson(root, source, onId);
	}

	public static int loadFile(Path file) throws IOException {
		return LOADER.loadFile(file);
	}

	//Adds a spell to the sheet's list of known spells if it didn't already have it — the same
	//{id,name,level} format /dndspells learn already stores (the Spellbook reads from there, not from
	//this server-side in-memory registry). Reused by SpellCommand.learn and by PresetRegistry.applyToSheet
	//(a caster preset's signature feature). Returns false if the spell doesn't exist in the registry or
	//was already known.
	public static boolean learn(JsonObject sheet, String spellId) {
		Spell spell = get(spellId);
		if (spell == null) return false;
		JsonArray known = sheet.getAsJsonArray("spells");
		for (JsonElement el : known) {
			JsonObject entry = el.getAsJsonObject();
			if (entry.has("id") && entry.get("id").getAsString().equals(spellId)) return false;
		}
		JsonObject entry = new JsonObject();
		entry.addProperty("id", spellId);
		entry.addProperty("name", spell.name());
		entry.addProperty("level", spell.level());
		//The school travels to the sheet alongside the level because the Spellbook DRAWS it and makes it
		//searchable, and the client doesn't have the registry (it only lives in server memory, see the
		//class javadoc). A sheet predating this field simply doesn't show it: nothing that was already
		//visible gets lost.
		if (spell.school() != MagicSchool.UNKNOWN) entry.addProperty("school", spell.school().label());
		known.add(entry);
		return true;
	}

	// --- Prepared spells (see CharacterRules.preparedLimitFor and GrimoireScreen) ---

	/**
	 * <p>Is it prepared? <b>A spell with no field counts as prepared</b>, and that's not a detail: it's
	 * what keeps any sheet written before preparation existed from suddenly having a mute caster
	 * (invariant 8). The list only becomes restrictive once someone starts unchecking things.</p>
	 *
	 * <p>Cantrips are always prepared: they're at-will and in 5e they aren't prepared.</p>
	 */
	public static boolean isPrepared(JsonObject sheet, String spellId) {
		JsonObject entry = entryFor(sheet, spellId);
		if (entry == null) return false;
		if (levelOfEntry(entry) <= 0) return true;
		return !entry.has("prepared") || entry.get("prepared").getAsBoolean();
	}

	/**
	 * <p>Does the prepared list allow casting this? <b>A spell the sheet doesn't know isn't managed by
	 * this list</b>, so it passes: that's the staff's case, which by design casts a spell without its
	 * wielder having learned it (see {@code SpellCommand.staff} — "always using the wielder's real ability
	 * scores and spell slots"). It only blocks what the sheet knows AND someone has manually unchecked.</p>
	 *
	 * <p>This is a different question from {@link #isPrepared}, which answers "is it checked?" for
	 * rendering in the Spellbook. Confusing the two left the staff useless for every leveled spell:
	 * unknown returned false and the cast was rejected without anyone having unchecked anything.</p>
	 */
	public static boolean preparationAllows(JsonObject sheet, String spellId) {
		JsonObject entry = entryFor(sheet, spellId);
		return entry == null || isPrepared(sheet, spellId);
	}

	/** @return false if the sheet doesn't know that spell, or if it's a cantrip (which isn't prepared). */
	public static boolean setPrepared(JsonObject sheet, String spellId, boolean prepared) {
		JsonObject entry = entryFor(sheet, spellId);
		if (entry == null || levelOfEntry(entry) <= 0) return false;
		entry.addProperty("prepared", prepared);
		return true;
	}

	/** How many are prepared, not counting cantrips — the number compared against the limit. */
	public static int preparedCount(JsonObject sheet) {
		if (sheet == null || !sheet.has("spells")) return 0;
		int count = 0;
		for (JsonElement el : sheet.getAsJsonArray("spells")) {
			JsonObject entry = el.getAsJsonObject();
			if (levelOfEntry(entry) <= 0) continue;
			if (!entry.has("prepared") || entry.get("prepared").getAsBoolean()) count++;
		}
		return count;
	}

	/**
	 * <p>How many they can have prepared, or 0 if it isn't a caster class. The formula lives in
	 * {@code CharacterRules} — which is where the rest of the character arithmetic lives — and it's
	 * re-exposed here because that class is package-private on purpose and the callers (the Spellbook and
	 * the network layer) are outside the package. This way the whole prepared-spells API is requested from
	 * a single place.</p>
	 */
	public static int preparedLimitFor(JsonObject sheet) {
		return CharacterRules.preparedLimitFor(sheet);
	}

	@Nullable
	private static JsonObject entryFor(JsonObject sheet, String spellId) {
		if (sheet == null || !sheet.has("spells") || spellId == null) return null;
		for (JsonElement el : sheet.getAsJsonArray("spells")) {
			JsonObject entry = el.getAsJsonObject();
			if (entry.has("id") && entry.get("id").getAsString().equals(spellId)) return entry;
		}
		return null;
	}

	//The level is read from the SHEET and not the registry on purpose: the registry only lives in server
	//memory, and this is also called by the client (see GrimoireScreen), where it doesn't exist.
	private static int levelOfEntry(JsonObject entry) {
		return entry.has("level") ? entry.get("level").getAsInt() : 0;
	}

	public static Spell parse(JsonObject json) {
		String id = json.get("id").getAsString();
		String name = json.has("name") ? json.get("name").getAsString() : id;
		int level = json.has("level") ? json.get("level").getAsInt() : 0;
		String mode = json.has("mode") ? json.get("mode").getAsString().toLowerCase(Locale.ROOT) : "attack";
		String castingAbility = json.has("castingAbility") ? json.get("castingAbility").getAsString().toLowerCase(Locale.ROOT) : "int";
		String saveAbility = json.has("saveAbility") ? json.get("saveAbility").getAsString().toLowerCase(Locale.ROOT) : "dex";
		//Optional since conditions exist: a spell can deal no damage at all and still have its whole
		//effect (Hold Person, Sleep, Suggestion). It used to be mandatory, so those spells couldn't even
		//be written — the parser discarded them with a warning.
		String dice = json.has("dice") ? json.get("dice").getAsString() : "0";
		boolean halfOnSave = !json.has("halfOnSave") || json.get("halfOnSave").getAsBoolean();
		String damageType = json.has("damageType") ? DamageTypes.normalize(json.get("damageType").getAsString()) : "physical";
		boolean concentration = json.has("concentration") && json.get("concentration").getAsBoolean();
		//Defensive ceiling: without this, an absurd radius in the JSON (deliberate or a typo) makes
		//SpellCastManager.findAoeTargets scan every loaded entity on the server on every cast, with no
		//upper bound — a real lag vector, not just a weird value.
		int aoeRadius = json.has("aoeRadius") ? Math.max(0, Math.min(json.get("aoeRadius").getAsInt(), 40)) : 0;
		//Sphere by default: any spell written before shapes existed behaves exactly as it always did. An
		//unrecognized value also falls back to sphere instead of discarding the whole spell.
		String aoeShape = json.has("aoeShape") ? json.get("aoeShape").getAsString().toLowerCase(Locale.ROOT) : "sphere";
		if (!aoeShape.equals("line") && !aoeShape.equals("cone") && !aoeShape.equals("wall")) aoeShape = "sphere";
		//A summon's vanilla body. Vex by default: it floats, is small, and doesn't resemble any specific
		//hostile mob, which is the closest thing to "a spiritual weapon" there is without its own model.
		String summonEntityId = json.has("summonEntity") ? json.get("summonEntity").getAsString() : "minecraft:vex";
		boolean followsCaster = json.has("followsCaster") && json.get("followsCaster").getAsBoolean();

		//Same nested format MonsterRegistry.parse/parseAttack use for their own monsters:
		//"appliesEffect": {"name": "...", "dice": "...", "turns": N}.
		JsonObject effect = json.has("appliesEffect") ? json.getAsJsonObject("appliesEffect") : null;
		String effectName = effect != null ? effect.get("name").getAsString() : null;
		String effectDice = effect != null ? effect.get("dice").getAsString() : null;
		int effectTurns = effect != null && effect.has("turns") ? effect.get("turns").getAsInt() : 0;

		//What gets added per slot level above its own (see Spell.upcastTo). Absent = the spell doesn't
		//improve when upcast, which in the SRD is half of them: Power Word Kill or Meteor Swarm gain
		//nothing from spending a higher slot, and pretending otherwise would break them.
		String upcastDice = json.has("upcastDice") ? json.get("upcastDice").getAsString() : null;

		//Who it can affect. Empty = everyone, which is how every spell used to behave. There are two
		//fields and not one because both forms exist in the SRD and each written as the other becomes
		//unreadable: Hold Person is "humanoids only" (one), and Hold Monster is "everything except undead"
		//(also one, but inverted — as an allowlist it would be thirteen entries).
		java.util.Set<CreatureType> affectsTypes = CreatureType.parseAll(json.has("affectsTypes") ? json.getAsJsonArray("affectsTypes") : null);
		java.util.Set<CreatureType> immuneTypes = CreatureType.parseAll(json.has("immuneTypes") ? json.getAsJsonArray("immuneTypes") : null);

		//School of magic (see MagicSchool). Absent = UNKNOWN, which is how the pack's 87 spells were until
		//the field existed: they cast with the same old generic effect and nothing else changes. It gates
		//no rule, it only decides what the CAST looks and sounds like (CombatFx.spellCast).
		MagicSchool school = MagicSchool.parse(json.has("school") ? json.get("school").getAsString() : null);

		//How long it takes to go off, in ticks. -1 (absent) is NOT zero: it means "not decided", in which
		//case the table configuration decides based on level (see castTicksAt). An explicit 0 in the JSON
		//does mean instantaneous no matter what, which is how you write a spell that must not be
		//interruptible — Shield, Counterspell — without touching the table's configuration.
		int declaredCastTicks = json.has("castTicks") ? Math.max(0, Math.min(json.get("castTicks").getAsInt(), 200)) : -1;

		return new Spell(id, name, level, mode, castingAbility, saveAbility, dice, halfOnSave, damageType, concentration, aoeRadius, aoeShape, summonEntityId, followsCaster,
			effectName, effectDice, effectTurns, upcastDice, affectsTypes, immuneTypes, school, declaredCastTicks);
	}

	//--- Quick-cast staff: any item tagged {dndsheets:{quickSpell:"id"}} (same pattern as custom weapons) ---

	@Nullable
	public static String quickSpellIdOf(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || !tag.contains("dndsheets")) return null;
		CompoundTag dndTag = tag.getCompound("dndsheets");
		return dndTag.contains("quickSpell") ? dndTag.getString("quickSpell") : null;
	}

	//With hundreds of spells, a separate staff per spell isn't viable: the one /dndspells staff grants
	//carries {dndsheets:{staffConfigurable:true}} alongside quickSpell, so the Spellbook (see
	//GrimoireScreen, StaffBindMessage) can rewrite its spell instead of creating a new item.
	public static boolean isConfigurableStaff(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && tag.contains("dndsheets") && tag.getCompound("dndsheets").getBoolean("staffConfigurable");
	}

	/** @return false if the item isn't a reconfigurable staff (nothing to rewrite). */
	public static boolean bindStaff(ItemStack stack, String spellId) {
		if (!isConfigurableStaff(stack)) return false;
		stack.getTag().getCompound("dndsheets").putString("quickSpell", spellId);
		return true;
	}
}
