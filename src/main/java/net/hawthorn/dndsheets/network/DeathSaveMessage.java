package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DeathSaveManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>The two buttons on the death saves window, in one message with an enum instead of two classes with
 * not even a single field, whose handlers only differed in which method they called (invariant 3).</p>
 *
 * <ul>
 *   <li>{@code ROLL} — "Roll death save".</li>
 *   <li>{@code GIVE_UP} — "Let yourself die": kills instantly, same path as 3 failures.</li>
 * </ul>
 *
 * <p>Both go from client to server and neither carries who it affects: it's always whoever sent the
 * packet. A modified client can only roll for — or give up — its own character, and {@code
 * DeathSaveManager} additionally checks that they're actually down.</p>
 */
public class DeathSaveMessage {

	//At the end, never in the middle: writeEnum travels by ordinal (see invariant 2 in PROJECT_CONTEXT.md).
	public enum Kind { ROLL, GIVE_UP }

	final Kind kind;

	public DeathSaveMessage(Kind kind) {
		this.kind = kind;
	}

	public DeathSaveMessage(FriendlyByteBuf buffer) {
		this.kind = buffer.readEnum(Kind.class);
	}

	public static void buffer(DeathSaveMessage message, FriendlyByteBuf buffer) {
		buffer.writeEnum(message.kind);
	}

	public static void handler(DeathSaveMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer player = context.getSender();
			if (player == null) return;
			switch (message.kind) {
				case ROLL -> DeathSaveManager.handleRollRequest(player);
				case GIVE_UP -> DeathSaveManager.handleGiveUpRequest(player);
			}
		});
	}
}
