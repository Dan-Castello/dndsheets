package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.gui.OptionsManageScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: la lista viva de una categoría (texto crudo del array JSON de strings), para abrir/
//refrescar OptionsManageScreen — eco de OptionsListRequestMessage, o tras guardar con OptionsSaveMessage.
public class OptionsListMessage {
	String category;
	String arrayJson;

	public OptionsListMessage(String category, String arrayJson) {
		this.category = category;
		this.arrayJson = arrayJson;
	}

	public OptionsListMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readUtf();
		this.arrayJson = buffer.readUtf(32767);
	}

	public static void buffer(OptionsListMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.category);
		buffer.writeUtf(message.arrayJson, 32767);
	}

	public static void handler(OptionsListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> OptionsManageScreen.open(message.category, message.arrayJson));
	}
}
