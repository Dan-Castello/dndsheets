package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.SheetLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Server -> client: patch of a few sheet fields (e.g. "nextAttackAdvantage" after consuming an
//advantage, "spellSlotsCurrent" after spending a slot), instead of the full JSON sheet. A JsonNull value in the patch means "delete this key" in the
//client's cached sheet (see SheetLoader.applyClientDelta), the same way the server deletes it with
//JsonObject.remove(...). Reserved for changes limited to a couple of known fields; bulk changes
//(applying a preset, loading the sheet on connect) keep using SheetClientMessage with the whole sheet.
public class SheetFieldUpdateMessage {
	byte[] data;

	public SheetFieldUpdateMessage(byte[] data) {
		this.data = data;
	}

	public SheetFieldUpdateMessage(FriendlyByteBuf buffer) {
		this.data = buffer.readByteArray();
	}

	public static void buffer(SheetFieldUpdateMessage message, FriendlyByteBuf buffer) {
		buffer.writeByteArray(message.data);
	}

	public static void handler(SheetFieldUpdateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> handle(message.data));
	}

	public static void handle(byte[] data) {
		JsonObject patch = JsonParser.parseString(new String(data, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
		SheetLoader.applyClientDelta(patch);
	}
}
