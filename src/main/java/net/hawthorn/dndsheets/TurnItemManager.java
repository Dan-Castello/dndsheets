package net.hawthorn.dndsheets;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

/**
 * <p>Two convenience items so players don't depend on the DM typing {@code /dndturns} every time: a
 * player with the turn can end it themself ({@code {dndsheets:{turnNext:true}}}, Compass) or undo their
 * own action to choose again without losing the turn ({@code {dndsheets:{turnUndo:true}}}, Echo Shard).
 * Both only work for whoever has the turn right now — see {@link TurnManager#isCurrentActor}.</p>
 */
public class TurnItemManager {

	//Triggered from AbilityItemDispatcher instead of subscribing to the 3 interaction events
	//separately.
	static void tryUse(PlayerInteractEvent event, boolean isNext) {
		event.setCanceled(true);
		if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getEntity().level() instanceof ServerLevel level)) return;

		if (!TurnManager.isActive()) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.turn.not_active").withStyle(ChatFormatting.GRAY));
			return;
		}
		if (!TurnManager.isCurrentActor(player)) {
			player.sendSystemMessage(Component.translatable("chat.dndsheets.turn.not_yours").withStyle(ChatFormatting.RED));
			return;
		}

		if (isNext) {
			TurnManager.next(level);
		} else {
			TurnManager.undoAction(level, player);
		}
	}

	public static ItemStack buildNextTurnStack() {
		return AbilityItem.build(ItemLook.TURN_NEXT, "turnNext", Component.translatable("chat.dndsheets.turn.end_item_name"),
			Component.translatable("chat.dndsheets.turn.end_item_lore").withStyle(ChatFormatting.GRAY));
	}

	public static ItemStack buildUndoTurnStack() {
		return AbilityItem.build(ItemLook.TURN_UNDO, "turnUndo", Component.translatable("chat.dndsheets.turn.undo_item_name"),
			Component.translatable("chat.dndsheets.turn.undo_item_lore").withStyle(ChatFormatting.GRAY));
	}
}
