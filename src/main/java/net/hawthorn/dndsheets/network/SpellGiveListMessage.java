package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.SpellGiveListScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de hechizos cargados (ids) para el objetivo elegido en PlayerPickerScreen,
//eco de SpellGiveListRequestMessage, abre SpellGiveListScreen con datos reales.
public class SpellGiveListMessage {
	String targetUuid;
	List<String> ids;

	public SpellGiveListMessage(String targetUuid, List<String> ids) {
		this.targetUuid = targetUuid;
		this.ids = ids;
	}

	public SpellGiveListMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(SpellGiveListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
	}

	public static void handler(SpellGiveListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> SpellGiveListScreen.open(message.targetUuid, message.ids));
	}
}
