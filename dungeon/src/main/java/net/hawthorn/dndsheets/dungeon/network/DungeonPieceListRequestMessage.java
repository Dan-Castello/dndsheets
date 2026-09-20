package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Client (the DM) -> server: asks for the list of registered dungeon pieces, to open
//DungeonPieceListScreen from the DM Panel (see DmPanelScreen).
public class DungeonPieceListRequestMessage {
	public DungeonPieceListRequestMessage() {
	}

	public DungeonPieceListRequestMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(DungeonPieceListRequestMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(DungeonPieceListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {

			DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm),
				DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
