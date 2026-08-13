package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DeathSaveManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador caído pulsó "Dejarse morir".
public class DeathSaveGiveUpMessage {
	public DeathSaveGiveUpMessage() {}

	public DeathSaveGiveUpMessage(FriendlyByteBuf buffer) {}

	public static void buffer(DeathSaveGiveUpMessage message, FriendlyByteBuf buffer) {}

	public static void handler(DeathSaveGiveUpMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player != null) {
				DeathSaveManager.handleGiveUpRequest(player);
			}
		});
	}
}
