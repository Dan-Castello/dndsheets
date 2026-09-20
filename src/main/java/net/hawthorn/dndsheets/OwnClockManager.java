package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * <p>Bosses that <b>don't queue up</b>. A creature with {@code "ownClock": true} in its stat block leaves
 * the initiative order but not combat: it takes damage under all the normal 5e rules, counts toward
 * ending the encounter, and a condition that incapacitates it stops it cold just like anyone else. The
 * only thing it ignores is waiting for its turn.</p>
 *
 * <p><b>The guardrail that keeps it fair</b> is {@link #CYCLE_TICKS}: it acts once every six seconds,
 * which is exactly the length of a 5e round. It doesn't get more actions than anyone else — its actions
 * are <em>desynchronized</em>. Removing that cap turns a boss into a bug.</p>
 *
 * <p><b>Why its target also gets locked in.</b> A boss that flees or loses aggro breaks the scene far
 * worse than one that waits its turn: players watch it leave and read that as a failure, not a design
 * choice. So on entering combat it has its vanilla flee goals removed
 * ({@code PanicGoal}, {@code AvoidEntityGoal} — a ravager on fire flees, and that's the end of the
 * encounter) and every cycle it gets re-targeted if it lost its target. No mixin needed: a {@code Mob}'s
 * goals can be removed by their class name.</p>
 */
public final class OwnClockManager {

	//Six seconds: the 5e round. This is the number that keeps this from being "the boss does whatever it wants".
	private static final int CYCLE_TICKS = 120;
	//How far it looks for a target if it lost one. Same range autoAct uses to pick who to hit.
	private static final double AGGRO_RANGE = 30.0;

	private OwnClockManager() {
	}

	/**
	 * <p>Starts this creature's clock and announces to the table what's happening. Idempotent by the
	 * caller's own design: it gets invoked when building initiative and when adding a monster late, and
	 * both times on different creatures.</p>
	 */
	public static void start(ServerLevel level, Entity boss) {
		if (!MonsterRegistry.isOffClock(boss)) return;
		hardenAggro(boss);
		announce(level, boss);
		schedule(level, boss.getId());
	}

	private static void schedule(ServerLevel level, int entityId) {
		DndsheetsMod.queueServerWork(CYCLE_TICKS, () -> {
			//Combat is what governs this: outside of it there's no clock to keep, and the boss goes back to
			//being a normal mob with its own AI. Without this early return the cycle would keep rearming forever.
			if (!TurnManager.isActive()) return;
			Entity boss = level.getEntity(entityId);
			if (boss == null || !boss.isAlive() || !MonsterRegistry.isOffClock(boss)) return;

			keepAggro(level, boss);
			//autoAct checks on its own whether the creature can act (paralyzed, stunned...) without
			//requesting a turn, since the stat block flags it. See MonsterActionManager.
			MonsterActionManager.autoAct(level, boss);
			schedule(level, entityId);
		});
	}

	/**
	 * <p>Removes the vanilla goals that would make it flee. The goal list is walked and entries are
	 * discarded by type: this is the only way to do it without mixins, and it works on the base entity
	 * regardless of whose mod it belongs to.</p>
	 */
	private static void hardenAggro(Entity boss) {
		if (!(boss instanceof Mob mob)) return;
		//Can't remove while iterating: goals are collected first and removed afterward.
		List<net.minecraft.world.entity.ai.goal.Goal> fleeing = new ArrayList<>();
		Set<WrappedGoal> goals = mob.goalSelector.getAvailableGoals();
		for (WrappedGoal wrapped : goals) {
			if (wrapped.getGoal() instanceof PanicGoal || wrapped.getGoal() instanceof AvoidEntityGoal<?>) {
				fleeing.add(wrapped.getGoal());
			}
		}
		for (net.minecraft.world.entity.ai.goal.Goal goal : fleeing) mob.goalSelector.removeGoal(goal);
		//And to keep it from unloading due to distance mid-fight, which is the other way to "disappear".
		mob.setPersistenceRequired();
	}

	/** If it lost its target (died, walked away, never had one), it's given the nearest living player. */
	private static void keepAggro(ServerLevel level, Entity boss) {
		if (!(boss instanceof Mob mob)) return;
		LivingEntity target = mob.getTarget();
		if (target != null && target.isAlive()) return;
		Player nearest = level.getNearestPlayer(boss, AGGRO_RANGE);
		if (nearest instanceof ServerPlayer serverPlayer && serverPlayer.isAlive()) mob.setTarget(serverPlayer);
	}

	/**
	 * <p>The heads-up to the table, on screen rather than in chat. This is half the design: without it, a
	 * player who sees the dragon move outside its turn concludes the mod is broken. With it, they conclude
	 * that creature is something else entirely — which is exactly what we wanted.</p>
	 *
	 * <p>The subtitle changes depending on creature type, because "cannot be stopped by turns" said of a
	 * dragon and said of an ooze are two different statements, and the one that doesn't fit reads as a
	 * template.</p>
	 */
	private static void announce(ServerLevel level, Entity boss) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(boss);
		if (block == null) return;

		Component title = ContentNames.of(block.name()).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD);
		Component subtitle = Component.translatable(subtitleKey(block.type())).withStyle(ChatFormatting.GRAY);

		for (ServerPlayer player : level.players()) {
			//Slow fade-in and fade-out: a title that pops up instantly reads as a game glitch.
			player.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
			player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
			player.connection.send(new ClientboundSetTitleTextPacket(title));
			//The sound does half the work: it alerts even someone who's looking at their sheet.
			player.playNotifySound(SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.6f, 1.4f);
		}
	}

	/** One phrase per creature type; the rest share the generic one. */
	static String subtitleKey(CreatureType type) {
		return switch (type) {
			case DRAGON, GIANT, MONSTROSITY, ABERRATION, FIEND, CELESTIAL, UNDEAD, ELEMENTAL, CONSTRUCT ->
				"chat.dndsheets.ownclock.subtitle." + type.name().toLowerCase(java.util.Locale.ROOT);
			default -> "chat.dndsheets.ownclock.subtitle.generic";
		};
	}
}
