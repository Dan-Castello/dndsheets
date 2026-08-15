package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.ContentPackFile;
import net.hawthorn.dndsheets.ContentType;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: pide las entradas de dm_created.json de un tipo, para abrir la lista del
//creador de contenido (ver ContentEntryListMessage) — el cliente no tiene acceso al disco del servidor.
public class ContentEntryListRequestMessage {
	ContentType type;

	public ContentEntryListRequestMessage(ContentType type) {
		this.type = type;
	}

	public ContentEntryListRequestMessage(FriendlyByteBuf buffer) {
		this.type = buffer.readEnum(ContentType.class);
	}

	public static void buffer(ContentEntryListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.type);
	}

	public static void handler(ContentEntryListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;

			String arrayJson = ContentPackFile.readArrayText(message.type.dmCreatedFile());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new ContentEntryListMessage(message.type, arrayJson));
		});
	}
}
