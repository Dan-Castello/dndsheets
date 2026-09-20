package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <p>Terrain surfaces, the other half of the height+shove combo brought over from Baldur's Gate 3:
 * REAL water in the world (actual blocks, not an abstract zone) puts out fire and conducts lightning, and
 * an area fire impact leaves the ground burning for a few rounds, not just the instant of the hit.</p>
 *
 * <p><b>Why this isn't a {@link ZoneManager.Zone}.</b> A zone BELONGS to someone — it has a caster, it has
 * concentration, it goes out if they lose it; a fire patch left by a Fireball belongs to no one, and any
 * other area hit that lands on it can rekindle it. They share the same {@code TurnManager.beginTurn} hook
 * because the QUESTION is the same (is there anything on the ground under my feet when my turn starts?),
 * but the OWNER of the answer is different.</p>
 *
 * <p><b>Why water is real and not another tracked surface.</b> Minecraft already knows where water is;
 * inventing a second "water surface" on top of it would have meant maintaining two sources of truth for
 * the same question. Putting out fire and conducting lightning check the world's actual block — no new
 * spell, no content to load, no state to clean up when the chunk unloads.</p>
 *
 * <p><b>Deliberate simplification</b>, {@code ponytail}: the fire patch isn't drawn as real fire blocks
 * (it could ignite a player's base with no one around to clean it up afterward) — it's an abstract zone
 * with particles, the same way {@link ZoneManager} already does with its walls. The lightning+water spark
 * is fixed damage, not a full roll with a save: it's a side effect of the impact, not a second spell, and
 * complicating it wouldn't add anything the player would ever notice.</p>
 */
class SurfaceManager {

	private record Fire(Vec3 origin, double radius, int roundsRemaining) {
		Fire tick() {
			return new Fire(origin, radius, roundsRemaining - 1);
		}
	}

	private static final List<Fire> fires = new ArrayList<>();

	private static final int FIRE_ROUNDS = 3;
	private static final String FIRE_DICE = "1d4";
	//Impact block and the one next to it: plenty of water radius without having to sweep a whole area
	//every frame each time a spell lands.
	private static final int WATER_CHECK_RADIUS = 2;
	//"Electric puddle": anyone standing in real water within this radius of a lightning impact takes the
	//same jolt, no save — it's water conducting electricity, not a rule you can dodge.
	private static final double LIGHTNING_WATER_RADIUS = 6.0;
	private static final String LIGHTNING_SPLASH_DICE = "1d6";

	/**
	 * <p>Called right after resolving an area spell's damage (see {@code SpellCastManager}).
	 * {@code alreadyHit} are the entities the spell already hit directly: the water+lightning spark skips
	 * them, so they don't get charged the same lightning bolt twice.</p>
	 */
	static void onAoeImpact(ServerLevel level, Vec3 impactPoint, String damageType, List<Entity> alreadyHit) {
		boolean wet = isNearWater(level, impactPoint);
		if ("fire".equals(damageType)) {
			if (wet) {
				broadcast(level, Component.translatable("chat.dndsheets.surface.fire_fizzles").withStyle(ChatFormatting.AQUA));
				return;
			}
			ignite(level, impactPoint);
		} else if ("lightning".equals(damageType) && wet) {
			shockWater(level, impactPoint, alreadyHit);
		}
	}

	//If there was already fire there, it gets rewound instead of stacking a second patch on top of the
	//first (two Fireballs in a row on the same spot shouldn't double the per-turn damage, only keep it going).
	private static void ignite(ServerLevel level, Vec3 point) {
		for (int i = 0; i < fires.size(); i++) {
			Fire existing = fires.get(i);
			if (existing.origin().distanceTo(point) <= existing.radius()) {
				fires.set(i, new Fire(existing.origin(), existing.radius(), FIRE_ROUNDS));
				return;
			}
		}
		fires.add(new Fire(point, 2.5, FIRE_ROUNDS));
		broadcast(level, Component.translatable("chat.dndsheets.surface.fire_ignites").withStyle(ChatFormatting.GOLD));
	}

	private static void shockWater(ServerLevel level, Vec3 impactPoint, List<Entity> alreadyHit) {
		for (Entity entity : level.getEntitiesOfClass(LivingEntity.class,
				new net.minecraft.world.phys.AABB(impactPoint, impactPoint).inflate(LIGHTNING_WATER_RADIUS))) {
			if (alreadyHit.contains(entity) || !entity.isInWaterOrBubble()) continue;
			Combatant combatant = Combatant.of(entity);
			if (combatant == null) continue;

			DiceManager.RollOutcome roll = DiceManager.roll(new com.google.gson.JsonObject(), LIGHTNING_SPLASH_DICE);
			if (roll.result() == null) continue;
			int amount = DamageTypes.applyMultiplier(roll.result().getValue(), combatant.effectiveDamageMultiplier("lightning", true));
			CombatFx.spellImpact(entity, false, "lightning");
			broadcast(level, Component.translatable("chat.dndsheets.surface.water_shock", combatant.name(), roll.formatted())
				.withStyle(ChatFormatting.AQUA));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Called when a combatant's turn starts: if they're standing over fire left on the ground, they burn. */
	static void onTurnStart(ServerLevel level, Entity entity) {
		if (fires.isEmpty()) return;
		Combatant combatant = Combatant.of(entity);
		if (combatant == null) return;

		for (Fire fire : fires) {
			if (fire.origin().distanceTo(entity.getBoundingBox().getCenter()) > fire.radius()) continue;

			DiceManager.RollOutcome roll = DiceManager.roll(new com.google.gson.JsonObject(), FIRE_DICE);
			if (roll.result() == null) continue;
			int amount = DamageTypes.applyMultiplier(roll.result().getValue(), combatant.effectiveDamageMultiplier("fire", true));
			CombatFx.spellImpact(entity, false, "fire");
			broadcast(level, Component.translatable("chat.dndsheets.surface.fire_tick", combatant.name(), roll.formatted())
				.withStyle(ChatFormatting.GOLD));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Full round: decrements duration, extinguishes what expires, and redraws whatever keeps burning. */
	static void endRound(ServerLevel level) {
		Iterator<Fire> it = fires.iterator();
		List<Fire> renewed = new ArrayList<>();
		while (it.hasNext()) {
			Fire fire = it.next().tick();
			it.remove();
			if (fire.roundsRemaining() > 0) {
				renewed.add(fire);
				draw(level, fire);
			}
		}
		fires.addAll(renewed);
	}

	static void clear() {
		fires.clear();
	}

	private static boolean isNearWater(ServerLevel level, Vec3 point) {
		BlockPos center = BlockPos.containing(point);
		for (BlockPos pos : BlockPos.betweenClosed(
				center.offset(-WATER_CHECK_RADIUS, -1, -WATER_CHECK_RADIUS),
				center.offset(WATER_CHECK_RADIUS, 1, WATER_CHECK_RADIUS))) {
			if (level.getFluidState(pos).is(FluidTags.WATER)) return true;
		}
		return false;
	}

	//Same visual criterion as ZoneManager: particles across the area instead of nothing, so a fire patch is
	//a visible tactical decision and not an invisible trap.
	private static void draw(ServerLevel level, Fire fire) {
		int samples = 10;
		for (int i = 0; i < samples; i++) {
			double angle = 2 * Math.PI * i / samples;
			double x = fire.origin().x + Math.cos(angle) * fire.radius();
			double z = fire.origin().z + Math.sin(angle) * fire.radius();
			level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME, x, fire.origin().y + 0.1, z, 1, 0, 0, 0, 0);
		}
	}

	private static void broadcast(ServerLevel level, Component message) {
		for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
	}
}
