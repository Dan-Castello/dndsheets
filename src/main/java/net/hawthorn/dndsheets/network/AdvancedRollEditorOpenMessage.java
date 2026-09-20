package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.world.inventory.AdvancedRollEditorMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/** Client -&gt; server: opens the advanced roll editor, or closes it if it was already open. */
public class AdvancedRollEditorOpenMessage {

	public AdvancedRollEditorOpenMessage() {
	}

	public AdvancedRollEditorOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(AdvancedRollEditorOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(AdvancedRollEditorOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> pressAction(context.getSender()));
	}

	public static void pressAction(Player entity) {
		if (entity == null) return;

		if (entity.containerMenu instanceof AdvancedRollEditorMenu) {
			entity.closeContainer();
		} else if (entity instanceof ServerPlayer player) {
			NetworkHooks.openScreen(player, new SimpleMenuProvider(
				(id, inventory, viewer) -> new AdvancedRollEditorMenu(id, inventory, null), Component.literal("AdvancedRollEditor")));
		}
	}
}
