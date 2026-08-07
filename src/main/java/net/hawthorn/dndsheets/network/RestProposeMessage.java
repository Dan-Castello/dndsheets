package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.RestManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: eligió corto o largo en RestChoiceScreen, arranca la votación.
public class RestProposeMessage {
	final boolean longRest;

	public RestProposeMessage(boolean longRest) {
		this.longRest = longRest;
	}

	public RestProposeMessage(FriendlyByteBuf buffer) {
		this.longRest = buffer.readBoolean();
	}

	public static void buffer(RestProposeMessage message, FriendlyByteBuf buffer) {
		buffer.writeBoolean(message.longRest);
	}

	public static void handler(RestProposeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer proposer = context.getSender();
			if (proposer != null) RestManager.propose(proposer, message.longRest ? RestManager.RestType.LONG : RestManager.RestType.SHORT);
		});
		context.setPacketHandled(true);
	}
}
