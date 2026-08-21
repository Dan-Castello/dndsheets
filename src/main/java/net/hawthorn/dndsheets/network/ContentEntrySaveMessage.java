package net.hawthorn.dndsheets.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.io.IOException;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: guarda (crea o edita, mismo id = pisa) una entrada en dm_created.json de un
//tipo, desde ContentFormScreen. El JSON ya viene armado por el cliente (ver ContentFormScreen.buildJson) —
//el servidor solo valida "id" y delega en el loadFile de siempre del tipo para interpretarlo.
public class ContentEntrySaveMessage {
	ContentType type;
	String entryJson;

	public ContentEntrySaveMessage(ContentType type, String entryJson) {
		this.type = type;
		this.entryJson = entryJson;
	}

	public ContentEntrySaveMessage(FriendlyByteBuf buffer) {
		this.type = buffer.readEnum(ContentType.class);
		this.entryJson = buffer.readUtf(32767);
	}

	public static void buffer(ContentEntrySaveMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.type);
		buffer.writeUtf(message.entryJson, 32767);
	}

	public static void handler(ContentEntrySaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {

			JsonObject entry;
			try {
				entry = JsonParser.parseString(message.entryJson).getAsJsonObject();
			} catch (RuntimeException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.form_unreadable", e.getMessage()));
				return;
			}
			if (!entry.has("id") || entry.get("id").getAsString().isBlank()) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.missing_id"));
				return;
			}

			try {
				ContentPackFile.upsert(message.type.dmCreatedFile(), "id", entry);
				//Recarga SOLO dm_created.json, no todo el registro — un pack cargado a mano aparte con
				///dnd... load no se toca ni se repite acá.
				message.type.load(message.type.dmCreatedFile());
			} catch (IOException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.save_failed", e.getMessage()));
				return;
			}

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.saved", entry.get("id").getAsString()));
			String arrayJson = ContentPackFile.readArrayText(message.type.dmCreatedFile());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new ContentEntryListMessage(message.type, arrayJson));
		});
	}
}
