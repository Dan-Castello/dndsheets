package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.PresetManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador eligió un preset en el selector; se aplica a su propia hoja.
public class PresetApplyMessage {
	String presetId;

	public PresetApplyMessage(String presetId) {
		this.presetId = presetId;
	}

	public PresetApplyMessage(FriendlyByteBuf buffer) {
		this.presetId = buffer.readUtf();
	}

	public static void buffer(PresetApplyMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.presetId);
	}

	public static void handler(PresetApplyMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player != null) PresetManager.applyPreset(player, message.presetId);
		});
		context.setPacketHandled(true);
	}
}
