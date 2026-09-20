package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Client (the DM) -> server: publishes the pools and generates the dungeon at the requested position,
//from DungeonGenerateScreen (the GUI equivalent of /dnddungeon generate).
public class DungeonGenerateMessage {
	String pool;
	int maxDepth;
	BlockPos pos;

	public DungeonGenerateMessage(String pool, int maxDepth, BlockPos pos) {
		this.pool = pool;
		this.maxDepth = maxDepth;
		this.pos = pos;
	}

	public DungeonGenerateMessage(FriendlyByteBuf buffer) {
		this.pool = buffer.readUtf();
		this.maxDepth = buffer.readVarInt();
		this.pos = buffer.readBlockPos();
	}

	public static void buffer(DungeonGenerateMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.pool);
		buffer.writeVarInt(message.maxDepth);
		buffer.writeBlockPos(message.pos);
	}

	public static void handler(DungeonGenerateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage((DungeonManager.poolNameError(message.pool)));
				return;
			}

			int maxDepth = Math.max(1, Math.min(7, message.maxDepth));
			boolean success = DungeonManager.generate(dm, message.pool, maxDepth, message.pos);
			//DungeonManager.generate already sends the DM the failure reason — only success needs confirming here.
			if (success) dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.generated", message.pos.toShortString()));
		});
	}
}
