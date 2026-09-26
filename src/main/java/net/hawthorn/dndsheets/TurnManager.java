package net.hawthorn.dndsheets;

import com.google.gson.JsonObject;
import javax.annotation.Nullable;
import net.hawthorn.dndsheets.network.TurnStateMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>Turn mode: sorts a list of combatants by initiative and applies status effects (poison, etc.) at
 * the start of each one's turn. Every combatant (player OR DM-controlled monster) is entitled to ONE
 * action per turn; any extra attempt — whether because they already acted this turn, or because it isn't
 * their turn — is IGNORED entirely, not queued for later. When a turn reaches someone, they choose from
 * scratch what to do; nothing they attempted out of turn stays pending.</p>
 *
 * <p>Whoever has the turn also doesn't move with Minecraft's full freedom: they can only get as far from
 * where their turn started as their sheet "speed" allows (5 feet = 1 block, 30 feet by default if the
 * field is empty or not a number); past that, they're snapped back to their last valid position — see
 * {@link MovementAnchorTracker#enforceMovementBudget}.</p>
 *
 * <p>The turn advances on its own: as soon as {@link #tryAct} accepts an action from whoever has the
 * turn, one tick later it moves on to the next combatant without anyone typing {@code /dndturns next} —
 * see {@link #scheduleAutoAdvance}. Whoever has the turn glows (vanilla Glowing effect) so it's noticeable
 * without reading chat, and the state (round, whose turn it is, whether they've acted) is sent to every
 * client for the HUD — see {@code network.TurnStateMessage} / {@code client.TurnHudOverlay}.</p>
 *
 * <p>Outside turn mode ({@link #isActive()} false), everything behaves exactly as before: none of this
 * interferes if nobody uses {@code /dndturns start}.</p>
 *
 * <p>Every state mutator (start/next/cancel) goes through a single-server-tick debounce, to avoid
 * duplicating an advance if the same command fires twice by accident — the same problem already solved
 * for spellcasting in {@link SpellCastManager}.</p>
 *
 * <p>Known ceiling: all the state below is static and unique per server, so there can only be ONE
 * turn-mode combat at a time on the entire server (dimension or distance don't matter). Deliberate
 * decision: a server for this mod is one table. If someday two groups play simultaneous encounters, this
 * becomes per-encounter state (one instance per combat, chosen by proximity/dimension) — a big rewrite,
 * not a patch.</p>
 */
@Mod.EventBusSubscriber
public class TurnManager { //ponytail: one combat per server; per-encounter state if there's ever 2 tables.
	//isMonster is set when initiative is assembled (startAt), never re-inferred afterward: if the
	//combatant gets removed from the world mid-encounter (DM, DM Wand...) there'd be no way left to ask
	//MonsterRegistry what it was. See allEnemiesDefeated, which depends on this flag. playerUuid is null
	//for monsters; for players it survives an entityId that changes on reconnect — see
	//reconcilePlayerEntity, which uses it to find this player's spot in order after a relog.
	//ponytail: none of this is persisted to disk (tried and deliberately reverted — a compatibility mob
	//recovered after a restart didn't remember its mid-turn NoAI/movement, leaving it in a state stranger
	//than simply losing the encounter). This mod's sessions are ephemeral; a server restart mid-combat
	//simply cuts it short, like anything else in memory.
	public record TurnEntry(int entityId, String name, boolean isMonster, String playerUuid) {}
	public record StatusEffect(String name, String damageDice, int remainingTurns) {}

	private static final List<TurnEntry> order = new ArrayList<>();
	private static int currentIndex = -1;
	private static int round = 0;
	private static boolean active = false;
	private static long lastActionTick = -1;

	//Area the encounter started from (see startAt): re-scanned on its own every
	//LATE_MONSTER_SCAN_INTERVAL_TICKS while still active, adding any hostile mob that shows up after the
	//start to the order (a spider that wanders in mid-fight, e.g.) — without this, only whoever was
	//already inside at the exact instant of /dndturns start would join. combatOrigin null = no scan
	//pending (encounter over).
	private static Vec3 combatOrigin;
	private static double combatRadius;
	private static final int LATE_MONSTER_SCAN_INTERVAL_TICKS = 20; //1s: no need to notice a newcomer instantly.

	//Current turn's generation: bumped every time the turn genuinely advances (advance) or an action is
	//undone (undoAction). scheduleAutoAdvance captures the value in effect when it queues the auto-advance
	//and only runs it if nothing changed it in the meantime — without this, undoing an action and acting
	//again (e.g. with the "Undo Turn" item) could fire both the old auto-advance AND the new one, giving a
	//free extra action, and a manual /dndturns next right before the auto-advance could advance the round
	//twice.
	private static int turnToken = 0;

	//Compatibility mobs (an Enemy with no stat block of its own, see isMonster): unlike this mod's own
	//monsters (NoAI fixed from the moment they're summoned, see MonsterRegistry.spawnAt), these DO need
	//their real vanilla AI to move/attack on their own turn — it gets switched off (freeze) while they
	//wait and given back (beginTurn) the instant their turn comes, the same way position anchoring does
	//for a player. originalNoAi remembers how the mob was BEFORE turn mode touched anything, so it can be
	//restored exactly at the end of combat (start/end) instead of assuming it was always false — in case
	//some other mod was already controlling it with NoAI on its own. This in-RAM memory doesn't survive an
	//unloaded chunk (which is exactly what happens almost every time someone dies: respawn moves the
	//player away from the area at the very instant it needs restoring) — FROZEN_TAG is the fallback: a tag
	//in the entity's own NBT (which DOES survive unload/reload and even a server restart), read by
	//onCompatMobLoaded the moment the entity loads again to give its AI back on its own if it's no longer
	//in combat.
	private static final Map<Integer, Boolean> originalNoAi = new HashMap<>();
	private static final String FROZEN_TAG = "dndsheets_turn_frozen";

	//Safety net for a compatibility mob's turn: neither "actually attacked" (onMobTurnAttack) nor
	//"exhausted its movement" (onMobTick) is guaranteed to ever happen — the real case that exposed this
	//is the smallest slime, which by vanilla design NEVER deals contact damage (Slime.playerTouch requires
	//!isTiny()), so it never fires the attack event; if it also doesn't move far enough from its origin,
	//it never exhausts movement either, and its turn would sit waiting forever again. With this, once
	//MOB_TURN_TIMEOUT_TICKS have passed since its turn started it gets cut off regardless, whether it
	//acted or not.
	private static final Map<Integer, Long> mobTurnStartTick = new HashMap<>();
	private static final long MOB_TURN_TIMEOUT_TICKS = 30; //1.5s at 20 ticks/sec.

	private static void setCompatMobActive(Entity entity, boolean active) {
		if (!(entity instanceof Mob mob)) return;
		originalNoAi.putIfAbsent(mob.getId(), mob.isNoAi());
		mob.setNoAi(!active);
		if (active) mob.getPersistentData().remove(FROZEN_TAG);
		else mob.getPersistentData().putBoolean(FROZEN_TAG, true);
	}

	private static void restoreAllCompatMobAi(ServerLevel level) {
		for (Integer id : new ArrayList<>(originalNoAi.keySet())) {
			Entity entity = level.getEntity(id);
			if (entity instanceof Mob mob) {
				mob.setNoAi(originalNoAi.get(id));
				mob.getPersistentData().remove(FROZEN_TAG);
			}
		}
		originalNoAi.clear();
	}

	//Fallback for restoreAllCompatMobAi for when the entity wasn't loaded at the end of combat (unloaded
	//chunk): the instant it loads again (spawn, chunk load, even after a server restart), if it's carrying
	//the tag and is no longer part of an ongoing combat, its AI is given back on its own instead of
	//staying frozen forever waiting for someone to edit it by hand.
	@SubscribeEvent
	public static void onCompatMobLoaded(net.minecraftforge.event.entity.EntityJoinLevelEvent event) {
		if (event.getLevel().isClientSide()) return;
		if (!(event.getEntity() instanceof Mob mob) || !mob.getPersistentData().getBoolean(FROZEN_TAG)) return;
		if (active && isInOrder(mob.getId())) return; //Still genuinely in combat: freeze()/beginTurn already handle it.
		mob.setNoAi(false);
		mob.getPersistentData().remove(FROZEN_TAG);
	}

	//Ids of combatants confirmed truly dead/removed (see markDefeated). allEnemiesDefeated can NOT rely on
	//level.getEntity(id)==null to infer "dead": a monster simply sitting in an unloaded chunk (nobody
	//nearby at that instant) also returns null, and without this distinction combat used to end on its
	//own with the monster perfectly alive as soon as everyone moved far enough away.
	private static final Set<Integer> confirmedDefeated = new HashSet<>();

	//Public: called by CombatManager/SpellCastManager/MonsterActionManager exactly where they actually
	//remove a monster (remove(RemovalReason...)) — that remove never fires LivingDeathEvent (this mod's
	//monsters don't die via LivingEntity#die()'s vanilla path), so there's no other generic point to learn
	//of a real death.
	public static void markDefeated(int entityId) {
		confirmedDefeated.add(entityId);
	}

	//Complement to markDefeated above: covers the death that DOES go through the real vanilla path
	//(LivingEntity#die(), which markDefeated explicitly doesn't cover) — a zombie-type monster (e.g. a
	//goblin) that burns in the sun, drowns, or falls into the void dies this way, not through one of our
	//remove(RemovalReason...) calls. Without this, allEnemiesDefeated never learned of that death (it's
	//not in confirmedDefeated, and level.getEntity(id) may still return the now-dead entity for an
	//instant, or null if it's already unloaded, which is deliberately treated as "still alive" to avoid
	//closing combat prematurely) — if that death happened to coincide with its turn coming up, combat
	//would keep spinning, unable to end on its own.
	@SubscribeEvent
	public static void onMonsterDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!isMonster(event.getEntity())) return;
		markDefeated(event.getEntity().getId());
		MonsterRegistry.forgetCustomAttacks(event.getEntity());
		if (event.getEntity().level() instanceof ServerLevel level) checkAllEnemiesDefeated(level);
	}

	//Turn consumption for compatibility mobs (an Enemy with no stat block of its own): there's no
	//resolveAttack to call like there is for this mod's own monsters (see MonsterActionManager.autoAct),
	//so they're left to actually attack with their real vanilla AI (re-enabled in beginTurn), and THAT hit
	//is detected as their turn's action. If they'd already spent their action (two quick hits before a
	//one-tick auto-advance manages to process), the second one is canceled — the same "one action per
	//turn" limit that already applies to players in CombatManager. instanceof Mob (not just "not a
	//player") is deliberate: a PLAYER's hit can also reach LivingAttackEvent (e.g. against a compatibility
	//mob, which CombatManager doesn't cancel if it connects) — CombatManager already called tryAct for it,
	//so processing it AGAIN here would find it "already acted" and cancel the player's own hit, which
	//actually should have gone through.
	@SubscribeEvent
	public static void onMobTurnAttack(net.minecraftforge.event.entity.living.LivingAttackEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		Entity attacker = event.getSource().getEntity();
		if (!(attacker instanceof Mob mob) || MonsterRegistry.statBlockOf(attacker) != null) return;

		//Frozen by us (not their turn): NoAI switches off its goal selector (chase, aim...) but NOT contact
		//damage, which runs separately from that — Slime.playerTouch, for instance, fires on hitbox
		//collision every tick regardless of NoAI. Without this, a slime pressed against the player kept
		//dealing damage even with its turn frozen. originalNoAi.containsKey confirms that WE were the ones
		//who froze it (and not NoAI set for some other reason unrelated to turn mode).
		if (mob.isNoAi() && originalNoAi.containsKey(mob.getId())) {
			event.setCanceled(true);
			return;
		}

		if (!isCurrentActor(attacker)) return;
		if (!tryAct(attacker)) event.setCanceled(true);
	}

	//A mob that hits a player from OUTSIDE the encounter's area (a skeleton or a ghast attacking at range,
	//e.g.) was never captured by startAt or the periodic re-scan (both bounded to the radius combat
	//started from) — it kept hitting for free, with no turn or freezing. Any hit from a mob to a player
	//adds it to combat (starting one if needed, centered on the VICTIM, not the attacker, who could be far
	//away) or inserts it by hand if one was already underway.
	@SubscribeEvent
	public static void onMobHitsPlayer(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
		if (event.getEntity().level().isClientSide()) return;
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		Entity attacker = event.getSource().getEntity();
		if (attacker == null || !isMonster(attacker) || !(attacker.level() instanceof ServerLevel level)) return;

		//The ambushing monster also opens the order: it's the same case as a player attacking first, seen
		//from the other side. Its hit has already landed by the time we get here, so leaving it in the
		//middle of the order would mean it hits, and then hits again once its turn comes up.
		if (!active && net.hawthorn.dndsheets.Config.auto(net.hawthorn.dndsheets.Config.Rule.TURNS)) startAt(level, player.position(), DEFAULT_RADIUS, attacker);
		if (active && !isInOrder(attacker.getId())) addLateMonster(level, attacker, nameOf(attacker));
	}

	//Movement budget for those same mobs: if they exhaust their speed (see
	//MovementAnchorTracker.speedBlocksForMob) without ever hitting anyone — chasing someone out of reach,
	//e.g. — their turn ends just as if they had acted. Without this, nothing else would advance their
	//turn, and everyone (the player included) would be left waiting forever again.
	@SubscribeEvent
	public static void onMobTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
		//LivingTickEvent fires for EVERY living entity in the world, every tick, client and server — this
		//check goes first deliberately: outside combat (the vast majority of play time) it bails before
		//even touching the entity or its Level, instead of paying for isClientSide/instanceof/isCurrentActor
		//on every server mob 20 times a second for nothing.
		if (!active) return;
		if (event.getEntity().level().isClientSide()) return;
		Entity entity = event.getEntity();
		if (entity instanceof Player || !isCurrentActor(entity) || MonsterRegistry.statBlockOf(entity) != null) return;

		Long startTick = mobTurnStartTick.get(entity.getId());
		boolean timedOut = startTick != null && entity.level().getGameTime() - startTick >= MOB_TURN_TIMEOUT_TICKS;
		boolean outOfMovement = movementAnchors.enforceMobMovementBudget(entity, MovementAnchorTracker.speedBlocksForMob(entity));
		if (timedOut || outOfMovement) tryAct(entity);
	}

	//Public: same criterion startAt uses to decide who counts as a "monster" for initiative — reused by
	//CombatManager to know whether hitting a mob with no stat block of its own (a boss or another mod's
	//enemy) should still engage turn mode. Enemy is the vanilla interface any hostile mob (this mod's own
	//or another mod's) already implements so the rest of Minecraft/Forge treats it as hostile — reusing it
	//avoids maintaining a separate compatibility list per mod.
	/**
	 * <p>Whether it counts as an <b>enemy</b>. Used to decide when an encounter ends, so a friendly NPC
	 * with a sheet deliberately does NOT enter here: if it did, a tavern keeper in the room would keep
	 * combat from ever ending.</p>
	 */
	public static boolean isMonster(Entity entity) {
		return MonsterRegistry.monsterIdOf(entity) != null || entity instanceof Enemy;
	}

	/**
	 * <p>Whether it's a valid target for combat rules, enemy or not. An NPC with a sheet
	 * ({@link Combatant#characterIdOf}) is attackable and healable under full 5e rules without being an
	 * enemy — separating the two questions is what allows having allies without breaking combat's end
	 * condition.</p>
	 *
	 * <p>A real PLAYER also counts, even without carrying the NPC NBT tag (that one is only for tying a
	 * world entity to a sheet, not how a player is identified — that's already {@code Combatant.of} by
	 * UUID). Without this, hitting another player never went through {@code onAttackEntity}'s turn block:
	 * {@code CombatManager} fell straight through to Minecraft's loose hit, spending no turn and no
	 * action, so PvP could be hit without limit while turn mode was throttling everything else.</p>
	 */
	public static boolean isCombatTarget(Entity entity) {
		return isMonster(entity) || entity instanceof Player || Combatant.characterIdOf(entity) != null;
	}

	//Public: called right after markDefeated when a monster is removed BY HAND mid-combat (DM Wand,
	//right-click + sneak). Without this, if it was the last living enemy, combat stayed "active" until
	//someone's turn came up again (the only point that already checked allEnemiesDefeated) instead of
	//ending instantly — the HUD/turn tracker was left running over an already-empty encounter.
	public static void checkAllEnemiesDefeated(ServerLevel level) {
		if (active && allEnemiesDefeated(level)) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_enemies_defeated").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			end(level);
		}
	}

	private static final Map<Integer, List<StatusEffect>> effects = new HashMap<>();

	//Turn mode's position anchoring and movement budget — see MovementAnchorTracker (audit finding F3).
	private static final MovementAnchorTracker movementAnchors = new MovementAnchorTracker();

	//Traits with a duration measured in rounds (barbarian Rage, etc.) instead of real ticks — see
	//BarbarianRageManager for the use case. One round = one full pass of the turn order.
	private record PendingRoundCallback(int roundsRemaining, Runnable action) {}
	private static final List<PendingRoundCallback> pendingRoundCallbacks = new ArrayList<>();

	//Who has already spent their action on THEIR current turn. Without this, whoever has the turn could
	//attack as many times as they wanted just by clicking repeatedly — turn mode was purely decorative.
	private static final Set<Integer> actedThisTurn = new HashSet<>();

	//Reactions (Opportunity Attack, Shield, Counterspell): unlike actedThisTurn, these can be spent on
	//ANYONE's turn, not just your own — which is why it's a separate set instead of reusing
	//actedThisTurn. Recovered at the start of one's own turn (beginTurn), same as the real 5e rule.
	//ponytail: only recovered for whoever is in the turn order; a player with a reaction item who never
	//got into initiative would be left with their reaction spent forever — a rare case, since
	///dndturns start pulls in every connected player within the radius.
	private static final Set<Integer> reactionUsed = new HashSet<>();

	//Bonus action: a resource DISTINCT from actedThisTurn — the SRD treats it separately from the action
	//(Wild Shape, Second Wind, Healing Word...), and before this the engine collapsed them into one:
	//spending the bonus action consumed the entire action (see DruidWildShapeManager, which used to use
	//tryAct instead of this). Same lifecycle as actedThisTurn (cleared in start/end/beginTurn/
	//reconcilePlayerEntity) but without triggering scheduleAutoAdvance: spending it doesn't end the turn.
	private static final Set<Integer> bonusActionUsed = new HashSet<>();

	//Turn mode's opportunity attacks — see OpportunityAttackTracker (audit finding F3).
	private static final OpportunityAttackTracker opportunityAttacks = new OpportunityAttackTracker();

	//Public: used by Shield/Counterspell (out of turn) and by the opportunity attack below (inside the
	//tick of whoever is moving). Same "once and done" as tryAct, but without requiring it to be the
	//reactor's turn.
	public static boolean tryReact(Entity actor) {
		//"An incapacitated creature can't take actions OR REACTIONS." This used to be only in tryAct, so a
		//paralyzed monster still made opportunity attacks and a stunned player could still use Shield and
		//Counterspell — half a dozen conditions that stopped meaning half of what they mean. Goes before
		//the turn-mode check, same as in tryAct: you're incapacitated outside initiative too.
		if (isIncapacitated(actor)) return false;
		if (!active) return true;
		return reactionUsed.add(actor.getId());
	}

	/**
	 * <p>The THREE ways of doing something — action, reaction, and legendary action — go through here to
	 * ask the same question. It used to be written only in {@link #tryAct}, and the other two behaved as
	 * if the rule didn't exist.</p>
	 */
	static boolean isIncapacitated(Entity actor) {
		Combatant combatant = Combatant.of(actor);
		return combatant != null && combatant.cannotAct();
	}

	public static boolean isActive() {
		return active;
	}

	@Nullable
	private static TurnEntry current() {
		return currentIndex >= 0 && currentIndex < order.size() ? order.get(currentIndex) : null;
	}

	/**
	 * <p>If turn mode isn't active, always allows acting (normal behavior, unchanged). If turn mode is
	 * active, {@code actor} (player or monster) can only act ONCE during their own turn: the first call
	 * marks the action as used and returns true; any extra attempt — out of turn, or repeated within
	 * their own turn — returns false without leaving any trace for later (nothing gets queued). The
	 * caller must check the result BEFORE spending any resource (spell slots, etc.), so as not to charge
	 * for an action that's going to be discarded.</p>
	 */
	/**
	 * <p>Whether it can act based on its <b>conditions</b>, without asking whose turn it is. This is the
	 * half of {@link #tryAct} that a boss with its own clock still has to respect: it ignores the queue,
	 * not the rules — paralyzed, stunned, petrified, or unconscious stops it just like anyone else.</p>
	 */
	public static boolean canActIgnoringTurn(Entity actor) {
		return !isIncapacitated(actor);
	}

	public static boolean tryAct(Entity actor) {
		//Before anything turn-mode related, and also outside combat: an incapacitating condition
		//(paralyzed, stunned, petrified, unconscious) prevents acting even with no initiative active.
		//Here, and not in every caller, because EVERY attack route — melee, projectile, PvP, spell —
		//already passes through this exact point.
		if (isIncapacitated(actor)) return false;
		if (!active) return true;
		TurnEntry currentEntry = current();
		if (currentEntry == null || currentEntry.entityId() != actor.getId()) return false;
		boolean acted = actedThisTurn.add(actor.getId());
		//As soon as the action is spent, the turn ends on its own: nobody has to type /dndturns next.
		//Deferred by one tick so the chat/HUD for the attack that just happened shows before "X's turn".
		if (acted && actor.level() instanceof ServerLevel level) {
			broadcastTurnState(level);
			scheduleAutoAdvance(level, actor.getId());
		}
		return acted;
	}

	/**
	 * <p>Same as {@link #tryAct} but for the bonus ACTION: same turn/incapacitation gating, same "once and
	 * done", but a separate resource (see {@link #bonusActionUsed}) that does NOT advance the turn when
	 * spent — unlike the action, the bonus action isn't the only thing that can be done in a turn.</p>
	 */
	public static boolean tryActBonus(Entity actor) {
		if (isIncapacitated(actor)) return false;
		if (!active) return true;
		TurnEntry currentEntry = current();
		if (currentEntry == null || currentEntry.entityId() != actor.getId()) return false;
		boolean acted = bonusActionUsed.add(actor.getId());
		if (acted && actor.level() instanceof ServerLevel level) broadcastTurnState(level);
		return acted;
	}

	//Uniform message for when tryAct() returns false, distinguishing "not your turn" from "already acted".
	public static void notifyCantAct(Entity actor) {
		if (!(actor instanceof Player player)) return;
		//An incapacitating condition overrides any other explanation: telling someone who's paralyzed
		//"it's not your turn" is exactly the kind of message that makes a change that DOES work look broken.
		Combatant combatant = Combatant.of(actor);
		if (combatant != null && combatant.cannotAct()) {
			String blocking = combatant.conditions().stream()
				.filter(Condition::preventsActions).findFirst().map(Condition::label).orElse("");
			player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.cant_act", blocking).withStyle(ChatFormatting.RED));
			return;
		}
		TurnEntry currentEntry = current();
		boolean isCurrentActor = currentEntry != null && currentEntry.entityId() == actor.getId();
		Component reason = isCurrentActor
			? Component.translatable("chat.dndsheets.turn.already_acted")
			: Component.translatable("chat.dndsheets.turn.not_your_turn",
				currentEntry != null ? currentEntry.name() : Component.translatable("chat.dndsheets.turn.other_combatant"));
		player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
	}

	/** Same message as {@link #notifyCantAct}, except for the "already spent" case — that one is for the bonus ACTION, not the action. */
	public static void notifyCantActBonus(Entity actor) {
		if (!(actor instanceof Player player)) return;
		Combatant combatant = Combatant.of(actor);
		if (combatant != null && combatant.cannotAct()) {
			String blocking = combatant.conditions().stream()
				.filter(Condition::preventsActions).findFirst().map(Condition::label).orElse("");
			player.sendSystemMessage(Component.translatable("chat.dndsheets.condition.cant_act", blocking).withStyle(ChatFormatting.RED));
			return;
		}
		TurnEntry currentEntry = current();
		boolean isCurrentActor = currentEntry != null && currentEntry.entityId() == actor.getId();
		Component reason = isCurrentActor
			? Component.translatable("chat.dndsheets.turn.already_used_bonus_action")
			: Component.translatable("chat.dndsheets.turn.not_your_turn",
				currentEntry != null ? currentEntry.name() : Component.translatable("chat.dndsheets.turn.other_combatant"));
		player.sendSystemMessage(reason.copy().withStyle(ChatFormatting.RED));
	}

	//One tick after spending the action, if it's still the same combatant (nobody advanced by hand in
	//between), the turn passes on its own. The margin tick lets the action's result be seen before the
	//next-round announcement, and avoids re-entering advance() in the middle of resolving the
	//attack/spell that's still running when tryAct returns true.
	private static void scheduleAutoAdvance(ServerLevel level, int entityId) {
		int scheduledToken = turnToken;
		DndsheetsMod.queueServerWork(1, () -> {
			TurnEntry stillCurrent = current();
			if (!active || stillCurrent == null || stillCurrent.entityId() != entityId || turnToken != scheduledToken) return;
			advance(level);
		});
	}

	/**
	 * <p>Holds back the auto-advance already queued by the action that was just spent. Used by
	 * {@link CastingManager} when starting a spell with a casting time: {@link #tryAct} queued the advance
	 * for the next tick, and without this the spell would resolve with someone else's turn already
	 * started.</p>
	 *
	 * <p>It's the same mechanism {@link #undoAction} uses — bumping the token invalidates what's queued —
	 * not a new system. It bumps the token for EVERYTHING pending, but at the instant it's called the only
	 * thing pending is the caster's own advance, which just spent its action one line earlier.</p>
	 */
	public static void holdAutoAdvance() {
		turnToken++;
	}

	/**
	 * <p>Gives the auto-advance back to the turn once the held casting finishes (resolved or interrupted,
	 * doesn't matter: the action was spent either way). If the turn has already changed hands in the
	 * meantime, or combat has ended, there's nothing to reschedule.</p>
	 */
	public static void resumeAutoAdvance(ServerLevel level, Entity actor) {
		if (!active || !isCurrentActor(actor) || !actedThisTurn.contains(actor.getId())) return;
		scheduleAutoAdvance(level, actor.getId());
	}

	//Used by convenience items (TurnItemManager): only whoever has the turn can use them.
	public static boolean isCurrentActor(Entity actor) {
		TurnEntry currentEntry = current();
		return active && currentEntry != null && currentEntry.entityId() == actor.getId();
	}

	//"Undo turn": gives whoever currently has the turn their action back, without losing their spot in
	//the order or passing the turn to anyone else — to correct an attack made by mistake, e.g.
	public static void undoAction(ServerLevel level, Entity actor) {
		if (!isCurrentActor(actor)) return;
		actedThisTurn.remove(actor.getId());
		turnToken++; //Invalidates any auto-advance already queued by the action that's just been undone.
		broadcast(level, Component.translatable("chat.dndsheets.turn.undo", current().name()).withStyle(ChatFormatting.YELLOW));
		broadcastTurnState(level);
	}

	public static final double DEFAULT_RADIUS = 30.0;

	//Rolls initiative (1d20 + Dexterity mod) for every player and summoned monster within a radius, sorts
	//highest to lowest, and starts turn mode. Public: used both by TurnCommand (/dndturns start) and the
	//DM Panel (network.TurnControlMessage) and CombatManager.autoStartCombatIfNeeded (a player's first
	//hit on a monster starts combat on its own if none was active) — used to live in TurnCommand,
	//inverting the dependency (domain logic calling the command layer).
	public static int startAt(ServerLevel level, Vec3 pos, double radius) {
		return startAt(level, pos, radius, null);
	}

	/**
	 * <p>The same, but knowing <b>who triggered combat</b>: whoever attacked opens the turn order,
	 * overriding whatever the initiative dice say.</p>
	 *
	 * <p>Without this, the hit that starts the encounter used to get lost. Combat would be created,
	 * initiative rolled, and if the attacker didn't win their own roll their attack was rejected as "not
	 * your turn": the player would hit and nothing would happen. Worse, the very same click worked or
	 * vanished depending on a d20 nobody had asked to roll. Before auto-start existed, that hit resolved
	 * fully, so the convenience of not typing {@code /dndturns start} was costing an action.</p>
	 *
	 * <p>Having it open the order instead of resolving "for free" outside of it is what keeps the rest of
	 * the rules standing: its action gets spent, its turn ends, and nobody hits twice. Whoever attacks
	 * first genuinely <i>has</i> acted first, which is exactly what initiative is meant to measure.</p>
	 *
	 * @param initiator whoever starts combat by attacking, or {@code null} if the DM starts it by hand
	 *                  ({@code /dndturns start}), where only the dice decide.
	 */
	public static int startAt(ServerLevel level, Vec3 pos, double radius, Entity initiator) {
		//ponytail: only one combat is allowed at a time on the whole server (see the comment further down
		//about the global debounce/lock) — before, if two groups were playing in different areas of the
		//map, the second /dndturns start (or the first hit that triggers autoStartCombatIfNeeded) would
		//SILENTLY clobber the first group's combat: turnToken would bump, the old order would be wiped,
		//and nobody in the first group would learn their encounter had vanished mid-fight. This doesn't
		//enable truly simultaneous combats (the full refactor would still be needed for that) — it only
		//turns the silent corruption into a clear warning when the two areas don't even overlap.
		if (active && combatOrigin != null && combatOrigin.distanceTo(pos) > radius + combatRadius) {
			for (ServerPlayer player : level.players()) {
				if (player.position().distanceToSqr(pos) <= radius * radius) {
					player.sendSystemMessage(Component.translatable("chat.dndsheets.turn.blocked_elsewhere").withStyle(ChatFormatting.RED));
				}
			}
			return 0;
		}

		AABB box = new AABB(pos.x - radius, pos.y - radius, pos.z - radius, pos.x + radius, pos.y + radius, pos.z + radius);

		record Rolled(int entityId, String name, int score, boolean isMonster, String playerUuid) {}
		List<Rolled> rolled = new ArrayList<>();
		//A spectator doesn't participate: they're watching, not playing, and in 5e they can neither take
		//damage nor act. Without this filter, a DM/observer in spectator mode near combat would join
		//initiative just like any PC and end up occupying a turn nobody was ever going to play.
		for (Entity entity : level.getEntities((Entity) null, box, e -> (e instanceof Player p && !p.isSpectator()) || isMonster(e))) {
			String playerUuid = entity instanceof Player player ? player.getStringUUID() : null;
			rolled.add(new Rolled(entity.getId(), nameOf(entity), rollInitiative(entity), isMonster(entity), playerUuid));
		}
		rolled.sort((a, b) -> b.score() - a.score());

		//Whoever triggered combat goes first no matter what their die shows. It's moved after sorting, not
		//by faking their roll: the score that gets announced is still the one they genuinely got.
		if (initiator != null) moveToFront(rolled, Rolled::entityId, initiator.getId());

		List<TurnEntry> combatants = new ArrayList<>();
		for (Rolled r : rolled) //ponytail: the name and the initiative score are glued into a single String, so the key gets
			//resolved HERE, in the server's language, not in each client's HUD. Separating them would
			//require changing TurnEntry's shape and the message that carries it; the tracker's other entry
			//points (addLateMonster) do send the clean name, and the client resolves that one.
			combatants.add(new TurnEntry(r.entityId(), ContentNames.plain(r.name()) + " (" + r.score() + ")", r.isMonster(), r.playerUuid()));

		if (combatants.isEmpty()) return 0;

		boolean wasActive = active;
		start(level, combatants);
		if (active) {
			//Always updated (even on a hot restart with a new center/radius), but the scan is only
			//re-queued if one wasn't already running — the one already underway rereads these two fields
			//fresh on every pass, so a hot restart gets the new area on its own.
			combatOrigin = pos;
			combatRadius = radius;
			if (!wasActive) scheduleLateMonsterScan(level);
			//Explained in chat because otherwise, someone with a 7 initiative showing up first reads as an
			//ordering bug rather than the rule it actually is.
			if (initiator != null) {
				broadcast(level, Component.translatable("chat.dndsheets.turn.initiator_first", nameOf(initiator)).withStyle(ChatFormatting.GOLD));
			}
		}
		return combatants.size();
	}

	//Re-scans the encounter's area every LATE_MONSTER_SCAN_INTERVAL_TICKS and re-queues itself while
	//combat stays active — cut off at the root as soon as it ends (see end()).
	private static void scheduleLateMonsterScan(ServerLevel level) {
		DndsheetsMod.queueServerWork(LATE_MONSTER_SCAN_INTERVAL_TICKS, () -> {
			if (!active || combatOrigin == null) return;
			AABB box = new AABB(combatOrigin.x - combatRadius, combatOrigin.y - combatRadius, combatOrigin.z - combatRadius,
				combatOrigin.x + combatRadius, combatOrigin.y + combatRadius, combatOrigin.z + combatRadius);
			for (Entity entity : level.getEntities((Entity) null, box, e -> isMonster(e) && !isInOrder(e.getId()))) {
				addLateMonster(level, entity, nameOf(entity));
			}
			scheduleLateMonsterScan(level);
		});
	}

	private static boolean isInOrder(int entityId) {
		for (TurnEntry entry : order) if (entry.entityId() == entityId) return true;
		return false;
	}

	/**
	 * <p>Moves the initiator to the front <b>while preserving the relative order of the rest</b>.
	 * Package-private and generic so it can be tested without a server behind it.</p>
	 *
	 * <p>Preserving the rest's order is the important half: swapping the initiator with whoever was first
	 * — the implementation that comes naturally — sends that first one to the spot the initiator occupied
	 * and scrambles everyone else's initiative, which IS sacred.</p>
	 */
	static <T> void moveToFront(List<T> entries, java.util.function.ToIntFunction<T> idOf, int id) {
		for (int i = 0; i < entries.size(); i++) {
			if (idOf.applyAsInt(entries.get(i)) == id) {
				entries.add(0, entries.remove(i));
				return;
			}
		}
	}

	private static int rollInitiative(Entity entity) {
		if (entity instanceof Player player) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			DiceManager.RollOutcome outcome = DiceManager.roll(sheet != null ? sheet : new JsonObject(), "1d20 + $dex");
			return outcome.result() != null ? outcome.result().getValue() : 10;
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		int mod = block != null ? block.abilityModifier("dex") : 0;
		DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), "1d20 + " + mod);
		return outcome.result() != null ? outcome.result().getValue() : 10;
	}

	public static String nameOf(Entity entity) {
		if (entity instanceof Player player) {
			return SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		}
		MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(entity);
		return block != null ? MonsterRegistry.displayNameOf(entity, block) : entity.getName().getString();
	}

	public static void start(ServerLevel level, List<TurnEntry> rolledOrder) {
		if (debounce(level)) return;

		//Hot restart (an encounter already active, e.g. /dndturns start fired twice or a hit that starts a
		//second combat by accident): without this, the combatant who had the turn in the OLD encounter
		//would be left with the Glowing effect stuck forever (nothing else cleared it), and since
		//turnToken wasn't bumped, an auto-advance already queued from the old encounter could sneak in and
		//skip the new one's first turn.
		if (active) {
			clearGlow(level, current());
			turnToken++;
			restoreAllCompatMobAi(level);
		}

		order.clear();
		effects.clear();
		ZoneManager.clear(); //Without a turn order there are no rounds to count, so there's no wall to maintain.
		SurfaceManager.clear(); //Same reason: no rounds to count, so no fire patch to maintain either.
		actedThisTurn.clear();
		reactionUsed.clear();
		bonusActionUsed.clear();
		TurnActionManager.clearAll(); //Outside combat, dodging/dashing/disengaging mean nothing.
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
		mobTurnStartTick.clear();
		order.addAll(rolledOrder);
		currentIndex = 0;
		round = 1;
		active = !order.isEmpty();
		if (!active) return;

		//Everyone except whoever starts gets anchored wherever they're standing right now.
		for (int i = 1; i < order.size(); i++) {
			freeze(level, order.get(i));
		}

		//And whoever doesn't wait in line starts its own clock, with the announcement shown to the table.
		//Goes here, with initiative already assembled, because the announcement only makes sense once
		//combat has started.
		for (TurnEntry entry : order) {
			Entity offClock = level.getEntity(entry.entityId());
			if (offClock != null) OwnClockManager.start(level, offClock);
		}

		//If whoever is supposed to open turns out to run on its own clock, it's skipped: it's never
		//their turn.
		for (int skipped = 0; skipped < order.size() && isOffClock(level, current()); skipped++) step(level);

		StringBuilder orderText = new StringBuilder();
		for (int i = 0; i < order.size(); i++) {
			if (i > 0) orderText.append(", ");
			orderText.append(i + 1).append(". ").append(ContentNames.plain(order.get(i).name()));
		}
		broadcast(level, Component.translatable("chat.dndsheets.turn.order_announce", orderText.toString()).withStyle(ChatFormatting.GOLD));
		//Help for someone who's never played D&D: the first time an encounter starts, explain the rule in
		//one line. The rest of the turns don't repeat this anymore — the HUD (see network.TurnStateMessage)
		//takes care of it.
		broadcast(level, Component.translatable("chat.dndsheets.turn.tutorial").withStyle(ChatFormatting.GRAY));
		beginTurn(level);
	}

	public static void next(ServerLevel level) {
		if (!active || debounce(level)) return;
		advance(level);
	}

	//Skips to the next combatant. Since actions no longer queue up, this simply advances the turn (to
	//skip someone AFK, e.g.), with nothing more to "cancel".
	public static void cancel(ServerLevel level) {
		if (!active || debounce(level)) return;
		advance(level);
	}

	public static void end(ServerLevel level) {
		if (!active) return;
		clearGlow(level, current());
		restoreAllCompatMobAi(level);
		broadcast(level, Component.translatable("chat.dndsheets.turn.ended").withStyle(ChatFormatting.GRAY));
		order.clear();
		effects.clear();
		ZoneManager.clear(); //Without a turn order there are no rounds to count, so there's no wall to maintain.
		SurfaceManager.clear(); //Same reason: no rounds to count, so no fire patch to maintain either.
		actedThisTurn.clear();
		reactionUsed.clear();
		bonusActionUsed.clear();
		TurnActionManager.clearAll(); //Outside combat, dodging/dashing/disengaging mean nothing.
		opportunityAttacks.clear();
		movementAnchors.clear();
		confirmedDefeated.clear();
		mobTurnStartTick.clear();
		combatOrigin = null; //Cuts off the late-monster re-scan (see scheduleLateMonsterScan) on its next pass.
		active = false;
		currentIndex = -1;
		round = 0;
		broadcastTurnState(level); //With active=false already set, this tells every HUD to hide itself.

		//Any trait with a round-based duration still pending is treated as finished now: without turn
		//mode there's no way to keep counting rounds, and leaving it hanging forever would be worse.
		List<Runnable> pending = new ArrayList<>();
		for (PendingRoundCallback callback : pendingRoundCallbacks) pending.add(callback.action());
		pendingRoundCallbacks.clear();
		pending.forEach(Runnable::run);
	}

	//A monster summoned mid-encounter (card, /dndmonsters spawn, generic NPC) never used to enter order:
	//it could never act (tryAct compared against an id that wasn't in the list) and, if it was also the
	//last one alive, allEnemiesDefeated would still declare combat over, with it still alive and hostile.
	//It's inserted right after whoever has the turn now (acts soon, without recalculating initiative for
	//the whole order) — called from MonsterRegistry.spawnAt (the four native summoning paths),
	//scheduleLateMonsterScan (a compatibility mob entering the area), and onMobHitsPlayer (one that hits
	//from outside the area).
	public static void addLateMonster(ServerLevel level, Entity monster, String displayName) {
		if (!active) return;
		//isMonster here means "is an ENEMY", and that's what decides when the encounter ends (see
		//allEnemiesDefeated). A player's summon joins initiative — it has to act — but is NOT an enemy:
		//marking it as one would leave combat never ending for as long as Spiritual Weapon lasted. Same
		//criterion that separates isMonster from isCombatTarget for friendly NPCs.
		boolean isEnemy = SummonManager.ownerOf(monster) == null;
		TurnEntry newEntry = new TurnEntry(monster.getId(), displayName, isEnemy, null);
		order.add(currentIndex + 1, newEntry);
		freeze(level, newEntry); //Freezes a freshly added compatibility mob right away (not its turn yet); a no-op for one of ours (already NoAI since it was summoned).
		broadcast(level, Component.translatable("chat.dndsheets.turn.joins_combat", displayName).withStyle(ChatFormatting.GOLD));
		broadcastTurnState(level);
	}

	//A player who arrives AFTER combat has already started (autoStartCombatIfNeeded only captured whoever
	//was within 30 blocks of the monster AT THAT INSTANT) never used to enter order: tryAct would return
	//false for them forever in this encounter, and their hit would get canceled without even vanilla
	//damage applying. Added right after whoever has the turn now (same pattern as addLateMonster) and
	//left anchored like any other waiting combatant, since it isn't their turn yet. Does nothing if they
	//already had a spot.
	public static void addLatePlayerIfMissing(ServerLevel level, ServerPlayer player) {
		if (!active) return;
		for (TurnEntry entry : order) {
			if (entry.entityId() == player.getId()) return;
		}
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		TurnEntry newEntry = new TurnEntry(player.getId(), name, false, player.getStringUUID());
		order.add(currentIndex + 1, newEntry);
		freeze(level, newEntry);
		broadcast(level, Component.translatable("chat.dndsheets.turn.joins_combat", name).withStyle(ChatFormatting.GOLD));
		broadcastTurnState(level);
	}

	//Reconnecting (crash, relog) gives the player a new entityId — Minecraft never reuses the old one.
	//Without this, their spot in order was left orphaned forever: a perpetual auto-skip (see beginTurn)
	//and tryAct(newEntity) would never match the saved id, blocking them from acting for the rest of the
	//encounter. Called from SheetLoader.clientJoinedServer on every join AND every respawn (same
	//EntityJoinLevelEvent), does nothing if there's no active combat or if the player had no spot in order.
	public static void reconcilePlayerEntity(ServerPlayer player) {
		if (!active) return;
		String uuid = player.getStringUUID();
		for (int i = 0; i < order.size(); i++) {
			TurnEntry old = order.get(i);
			if (!uuid.equals(old.playerUuid()) || old.entityId() == player.getId()) continue;

			int oldId = old.entityId();
			int newId = player.getId();
			order.set(i, new TurnEntry(newId, old.name(), old.isMonster(), old.playerUuid()));
			movementAnchors.rekey(oldId, newId);
			if (effects.containsKey(oldId)) effects.put(newId, effects.remove(oldId));
			if (actedThisTurn.remove(oldId)) actedThisTurn.add(newId);
			if (reactionUsed.remove(oldId)) reactionUsed.add(newId);
			if (bonusActionUsed.remove(oldId)) bonusActionUsed.add(newId);
			opportunityAttacks.rekey(oldId, newId);
			//A player who genuinely dies (onPlayerRealDeath) stays in confirmedDefeated forever — correct
			//while they remain dead, but nothing ever removed them from it if they were revived and came
			//back: the OLD id was left orphaned in the set (nothing above overwrites it) and the NEW one
			//was never marked, so in theory it no longer counted as defeated, but it also wasn't treated as
			//still in combat — it was stuck in limbo. Reconciling only happens with a ServerPlayer freshly
			//joined or respawned, i.e. alive right now: the defeated mark is removed from both ids, old and
			//new, so they count in the encounter again just as if they'd never died.
			confirmedDefeated.remove(oldId);
			confirmedDefeated.remove(newId);
			return; //Only one spot per UUID in the order, no need to keep searching.
		}
	}

	public static void applyEffect(Entity target, String name, String dice, int turns) {
		applyEffect(target, name, dice, turns, null);
	}

	/**
	 * @param source who caused it, or {@code null} if unknown (the DM applying it by hand). Only matters
	 *               for charmed and frightened, the two 5e conditions whose effect depends on who the
	 *               source is — see {@link Combatant#cannotAttack} and {@link Combatant#seesSourceOf}.
	 */
	public static void applyEffect(Entity target, String name, String dice, int turns, Entity source) {
		effects.computeIfAbsent(target.getId(), id -> new ArrayList<>()).add(new StatusEffect(name, dice, turns));
		//If the effect's name IS a 5e condition ("prone", "paralyzed"...), besides counting turns and
		//dealing its damage it also gets genuinely applied as a condition, with its full mechanical
		//consequences. This way, everything that already knew how to apply effects — /dndturns effect,
		//monster attacks and spells, player spells — starts producing real conditions without a new
		//command or a new JSON field. A free-form name ("fire", "bleeding") remains exactly what it was: a
		//damage timer.
		Condition condition = Condition.fromLabel(name);
		if (condition == null) return;
		Combatant combatant = Combatant.of(target);
		if (combatant != null) combatant.addCondition(condition, source == null ? Combatant.NO_SOURCE : source.getId());
	}

	//The only way to remove an effect BEFORE it expires on its own via tickEffects — until now it could
	//only be lost through natural expiration or the whole map being wiped in start()/end(). Used by
	//ConcentrationManager to revert a concentration spell's effect the instant concentration is lost
	//(failed Constitution save) — before, that only rolled the die and sent a message, without actually
	//undoing anything. No-op if the effect is no longer there (already expired, already removed, or
	//combat has already ended and effects is empty).
	public static void removeEffect(ServerLevel level, int entityId, String name) {
		//The condition is removed even if the effect was no longer in the map: both paths got applied
		//together in applyEffect, but the timer lives in memory and only during combat, while the
		//condition is persisted. Without this, ending combat would leave someone paralyzed forever.
		Condition condition = Condition.fromLabel(name);
		if (condition != null && level != null) {
			Entity target = level.getEntity(entityId);
			Combatant combatant = target == null ? null : Combatant.of(target);
			if (combatant != null) combatant.removeCondition(condition);
		}

		List<StatusEffect> current = effects.get(entityId);
		if (current == null) return;
		List<StatusEffect> remaining = current.stream().filter(e -> !e.name().equals(name)).toList();
		if (remaining.isEmpty()) effects.remove(entityId);
		else effects.put(entityId, remaining);
	}

	/**
	 * <p>Schedules a timed trait's end in whichever unit applies: <b>rounds</b> if turn mode is active,
	 * <b>real ticks</b> if not. A 5e "1 minute" is 10 rounds in combat, but 1,200 ticks outside of it, and
	 * counting it in the wrong unit shortens or lengthens the trait silently.</p>
	 *
	 * <p>This decision used to be hand-written, identically, in the four timed traits (Rage, Wild Shape,
	 * Hunter's Mark, Bardic Inspiration), each with its own pair of constants. What each one does upon
	 * expiring IS different — one notifies nobody, another checks a token — so that stays in each manager:
	 * only what was genuinely the same line four times over lives here.</p>
	 */
	public static void scheduleExpiry(int rounds, int ticks, Runnable onExpire) {
		if (isActive()) onRoundsPass(rounds, onExpire);
		else DndsheetsMod.queueServerWork(ticks, onExpire);
	}

	//Public: so a timed trait (barbarian Rage, etc.) counts in full rounds instead of real ticks while
	//turn mode is active — see BarbarianRageManager. If turn mode ends before the rounds elapse, it still
	//fires (see end()): it doesn't stay hanging forever.
	public static void onRoundsPass(int rounds, Runnable action) {
		pendingRoundCallbacks.add(new PendingRoundCallback(rounds, action));
	}

	private static void fireDueRoundCallbacks() {
		List<PendingRoundCallback> remaining = new ArrayList<>();
		List<Runnable> due = new ArrayList<>();
		for (PendingRoundCallback pending : pendingRoundCallbacks) {
			int left = pending.roundsRemaining() - 1;
			if (left <= 0) due.add(pending.action());
			else remaining.add(new PendingRoundCallback(left, pending.action()));
		}
		pendingRoundCallbacks.clear();
		pendingRoundCallbacks.addAll(remaining);
		due.forEach(Runnable::run);
	}

	//One spot in the order. Split out of advance() because skipping past a boss with its own clock still
	//has to decrement a round like any other advance: if the skip didn't go through here, a round ending
	//on the dragon would never finish being counted.
	private static void step(ServerLevel level) {
		currentIndex++;
		if (currentIndex >= order.size()) {
			currentIndex = 0;
			round++;
			fireDueRoundCallbacks();
			ZoneManager.endRound(level); //Full round: walls decrement their duration and get redrawn.
			SurfaceManager.endRound(level); //Same round: fire patches decrement their duration and get redrawn.
			tickWeaponBuffs(level);
			SummonManager.endRound(level);
		}
	}

	private static boolean isOffClock(ServerLevel level, TurnEntry entry) {
		if (entry == null) return false;
		Entity entity = level.getEntity(entry.entityId());
		return entity != null && MonsterRegistry.isOffClock(entity);
	}

	private static void advance(ServerLevel level) {
		turnToken++; //Any auto-advance queued before this real advance is invalidated.
		TurnEntry finishing = current();
		//Legendary actions: in 5e a boss acts RIGHT AS another's turn ENDS, and this is that exact moment.
		//Goes before moving the index so "whoever just played" is still who it is.
		LegendaryActionManager.onTurnEnded(level, finishing, order);
		if (finishing != null) freeze(level, finishing); //Anchored wherever their turn ends.
		step(level);
		//Bosses with their own clock stay IN the list — otherwise, combat would end believing no enemy is
		//left while the dragon is still alive (see allEnemiesDefeated) — but it's never their turn: they
		//act on their own every 6 seconds. See OwnClockManager. The loop cap is in case EVERYONE ever ends
		//up on their own clock: an empty round is better than an infinite loop on the server thread.
		for (int skipped = 0; skipped < order.size() && isOffClock(level, current()); skipped++) step(level);
		beginTurn(level);
	}

	//Timed weapon buffs (Divine Favor) decrement per full round, not per turn: a 5e "1 minute" is 10
	//rounds, and decrementing it per turn would shorten it as many times as there are combatants in
	//initiative.
	private static void tickWeaponBuffs(ServerLevel level) {
		for (ServerPlayer player : level.players()) {
			JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
			//Both tickRound calls are pure helpers over the JsonObject and decrement rounds on the sheet,
			//so persisting is this method's job (invariant 4). Only written when something genuinely
			//changed: saving every player's sheet on every round would be one write per combatant per round.
			boolean changed = WeaponBuffManager.tickRound(sheet);
			if (changed) {
				player.sendSystemMessage(Component.translatable("chat.dndsheets.spell.buff_faded").withStyle(ChatFormatting.GRAY));
			}
			for (String type : ConsumableManager.tickRound(sheet)) {
				changed = true;
				player.sendSystemMessage(Component.translatable("chat.dndsheets.item.resistance_faded", type).withStyle(ChatFormatting.GRAY));
			}
			if (changed) SheetLoader.saveServer(sheet, player.getStringUUID());
		}
	}

	private static void freeze(ServerLevel level, TurnEntry entry) {
		Entity entity = level.getEntity(entry.entityId());
		if (entity != null) {
			//A boss with its own clock is neither anchored nor frozen: moving on its own is exactly what
			//that is.
			if (MonsterRegistry.isOffClock(entity)) return;
			movementAnchors.pin(level, entry.entityId(), entity.position());
			//The criterion is "does this mob have its AI on?", not "is it from another mod?". They used to
			//be the same thing — this mod's own monsters were ALWAYS summoned with NoAI — and stopped being
			//so with "ai": true in the monster block (see MonsterRegistry.keepsOwnAi): an NPC that patrols
			//or follows the party has to stay put while it isn't its turn just like anyone else, or it
			//wanders around combat during other people's turns. Asking about the AI covers both cases with
			//a single rule, and incidentally includes an ally with AI, who used to slip through isMonster's
			//gate.
			if (entity instanceof Mob mob && !mob.isNoAi()) setCompatMobActive(entity, false);
		}
		clearGlow(level, entry);
	}

	//Visual aid (the "for new players" feature): whoever has the turn is marked with the vanilla Glowing
	//effect (visible through walls), without relying on reading chat to know whose turn it is. GLOWING
	//does nothing else (it isn't a real combat buff), so it's safe to apply/remove without touching any
	//other mechanic.
	private static void glow(Entity entity) {
		if (entity instanceof LivingEntity living) living.addEffect(new MobEffectInstance(MobEffects.GLOWING, 30 * 20 * 60, 0, false, false));
	}

	private static void clearGlow(ServerLevel level, TurnEntry entry) {
		if (entry == null) return;
		Entity entity = level.getEntity(entry.entityId());
		if (entity instanceof LivingEntity living) living.removeEffect(MobEffects.GLOWING);
	}

	private static void beginTurn(ServerLevel level) {
		if (allEnemiesDefeated(level)) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_enemies_defeated").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
			end(level);
			return;
		}

		TurnEntry entry = current();
		if (entry == null) return;
		actedThisTurn.remove(entry.entityId()); //New turn, new action available.
		bonusActionUsed.remove(entry.entityId()); //New turn, new bonus action available.
		//Dodge lasts "until the start of your next turn", which is exactly right here. Dash and Disengage
		//only lasted for their own turn, so they expire in the same place.
		TurnActionManager.clearFor(entry.entityId());
		reactionUsed.remove(entry.entityId()); //New turn, new reaction available (real 5e rule).
		movementAnchors.release(entry.entityId()); //Whoever's turn it is now gets their anchor released.

		Entity entity = level.getEntity(entry.entityId());
		if (entity == null || !entity.isAlive()) {
			//Can no longer act (died, disconnected...): nobody's going to type /dndturns next for them, so
			//their turn is skipped on its own instead of leaving the encounter hanging forever.
			scheduleAutoAdvance(level, entry.entityId());
			return;
		}

		//A boss recovers its legendary actions at the start of its turn, which is how they recharge in 5e.
		LegendaryActionManager.onOwnTurnStart(entity);
		tickEffects(level, entity, entry);
		//Persistent walls: 5e resolves them right here, at the start of the turn of whoever's inside.
		ZoneManager.onTurnStart(level, entity);
		SurfaceManager.onTurnStart(level, entity); //Same moment: if there's a fire patch underfoot, it burns now.
		if (!entity.isAlive()) { //The status effect itself (poison...) may have just killed them.
			scheduleAutoAdvance(level, entry.entityId());
			return;
		}

		glow(entity);
		broadcast(level, Component.translatable("chat.dndsheets.turn.round_header", round).withStyle(ChatFormatting.AQUA)
			.append(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)));

		if (entity instanceof ServerPlayer serverPlayer) {
			CombatFx.actionBar(serverPlayer, Component.translatable("chat.dndsheets.turn.your_turn").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD));
			opportunityAttacks.seedReachState(level, serverPlayer, order);
			movementAnchors.beginMovementBudget(level, entry.entityId(), serverPlayer.position());
			broadcastTurnState(level);
		} else if (MonsterRegistry.statBlockOf(entity) != null) {
			//Without a live DM, a monster can't wait for someone to click it with the DM Wand: it acts on
			//its own as soon as its turn comes (see MonsterActionManager.autoAct). autoAct always reaches
			//tryAct (block/isAlive were already checked above), which already sends its own
			//broadcastTurnState — no need for another one here, it would be the same packet duplicated.
			MonsterActionManager.autoAct(level, entity);
		} else {
			//Compatibility mob (another mod's Enemy, or any loose vanilla hostile that entered via
			//TurnManager.isMonster) with no stat block of its own: there's no 5e attack/damage to resolve
			//like with this mod's own monsters, so its real vanilla AI is given back for this turn (turned
			//off again in freeze() once it ends) and it's given a movement budget just like a player. Its
			//turn is only consumed once it genuinely attacks (onMobTurnAttack) or exhausts that budget
			//without reaching anyone (onMobTick) — it never gets stuck waiting for a tryAct nobody was
			//going to call for it, and in case neither of those two ever happens (the smallest slime, e.g.,
			//never deals contact damage by vanilla design — see mobTurnStartTick), a time limit cuts it off
			//regardless.
			setCompatMobActive(entity, true);
			movementAnchors.beginMovementBudget(level, entry.entityId(), entity.position());
			mobTurnStartTick.put(entry.entityId(), level.getGameTime());
			broadcastTurnState(level);
		}
	}

	//Automatic end: if the encounter started with at least one monster and none are left alive (dead or
	//removed from the world), it ends on its own — nobody has to type /dndturns end. Doesn't count
	//players (a player hitting 0 HP doesn't end combat, see DeathSaveManager) nor encounters that started
	//with no monsters (turn mode used for something else, e.g. a scene with no real combat).
	private static boolean allEnemiesDefeated(ServerLevel level) {
		boolean hadMonster = false;
		for (TurnEntry entry : order) {
			if (!entry.isMonster()) continue;
			hadMonster = true;
			if (confirmedDefeated.contains(entry.entityId())) continue; //Confirmed genuinely dead/removed.
			Entity entity = level.getEntity(entry.entityId());
			//entity==null without the confirmation above could be an unloaded chunk, not a death: assumed
			//to still be standing so as not to end combat prematurely (see markDefeated).
			if (entity == null || entity.isAlive()) return false;
		}
		return hadMonster;
	}

	//Symmetric to allEnemiesDefeated: if ALL players in the encounter have genuinely died, it ends on its
	//own — without this, dying would leave any compatibility mob frozen (NoAI) forever, with nobody having
	//permission to run /dndturns end if playing without a live DM. Only counts a real death (see
	//onPlayerRealDeath), NOT being down with pending death saves (the game continues while someone can
	//still revive them) nor disconnecting (reconcilePlayerEntity already assumes they can come back).
	private static boolean allPlayersDefeated() {
		boolean hadPlayer = false;
		for (TurnEntry entry : order) {
			if (entry.isMonster()) continue;
			hadPlayer = true;
			if (!confirmedDefeated.contains(entry.entityId())) return false;
		}
		return hadPlayer;
	}

	//Public: called by DeathSaveManager.killForReal right where a player genuinely dies (3 failed death
	//saves, or "Let Die"). If they were the last one alive in the encounter, it ends it right away —
	//restores AI for any compatibility mob that had been left frozen (see end()).
	public static void onPlayerRealDeath(ServerLevel level, ServerPlayer player) {
		if (!active) return;
		markDefeated(player.getId());
		if (allPlayersDefeated()) {
			broadcast(level, Component.translatable("chat.dndsheets.turn.all_players_defeated").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			end(level);
		}
	}

	//Server -> every client: turn mode's current state, for the HUD (see client.TurnHudOverlay). Sent
	//every time something visible changes (starts, advances, someone spends/undoes their action) —
	//no client has to request it, it always just arrives.
	//The level active combat lives on, remembered for onCombatHeartbeat's pulse: this class's state
	//doesn't track dimension (one combat per server, see the ceiling in the class's javadoc) and the
	//roster's HP changes through paths that don't go through any turn event (reactions, multiattack, zone
	//damage). Clears itself: broadcastTurnState also runs when combat ends.
	private static ServerLevel combatLevel;

	//HP/condition refresh once a second while combat is ongoing: chasing every point of damage
	//individually would mean hooking into every damage path (and missing one); a 20-tick pulse over an
	//already-existing message is simpler, and nothing runs outside combat.
	@SubscribeEvent
	public static void onCombatHeartbeat(TickEvent.ServerTickEvent event) {
		if (!active || event.phase != TickEvent.Phase.END || combatLevel == null) return;
		if (combatLevel.getGameTime() % 20 != 0) return;
		broadcastTurnState(combatLevel);
	}

	/** Re-sends the HUD state (e.g. after the DM changes feet per block mid-combat). */
	public static void refreshHud() {
		if (active && combatLevel != null) broadcastTurnState(combatLevel);
	}

	private static void broadcastTurnState(ServerLevel level) {
		combatLevel = active ? level : null;
		TurnEntry entry = current();
		int entityId = entry != null ? entry.entityId() : -1;
		boolean actioned = entry != null && actedThisTurn.contains(entry.entityId());
		Vec3 origin = entry != null ? movementAnchors.originOf(entry.entityId()) : Vec3.ZERO;
		DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.ALL.noArg(),
			new TurnStateMessage(active, round, entityId, actioned, origin.x, origin.y, origin.z, rosterOf(level), net.hawthorn.dndsheets.Config.feetPerBlock()));
	}

	//The entire initiative row for the HUD (see TurnStateMessage.RosterRow): who's up, who's already
	//acted, who's down, and what conditions each one is carrying — everything that at a real table is
	//seen just by looking at the board, and that used to only exist scattered across chat.
	private static List<TurnStateMessage.RosterRow> rosterOf(ServerLevel level) {
		List<TurnStateMessage.RosterRow> rows = new ArrayList<>(order.size());
		for (TurnEntry entry : order) {
			List<String> conditionLabels = List.of();
			int currentHp = 0;
			int maxHp = 0;
			Entity entity = level.getEntity(entry.entityId());
			if (entity != null) {
				Combatant combatant = Combatant.of(entity);
				if (combatant != null) {
					if (!combatant.conditions().isEmpty()) {
						conditionLabels = new ArrayList<>(combatant.conditions().size());
						for (Condition condition : combatant.conditions()) conditionLabels.add(condition.displayLabel());
					}
					currentHp = combatant.currentHp();
					maxHp = combatant.maxHp();
				} else if (entity instanceof LivingEntity living) {
					//Compatibility mob with no stat block (outside the rules, invariant 9): its vanilla HP,
					//ONLY to show on the board and the floating name — no rule decides based on this.
					//Without this fallback, an encounter with vanilla creepers/skeletons would come out
					//entirely without a health bar, which was the first thing noticed on screen.
					currentHp = (int) Math.ceil(living.getHealth());
					maxHp = (int) Math.ceil(living.getMaxHealth());
				}
			}
			rows.add(new TurnStateMessage.RosterRow(entry.entityId(), entry.name(), entry.isMonster(),
				confirmedDefeated.contains(entry.entityId()), actedThisTurn.contains(entry.entityId()),
				reactionUsed.contains(entry.entityId()), bonusActionUsed.contains(entry.entityId()), conditionLabels,
				currentHp, maxHp));
		}
		return rows;
	}

	private static void tickEffects(ServerLevel level, Entity entity, TurnEntry entry) {
		List<StatusEffect> active_ = effects.get(entry.entityId());
		if (active_ == null || active_.isEmpty()) return;

		List<StatusEffect> remaining = new ArrayList<>();
		for (StatusEffect effect : active_) {
			DiceManager.RollOutcome outcome = DiceManager.roll(new JsonObject(), effect.damageDice());
			//amount > 0, not just "could be rolled": a pure condition (Hold Person, Sleep) is stored with
			//"0" dice because it deals no damage, and without this filter it would announce a 0-point tick
			//every turn it lasts — noise in chat right when it's already at its busiest.
			if (outcome.result() != null && outcome.result().getValue() > 0) {
				int amount = outcome.result().getValue();
				SpellCastManager.applyDamage(entity, amount, effect.name());
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_tick", entry.name(), effect.name(), outcome.formatted()).withStyle(ChatFormatting.DARK_GREEN));
			}
			int left = effect.remainingTurns() - 1;
			if (left > 0) {
				remaining.add(new StatusEffect(effect.name(), effect.damageDice(), left));
			} else {
				//The counter ran out: if the effect was a genuine condition, the condition is lifted too.
				//Without this the timer would expire but the character would stay prone or paralyzed
				//forever, because the condition is persisted and the timer isn't.
				Condition condition = Condition.fromLabel(effect.name());
				if (condition != null) {
					Combatant combatant = Combatant.of(entity);
					if (combatant != null) combatant.removeCondition(condition);
				}
				broadcast(level, Component.translatable("chat.dndsheets.turn.effect_ended", entry.name(), effect.name()).withStyle(ChatFormatting.GRAY));
			}
		}
		if (remaining.isEmpty()) effects.remove(entry.entityId());
		else effects.put(entry.entityId(), remaining);
	}

	//ponytail: a single global debounce for every mutator instead of one per encounter — only one
	//turn-mode encounter is ever allowed at a time. If simultaneous encounters are ever needed, this
	//would have to become a map per encounter.
	private static boolean debounce(ServerLevel level) {
		long now = level.getGameTime();
		if (lastActionTick == now) return true;
		lastActionTick = now;
		return false;
	}

	//Used to be server-wide: turn announcements ("X's turn", round N, combat ended...) were seen by
	//anyone connected, not just whoever was in or near this encounter — the same problem as
	//ChatFeedback.broadcast, the same fix criterion (radius from where combat started, not the whole
	//server). With no known zone (shouldn't happen with active combat, but just in case) it falls back to
	//server-wide rather than lose the announcement.
	private static void broadcast(ServerLevel level, Component message) {
		if (combatOrigin == null) {
			level.getServer().getPlayerList().broadcastSystemMessage(message, false);
			return;
		}
		double radiusSq = combatRadius * combatRadius;
		for (ServerPlayer player : level.players()) {
			if (player.position().distanceToSqr(combatOrigin) <= radiusSq) player.sendSystemMessage(message);
		}
	}

	//Movement lockdown: whoever has an anchored position (not their turn) gets sent back there as soon as
	//they wander off, and is told why. With no anchor (it's their turn, or turn mode is off) it does nothing.
	@SubscribeEvent
	public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
		if (event.phase != TickEvent.Phase.END || !active) return;
		if (!(event.player instanceof ServerPlayer player)) return;

		if (movementAnchors.isAnchorHandledThisTick(player)) return;

		TurnEntry currentEntry = current();
		if (currentEntry != null && currentEntry.entityId() == player.getId() && player.level() instanceof ServerLevel level) {
			if (net.hawthorn.dndsheets.Config.auto(net.hawthorn.dndsheets.Config.Rule.OPPORTUNITY_ATTACKS)) opportunityAttacks.checkOpportunityAttacks(level, player, order);
			movementAnchors.enforceMovementBudget(player);
		}
	}
}
