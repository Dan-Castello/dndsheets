package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * <p>Summons that act on their own on later turns: Spiritual Weapon, Flaming Sphere,
 * Find Familiar. That's the difference from a normal damage spell — it isn't resolved once and done, it
 * leaves something in the world that joins initiative and attacks by itself.</p>
 *
 * <p>Almost all the machinery already existed and this class barely wires it together: {@link
 * MonsterRegistry#spawnAt} spawns entities with a stat block, {@link TurnManager#addLateMonster} adds
 * them to the turn order mid-encounter, and {@link MonsterActionManager#autoAct} already makes a monster
 * attack on its own when its turn comes. The one thing genuinely missing was <b>who</b> it attacks: {@code
 * autoAct} targets the nearest player, which for a player's own summon is exactly the wrong target.</p>
 *
 * <p>The stat block is generated on the fly from the spell and registered with a synthetic id, instead of
 * requiring the DM to register a monster for every possible summon.</p>
 */
public class SummonManager {

	private static final String OWNER_KEY = "summonOwner";
	private static final String ROUNDS_KEY = "summonRounds";

	//A summon can't take hits: in 5e most are objects or effects that dissipate on taking damage, and
	//giving them real HP would turn them into a free shield. 1 HP and low AC: they exist to attack, not
	//to tank.
	private static final int SUMMON_HP = 1;
	private static final int SUMMON_AC = 10;

	/**
	 * <p>Spawns the spell's entity in front of the caster and adds it to initiative. The stat block
	 * inherits the summoner's casting ability score and proficiency, which is how 5e calculates a summoned
	 * weapon's attack.</p>
	 */
	public static Entity summon(ServerPlayer caster, SpellRegistry.Spell spell, int proficiency, int abilityMod) {
		if (!(caster.level() instanceof ServerLevel level)) return null;

		String monsterId = "dndsheets:summon_" + spell.id().replace(':', '_');
		//The stat block is re-registered on every summon on purpose: that way a change to the spell's JSON
		//shows up on the next cast with no reload needed, and there's no parallel registry of summonables
		//to maintain.
		MonsterRegistry.replace(new MonsterRegistry.MonsterStatBlock(
			monsterId, spell.name(), spell.summonEntityId(), SUMMON_AC, SUMMON_HP,
			//Ability scores are set so abilityModifier returns exactly the summoner's modifier:
			//10 + 2*mod gives that mod back, so the attack comes out with the caster's own numbers without
			//inventing a new field on the block.
			Map.of("str", 10 + 2 * abilityMod, "dex", 10 + 2 * abilityMod, "con", 10,
				"int", 10, "wis", 10, "cha", 10),
			proficiency,
			List.of(new MonsterRegistry.MonsterAttack(spell.name(), "str", spell.dice(), "str",
				spell.damageType(), null, null, 0)),
			List.of(), Map.of(), Map.of(),
			//A spiritual weapon or a flaming sphere is a construct: it isn't a living creature, it's magic
			//given shape. Barely matters today, but leaving it as UNKNOWN would say "I don't know" about
			//something that is, in fact, known.
			//A summon isn't a boss: no Legendary Resistance.
			//It glows: a summon belongs to you and lasts briefly, and at a table of six players you need to
			//be able to tell it apart from a DM's monster at a glance, even through a wall.
			//Frozen, like any of the mod's monsters: a summon acts on its turn via autoAct, not on its own.
			//Its own AI is for ambient NPCs, not for this.
			//No declared size: a summon is drawn at the size of the entity lending it a body, which is
			//exactly what the spell chose. Scaling it would contradict that choice.
			CreatureType.CONSTRUCT, 0, 0, 1, CreatureSize.UNKNOWN, MonsterRegistry.Appearance.GLOWING, false, false, false));

		//In front of the caster, not on top of them: spawning it inside their own hitbox would leave it pushing them.
		Vec3 spot = caster.position().add(caster.getViewVector(1.0f).scale(2.0));
		//The owner tag is set BEFORE it joins the turn order: addLateMonster reads it to decide whether
		//it's an enemy, and doing it afterward would add it as one — combat would never end for as long as
		//the summon lasted.
		Entity summoned = MonsterRegistry.spawnAt(level, spot.x, spot.y, spot.z, monsterId, entity -> {
			CompoundTag data = entity.getPersistentData();
			CompoundTag tag = data.getCompound("dndsheets"); //Same compartment as the rest of the NBT state.
			tag.putString(OWNER_KEY, caster.getStringUUID());
			tag.putInt(ROUNDS_KEY, ZoneManager.DEFAULT_ROUNDS);
			data.put("dndsheets", tag);
		});
		if (summoned == null) return null;

		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.summoned",
			SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster), ContentNames.of(spell.name()))
			.withStyle(ChatFormatting.GOLD));
		return summoned;
	}

	/** UUID of the player who summoned it, or {@code null} if that entity isn't a summon. */
	public static String ownerOf(Entity entity) {
		CompoundTag data = entity.getPersistentData();
		if (!data.contains("dndsheets")) return null;
		String owner = data.getCompound("dndsheets").getString(OWNER_KEY);
		return owner.isEmpty() ? null : owner;
	}

	/**
	 * <p>Who a summon attacks on its turn: its owner's nearest enemy, not the nearest player. Without
	 * this, a player's Spiritual Weapon would attack them, which is exactly the opposite of the target it
	 * exists to attack.</p>
	 */
	public static Entity findEnemyTarget(ServerLevel level, Entity summoned, double range) {
		Entity best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (Entity candidate : level.getEntities(summoned,
				summoned.getBoundingBox().inflate(range),
				e -> e.isAlive() && TurnManager.isMonster(e) && ownerOf(e) == null)) {
			double distSq = candidate.position().distanceToSqr(summoned.position());
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				best = candidate;
			}
		}
		return best;
	}

	//getAllEntities() returns an Iterable, not a collection, and it also has to be copied before iterating:
	//dismiss() removes entities, and doing that over the level's live view is a ConcurrentModificationException
	//waiting for someone to have two summons at once.
	private static List<Entity> snapshot(ServerLevel level) {
		List<Entity> all = new ArrayList<>();
		level.getAllEntities().forEach(all::add);
		return all;
	}

	/** Decrements one round for each summon and dismisses the ones that expire. */
	public static void endRound(ServerLevel level) {
		for (Entity entity : snapshot(level)) {
			if (ownerOf(entity) == null) continue;
			CompoundTag tag = entity.getPersistentData().getCompound("dndsheets");
			int left = tag.getInt(ROUNDS_KEY) - 1;
			if (left > 0) {
				tag.putInt(ROUNDS_KEY, left);
				entity.getPersistentData().put("dndsheets", tag);
				continue;
			}
			dismiss(level, entity);
		}
	}

	/** Dismisses that player's summons: these spells require concentration. */
	public static void removeFor(ServerLevel level, UUID ownerId) {
		String owner = ownerId.toString();
		for (Entity entity : snapshot(level)) {
			if (owner.equals(ownerOf(entity))) dismiss(level, entity);
		}
	}

	//DISCARDED and not KILLED: a summon that dissipates doesn't "die", so it shouldn't drop loot, grant XP,
	//or count as a defeated enemy. markDefeated is still needed so it doesn't block combat from ending.
	private static void dismiss(ServerLevel level, Entity entity) {
		TurnManager.markDefeated(entity.getId());
		CombatFx.defeated(entity);
		entity.remove(Entity.RemovalReason.DISCARDED);
		for (Player player : level.players()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.spell.summon_faded",
				entity.getName().getString()).withStyle(ChatFormatting.GRAY));
		}
	}
}
