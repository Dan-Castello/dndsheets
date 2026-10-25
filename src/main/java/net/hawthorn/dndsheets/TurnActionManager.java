package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

/**
 * <p>The 5e turn actions that are neither attacking nor casting a spell: <b>Dodge</b>, <b>Dash</b>, and
 * <b>Disengage</b>.</p>
 *
 * <p>Until now a turn could only be spent hitting or casting something, so turn mode was a "who hits now"
 * by order. These three are what turns the turn into a decision: a cornered character with low HP almost
 * never wants to attack, they want to get out of there without eating an opportunity attack, or hunker
 * down and weather the round.</p>
 *
 * <p>None of them needs new rules: all three plug into machinery that already existed and wasn't being
 * used for anything else — {@link MovementAnchorTracker}'s movement budget, {@code
 * OpportunityAttackTracker}'s reach tracking, and {@link Combatant#advantageAgainst}'s advantage/disadvantage.</p>
 *
 * <p><b>Help</b> is not here: it grants advantage to an ally's attack and therefore needs someone to point at,
 * same as Bardic Inspiration, so it is an interact-with-entity item ({@link HelpActionManager}). The menu also
 * carries Search, Hide and Ready, and the class features (Action Surge, Flurry of Blows, Patient Defense, Step
 * of the Wind) gated by class on the server.</p>
 */
public class TurnActionManager {

	public enum TurnAction {
		/** Every attack against you has disadvantage until your next turn. */
		DODGE,
		/** Double movement this turn. */
		DASH,
		/** Moving away doesn't provoke opportunity attacks this turn. */
		DISENGAGE,
		//Appended (invariant 2: this enum travels by ordinal). Class features, gated by class on the server.
		/** Fighter: one extra action this turn, once per rest. */
		ACTION_SURGE,
		/** Monk, 1 ki: Dodge as a bonus action. */
		PATIENT_DEFENSE,
		/** Monk, 1 ki: Dash as a bonus action. */
		STEP_DASH,
		/** Monk, 1 ki: Disengage as a bonus action. */
		STEP_DISENGAGE,
		/** Perception check; the table (DM) decides what it finds. */
		SEARCH,
		/** Stealth check against the passive Perception of nearby monsters; hidden = advantage on the next attack. */
		HIDE,
		/** Hold the action: act once, out of turn, spending the reaction. Expires when the next turn starts. */
		READY,
		/** Monk, 1 ki: two extra unarmed strikes this turn (bonus action; arm it BEFORE the first attack). */
		FLURRY_OF_BLOWS,
	}

	//A single map for all three, not three separate sets: they all expire at the same time (when the
	//user's next turn starts) and get cleared from the same place, so splitting them would just be three
	//things to remember to clear instead of one.
	private static final Map<Integer, EnumSet<TurnAction>> active = new HashMap<>();

	public static void use(ServerPlayer player, TurnAction action) {
		//Outside combat these mean nothing: no turn to spend, no opportunity attacks, no movement budget
		//to double. Saying so is better than accepting the click and doing nothing.
		if (!TurnManager.isActive()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.needs_combat").withStyle(ChatFormatting.GRAY));
			return;
		}
		switch (action) {
			case ACTION_SURGE -> { useActionSurge(player); return; }
			case FLURRY_OF_BLOWS -> { useFlurry(player); return; }
			case PATIENT_DEFENSE -> { useKi(player, TurnAction.DODGE); return; }
			case STEP_DASH -> { useKi(player, TurnAction.DASH); return; }
			case STEP_DISENGAGE -> { useKi(player, TurnAction.DISENGAGE); return; }
			default -> { }
		}
		//They ARE the turn's action, same as attacking: checked with the same tryAct, so using one also
		//ends the turn on its own and you can't dodge AND attack in the same round.
		//Dash and Disengage do NOT end the turn: their effect is for the movement made after them (see tryActNoAdvance).
		boolean keepsTurn = action == TurnAction.DASH || action == TurnAction.DISENGAGE;
		if (!(keepsTurn ? TurnManager.tryActNoAdvance(player) : TurnManager.tryAct(player))) {
			TurnManager.notifyCantAct(player);
			return;
		}

		if (action == TurnAction.SEARCH) { search(player); return; }
		if (action == TurnAction.HIDE) { hide(player); return; }

		active.computeIfAbsent(player.getId(), id -> EnumSet.noneOf(TurnAction.class)).add(action);
		CombatFx.activate(player);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		ChatFeedback.broadcast(player, Component.translatable(messageKeyOf(action), name).withStyle(ChatFeedback.RESOURCE));
	}

	//--- Hide / Search / Ready ---

