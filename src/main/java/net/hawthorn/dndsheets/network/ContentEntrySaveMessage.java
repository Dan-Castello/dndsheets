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

//Client (the DM) -> server: saves (creates or edits, same id = overwrite) an entry in a type's
//dm_created.json, from ContentFormScreen. The JSON arrives already built by the client (see
//ContentFormScreen.buildJson) — the server only validates "id" and delegates to the type's usual loadFile
//to interpret it.
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
			//Content commands read their id with ResourceLocationArgument, so an id with uppercase letters
			//or spaces ("Goblin Ambush") saves fine and afterward CANNOT be named: Brigadier rejects it
			//while parsing, and the DM Panel button that sends that command does nothing and explains
			//nothing. It's cut off here, while the DM is still looking at the form.
			String id = entry.get("id").getAsString();
			if (!net.minecraft.resources.ResourceLocation.isValidResourceLocation(id)) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.bad_id", id));
				return;
			}

			try {
				ContentPackFile.upsert(message.type.dmCreatedFile(), "id", entry);
				//Reloads ONLY dm_created.json, not the whole registry — a pack loaded manually with
				///dnd... load is neither touched nor redone here.
				message.type.load(message.type.dmCreatedFile());
			} catch (IOException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.save_failed", e.getMessage()));
				return;
			}

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.saved", entry.get("id").getAsString()));
			BrowseActionMessage.sendContentEntries(dm, message.type.name());
		});
	}
}
