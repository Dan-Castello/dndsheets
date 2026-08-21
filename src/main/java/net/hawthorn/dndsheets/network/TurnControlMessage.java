package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.TurnManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: botón de Iniciar/Siguiente/Cancelar/Terminar en TurnControlScreen
//(equivalente en GUI a /dndturns start|next|cancel|end).
public class TurnControlMessage {
	String action;

	public TurnControlMessage(String action) {
		this.action = action;
	}

	public TurnControlMessage(FriendlyByteBuf buffer) {
		this.action = buffer.readUtf();
	}

	public static void buffer(TurnControlMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.action);
	}

	public static void handler(TurnControlMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			ServerLevel level = dm.serverLevel();

			switch (message.action) {
				case "start" -> TurnManager.startAt(level, dm.position(), TurnManager.DEFAULT_RADIUS);
				case "next" -> TurnManager.next(level);
				case "cancel" -> TurnManager.cancel(level);
				case "end" -> TurnManager.end(level);
			}
		});
	}
}
