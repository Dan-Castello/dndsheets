package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * <p>Lair actions: on initiative count 20 of each round (before anyone acts) a monster that has them uses one,
 * once per round for the whole lair. Fired from {@link TurnManager} at the start of the encounter and at every new
 * round. ponytail: the first monster in the order that declares {@code lairActions} acts, against the nearest
 * player within {@value #REACH} blocks; there is no notion of "the lair" as a place.</p>
 */
final class LairActionManager {
	private static final double REACH = 40.0;

	private LairActionManager() {
	}

	static void onRoundStart(ServerLevel level, List<TurnManager.TurnEntry> order) {
		for (TurnManager.TurnEntry entry : order) {
			if (!entry.isMonster()) continue;
			Entity monster = level.getEntity(entry.entityId());
			if (monster == null || !monster.isAlive()) continue;
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(monster);
			if (block == null || block.lairActions().isEmpty()) continue;

			Player target = MonsterActionManager.nearestLivingPlayer(level, monster, REACH);
			if (target == null) return;
			List<MonsterRegistry.MonsterSpell> options = block.lairActions();
			MonsterActionManager.resolveLairAction(monster, block, options.get(monster.level().getRandom().nextInt(options.size())), target);
			return;
		}
	}
}
