package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.client.DiceRollOverlay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * <p>Server -> client: a d20 was rolled nearby (attack, spell attack, death save), so the screen can show the die
 * tumbling and settling instead of only a line in chat. Purely visual: the rules were already resolved on the
 * server and announced in chat; this carries nothing the client could act on. Sent to the same players who
 * receive the chat line (see {@code CombatFx.diceRoll}).</p>
 */
public class DiceRollMessage {
	final int natural;
	final int total;
	final int kind; //0 = normal, 1 = natural 20, 2 = natural 1.
	final String who;

	public DiceRollMessage(int natural, int total, int kind, String who) {
		this.natural = natural;
		this.total = total;
		this.kind = kind;
		this.who = who;
	}

	public DiceRollMessage(FriendlyByteBuf buffer) {
		this.natural = buffer.readVarInt();
		this.total = buffer.readInt();
		this.kind = buffer.readVarInt();
		this.who = buffer.readUtf(64);
	}

	public static void buffer(DiceRollMessage message, FriendlyByteBuf buffer) {
		buffer.writeVarInt(message.natural);
		buffer.writeInt(message.total);
		buffer.writeVarInt(message.kind);
		buffer.writeUtf(message.who, 64);
	}

	public static void handler(DiceRollMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> DiceRollOverlay.show(message.natural, message.total, message.kind, message.who));
	}
}
