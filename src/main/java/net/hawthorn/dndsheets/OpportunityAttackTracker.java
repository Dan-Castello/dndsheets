package net.hawthorn.dndsheets;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

//Opportunity attacks for turn mode — extracted from TurnManager (see finding F3): which
//monsters had the mover within melee reach, so their reaction fires the moment they move away without
//disengaging. Owns its own state; receives the turn order as a parameter instead of reading
//TurnManager's field directly.
class OpportunityAttackTracker {
	//Approximate melee reach (same range Minecraft already uses for hitting).
	//Package-private on purpose: MonsterActionManager reuses this same value to decide when one of our own
	//monsters is already close enough to attack without moving closer (see autoAct/moveTowardIfNeeded).
	static final double MELEE_REACH = 3.0;
	private final Set<Integer> withinReach = new HashSet<>();

	//The last checked position is stored to skip iterating over all combatants if it hasn't changed since
	//the previous tick — checkOpportunityAttacks used to run on every tick of the player with the turn,
	//even while standing still.
	private final Map<Integer, Vec3> lastCheckedPos = new HashMap<>();

	void clear() {
		withinReach.clear();
	}

	void rekey(int oldId, int newId) {
		if (withinReach.remove(oldId)) withinReach.add(newId);
	}

	//When a player's turn starts, we note which monsters already have them within reach (adjacent from the
	//start, without "leaving" anything) so as not to fire a false reaction on the first tick of their turn.
	void seedReachState(ServerLevel level, ServerPlayer mover, List<TurnManager.TurnEntry> order) {
		withinReach.clear();
		//Without this, if the player ends a previous turn and starts this one WITHOUT moving from that
		//position, the first tick of the new turn would see the same "already checked" position from the
		//previous turn and would skip the opportunity check that actually needs to be reevaluated from scratch.
		lastCheckedPos.remove(mover.getId());
		for (TurnManager.TurnEntry entry : order) {
			if (entry.entityId() == mover.getId()) continue;
			Entity entity = level.getEntity(entry.entityId());
			//A boss with its own clock is excluded: it moves constantly, so it would trigger opportunity
			//attacks nonstop and combat would turn into a drip-feed of reaction rolls.
			if (entity != null && MonsterRegistry.statBlockOf(entity) != null && !MonsterRegistry.isOffClock(entity)
					&& entity.position().distanceTo(mover.position()) <= MELEE_REACH) {
				withinReach.add(entry.entityId());
			}
		}
	}

	//Called every tick while the player who's free to move (has the turn) is still alive: any monster in
	//the turn order that was within melee reach the previous tick and isn't anymore (moved away without
	//disengaging) spends its reaction on an opportunity attack with its first available real attack.
	//Deliberate simplification: monsters only, no PvP between players (other players are anchored and
	//can't move anyway while it isn't their turn).
	void checkOpportunityAttacks(ServerLevel level, ServerPlayer mover, List<TurnManager.TurnEntry> order) {
		//Disengaging: moving away stops triggering reactions this turn. We return BEFORE iterating anything,
		//but without touching withinReach — once the turn ends and the action expires, the record of who
		//had them in reach needs to still be accurate.
		if (TurnActionManager.isDisengaged(mover)) return;
		Vec3 pos = mover.position();
		if (pos.equals(lastCheckedPos.get(mover.getId()))) return; //No position change, nothing to reevaluate.
		lastCheckedPos.put(mover.getId(), pos);

		for (TurnManager.TurnEntry entry : order) {
			if (entry.entityId() == mover.getId()) continue;
			Entity entity = level.getEntity(entry.entityId());
			if (entity == null || !entity.isAlive() || MonsterRegistry.statBlockOf(entity) == null) continue;
			if (MonsterRegistry.isOffClock(entity)) continue; //Out of the order, out of reactions.


			boolean nowInReach = entity.position().distanceTo(mover.position()) <= MELEE_REACH;
			if (nowInReach) {
				withinReach.add(entry.entityId());
			} else if (withinReach.remove(entry.entityId()) && TurnManager.tryReact(entity)) {
				MonsterActionManager.resolveOpportunityAttack(entity, mover);
			}
		}
	}
}
