package net.hawthorn.dndsheets.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Legacy, kept only so the message ids after it keep their numbers (invariant 1): the character sheet is now a client
 * screen opened by the H key ({@code PlayerSheetScreen}) and needs no container menu, so this message does nothing.</p>
 */
public class CharacterSheetOpenMessage {

	public CharacterSheetOpenMessage() {
	}

	public CharacterSheetOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(CharacterSheetOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(CharacterSheetOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		contextSupplier.get().setPacketHandled(true);
	}
}
