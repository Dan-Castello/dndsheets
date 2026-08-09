
package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SheetClientMessage {
	byte[] data;

	public SheetClientMessage(byte[] data) {
		this.data = data;
	}

	public SheetClientMessage(FriendlyByteBuf buffer) {
		this.data = buffer.readByteArray();
	}

	public static void buffer(SheetClientMessage message, FriendlyByteBuf buffer) {
		buffer.writeByteArray(message.data);
	}

	public static void handler(SheetClientMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> handle(message.data));
	}

	public static void handle(byte[] data) {
		String json = new String(data);
		JsonObject sheet = JsonParser.parseString(json).getAsJsonObject();
		SheetLoader.setClient(sheet);
	}
}
