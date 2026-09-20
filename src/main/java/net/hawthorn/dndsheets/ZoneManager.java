package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * <p>Persistent zones: an area that gets <em>placed</em> and stays there across rounds, damaging whoever
 * starts their turn inside it. Covers both walls (Wall of Fire, Wall of Thorns) and lingering area
 * effects (Moonbeam, Cloudkill, Spirit Guardians).</p>
 *
 * <p>This is a distinct capability from the area shapes in {@link SpellCastManager}, even though they
 * share the geometry: a line or a cone is emitted from the caster and resolved <em>once</em>. What sets
 * a zone apart is <b>persistence</b>, not shape — that's why shape is just another field instead of a
 * separate class, and why {@code inShape} is reused as-is.</p>
 *
 * <p>The wall doesn't place actual blocks: changing the world would require cleaning it up afterward and
 * deciding what happens if someone mines it or the chunk unloads. It's stored as a region and checked at
 * the start of each turn, which is exactly when 5e says the saving throw should happen. The geometry is
 * the same {@code inShape("wall", ...)} already checked by the self-test.</p>
 *
 * <p>In-memory, per-encounter state, same as turn order: a wall shouldn't survive a server restart,
 * because the combat it was cast in doesn't survive one either.</p>
 */
public class ZoneManager {

	/**
	 * @param origin      base of the wall, at the caster's foot height (height is measured upward from
	 *                    there, see {@code SpellCastManager.WALL_HEIGHT}).
	 * @param casterId    whose it is: needed to remove it if they lose concentration.
	 */
	/**
	 * @param shape          geometry, the same kind {@code SpellCastManager.inShape} understands:
	 *                       {@code wall}, {@code sphere}, {@code cone}, {@code line}.
	 * @param followsCaster  the zone re-centers on the caster every round (Spirit Guardians).
	 *                       Without this we'd have to choose between not having that spell or lying about it.
	 */
	public record Zone(UUID casterId, String spellName, Vec3 origin, Vec3 direction, double size,
	                   String shape, boolean followsCaster,
	                   String dice, String damageType, String saveAbility, int saveDc, boolean halfOnSave,
	                   int roundsRemaining) {

		Zone tick() {
			return new Zone(casterId, spellName, origin, direction, size, shape, followsCaster,
				dice, damageType, saveAbility, saveDc, halfOnSave, roundsRemaining - 1);
		}

		Zone movedTo(Vec3 newOrigin) {
			return new Zone(casterId, spellName, newOrigin, direction, size, shape, followsCaster,
				dice, damageType, saveAbility, saveDc, halfOnSave, roundsRemaining);
		}
	}

	private static final List<Zone> active = new ArrayList<>();

	//Default duration: 5e's 1 minute = 10 rounds, which is how long most walls last.
	public static final int DEFAULT_ROUNDS = 10;

	/**
	 * @param aimPoint where the caster is aiming (ray hit against the terrain), or {@code null} to
	 *                 place it right in front. A zone that follows the caster (Spirit Guardians) ignores
	 *                 this: it's born centered on them and re-centers at the end of each round.
	 */
	public static void place(ServerPlayer caster, SpellRegistry.Spell spell, int saveDc, Vec3 aimPoint) {
		Zone zone = zoneAt(caster, spell, saveDc, aimPoint);
		active.add(zone);

		if (caster.level() instanceof ServerLevel level) draw(level, zone);
		ChatFeedback.broadcast(caster, Component.translatable("chat.dndsheets.spell.zone_placed",
			SheetLoader.characterNameOf(SheetLoader.getServerSheet(caster.getStringUUID()), caster), ContentNames.of(spell.name()))
			.withStyle(ChatFormatting.DARK_PURPLE));
	}

	/**
	 * <p>Sneak + click with a zone staff: draws where it would land <b>without placing it</b> — costs no
	 * spell slot, no action, and doesn't enter {@code active}.</p>
	 *
	 * <p>It comes from {@link #zoneAt} and is drawn with the same {@link #draw} used for the real thing,
	 * and that's not just a line-count saving: a preview computed separately is one that can lie. A wall
	 * shown two blocks to the left of where it will actually land is worse than showing nothing, because
	 * on top of that it makes you trust it.</p>
	 */
	public static void preview(ServerPlayer caster, SpellRegistry.Spell spell, Vec3 aimPoint) {
		if (caster.level() instanceof ServerLevel level) draw(level, zoneAt(caster, spell, 0, aimPoint));
	}

	//The zone that would result from this cast, without placing it: what place() registers and what
	//preview() shows both come from here, so they can never disagree.
	private static Zone zoneAt(ServerPlayer caster, SpellRegistry.Spell spell, int saveDc, Vec3 aimPoint) {
		//A zone's axis is HORIZONTAL, always: the zone rises FROM THE GROUND. With the raw view vector,
		//aiming at the ground — which is exactly how one gets placed — tilted the axis downward, so the
		//wall sank into the terrain and its "along" (see SpellCastManager.inShape) stopped lining up with
		//anyone: the zone got placed, got announced in chat, and never hit anybody.
		Vec3 flat = new Vec3(caster.getViewVector(1.0f).x, 0, caster.getViewVector(1.0f).z);
		//Looking straight up or down leaves no horizontal direction to normalize: fall back to body yaw.
		Vec3 direction = flat.lengthSqr() < 1.0e-6 ? Vec3.directionFromRotation(0, caster.getYRot()) : flat.normalize();

		//Where they're aiming, not a couple of blocks ahead. Always placing it two steps out turned the
		//wall into something that could only be put on top of yourself: there was no way to block a
		//corridor ten blocks away or to cut off an enemy's path, which is exactly what the spell is for.
		//The base sits at foot height rather than eye height — the aimed point IS the ray's hit against
		//the ground — which is what a wall needs so it isn't born with its lower half buried.
		Vec3 ahead = caster.position().add(direction.scale(2.0));
		Vec3 origin = spell.followsCaster() ? caster.position() : aimPoint != null ? aimPoint : ahead;
		return new Zone(caster.getUUID(), spell.name(), origin, direction, spell.aoeRadius(),
			spell.aoeShape(), spell.followsCaster(),
			spell.dice(), spell.damageType(), spell.saveAbility(), saveDc, spell.halfOnSave(), DEFAULT_ROUNDS);
	}

