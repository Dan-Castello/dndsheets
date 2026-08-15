package net.hawthorn.dndsheets.network;

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

//Cliente (el DM) -> servidor: borra una entrada de dm_created.json de un tipo, desde ContentEntryListScreen.
//Solo borra entradas creadas in-game (viven en dm_created.json) — un pack cargado a mano aparte no se toca.
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
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			try {
				if (!ContentPackFile.removeById(message.type.dmCreatedFile(), "id", message.id)) {
					dm.sendSystemMessage(Component.literal("\"" + message.id + "\" no estaba en el contenido creado in-game."));
					return;
				}
			} catch (IOException e) {
				dm.sendSystemMessage(Component.literal("No pude borrar: " + e.getMessage()));
				return;
			}
			message.type.remove(message.id);

			dm.sendSystemMessage(Component.literal("Borrado \"" + message.id + "\"."));
			String arrayJson = ContentPackFile.readArrayText(message.type.dmCreatedFile());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new ContentEntryListMessage(message.type, arrayJson));
		});
	}
}
