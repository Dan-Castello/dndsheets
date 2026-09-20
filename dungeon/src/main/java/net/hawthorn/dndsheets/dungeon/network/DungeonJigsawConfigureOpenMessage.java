package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.client.gui.DungeonJigsawConfigureScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Server -> client (the DM): opens the form to configure the jigsaw block they just right-clicked
//with the DM Wand, prefilled with whatever it already had saved (see DungeonToolManager).
public class DungeonJigsawConfigureOpenMessage {
	BlockPos pos;
	String currentPool;
	boolean currentIsStart;

	public DungeonJigsawConfigureOpenMessage(BlockPos pos, String currentPool, boolean currentIsStart) {
		this.pos = pos;
		this.currentPool = currentPool;
		this.currentIsStart = currentIsStart;
	}

	public DungeonJigsawConfigureOpenMessage(FriendlyByteBuf buffer) {
		this.pos = buffer.readBlockPos();
		this.currentPool = buffer.readUtf();
		this.currentIsStart = buffer.readBoolean();
	}

	public static void buffer(DungeonJigsawConfigureOpenMessage message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.pos);
		buffer.writeUtf(message.currentPool);
		buffer.writeBoolean(message.currentIsStart);
	}

	public static void handler(DungeonJigsawConfigureOpenMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnClient(context, () -> DungeonJigsawConfigureScreen.open(message.pos, message.currentPool, message.currentIsStart));
	}
}
