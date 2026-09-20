package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Client (the DM) -> server: deletes an already-captured dungeon piece, from DungeonPieceListScreen
//(the GUI equivalent of /dnddungeon piece remove <id>, which until now was the only way to do it).
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
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (DungeonPieceRegistry.get(message.id) == null) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.no_such_piece", message.id));
				return;
			}

			DungeonManager.removePiece(dm.getServer(), message.id);
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.piece_deleted", message.id));
			//Reopens the list without the deleted piece, instead of simply closing — same echo pattern
			//DungeonPieceUpdateMessage uses after editing.
			DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
