package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//Movement budget and position anchoring for turn mode — extracted from TurnManager (see
//finding F3): whoever does NOT have the turn stays anchored where they were; whoever DOES have it can only
//move away up to their sheet "speed" before being sent back to the last valid position. Owns its own
//state, without touching turn order — receives what it needs as a parameter instead of reading
//TurnManager's fields directly.
class MovementAnchorTracker {
	private record Pinned(ResourceKey<Level> dimension, Vec3 pos) {}

	//Position where whoever doesn't have the turn must stay; no entry = free to move (it's their turn).
	private final Map<Integer, Pinned> anchors = new HashMap<>();
	private static final double ANCHOR_TOLERANCE = 0.05;

	//Movement budget of whoever DOES have the turn: where they were when it started (origin) and the last
	//position seen within their range (lastGoodPos, where they get sent back if they overshoot).
	private final Map<Integer, Pinned> moveOrigin = new HashMap<>();
	private final Map<Integer, Pinned> lastGoodPos = new HashMap<>();
	private static final int DEFAULT_SPEED_FEET = 30;
	private static final double FEET_PER_BLOCK = 5.0;
	//speedBlocksFor runs 20 times/sec for the duration of a player's turn: the Pattern is cached instead
	//of being recompiled every tick.
	private static final Pattern SPEED_FEET_PATTERN = Pattern.compile("\\d+");

	//"speed" isn't clamped when saved (it's free-form text like "30 ft (climb 30 ft)", not a purely
	//numeric field like the ability scores) — the cap has to be applied here, the only place where that
	//text gets turned into an actual number that governs movement in combat. Without this, a player could
	//write "99999 ft" and completely bypass the movement budget.
	private static final int MAX_SPEED_FEET = 500;

	void pin(ServerLevel level, int entityId, Vec3 pos) {
		anchors.put(entityId, new Pinned(level.dimension(), pos));
	}

	void release(int entityId) {
		anchors.remove(entityId);
	}

	void clear() {
		anchors.clear();
		moveOrigin.clear();
		lastGoodPos.clear();
	}

	void rekey(int oldId, int newId) {
		if (anchors.containsKey(oldId)) anchors.put(newId, anchors.remove(oldId));
		if (moveOrigin.containsKey(oldId)) moveOrigin.put(newId, moveOrigin.remove(oldId));
		if (lastGoodPos.containsKey(oldId)) lastGoodPos.put(newId, lastGoodPos.remove(oldId));
	}

	void beginMovementBudget(ServerLevel level, int entityId, Vec3 pos) {
		Pinned pinned = new Pinned(level.dimension(), pos);
		moveOrigin.put(entityId, pinned);
		lastGoodPos.put(entityId, pinned);
	}

	Vec3 originOf(int entityId) {
		Pinned pinned = moveOrigin.get(entityId);
		return pinned != null ? pinned.pos() : Vec3.ZERO;
	}

	/** @return true if this player's tick was already resolved by the anchor (the caller should stop there). */
	boolean isAnchorHandledThisTick(ServerPlayer player) {
		Pinned anchor = anchors.get(player.getId());
		if (anchor == null) return false;

		if (player.level().dimension() != anchor.dimension()) {
			//Changed dimension while anchored (pushed into a portal, e.g.): the anchor is released instead
			//of comparing/teleporting across different levels.
			anchors.remove(player.getId());
			return false;
		}

		//Only the horizontal plane (X/Z) is corrected; Y is left completely free. This used to
		//compare/restore the position in 3D: if the turn ended with the player in the air (e.g. jumping
		//to force a Minecraft crit), they'd stay anchored at exactly that height forever, with gravity
		//fighting every tick against the teleport back — frozen in midair. Leaving Y untouched, gravity
		//lands them on its own while the horizontal anchor still prevents walking away.
		double dx = player.getX() - anchor.pos().x;
		double dz = player.getZ() - anchor.pos().z;
		if (dx * dx + dz * dz <= ANCHOR_TOLERANCE * ANCHOR_TOLERANCE) {
			return true;
		}

		player.teleportTo(anchor.pos().x, player.getY(), anchor.pos().z);
		Vec3 delta = player.getDeltaMovement();
		player.setDeltaMovement(0, delta.y, 0);
		CombatFx.actionBar(player, Component.translatable("chat.dndsheets.turn.cant_move").withStyle(ChatFormatting.RED));
		return true;
	}

