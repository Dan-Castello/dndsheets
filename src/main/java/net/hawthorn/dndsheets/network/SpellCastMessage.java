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
	/**
	 * Nivel de espacio elegido para lanzarlo, o 0 para "el más bajo que sirva". Va en el mensaje y no se
	 * decide en el servidor porque subir de nivel un conjuro es una DECISIÓN del jugador: gastar un espacio
	 * de 5º en una Bola de Fuego a cambio de más dados es exactamente lo que el servidor no puede adivinar.
	 */
	int slotLevel;

	public SpellCastMessage(String spellId) {
		this(spellId, 0);
	}

	public SpellCastMessage(String spellId, int slotLevel) {
		this.spellId = spellId;
		this.slotLevel = slotLevel;
	}

	public SpellCastMessage(FriendlyByteBuf buffer) {
		this.spellId = buffer.readUtf();
		this.slotLevel = buffer.readVarInt();
	}

	public static void buffer(SpellCastMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.spellId);
		buffer.writeVarInt(message.slotLevel);
	}

	public static void handler(SpellCastMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player != null) SpellCastManager.handleCastRequest(player, message.spellId, message.slotLevel);
		});
	}
}
