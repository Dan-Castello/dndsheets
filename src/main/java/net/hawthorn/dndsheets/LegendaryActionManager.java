package net.hawthorn.dndsheets;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * <p>Legendary actions: a boss acts <b>at the end of someone else's turn</b>, not just on its own. It's
 * the rule that makes a dragon against four players a fight instead of a turn exchange where the party
 * hits four times for every one hit the dragon lands.</p>
 *
 * <p>The other half of Legendary Resistance, and the one that actually changes how combat feels. Without
 * it, the bestiary had 43 dragons, a tarrasque, and a lich all fighting like an oversized goblin.</p>
 *
 * <p><b>Model deliberately reduced to "one attack."</b> In the SRD each boss has its own list of
 * legendary actions with different costs (Attack for 1, Tail Attack for 2, Detect for 1...). Here a
 * legendary action is <em>one of its own attacks</em>, costing 1, until its per-round budget runs out.
 * It's the one nearly all of them share and the one that decides the fight; inventing a scheme for the
 * rest would mean writing a field per boss for abilities that, in this mod, don't even have anything to
 * target (moving without provoking, detecting, changing the terrain).</p>
 *
 * <p>The budget lives in the entity's NBT tag, same as HP and legendary resistances: it belongs to the
 * individual, not the species, and Minecraft already saves and loads it on its own.</p>
 */
public class LegendaryActionManager {

	private static final String LEGENDARY_ACTIONS_LEFT = "legendaryActionsLeft";
	/** How far it looks for a target to punish. Same value the rest of the monster actions use. */
	private static final double TARGET_RANGE = 30.0;

	/**
	 * <p>Called when someone's turn ENDS, which is when 5e lets a boss act. Every legendary creature in
	 * the turn order —except the one that just went— spends an action if it has one left.</p>
	 *
	 * <p>The creature that just finished its own turn doesn't get the action: in 5e these are actions
	 * meant to be used <em>outside</em> its own turn, and giving it one here would hand it a free extra
	 * attack right after the one it already made.</p>
	 */
	static void onTurnEnded(ServerLevel level, TurnManager.TurnEntry finishing, List<TurnManager.TurnEntry> order) {
		for (TurnManager.TurnEntry entry : order) {
			if (finishing != null && entry.entityId() == finishing.entityId()) continue;
			if (!entry.isMonster()) continue;

			Entity boss = level.getEntity(entry.entityId());
			if (boss == null || !boss.isAlive()) continue;
			//A paralyzed, stunned, or unconscious boss doesn't take legendary actions: in 5e the rule itself
			//says so ("can't use them while incapacitated"), and without this a sleeping dragon kept
			//dealing out three attacks per round. Checked BEFORE spending, so it isn't charged a use for
			//an action that never actually happens.
			if (TurnManager.isIncapacitated(boss)) continue;
			if (!spendAction(boss)) continue;

			Player target = level.getNearestPlayer(boss, TARGET_RANGE);
			if (target == null) {
				//Nobody in range: the use is refunded instead of wasted. Spending it against no one would
				//punish the boss for where the party happens to be, which is exactly the opposite of what
				//the rule is for.
				refundAction(boss);
				continue;
			}
			MonsterActionManager.resolveLegendaryAttack(boss, target);
		}
	}

	/** At the start of its own turn it recovers them all: that's how they recharge in 5e. */
	static void onOwnTurnStart(Entity boss) {
		int budget = budgetOf(boss);
		if (budget <= 0) return;
		write(boss, budget);
	}

	private static int budgetOf(Entity entity) {
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		if (block == null) return 0;
		//With its own clock, none. Legendary actions exist in 5e so a boss can do something while it
		//waits for its turn; something that doesn't wait for a turn doesn't need them, and giving them
		//anyway would be granting two action economies at once.
		if (block.ownClock()) return 0;
		return block.legendaryActions();
	}

	/**
	 * <p>Spends an action. Returns false if the creature isn't legendary or if it already used up its
	 * budget this round.</p>
	 *
	 * <p>Without the tag set yet, it still counts as "has them all," same as Legendary Resistance: a boss
	 * summoned before the rule existed shouldn't be stuck without them forever.</p>
	 */
	private static boolean spendAction(Entity boss) {
		int budget = budgetOf(boss);
		if (budget <= 0) return false;
		CompoundTag tag = boss.getPersistentData().getCompound("dndsheets");
		int left = tag.contains(LEGENDARY_ACTIONS_LEFT) ? tag.getInt(LEGENDARY_ACTIONS_LEFT) : budget;
		if (left <= 0) return false;
		write(boss, left - 1);
		return true;
	}

	private static void refundAction(Entity boss) {
		CompoundTag tag = boss.getPersistentData().getCompound("dndsheets");
		write(boss, Math.min(budgetOf(boss), tag.getInt(LEGENDARY_ACTIONS_LEFT) + 1));
	}

	private static void write(Entity boss, int left) {
		CompoundTag data = boss.getPersistentData();
		CompoundTag tag = data.getCompound("dndsheets");
		tag.putInt(LEGENDARY_ACTIONS_LEFT, Math.max(0, left));
		data.put("dndsheets", tag);
	}
}
