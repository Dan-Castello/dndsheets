package net.hawthorn.dndsheets.network;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.DndPaths;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Client (the DM) -> server: replaces the WHOLE list for a category (race/background/class) from
//OptionsManageScreen — CharacterOptionsRegistry.loadFile replaces, it doesn't merge (see its javadoc), so
//"adding an option" is actually "saving the entire list with one more/fewer option".
public class OptionsSaveMessage {
	String category;
	String arrayJson;

	public OptionsSaveMessage(String category, String arrayJson) {
		this.category = category;
		this.arrayJson = arrayJson;
	}

	public OptionsSaveMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readUtf();
		this.arrayJson = buffer.readUtf(32767);
	}

	public static void buffer(OptionsSaveMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.category);
		buffer.writeUtf(message.arrayJson, 32767);
	}

	private static Path dirFor(String category) {
		return switch (category) {
			case CharacterOptionsRegistry.CLASS -> DndPaths.CLASSES_DIR;
			default -> null;
		};
	}

	public static void handler(OptionsSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			Path dir = dirFor(message.category);
			if (dir == null) return;

			List<String> values = new ArrayList<>();
			for (JsonElement el : JsonParser.parseString(message.arrayJson).getAsJsonArray()) values.add(el.getAsString());

			try {
				ContentPackFile.writeStringArray(DndPaths.dmCreatedFile(dir), values);
			} catch (IOException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.options.save_failed", e.getMessage()));
				return;
			}
			CharacterOptionsRegistry.replace(message.category, values);

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.options.updated", message.category, values.size()));
			//The echo rereads the registry, which was just replaced two lines above: same content as
			//the array received, without rebuilding it here by hand.
			BrowseActionMessage.sendOptions(dm, BrowseListMessage.Kind.MANAGE_OPTIONS, message.category);
		});
	}
}
