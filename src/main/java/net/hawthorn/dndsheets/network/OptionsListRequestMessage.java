package net.hawthorn.dndsheets.network;

import com.google.gson.JsonArray;
import net.hawthorn.dndsheets.CharacterOptionsRegistry;
import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: pide la lista VIVA (no un archivo puntual) de una categoría de
//CharacterOptionsRegistry (race/background/class), para abrir OptionsManageScreen.
public class OptionsListRequestMessage {
	String category;

	public OptionsListRequestMessage(String category) {
		this.category = category;
	}

	public OptionsListRequestMessage(FriendlyByteBuf buffer) {
		this.category = buffer.readUtf();
	}

	public static void buffer(OptionsListRequestMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.category);
	}

	public static void handler(OptionsListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (!CharacterOptionsRegistry.isValidCategory(message.category)) return;

			JsonArray array = new JsonArray();
			for (String value : CharacterOptionsRegistry.get(message.category)) array.add(value);
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), new OptionsListMessage(message.category, array.toString()));
		});
	}
}
