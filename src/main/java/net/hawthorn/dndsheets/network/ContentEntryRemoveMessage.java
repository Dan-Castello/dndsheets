package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.ContentType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;

import java.io.IOException;
import java.util.function.Supplier;

//Client (the DM) -> server: deletes an entry of a type from dm_created.json, from ContentEntryListScreen.
//Only deletes entries created in-game (they live in dm_created.json) — a separately hand-loaded pack is left alone.
public class ContentEntryRemoveMessage {
	ContentType type;
	String id;

	public ContentEntryRemoveMessage(ContentType type, String id) {
		this.type = type;
		this.id = id;
	}

	public ContentEntryRemoveMessage(FriendlyByteBuf buffer) {
		this.type = buffer.readEnum(ContentType.class);
		this.id = buffer.readUtf();
	}

	public static void buffer(ContentEntryRemoveMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.type);
		buffer.writeUtf(message.id);
	}

	public static void handler(ContentEntryRemoveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {

			try {
				if (!ContentPackFile.removeById(message.type.dmCreatedFile(), "id", message.id)) {
					dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.not_present", message.id));
					return;
				}
			} catch (IOException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.delete_failed", e.getMessage()));
				return;
			}
			message.type.remove(message.id);

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.content.deleted", message.id));
			BrowseActionMessage.sendContentEntries(dm, message.type.name());
		});
	}
}
