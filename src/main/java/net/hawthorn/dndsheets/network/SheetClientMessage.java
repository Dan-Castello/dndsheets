
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
		//Explicit UTF-8: without this, one player's machine and another's on the same LAN can have different
		//default encodings, and a race/background name with an accent arrives unreadable or outright breaks
		//the JSON (see the same fix on every .getBytes()/new String(...) in sheet transport).
		String json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
		JsonObject sheet = JsonParser.parseString(json).getAsJsonObject();
		SheetLoader.setClient(sheet);
		//And if the sheet is OPEN on screen, it gets refilled. Without this, switching character (or
		//resting, or applying a preset) with the sheet open left it showing the previous character; and
		//since almost any interaction on that screen saves whatever its fields hold
		//(CharacterSheetSaveProcedure), the first roll after switching would write the old character's
		//data OVER the new one. It wasn't just a stale display: data was actually lost.
		net.hawthorn.dndsheets.client.gui.CharacterSheetScreen.refreshIfOpen();
		net.hawthorn.dndsheets.client.gui.SkillProficiencyScreen.refreshIfOpen();
		net.hawthorn.dndsheets.client.gui.CharacterSetupScreen.refreshIfOpen();
	}
}
