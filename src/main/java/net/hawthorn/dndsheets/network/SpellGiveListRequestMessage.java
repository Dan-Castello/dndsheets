package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: eligió a quién enseñar un hechizo en PlayerPickerScreen, pide la lista de
//hechizos cargados (el registro solo vive en memoria del servidor) para abrir SpellGiveListScreen.
public class SpellGiveListRequestMessage {
	String targetUuid;

	public SpellGiveListRequestMessage(String targetUuid) {
		this.targetUuid = targetUuid;
	}

	public SpellGiveListRequestMessage(FriendlyByteBuf buffer) {
		this.targetUuid = buffer.readUtf();
	}

	public static void buffer(SpellGiveListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.targetUuid);
	}

	public static void handler(SpellGiveListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, (ServerPlayer dm) -> {
			List<String> ids = new ArrayList<>(SpellRegistry.ids());
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new SpellGiveListMessage(message.targetUuid, ids));
		});
	}
}
