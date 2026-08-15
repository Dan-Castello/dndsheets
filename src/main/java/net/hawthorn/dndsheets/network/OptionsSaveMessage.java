package net.hawthorn.dndsheets.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.DndPaths;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: reemplaza la lista COMPLETA de una categoría (race/background/class) desde
//OptionsManageScreen — CharacterOptionsRegistry.loadFile reemplaza, no fusiona (ver su javadoc), así que
//"añadir una opción" es en realidad "guardar la lista entera con la opción de más/menos".
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
			case CharacterOptionsRegistry.RACE -> DndPaths.RACES_DIR;
			case CharacterOptionsRegistry.BACKGROUND -> DndPaths.BACKGROUNDS_DIR;
			case CharacterOptionsRegistry.CLASS -> DndPaths.CLASSES_DIR;
			default -> null;
		};
	}

	public static void handler(OptionsSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			Path dir = dirFor(message.category);
			if (dir == null) return;

			List<String> values = new ArrayList<>();
			for (JsonElement el : JsonParser.parseString(message.arrayJson).getAsJsonArray()) values.add(el.getAsString());

			try {
				ContentPackFile.writeStringArray(DndPaths.dmCreatedFile(dir), values);
			} catch (IOException e) {
				dm.sendSystemMessage(Component.literal("No pude guardar: " + e.getMessage()));
				return;
			}
			CharacterOptionsRegistry.replace(message.category, values);

			dm.sendSystemMessage(Component.literal("Lista de " + message.category + " actualizada (" + values.size() + " opciones)."));
			JsonArray echo = new JsonArray();
			for (String value : values) echo.add(value);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new OptionsListMessage(message.category, echo.toString()));
		});
	}
}
