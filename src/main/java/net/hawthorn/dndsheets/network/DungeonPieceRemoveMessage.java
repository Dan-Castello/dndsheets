package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: borra una pieza de mazmorra ya capturada, desde DungeonPieceListScreen
//(equivalente en GUI a /dnddungeon piece remove <id>, que hasta ahora era la única forma de hacerlo).
public class DungeonPieceRemoveMessage {
	String id;

	public DungeonPieceRemoveMessage(String id) {
		this.id = id;
	}

	public DungeonPieceRemoveMessage(FriendlyByteBuf buffer) {
		this.id = buffer.readUtf();
	}

	public static void buffer(DungeonPieceRemoveMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.id);
	}

	public static void handler(DungeonPieceRemoveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (DungeonPieceRegistry.get(message.id) == null) {
				dm.sendSystemMessage(Component.literal("No conozco ninguna pieza \"" + message.id + "\"."));
				return;
			}

			DungeonManager.removePiece(dm.getServer(), message.id);
			dm.sendSystemMessage(Component.literal("Pieza \"" + message.id + "\" borrada."));
			//Reabre la lista ya sin la pieza borrada, en vez de simplemente cerrar — mismo eco que usa
			//DungeonPieceUpdateMessage tras editar.
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
