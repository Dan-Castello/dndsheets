package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.WeaponGiveListScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.List;
import java.util.function.Supplier;

//Servidor -> cliente: la lista de armas cargadas (ids) para el objetivo elegido en PlayerPickerScreen, eco
//de WeaponGiveListRequestMessage, abre WeaponGiveListScreen con datos reales.
public class WeaponGiveListMessage {
	String targetUuid;
	List<String> ids;

	public WeaponGiveListMessage(String targetUuid, List<String> ids) {
		this.targetUuid = targetUuid;
		this.ids = ids;
	}

	public WeaponGiveListMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
		this.ids = buffer.readList(FriendlyByteBuf::readUtf);
	}

	public static void buffer(WeaponGiveListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
		buffer.writeCollection(message.ids, FriendlyByteBuf::writeUtf);
	}

	public static void handler(WeaponGiveListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> WeaponGiveListScreen.open(message.targetUuid, message.ids));
	}
}
