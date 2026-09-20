package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.client.gui.DungeonPieceAddScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Server -> client (the DM): opens the "Add piece" form pre-filled with the id already held by the
//structure block they just right-clicked with the DM Wand (see DungeonToolManager) — without this, the
//DM had to retype by hand the same id they already wrote once when saving the structure.
public class DungeonPieceAddOpenMessage {
	String structureId, suggestedId;

	public DungeonPieceAddOpenMessage(String structureId, String suggestedId) {
		this.structureId = structureId;
		this.suggestedId = suggestedId;
	}

	public DungeonPieceAddOpenMessage(FriendlyByteBuf buffer) {
		this.structureId = buffer.readUtf();
		this.suggestedId = buffer.readUtf();
	}

	public static void buffer(DungeonPieceAddOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.structureId);
		buffer.writeUtf(message.suggestedId);
	}

	public static void handler(DungeonPieceAddOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> DungeonPieceAddScreen.open(message.structureId, message.suggestedId));
	}
}
