package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.TurnActionManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client -> server: the player chose Dodge/Dash/Disengage in TurnActionScreen.
public class TurnActionMessage {
	//At the end, never in the middle: writeEnum travels by ordinal (invariant 2 of PROJECT_CONTEXT.md).
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
