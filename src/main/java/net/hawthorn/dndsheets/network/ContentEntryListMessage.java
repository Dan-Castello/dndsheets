package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.client.gui.ContentEntryListScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Servidor -> cliente: entradas de dm_created.json de un tipo (texto crudo del array JSON), para abrir/
//refrescar ContentEntryListScreen — eco de ContentEntryListRequestMessage, o tras guardar/borrar una entrada.
public class ContentEntryListMessage {
	ContentType type;
	String arrayJson;

	public ContentEntryListMessage(ContentType type, String arrayJson) {
		this.type = type;
		this.arrayJson = arrayJson;
	}

	public ContentEntryListMessage(FriendlyByteBuf buffer) {
		this.type = buffer.readEnum(ContentType.class);
		this.arrayJson = buffer.readUtf(32767);
	}

	public static void buffer(ContentEntryListMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.type);
		buffer.writeUtf(message.arrayJson, 32767);
	}

	public static void handler(ContentEntryListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> ContentEntryListScreen.open(message.type, message.arrayJson));
	}
}
