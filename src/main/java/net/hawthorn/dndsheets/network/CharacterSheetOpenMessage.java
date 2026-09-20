package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.world.inventory.CharacterSheetMenu;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkHooks;

import java.util.function.Supplier;

/**
 * <p>Client -&gt; server: opens the character sheet, or closes it if it was already open (the H key and
 * the "Sheet" button on both roll editors toggle it with the same message).</p>
 *
 * <p>No longer carries the {@code type} and {@code pressedms} fields that MCreator generated: all three
 * callers sent {@code (0, 0)} and the handler only had a branch for {@code type == 0}. Removing them
 * changes what travels over the wire, so it bumps {@code PROTOCOL_VERSION} — see invariant 1.</p>
 */
public class CharacterSheetOpenMessage {

	public CharacterSheetOpenMessage() {
	}

	public CharacterSheetOpenMessage(FriendlyByteBuf buffer) {
	}

	public static void buffer(CharacterSheetOpenMessage message, FriendlyByteBuf buffer) {
	}

	public static void handler(CharacterSheetOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> pressAction(context.getSender()));
	}

	/**
	 * <p>Public and typed as {@code Player} (not {@code ServerPlayer}) because the client also calls it
	 * with its own player right after sending the packet (see {@code DndsheetsModKeyMappings}): that way
	 * closing is seen instantly instead of waiting on the round trip. On the client it only ever enters
	 * through the close branch; opening is the server's job, since it's the one holding the {@code
	 * ServerPlayer}.</p>
	 */
	public static void pressAction(Player entity) {
		if (entity == null) return;

		if (entity.containerMenu instanceof CharacterSheetMenu) {
			entity.closeContainer();
		} else if (entity instanceof ServerPlayer player) {
			//No BlockPos: the screen doesn't depend on where it was opened. That also drops the
			//"hasChunkAt" guard the template used to carry — it protected against generating a chunk at
			//arbitrary coordinates, and here there's no coordinate left to pass at all.
			NetworkHooks.openScreen(player, new SimpleMenuProvider(
				(id, inventory, viewer) -> new CharacterSheetMenu(id, inventory, null), Component.literal("CharacterSheet")));
		}
	}
}
