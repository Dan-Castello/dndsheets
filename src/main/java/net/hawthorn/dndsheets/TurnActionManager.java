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
 * <p><b>Missing: Help</b>, the fourth one: it grants advantage to an ally's attack and therefore needs
 * someone to point at, same as Bardic Inspiration. It's an interact-with-entity item, not an entry in
 * this menu, and that's why it doesn't belong here.</p>
 */
public class TurnActionManager {

	public enum TurnAction {
		/** Every attack against you has disadvantage until your next turn. */
		DODGE,
		/** Double movement this turn. */
		DASH,
		/** Moving away doesn't provoke opportunity attacks this turn. */
		DISENGAGE,
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
		//They ARE the turn's action, same as attacking: checked with the same tryAct, so using one also
		//ends the turn on its own and you can't dodge AND attack in the same round.
		if (!TurnManager.tryAct(player)) {
			TurnManager.notifyCantAct(player);
			return;
		}

		active.computeIfAbsent(player.getId(), id -> EnumSet.noneOf(TurnAction.class)).add(action);
		CombatFx.activate(player);
		String name = SheetLoader.characterNameOf(SheetLoader.getServerSheet(player.getStringUUID()), player);
		ChatFeedback.broadcast(player, Component.translatable(messageKeyOf(action), name).withStyle(ChatFeedback.RESOURCE));
	}

	private static String messageKeyOf(TurnAction action) {
		return switch (action) {
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
