package net.hawthorn.dndsheets.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Legacy, kept only so the message ids after it keep their numbers (invariant 1): the formula editor is now a client
 * screen ({@code RollFormulaScreen}) and needs no container menu, so this message does nothing.</p>
 */
public class RollEditorOpenMessage {

	public RollEditorOpenMessage() {
	}

	public RollEditorOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(RollEditorOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(RollEditorOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		contextSupplier.get().setPacketHandled(true);
	}
}
