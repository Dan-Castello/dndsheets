package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: pide la lista de piezas de mazmorra registradas, para abrir
//DungeonPieceListScreen desde el Panel de DM (ver DmPanelScreen).
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

			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm),
				DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