	/** Stealth is skill index 3 in the sheet's fixed skill order (see RollIndex; checked by the self-test). */
	static final int STEALTH_SKILL_INDEX = 3;
	/** How far a monster notices a hiding player, in blocks. */
	private static final double NOTICE_RADIUS = 30.0;

	//Hidden creatures and the Stealth total they hid with. Unlike the turn actions, this survives their
	//next turn: it ends when they attack or cast, or when the encounter ends.
	private static final Map<Integer, Integer> hidden = new HashMap<>();

	private static net.hawthorn.dndsheets.DiceManager.RollOutcome skillRoll(com.google.gson.JsonObject sheet, int index) {
		String expression = "1d20";
		if (sheet != null && sheet.has("skills") && index < sheet.getAsJsonArray("skills").size()) {
			expression = sheet.getAsJsonArray("skills").get(index).getAsString();
		}
		return DiceManager.roll(sheet != null ? sheet : new com.google.gson.JsonObject(), expression);
	}

	private static void search(ServerPlayer player) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		DiceManager.RollOutcome roll = skillRoll(sheet, RollIndex.PERCEPTION_SKILL_INDEX);
		CombatFx.activate(player);
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.action.search",
			SheetLoader.characterNameOf(sheet, player), roll.formatted()).withStyle(ChatFeedback.RESOURCE));
	}

	/**
	 * <p>Hide: a Stealth check against the highest passive Perception (10 + Wisdom modifier) among the monsters
	 * within {@value #NOTICE_RADIUS} blocks; with none around it simply works. Hidden = advantage on the next
	 * attack, which also reveals them. ponytail: no line-of-sight or cover requirement to hide, the DM's call.</p>
	 */
	private static void hide(ServerPlayer player) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		DiceManager.RollOutcome roll = skillRoll(sheet, STEALTH_SKILL_INDEX);
		int total = roll.result() != null ? roll.result().getValue() : 0;
		int worst = 0;
		for (net.minecraft.world.entity.LivingEntity e : player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
				player.getBoundingBox().inflate(NOTICE_RADIUS), TurnManager::isMonster)) {
			MonsterRegistry.MonsterStatBlock block = MonsterRegistry.statBlockOf(e);
			worst = Math.max(worst, 10 + (block != null ? block.abilityModifier("wis") : 0));
		}
		String name = SheetLoader.characterNameOf(sheet, player);
		if (total > worst) {
			hidden.put(player.getId(), total);
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.action.hide_ok", name, roll.formatted()).withStyle(ChatFeedback.RESOURCE));
		} else {
			hidden.remove(player.getId());
			ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.action.hide_fail", name, roll.formatted()).withStyle(ChatFormatting.GRAY));
		}
	}

	/** Hidden attackers get advantage -- see {@link Combatant#ownAttackAdvantage}. */
	public static boolean isHidden(Entity entity) {
		return entity != null && hidden.containsKey(entity.getId());
	}

	/** Attacking or casting gives a hiding creature away. */
	public static void reveal(Entity entity) {
		if (entity != null && hidden.remove(entity.getId()) != null && entity instanceof ServerPlayer player) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.revealed").withStyle(ChatFormatting.GRAY));
		}
	}

	/**
	 * <p>A readied action fires: once, out of turn, spending the reaction. Called by {@link TurnManager#tryAct} when
	 * it isn't the actor's turn. The trigger is the player's own call, as at the table.</p>
	 */
	static boolean releaseReadied(Entity actor) {
		EnumSet<TurnAction> taken = actor == null ? null : active.get(actor.getId());
		if (taken == null || !taken.contains(TurnAction.READY) || !TurnManager.tryReact(actor)) return false;
		taken.remove(TurnAction.READY);
		return true;
	}

	private static void useActionSurge(ServerPlayer player) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		if (CharacterRules.classLevelOf(sheet, "fighter", "guerrero") < 2) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.not_fighter").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (RestResource.isSpent(sheet, RestResource.ACTION_SURGE)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.surge_spent").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.armExtraActions(player, 1)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.surge_too_late").withStyle(ChatFormatting.RED));
			return;
		}
		RestResource.spend(player, RestResource.ACTION_SURGE);
		CombatFx.activate(player);
		String name = SheetLoader.characterNameOf(sheet, player);
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.action.surge", name).withStyle(ChatFeedback.RESOURCE));
	}

	/**
	 * <p>Flurry of Blows: 1 ki and the bonus action buy two extra strikes. Armed before the first attack, so the turn
	 * waits for them (see {@link TurnManager#armExtraActions}). ponytail: the extra strikes are not checked to be unarmed.</p>
	 */
	private static void useFlurry(ServerPlayer player) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int max = CharacterRules.kiPointsFor(CharacterRules.classLevelOf(sheet, "monk", "monje"));
		if (max <= 0) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.not_monk").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (RestResource.usesLeft(sheet, RestResource.KI_USES, max) <= 0) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.no_ki").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.canArmExtraActions(player)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.surge_too_late").withStyle(ChatFormatting.RED));
			return;
		}
		if (!TurnManager.tryActBonus(player)) {
			TurnManager.notifyCantActBonus(player);
			return;
		}
		TurnManager.armExtraActions(player, 2);
		RestResource.spendUse(player, RestResource.KI_USES, max);
		CombatFx.activate(player);
		ChatFeedback.broadcast(player, Component.translatable("chat.dndsheets.action.flurry", SheetLoader.characterNameOf(sheet, player)).withStyle(ChatFeedback.RESOURCE));
		player.sendSystemMessage(Component.translatable("chat.dndsheets.action.ki_left", RestResource.usesLeft(sheet, RestResource.KI_USES, max)).withStyle(ChatFormatting.GRAY));
	}

	/** Monk ki techniques: a bonus action and 1 ki point for the Dodge / Dash / Disengage effect. */
	private static void useKi(ServerPlayer player, TurnAction effect) {
		com.google.gson.JsonObject sheet = SheetLoader.getServerSheet(player.getStringUUID());
		int max = CharacterRules.kiPointsFor(CharacterRules.classLevelOf(sheet, "monk", "monje"));
		if (max <= 0) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.not_monk").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (RestResource.usesLeft(sheet, RestResource.KI_USES, max) <= 0) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.action.no_ki").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.tryActBonus(player)) {
			TurnManager.notifyCantActBonus(player);
			return;
		}
		RestResource.spendUse(player, RestResource.KI_USES, max);
		active.computeIfAbsent(player.getId(), id -> EnumSet.noneOf(TurnAction.class)).add(effect);
		CombatFx.activate(player);
		String name = SheetLoader.characterNameOf(sheet, player);
		ChatFeedback.broadcast(player, Component.translatable(messageKeyOf(effect), name).withStyle(ChatFeedback.RESOURCE));
		player.sendSystemMessage(Component.translatable("chat.dndsheets.action.ki_left", RestResource.usesLeft(sheet, RestResource.KI_USES, max)).withStyle(ChatFormatting.GRAY));
	}

	private static String messageKeyOf(TurnAction action) {
		return switch (action) {
			case ACTION_SURGE, PATIENT_DEFENSE, STEP_DASH, STEP_DISENGAGE, SEARCH, HIDE, FLURRY_OF_BLOWS -> throw new IllegalArgumentException(action.name());
			case READY -> "chat.dndsheets.action.ready";
			case DODGE -> "chat.dndsheets.action.dodge";
			case DASH -> "chat.dndsheets.action.dash";
			case DISENGAGE -> "chat.dndsheets.action.disengage";
		};
	}

	private static boolean has(Entity entity, TurnAction action) {
		EnumSet<TurnAction> taken = entity == null ? null : active.get(entity.getId());
		return taken != null && taken.contains(action);
	}

	/** Every attack against them has disadvantage. Lasts until their turn comes around again, i.e. the whole round. */
	public static boolean isDodging(Entity entity) {
		return has(entity, TurnAction.DODGE);
	}

	/** Double movement budget this turn — see {@link MovementAnchorTracker}. */
	public static boolean isDashing(Entity entity) {
		return has(entity, TurnAction.DASH);
	}

	/** Moving away doesn't provoke opportunity attacks — see {@code OpportunityAttackTracker}. */
	public static boolean isDisengaged(Entity entity) {
		return has(entity, TurnAction.DISENGAGE);
	}

	/**
	 * <p>They expire when THEIR next turn starts, which is literally what Dodge says in 5e ("until the
	 * start of your next turn"). Dash and Disengage only matter during their own turn, so clearing them
	 * here too is also correct and avoids leaving three separate clocks to reconcile.</p>
	 */
	static void clearFor(int entityId) {
		active.remove(entityId);
	}

	/** Cleared entirely when an encounter ends: outside combat none of the three mean anything. */
	static void clearAll() {
		active.clear();
		hidden.clear();
	}

	//Triggered from AbilityItemDispatcher, same as the rest of the ability items.
	static void tryUse(PlayerInteractEvent event) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player)) return;
		DndsheetsMod.PACKET_HANDLER.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
			new net.hawthorn.dndsheets.network.ScreenActionMessage(net.hawthorn.dndsheets.network.ScreenActionMessage.Action.TURN_ACTION_OPEN));
	}

	public static ItemStack buildTurnActionStack() {
		return AbilityItem.build(ItemLook.TURN_ACTIONS, "turnActions", Component.translatable("chat.dndsheets.turn.actions_item_name"),
			Component.translatable("chat.dndsheets.turn.actions_item_lore").withStyle(ChatFormatting.GRAY));
	}
}
