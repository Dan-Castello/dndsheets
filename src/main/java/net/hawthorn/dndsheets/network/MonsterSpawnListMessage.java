package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.MonsterSpawnListScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de monstruos cargados (ids), eco de MonsterSpawnListRequestMessage, para
//abrir MonsterSpawnListScreen con datos reales aunque el cliente y el servidor sean procesos distintos.
public class MonsterSpawnListMessage {
	List<String> ids;

	public MonsterSpawnListMessage(List<String> ids) {
		this.ids = ids;
	}

	public MonsterSpawnListMessage(FriendlyByteBuf buffer) {
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(MonsterSpawnListMessage message, FriendlyByteBuf buffer) {
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
	}

	public static void handler(MonsterSpawnListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> MonsterSpawnListScreen.open(message.ids));
	}
}
