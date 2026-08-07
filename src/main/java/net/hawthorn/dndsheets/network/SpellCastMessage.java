package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellCastManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente -> servidor: el jugador pulsó "Lanzar" sobre un hechizo conocido en su Grimorio.
public class SpellCastMessage {
	String spellId;

	public SpellCastMessage(String spellId) {
		this.spellId = spellId;
	}

	public SpellCastMessage(FriendlyByteBuf buffer) {
		this.spellId = buffer.readUtf();
	}

	public static void buffer(SpellCastMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.spellId);
	}

	public static void handler(SpellCastMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		context.enqueueWork(() -> {
			ServerPlayer player = context.getSender();
			if (player != null) SpellCastManager.handleCastRequest(player, message.spellId);
		});
		context.setPacketHandled(true);
	}
}
