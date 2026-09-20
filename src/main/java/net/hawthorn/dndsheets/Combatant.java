package net.hawthorn.dndsheets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>A participant in the 5e rules, whether a player (with a sheet) or a monster (with a stat block).
 * No such thing used to exist: state lived in two incompatible places — {@code JsonObject} + Minecraft's
 * health attribute for the player, {@link MonsterRegistry.MonsterStatBlock} + the entity's NBT for the
 * monster — and every rule that needed "the target's AC" or "take N hit points off it" was written
 * twice, with a {@code boolean isMonster} deciding which.</p>
 *
 * <p>The cost of that split was measurable and unintended: the monster had no resistances
 * ({@link DamageTypes#multiplierFor} required a sheet), no Shield reaction
 * ({@link ShieldManager#effectiveAc} required a {@code ServerPlayer}), and no concentration
 * ({@link ConcentrationManager#onDamageTaken} did a hard cast). None of those three omissions was a
 * design decision. By going through this interface, each one gets fixed in a single place.</p>
 *
 * <p>Implementations do NOT keep their own state: they read and write wherever that state already
 * lived (the player's JSON sheet, the monster's NBT), so they can be created and discarded on every call
 * without caching anything, and conditions survive restarts and reconnections through the same path HP
 * already used.</p>
 */
public interface Combatant {

	Entity entity();

	String name();

	/** Base AC, not counting reactions. */
	int armorClass();

	int currentHp();

	int maxHp();

	/** Ability modifier by short key: {@code str}, {@code dex}, {@code con}, {@code int}, {@code wis}, {@code cha}. */
	/**
	 * <p>The six 5e abilities with the exact key {@link #abilityModifier(String)} accepts, in the sheet's
	 * order. Lives here, next to the method that consumes them, for the same reason
	 * {@code DamageTypes.CANONICAL} lives next to resistances: it was the same list written by hand in
	 * <b>eight</b> places (two registries, a command, a network message, and four screens), and two
	 * copies drifting apart is a menu that offers an ability that then modifies nothing.</p>
	 */
	String[] ABILITIES = {"str", "dex", "con", "int", "wis", "cha"};

	int abilityModifier(String ability);

	int proficiencyBonus();

	/**
	 * <p>Damage multiplier from resistances/vulnerabilities/immunities. Petrified (resistance to
	 * everything) is applied by {@link #effectiveDamageMultiplier}, shared by both sides.</p>
	 *
	 * @param magical whether the hit counts as magical. Half a dozen SRD resistances depend on it
	 *                ("bludgeoning, piercing, and slashing from nonmagical attacks"), and without that
	 *                data the entire bestiary came out softer than its stat block says.
	 */
	double damageMultiplier(String damageType, boolean magical);

	/**
	 * <p>Applies damage to the real hit points, already multiplied and with temporary HP already
	 * deducted. Not called directly from the rules: the entry point is {@link #takeDamage}, which is what
	 * applies the temporary HP absorption before reaching here.</p>
	 */
	void applyRealDamage(int amount);

	/**
	 * <p>Temporary hit points: a pool that absorbs damage BEFORE the real ones and that neither heals nor
	 * stacks — a new pile replaces the old one, keeping the larger. In 5e these are granted by False
	 * Life, Heroism, Word of Encouragement, and several class features.</p>
	 */
	int temporaryHp();

	void setTemporaryHp(int amount);

	/**
	 * <p>Damage entry point for ALL rules. Temporary HP is deducted first and only the remainder reaches
	 * real HP. Lives here and not in each implementation because the rule is identical for player, NPC,
	 * and monster — repeating it three times is exactly what {@link Combatant} came to avoid.</p>
	 */
	default void takeDamage(int amount) {
		applyRealDamage(absorbWithTemporaryHp(amount));
	}

	/**
	 * <p>Spends temporary HP against that damage and returns what's left to apply. Public because there's
	 * a path that CANNOT use {@link #takeDamage}: PvP with a weapon lives inside Minecraft's
	 * {@code LivingHurtEvent} and delivers damage with {@code setAmount}, so it needs to deduct the pool
	 * and keep the remainder instead of applying it itself. Without this, temporary HP absorbed spells
	 * and monster hits but not another player's sword swing.</p>
	 */
	default int absorbWithTemporaryHp(int amount) {
		if (amount <= 0) return 0;
		int temporary = temporaryHp();
		if (temporary <= 0) return amount;
		int absorbed = Math.min(temporary, amount);
		setTemporaryHp(temporary - absorbed);
		return amount - absorbed;
	}

	/**
	 * <p>Grants temporary HP. It doesn't add to whatever is already there: in 5e you pick one of the two
	 * piles, and keeping the larger one is the standard reading and the one that doesn't punish recasting
	 * the spell.</p>
	 */
	default void grantTemporaryHp(int amount) {
		if (amount > temporaryHp()) setTemporaryHp(amount);
	}

	boolean isDefeated();

	/**
	 * <p>Active conditions and, for each one, the entity id that caused it, or {@link #NO_SOURCE} if
	 * unknown. The source is needed for the two 5e conditions whose effect depends on <em>who</em>
	 * caused them: charmed (you can't attack whoever charmed you) and frightened (disadvantage only while
	 * you can see the source). For the other twelve it's unnecessary, which is why it's allowed to be absent.</p>
	 */
	Map<Condition, Integer> conditionSources();

	/** The single write point for conditions: each implementation persists it wherever it already stores everything else. */
	void setConditionSources(Map<Condition, Integer> sources);

	/** Unknown source: the condition was applied without saying who caused it (e.g. applied by hand by the DM). */
	int NO_SOURCE = -1;

	//The keySet of the freshly built EnumMap, without copying it to a separate EnumSet: the map was just
	//created by conditionSources() and nobody else holds it, so its keySet is already a private view and
	//just as fast for contains. Saves one collection per call, and this gets called 6 to 10 times per
	//attack resolution (advantage, auto-crit, resistances, cannotAct, cannotMove...).
	default Set<Condition> conditions() {
		return conditionSources().keySet();
	}

	default int sourceOf(Condition condition) {
		Integer source = conditionSources().get(condition);
		return source == null ? NO_SOURCE : source;
	}

	/**
	 * Effective AC after defensive reactions (Shield). Defaults to base AC: a combatant that doesn't yet
	 * know how to react simply doesn't change anything, and the day a monster gets reactions this gets
	 * overridden here without touching any combat path.
	 */
	default int reactiveArmorClass(int attackRollValue) {
		return armorClass();
	}

	default boolean hasCondition(Condition condition) {
		return conditions().contains(condition);
	}

	default void addCondition(Condition condition) {
		addCondition(condition, NO_SOURCE);
	}

	default void addCondition(Condition condition, int sourceEntityId) {
		Map<Condition, Integer> updated = new EnumMap<>(Condition.class);
		updated.putAll(conditionSources()); //putAll, not the copy constructor: EnumMap(Map) throws if the map comes in empty and isn't already an EnumMap.
		//Overwritten even if already present: reapplying the same condition from a different source must
		//update it (the dragon is scaring you now, not the goblin from last turn anymore).
		Integer previous = updated.put(condition, sourceEntityId);
		if (previous == null || previous != sourceEntityId) setConditionSources(updated);
	}

	default void removeCondition(Condition condition) {
		Map<Condition, Integer> updated = new EnumMap<>(Condition.class);
		updated.putAll(conditionSources()); //putAll, not the copy constructor: EnumMap(Map) throws if the map comes in empty and isn't already an EnumMap.
		if (updated.remove(condition) != null) setConditionSources(updated);
	}

	/**
	 * <p>Whether it can see the source of that condition. With no source registered returns {@code true}:
	 * the conservative approach of applying the effect anyway, which is what the mod did before tracking sources.</p>
	 */
	default boolean seesSourceOf(Condition condition) {
		int sourceId = sourceOf(condition);
		if (sourceId == NO_SOURCE) return true;
		Entity source = entity().level().getEntity(sourceId);
		if (source == null) return false; //The source is no longer in the world: it stopped scaring you.
		return !(entity() instanceof LivingEntity living) || living.hasLineOfSight(source);
	}

	/**
	 * <p>Charmed: you can't attack whoever charmed you. Every other target is still valid, so this
	 * depends on the specific target and can't be resolved by looking only at conditions.</p>
	 */
	default boolean cannotAttack(Entity target) {
		if (target == null || !hasCondition(Condition.CHARMED)) return false;
		return sourceOf(Condition.CHARMED) == target.getId();
	}

	/** Can't act (incapacitated, paralyzed, petrified, stunned, unconscious). */
	default boolean cannotAct() {
		return conditions().stream().anyMatch(Condition::preventsActions);
	}

	/** Speed 0 (grappled, restrained, paralyzed, petrified, unconscious). */
	default boolean cannotMove() {
		return conditions().stream().anyMatch(Condition::preventsMovement);
	}

	/** Advantage/disadvantage on attack rolls made BY this combatant, from its own conditions. */
	default DiceManager.Advantage ownAttackAdvantage() {
		//Frightened is the only one whose disadvantage depends on seeing the source; the rest always apply.
		boolean disadvantage = conditions().stream()
			.filter(condition -> condition != Condition.FRIGHTENED || seesSourceOf(Condition.FRIGHTENED))
			.anyMatch(Condition::selfAttackDisadvantage);
		return DiceManager.combineAdvantage(
			conditions().stream().anyMatch(Condition::selfAttackAdvantage) ? DiceManager.Advantage.ADVANTAGE : DiceManager.Advantage.NORMAL,
			disadvantage ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL);
	}

	/**
	 * Advantage/disadvantage for whoever attacks THIS combatant. {@code melee} decides the prone case,
	 * the only condition whose effect changes with distance (advantage within 5 feet, disadvantage at range).
	 */
	default DiceManager.Advantage advantageAgainst(boolean melee) {
		boolean advantage = conditions().stream().anyMatch(Condition::attackersAdvantage);
		boolean disadvantage = conditions().stream().anyMatch(Condition::attackersDisadvantage);
		if (hasCondition(Condition.PRONE)) {
			if (melee) advantage = true;
			else disadvantage = true;
		}
		return DiceManager.combineAdvantage(
			advantage ? DiceManager.Advantage.ADVANTAGE : DiceManager.Advantage.NORMAL,
			disadvantage ? DiceManager.Advantage.DISADVANTAGE : DiceManager.Advantage.NORMAL);
	}

	/** A melee hit against it is an automatic critical (paralyzed, unconscious). */
	default boolean autoCritInMelee() {
		return conditions().stream().anyMatch(Condition::autoCritInMelee);
	}

	/** Automatically fails Strength and Dexterity saves. */
	default boolean autoFailsStrDexSaves() {
		return conditions().stream().anyMatch(Condition::autoFailsStrDexSaves);
	}

	/**
	 * <p>Result of a saving throw. When a condition makes it fail without rolling — paralyzed,
	 * petrified, stunned, and unconscious automatically fail Strength and Dexterity saves in 5e —
	 * {@code blockedBy} says which one and {@code outcome} is {@code null}: nothing is rolled, so there's
	 * no number to show.</p>
	 */
	record SaveRoll(DiceManager.RollOutcome outcome, Condition blockedBy) {

		public boolean succeeds(int dc) {
			return blockedBy == null && outcome != null && outcome.result() != null && outcome.result().getValue() >= dc;
		}

		/** Text for chat, or {@code null} if the expression couldn't even be rolled. */
		@Nullable
		public String formatted() {
			if (blockedBy != null) return "auto (" + blockedBy.displayLabel() + ")";
			return outcome == null || outcome.result() == null ? null : outcome.formatted();
		}
	}

	/**
	 * Ability saving throw, by short or long key ({@code dex} or {@code dexterity}). This used to be
	 * resolved with an {@code if (target instanceof Player)} at every site that needed a save; by going
	 * through here, the auto-fail rule applies to players and monsters alike, in a single place.
	 */
	default SaveRoll rollSave(String ability) {
		String key = ability == null ? "" : ability.toLowerCase(Locale.ROOT);
		if ((key.startsWith("str") || key.startsWith("dex")) && autoFailsStrDexSaves()) {
			Condition blocking = conditions().stream().filter(Condition::autoFailsStrDexSaves).findFirst().orElse(null);
			return new SaveRoll(null, blocking);
		}
		//Expression with the modifier already resolved instead of "$dex": the target can be a monster,
		//which has no sheet DiceManager could pull the ability from.
		return new SaveRoll(DiceManager.roll(new JsonObject(), "1d20 + " + abilityModifier(key)), null);
	}

	/** Its own resistances plus petrified's resistance to all damage, which applies to both sides. */
	default double effectiveDamageMultiplier(String damageType, boolean magical) {
		double multiplier = damageMultiplier(damageType, magical);
		if (conditions().stream().anyMatch(Condition::resistsAllDamage)) multiplier = Math.min(multiplier, 0.5);
		return multiplier;
	}

	/**
	 * {@code null} if the entity doesn't participate in the 5e rules (a mob from another mod with no
	 * stat block, a training armor stand, a player with no sheet loaded): the caller must fall back to
	 * Minecraft's normal behavior, exactly as it did before.
	 */
	@Nullable
	static Combatant of(Entity entity) {
		if (entity instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			return sheet == null ? null : new PlayerCombatant(player, sheet);
		}
		//The NPC is checked BEFORE the monster stat block: an entity with a character sheet plays by the
		//full rules of a PC, and those override any monster stats it might be carrying.
		String characterId = characterIdOf(entity);
		if (characterId != null) {
			JsonObject sheet = SheetLoader.getCharacterSheet(characterId);
			if (sheet != null) return new NpcCombatant(entity, sheet, characterId);
			//Sheet deleted with its body still in the world: falls back to monster/vanilla instead of crashing.
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		return block == null ? null : new MonsterCombatant(entity, block);
	}

	/**
	 * <p>Links a world entity to a character sheet. Same persistent NBT compartment
	 * {@code MonsterRegistry.tagAsMonster} already uses — Minecraft saves and loads it on its own — so as
	 * not to invent a second tagging mechanism that behaves differently on chunk reload.</p>
	 */
	static void tagAsCharacter(Entity entity, String characterId) {
		CompoundTag data = entity.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets"); //Empty if it didn't exist, same as MonsterRegistry.
		tag.putString("character", characterId);
		data.put("dndsheets", tag);
	}

	/** Character id linked to that entity, or {@code null} if it carries no sheet. */
	@Nullable
	static String characterIdOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		CompoundTag tag = data.getCompound("dndsheets");
		String characterId = tag.contains("character") ? tag.getString("character") : null;
		return characterId == null || characterId.isEmpty() ? null : characterId;
	}

	//--- Backed by a sheet -------------------------------------------------------------------------------

	/**
	 * <p>What a player and an NPC share: both carry a character sheet, so abilities, proficiency, damage
	 * affinities, and conditions are read the same way for both. Exists so as not to reintroduce, through
	 * the back door, the same split {@link Combatant} came to erase — an NPC with a sheet isn't "a weird
	 * monster," it's a character with nobody sitting behind it.</p>
	 *
	 * <p>What they DON'T share is left out and set by each implementation: where HP comes from
	 * (Minecraft's health attribute for the player, the sheet itself for the NPC), whether real equipped
	 * armor and shield count toward AC, and whether it knows how to react.</p>
	 */
	interface SheetBacked extends Combatant {

		JsonObject sheet();

		/** Id the sheet is persisted under: the player's UUID, or the character's id for an NPC. */
		String saveId();

		//The sheet uses long names ("dexterity"); roll expressions and monster stat blocks use short ones
		//("dex"). The interface speaks in short ones, so the translation lives here in one single place.
		Map<String, String> LONG_ABILITY_KEYS = Map.of(
			"str", "strength", "dex", "dexterity", "con", "constitution",
			"int", "intelligence", "wis", "wisdom", "cha", "charisma");

		@Override default int abilityModifier(String ability) {
			String key = LONG_ABILITY_KEYS.getOrDefault(ability.toLowerCase(Locale.ROOT), ability);
			return CombatManager.abilityModifier(sheet(), key);
		}

		@Override default int proficiencyBonus() {
			if (!sheet().has("proficiencyBonus")) return 2;
			try {
				return Integer.parseInt(sheet().get("proficiencyBonus").getAsString());
			} catch (RuntimeException e) {
				return 2; //Same criterion as CombatManager.abilityModifier: a corrupted old sheet shouldn't take down combat.
			}
		}

		//A character sheet only has unconditional affinities ("damageAffinities"), so "magical" is
		//deliberately ignored: the day a magic item grants a conditional one, it gets read here and that's it.
		@Override default double damageMultiplier(String damageType, boolean magical) {
			double multiplier = DamageTypes.multiplierFor(entity(), sheet(), damageType);
			//Temporary resistance from a potion: combined by keeping the more protective one, same as
			//item resistances — drinking two potions of the same type doesn't grant immunity.
			String temporary = ConsumableManager.activeAffinity(sheet(), damageType == null ? null : damageType.toLowerCase(Locale.ROOT));
			if (temporary != null) multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(temporary));
			//Magic item resistances are combined with the sheet's by keeping the MORE protective one, not
			//adding them: in 5e two sources of fire resistance are still fire resistance, not immunity.
			if (entity() instanceof Player wearer && damageType != null) {
				for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(wearer, sheet())) {
					String declared = item.affinities().get(damageType.toLowerCase(Locale.ROOT));
					if (declared != null) multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(declared));
				}
			}
			return multiplier;
		}

		//On the sheet, same as conditions: it's what persists and what the player sees when opening it.
		@Override default int temporaryHp() {
			return sheet().has("temporaryHp") ? sheet().get("temporaryHp").getAsInt() : 0;
		}

		@Override default void setTemporaryHp(int amount) {
			sheet().addProperty("temporaryHp", Math.max(0, amount));
			SheetLoader.saveServer(sheet(), saveId());
		}

		@Override default Map<Condition, Integer> conditionSources() {
			Map<Condition, Integer> result = new EnumMap<>(Condition.class);
			if (!sheet().has("conditions")) return result;
			for (var element : sheet().getAsJsonArray("conditions")) {
				parseEntry(element.getAsString(), result);
			}
			return result;
		}

		@Override default void setConditionSources(Map<Condition, Integer> sources) {
			JsonArray array = new JsonArray();
			for (Map.Entry<Condition, Integer> entry : sources.entrySet()) array.add(formatEntry(entry));
			sheet().add("conditions", array);
			//To disk immediately, without relying on the 5-minute autosave: the same bug that already
			//cost lost gold/slot changes made by the DM (see PROJECT_CONTEXT.md, bug #5).
			SheetLoader.saveServer(sheet(), saveId());

			//And to the client. This is the ONLY write point for conditions, so it's the only place it's
			//needed: without it, the player's copy was left with stale ones and the HUD couldn't show
			//anything reliable. A short patch, not the whole sheet — it arrives mid-combat.
			if (entity() instanceof net.minecraft.server.level.ServerPlayer player) {
				JsonObject patch = new JsonObject();
				patch.add("conditions", array);
				DndsheetsMod.sendSheetFieldUpdate(player, patch);
			}
		}
	}

	//--- Player ----------------------------------------------------------------------------------------

	record PlayerCombatant(Player player, JsonObject sheet) implements SheetBacked {

		@Override public Entity entity() { return player; }

		@Override public String saveId() { return player.getStringUUID(); }

		@Override public String name() { return SheetLoader.characterNameOf(sheet, player); }

		/** Includes the REAL equipped armor and shield, something only a player has. */
		@Override public int armorClass() {
			int base = CombatManager.armorClassOf(player, sheet);
			//Magic items are added here and not in CombatManager because this IS the single point every
			//"what's their AC?" question passes through — including the DM Panel's and a monster deciding
			//whether it hits.
			for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(player, sheet)) base += item.acBonus();
			return base;
		}

		@Override public Combatant.SaveRoll rollSave(String ability) {
			Combatant.SaveRoll roll = SheetBacked.super.rollSave(ability);
			int bonus = 0;
			for (MagicItemRegistry.MagicItem item : MagicItemRegistry.activeFor(player, sheet)) bonus += item.saveBonus();
			//Only if it was actually rolled: a save that fails outright from a condition doesn't improve
			//by wearing a ring, and adding the bonus to it would turn it into a number that means nothing.
			if (bonus == 0 || roll.blockedBy() != null || roll.outcome() == null || roll.outcome().result() == null) return roll;
			return new Combatant.SaveRoll(DiceManager.roll(sheet, "1d20 + " + (abilityModifier(ability) + bonus)), null);
		}

		//HP from Minecraft's real health attribute, not from the sheet: for a player that IS their life,
		//and the sheet only reflects it.
		@Override public int currentHp() { return (int) Math.ceil(player.getHealth()); }

		@Override public int maxHp() { return (int) Math.ceil(player.getMaxHealth()); }

		@Override public int reactiveArmorClass(int attackRollValue) {
			if (!(player instanceof ServerPlayer serverPlayer)) return armorClass();
			return ShieldManager.effectiveAc(serverPlayer, attackRollValue, armorClass());
		}

		/**
		 * WARNING: don't call from inside a {@code LivingHurtEvent} — there the damage is already in
		 * flight and the event itself applies it with {@code setAmount}; calling this there would
		 * recurse. This path exists for damage that does NOT originate from a vanilla hit (spells,
		 * monster attacks, per-turn effects).
		 */
		@Override public void applyRealDamage(int amount) {
			if (amount <= 0) return;
			player.hurt(player.damageSources().generic(), amount);
			if (player instanceof ServerPlayer serverPlayer) ConcentrationManager.onDamageTaken(serverPlayer, amount);
		}

		@Override public boolean isDefeated() { return player.getHealth() <= 0; }
	}

	//--- NPC (character with a sheet, no player behind it) ----------------------------------------------

	/**
	 * <p>A character sheet the DM runs on a world entity: allies, sidekicks, enemies with class levels.
	 * Plays by exactly the same rules as a PC — hence why it shares {@link SheetBacked} — instead of
	 * having to be downgraded to a monster stat block.</p>
	 *
	 * <p>Its HP lives on the sheet, not in Minecraft's health attribute: the sheet is what persists
	 * across sessions and survives the entity unloading or being resummoned. The entity is the body, not
	 * the character.</p>
	 */
	record NpcCombatant(Entity npc, JsonObject sheet, String characterId) implements SheetBacked {

		@Override public Entity entity() { return npc; }

		@Override public String saveId() { return characterId; }

		@Override public String name() {
			return sheet.has("characterName") ? sheet.get("characterName").getAsString() : characterId;
		}

		/**
		 * With no real armor or shield to check (a mob doesn't equip like a player): AC from the DM's
		 * manual override if there is one, otherwise the 5e base, 10 + Dex mod.
		 */
		@Override public int armorClass() {
			if (sheet.has("armorClassOverride")) return sheet.get("armorClassOverride").getAsInt();
			return 10 + abilityModifier("dex");
		}

		@Override public int maxHp() {
			return SheetLoader.maxHitPointsFor(sheet, SheetLoader.characterLevelOf(sheet));
		}

		//When it's summoned the sheet doesn't yet carry "currentHp"; it starts at full HP instead of 0,
		//which would kill it on the first hit.
		@Override public int currentHp() {
			return sheet.has("currentHp") ? sheet.get("currentHp").getAsInt() : maxHp();
		}

		@Override public void applyRealDamage(int amount) {
			if (amount <= 0) return;
			int remaining = Math.max(0, currentHp() - amount);
			sheet.addProperty("currentHp", remaining);
			SheetLoader.saveServer(sheet, characterId);
			if (remaining > 0) return;

			CombatFx.defeated(npc);
			TurnManager.markDefeated(npc.getId());
			//Same dance as a monster: our 5e health lives separately from Minecraft's, so die() can't
			//infer death on its own, and setHealth(0) beforehand is essential for isDeadOrDying() to stop
			//returning false — without that, the body would just lie there and never disappear.
			if (npc instanceof LivingEntity living) {
				living.setHealth(0.0F);
				living.die(npc.damageSources().generic());
			} else {
				npc.remove(Entity.RemovalReason.KILLED);
			}
		}

		@Override public boolean isDefeated() { return currentHp() <= 0; }
	}

	//--- Monster ---------------------------------------------------------------------------------------

	record MonsterCombatant(Entity monster, MonsterRegistry.MonsterStatBlock block) implements Combatant {

		private static final String CONDITIONS_KEY = "conditions";

		@Override public Entity entity() { return monster; }

		@Override public String name() { return block.name(); }

		@Override public int armorClass() { return block.ac(); }

		@Override public int currentHp() { return MonsterRegistry.currentHpOf(monster); }

		//Scaled by difficulty (Config.scaleMonsterMaxHp): this is the only place that reads an already
		//existing monster's max, so scaling here is enough for the HP bar to match what it actually has,
		//without touching the shared stat block (block.maxHp() stays the raw SRD value, the same for every
		//instance). Initial HP on summon is scaled separately, in MonsterRegistry.spawnAt/applyStatBlock,
		//with the same method — if the two ever drift apart, a freshly summoned monster would appear with
		//less HP than its own bar announces.
		@Override public int maxHp() { return Config.scaleMonsterMaxHp(block.maxHp()); }

		@Override public int abilityModifier(String ability) { return block.abilityModifier(ability); }

		@Override public int proficiencyBonus() { return block.proficiencyBonus(); }

		/**
		 * Same vocabulary as a player sheet's affinities — see {@link DamageTypes#multiplierForLabel}.
		 * Keeps the MORE protective of the two when both apply: an unconditional immunity shouldn't get
		 * worse just because the hit happens to be magical.
		 */
		@Override public double damageMultiplier(String damageType, boolean magical) {
			if (damageType == null) return 1.0;
			String type = damageType.toLowerCase(Locale.ROOT);
			double multiplier = DamageTypes.multiplierForLabel(block.damageAffinities().get(type));
			//Only if there IS a conditional entry for that type: comparing against the 1.0 an absent one
			//returns would flatten any unconditional resistance down to "normal damage," the opposite of
			//what's intended. When both exist, the more protective one wins — an immunity shouldn't get
			//worse just because the hit also happens to be nonmagical.
			String conditional = magical ? null : block.nonmagicalAffinities().get(type);
			if (conditional != null) {
				multiplier = Math.min(multiplier, DamageTypes.multiplierForLabel(conditional));
			}
			return multiplier;
		}

		//In the same NBT compartment as HP and conditions — see MonsterRegistry.setCurrentHp.
		@Override public int temporaryHp() {
			CompoundTag data = monster.getPersistentData();
			return data.contains("dndsheets") ? data.getCompound("dndsheets").getInt("temporaryHp") : 0;
		}

		@Override public void setTemporaryHp(int amount) {
			CompoundTag data = monster.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets");
			tag.putInt("temporaryHp", Math.max(0, amount));
			data.put("dndsheets", tag);
		}

		@Override public void applyRealDamage(int amount) {
			int remaining = currentHp() - amount;
			if (remaining > 0) {
				MonsterRegistry.setCurrentHp(monster, remaining);
				return;
			}
			MonsterRegistry.setCurrentHp(monster, 0);
			CombatFx.defeated(monster);
			TurnManager.markDefeated(monster.getId());
			//die(), not remove(): a plain remove() never goes through vanilla's death path (loot table,
			//XP...). Our real Minecraft health never drops (5e HP is tracked separately), so die() can't
			//infer death on its own; setHealth(0) beforehand is essential because isDeadOrDying() keeps
			//returning false with full health and the mob would just lie there and never disappear.
			if (monster instanceof LivingEntity living) {
				living.setHealth(0.0F);
				living.die(monster.damageSources().generic());
			} else {
				monster.remove(Entity.RemovalReason.KILLED);
			}
		}

		@Override public boolean isDefeated() { return currentHp() <= 0; }

		@Override public Map<Condition, Integer> conditionSources() {
			Map<Condition, Integer> result = new EnumMap<>(Condition.class);
			CompoundTag data = monster.getPersistentData();
			if (!data.contains("dndsheets")) return result;
			String joined = data.getCompound("dndsheets").getString(CONDITIONS_KEY);
			if (joined.isEmpty()) return result;
			for (String entry : joined.split(",")) {
				parseEntry(entry, result);
			}
			return result;
		}

		@Override public void setConditionSources(Map<Condition, Integer> sources) {
			StringBuilder joined = new StringBuilder();
			for (Map.Entry<Condition, Integer> entry : sources.entrySet()) {
				if (joined.length() > 0) joined.append(',');
				joined.append(formatEntry(entry));
			}
			CompoundTag data = monster.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets"); //Empty if it didn't exist, same as MonsterRegistry.setCurrentHp.
			tag.putString(CONDITIONS_KEY, joined.toString());
			data.put("dndsheets", tag);
		}
	}

	//--- On-disk format ----------------------------------------------------------------------------------

	/**
	 * <p>A condition is stored as {@code label} or {@code label@sourceId}, both in the sheet's JSON array
	 * and in the monster's NBT string alike. The suffix is deliberately optional: what was saved before
	 * sources existed is still read as-is, as a condition with no known source.</p>
	 *
	 * <p>The entity id doesn't survive a server restart (Minecraft reassigns them), so after a restart a
	 * condition keeps its effect but loses track of who it was pointing at. That's acceptable: the two
	 * conditions that use the source already treat "can't see it" as "doesn't apply," which is the safe side.</p>
	 */
	//Public, not private, even though only the two implementations below use it: it's the on-disk
	//format, and breaking it makes conditions stop surviving a restart WITHOUT anything visibly failing.
	//Exposed so JsonContentSelfTest can pin down the round trip.
	public static void parseEntry(String entry, Map<Condition, Integer> into) {
		int separator = entry.indexOf('@');
		String label = separator < 0 ? entry : entry.substring(0, separator);
		Condition condition = Condition.fromLabel(label);
		if (condition == null) return;
		int source = NO_SOURCE;
		if (separator >= 0) {
			try {
				source = Integer.parseInt(entry.substring(separator + 1));
			} catch (NumberFormatException e) {
				source = NO_SOURCE; //Hand-tampered label: left with no source instead of taking down the load.
			}
		}
		into.put(condition, source);
	}

	public static String formatEntry(Map.Entry<Condition, Integer> entry) {
		int source = entry.getValue() == null ? NO_SOURCE : entry.getValue();
		return source == NO_SOURCE ? entry.getKey().label() : entry.getKey().label() + "@" + source;
	}
}