	/**
	 * <p>Called when a combatant's turn starts: if they're inside a wall, they roll their save and take
	 * damage. This is the exact moment 5e calls for it, which is why it hooks into
	 * {@code TurnManager.beginTurn} alongside status effects instead of running on its own tick.</p>
	 */
	public static void onTurnStart(ServerLevel level, Entity entity) {
		if (active.isEmpty()) return;
		Combatant combatant = Combatant.of(entity);
		if (combatant == null) return;

		for (Zone wall : new ArrayList<>(active)) {
			if (!SpellCastManager.inShape(wall.shape(), wall.origin(), wall.direction(), wall.size(),
					entity.getBoundingBox().getCenter())) {
				continue;
			}

			DiceManager.RollOutcome damageRoll = DiceManager.roll(new com.google.gson.JsonObject(), wall.dice());
			if (damageRoll.result() == null) continue;

			Combatant.SaveRoll save = combatant.rollSave(wall.saveAbility());
			boolean saved = save.succeeds(wall.saveDc());
			int amount = saved ? (wall.halfOnSave() ? damageRoll.result().getValue() / 2 : 0) : damageRoll.result().getValue();
			//A spell always counts as magical, same as in SpellCastManager and MonsterActionManager.
			amount = DamageTypes.applyMultiplier(amount, combatant.effectiveDamageMultiplier(wall.damageType(), true));

			CombatFx.spellImpact(entity, saved, wall.damageType());
			broadcast(level, Component.translatable("chat.dndsheets.spell.zone_tick",
				combatant.name(), wall.spellName(), save.formatted(), wall.saveDc(), amount).withStyle(ChatFormatting.DARK_RED));
			if (amount > 0) combatant.takeDamage(amount);
		}
	}

	/** Called when a full round ends: decrements duration and removes anything that expires. */
	public static void endRound(ServerLevel level) {
		Iterator<Zone> it = active.iterator();
		List<Zone> renewed = new ArrayList<>();
		while (it.hasNext()) {
			Zone wall = it.next().tick();
			//Spirit Guardians and similar: the zone travels with its caster, so it re-centers on them at the
			//end of the round. If the caster is no longer in the world, it stays where it was instead of
			//vanishing without warning.
			if (wall.followsCaster()) {
				ServerPlayer owner = level.getServer().getPlayerList().getPlayer(wall.casterId());
				if (owner != null) wall = wall.movedTo(owner.position());
			}
			it.remove();
			if (wall.roundsRemaining() > 0) {
				renewed.add(wall);
				draw(level, wall); //Redrawn every round: without this the wall is invisible except at the instant it was cast.
			} else {
				broadcast(level, Component.translatable("chat.dndsheets.spell.zone_faded", wall.spellName()).withStyle(ChatFormatting.GRAY));
			}
		}
		active.addAll(renewed);
	}

	/**
	 * <p>Removes that caster's walls. Walls require concentration, so losing it snuffs them out —
	 * without this, failing the Constitution save left the wall burning anyway, which is exactly the bug
	 * that was already fixed once for status effects.</p>
	 */
	public static void removeFor(UUID casterId) {
		active.removeIf(wall -> wall.casterId().equals(casterId));
	}

	/** Combat has ended: with no turn order there are no rounds to count, so there's no wall to maintain. */
	public static void clear() {
		active.clear();
	}

	//Particles along the wall and across its full height, so its location is visible: without visual
	//representation, a persistent wall is an invisible trap instead of a tactical decision.
	private static void draw(ServerLevel level, Zone wall) {
		if ("wall".equals(wall.shape())) {
			int samplesAlong = Math.max(4, (int) (wall.size() * 2));
			for (int i = 0; i <= samplesAlong; i++) {
				Vec3 base = wall.origin().add(wall.direction().scale(wall.size() * i / samplesAlong));
				for (double y = 0; y <= SpellCastManager.WALL_HEIGHT; y += 0.5) {
					level.sendParticles(net.minecraft.core.particles.ParticleTypes.FLAME,
						base.x, base.y + y, base.z, 1, 0, 0, 0, 0);
				}
			}
			return;
		}
		//Any other shape is marked with the same ring already used for an instant area effect: the player
		//already knows how to read it, and reinventing a drawing per shape adds nothing.
		CombatFx.aoeRing(level, wall.origin(), wall.size());
	}

	private static void broadcast(ServerLevel level, Component message) {
		for (ServerPlayer player : level.players()) player.sendSystemMessage(message);
	}
}