	//How many blocks can be moved this turn: the sheet's "speed" (free-form text, e.g. "30 ft") converted
	//to blocks at 5 feet per block (the same grid conversion the rest of tabletop VTTs use). With no field
	//or no recognizable number, it falls back to the standard 5e speed (30 feet = 6 blocks).
	private static double speedBlocksFor(JsonObject sheet) {
		int feet = DEFAULT_SPEED_FEET;
		if (sheet != null && sheet.has("speed")) {
			try {
				Matcher matcher = SPEED_FEET_PATTERN.matcher(sheet.get("speed").getAsString());
				if (matcher.find()) {
					feet = Integer.parseInt(matcher.group());
				}
			} catch (RuntimeException ignored) {
				//NumberFormatException from parsing, or UnsupportedOperationException if "speed" ended up
				//as a JsonObject/JsonArray in an old sheet: either way, it falls back to DEFAULT_SPEED_FEET.
			}
		}
		feet = Math.max(0, Math.min(MAX_SPEED_FEET, feet));
		return feet / FEET_PER_BLOCK;
	}

	//Approximate conversion of a mob's vanilla speed (MOVEMENT_SPEED attribute, an internal unit with no
	//exact equivalence in blocks/turn given how Minecraft's movement physics actually work: friction,
	//terrain, jumping...) into a blocks-per-turn budget: it's scaled against a zombie's base speed (a
	//common "normal mob" reference) as if that speed represented the standard 5e 30 feet/6 blocks, with a
	//reasonable clamp range so as not to give absurd budgets to very fast/slow mobs. ponytail: a heuristic,
	//not a real physics simulation — if some modded mob ends up clearly too short or too long, adjust
	//ZOMBIE_BASELINE_SPEED or the clamp range.
	private static final double ZOMBIE_BASELINE_SPEED = 0.23;
	private static final double MIN_MOB_SPEED_BLOCKS = 2.0;
	private static final double MAX_MOB_SPEED_BLOCKS = 12.0;

	static double speedBlocksForMob(Entity entity) {
		if (!(entity instanceof LivingEntity living)) return 6.0;
		AttributeInstance speedAttr = living.getAttribute(Attributes.MOVEMENT_SPEED);
		double raw = speedAttr != null ? speedAttr.getValue() : ZOMBIE_BASELINE_SPEED;
		double blocks = 6.0 * (raw / ZOMBIE_BASELINE_SPEED);
		return Math.max(MIN_MOB_SPEED_BLOCKS, Math.min(MAX_MOB_SPEED_BLOCKS, blocks));
	}

	//Movement lockout for whoever DOES have the turn: as soon as they move further than their speed (in a
	//straight line from where the turn started) away from moveOrigin, they get sent back to the last
	//position seen within range. ponytail: straight-line distance from the origin, not accumulated path
	//and not horizontal-only — enough to cut off Minecraft's "free flight," not a real grid-square tracker.
	void enforceMovementBudget(ServerPlayer player) {
		//Dashing doubles the budget, which is what the Dash action does in 5e: it doesn't grant "extra"
		//movement on the side, it doubles what you already had.
		double speed = speedBlocksFor(SheetLoader.getServerSheet(player.getStringUUID()));
		if (TurnActionManager.isDashing(player)) speed *= 2;
		if (enforceBudget(player, speed)) {
			CombatFx.actionBar(player, Component.translatable("chat.dndsheets.turn.no_movement_left").withStyle(ChatFormatting.RED));
		}
	}

	//Same mechanism as above, for a compatibility mob (see TurnManager.isMonster): there's no HUD to warn,
	//so the caller (TurnManager.onMobTick) decides what to do with the result — in its case, ending the
	//mob's turn, since a mob has no "keep trying" once it runs out of movement.
	//@return true if its movement budget ran out (and it was sent back to its last valid position).
	boolean enforceMobMovementBudget(Entity entity, double speedBlocks) {
		return enforceBudget(entity, speedBlocks);
	}

	private boolean enforceBudget(Entity entity, double speedBlocks) {
		int id = entity.getId();
		Pinned origin = moveOrigin.get(id);
		if (origin == null) return false;
		//Speed 0 due to a condition (grappled, restrained, paralyzed, petrified, unconscious): this is
		//enforced here, the single point that both the player and the mob already pass through, instead of
		//in both callers. speedBlocks is ignored entirely, not reduced: in 5e the speed is 0, not "less".
		Combatant combatant = Combatant.of(entity);
		if (combatant != null && combatant.cannotMove()) speedBlocks = 0.0;
		if (entity.level().dimension() != origin.dimension()) {
			//Crossed to another level (portal) with the turn active: the recorded coordinates no longer
			//mean anything here — the budget is released instead of comparing/teleporting across dimensions.
			moveOrigin.remove(id);
			lastGoodPos.remove(id);
			return false;
		}
		Vec3 pos = entity.position();
		if (pos.distanceTo(origin.pos()) <= speedBlocks) {
			lastGoodPos.put(id, new Pinned(origin.dimension(), pos));
			return false;
		}
		Vec3 fallback = lastGoodPos.getOrDefault(id, origin).pos();
		entity.teleportTo(fallback.x, fallback.y, fallback.z);
		entity.setDeltaMovement(Vec3.ZERO);
		return true;
	}
}
