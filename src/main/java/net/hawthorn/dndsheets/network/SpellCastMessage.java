package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.SpellCastManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client -> server: the player pressed "Cast" on a known spell in their Spellbook.
public class SpellCastMessage {
	String spellId;
	/**
	 * Slot level chosen to cast it, or 0 for "the lowest that works". It travels in the message rather
	 * than being decided server-side because upcasting a spell is a player DECISION: spending a 5th-level
	 * slot on a Fireball in exchange for more dice is exactly what the server can't guess on its own.
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
