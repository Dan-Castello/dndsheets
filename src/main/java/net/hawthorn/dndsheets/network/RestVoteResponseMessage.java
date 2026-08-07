package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: aceptó o rechazó la votación de descanso pendiente.
public class RestVoteResponseMessage {
	final boolean accept;

	public RestVoteResponseMessage(boolean accept) {
		this.accept = accept;
	}

	public RestVoteResponseMessage(FriendlyByteBuf buffer) {
		this.accept = buffer.readBoolean();
	}

	public static void buffer(RestVoteResponseMessage message, FriendlyByteBuf buffer) {
		buffer.writeBoolean(message.accept);
	}

	public static void handler(RestVoteResponseMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer voter = context.getSender();
			if (voter != null) RestManager.registerVote(voter, message.accept);
		});
		context.setPacketHandled(true);
	}
}
