package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DeathSaveManager;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador caído pulsó "Tirar salvación de muerte".
public class DeathSaveRollMessage {
	public DeathSaveRollMessage() {}

	public DeathSaveRollMessage(FriendlyByteBuf buffer) {}

	public static void buffer(DeathSaveRollMessage message, FriendlyByteBuf buffer) {}

	public static void handler(DeathSaveRollMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player != null) {
				DeathSaveManager.handleRollRequest(player);
			}
		});
	}
}
