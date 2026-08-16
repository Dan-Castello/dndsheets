package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.TurnActionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador eligió Esquivar/Correr/Desengancharse en TurnActionScreen.
public class TurnActionMessage {
	//Al final, nunca en medio: writeEnum viaja por ordinal (invariante 2 de PROJECT_CONTEXT.md).
	final TurnActionManager.TurnAction action;

	public TurnActionMessage(TurnActionManager.TurnAction action) {
		this.action = action;
	}

	public TurnActionMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readEnum(TurnActionManager.TurnAction.class);
	}

	public static void buffer(TurnActionMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.action);
	}

	public static void handler(TurnActionMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player != null) TurnActionManager.use(player, message.action);
		});
	}
}
