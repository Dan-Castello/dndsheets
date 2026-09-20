package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

//Encapsulates the framework boilerplate repeated in every message handler (enqueueWork+setPacketHandled,
//and DistExecutor for ones that should only run on the client) — see finding F13. Doesn't apply
//to encode/decode: fields differ too much between messages to generalize them with generics without
//losing readability.
public final class NetworkUtil {
	private NetworkUtil() {}

	public static void handleOnServer(NetworkEvent.Context context, Runnable action) {
		context.enqueueWork(action);
		context.setPacketHandled(true);
	}

	/**
	 * <p>Like {@link #handleOnServer}, but only for whoever is DM: resolves the sender, checks the
	 * operator permission, and passes the already-validated player. If they aren't one, the packet is
	 * discarded without doing anything.</p>
	 *
	 * <p>Exists because the guard was copied <b>22 times</b>, word for word, across messages that only a
	 * DM should be able to send. And that isn't ugliness: it's a permission check that has to be
	 * remembered. A new message that forgets it doesn't fail or warn — it lets any player delete dungeon
	 * pieces, spawn monsters, or edit content, because the client can send the packet regardless of
	 * whether the menu is even open. Here it can't be forgotten: either you request the player through
	 * this door, or you don't have one.</p>
	 */
	public static void handleOnServerAsDm(NetworkEvent.Context context, java.util.function.Consumer<net.minecraft.server.level.ServerPlayer> action) {
		handleOnServer(context, () -> {
			net.minecraft.server.level.ServerPlayer dm = context.getSender();
			if (dm == null) return;
			//A notice is sent instead of discarding silently: the DM Panel is deliberately opened for
			//anyone (the client has no way to know whether Solo mode is on — see DndsheetsModKeyMappings),
			//so without this every button "did nothing" for a player without permission, which reads as a
			//broken mod.
			if (!DndsheetsMod.canActAsDm(dm)) {
				dm.sendSystemMessage(net.minecraft.network.chat.Component
					.translatable("chat.dndsheets.dm_required").withStyle(net.minecraft.ChatFormatting.RED));
				return;
			}
			action.accept(dm);
		});
	}

	public static void handleOnClient(NetworkEvent.Context context, Runnable action) {
		context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> action::run));
		context.setPacketHandled(true);
	}
}
