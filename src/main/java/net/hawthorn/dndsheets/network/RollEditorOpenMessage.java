package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.world.inventory.RollEditorMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/** Client -&gt; server: opens the roll editor, or closes it if it was already open. */
public class RollEditorOpenMessage {

	public RollEditorOpenMessage() {
	}

	public RollEditorOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(RollEditorOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(RollEditorOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> pressAction(context.getSender()));
	}

	public static void pressAction(Player entity) {
		if (entity == null) return;

		if (entity.containerMenu instanceof RollEditorMenu) {
			entity.closeContainer();
		} else if (entity instanceof ServerPlayer player) {
			NetworkHooks.openScreen(player, new SimpleMenuProvider(
				(id, inventory, viewer) -> new RollEditorMenu(id, inventory, null), Component.literal("RollEditor")));
		}
	}
}
