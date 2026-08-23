package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.MonsterRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: pide la lista de monstruos cargados para abrir MonsterSpawnListScreen — el
//registro solo vive en memoria del servidor, y un DM que sea un cliente aparte (invitado por LAN) lo ve
//siempre vacío si el cliente intenta leerlo directo.
public class MonsterSpawnListRequestMessage {

	public MonsterSpawnListRequestMessage() {
	}

	public MonsterSpawnListRequestMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(MonsterSpawnListRequestMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(MonsterSpawnListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, (ServerPlayer dm) -> {
			List<String> ids = new ArrayList<>(MonsterRegistry.ids());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new MonsterSpawnListMessage(ids));
		});
	}
}
