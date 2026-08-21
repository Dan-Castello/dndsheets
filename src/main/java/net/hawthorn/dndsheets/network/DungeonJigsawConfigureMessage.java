package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DungeonManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: aplica pool/inicio elegidos en DungeonJigsawConfigureScreen al jigsaw block
//en pos, escribiendo Name/Target/Pool/Joint directo (ver DungeonManager.configureJigsaw).
public class DungeonJigsawConfigureMessage {
	BlockPos pos;
	String pool;
	boolean isStart;

	public DungeonJigsawConfigureMessage(BlockPos pos, String pool, boolean isStart) {
		this.pos = pos;
		this.pool = pool;
		this.isStart = isStart;
	}

	public DungeonJigsawConfigureMessage(FriendlyByteBuf buffer) {
		this.pos = buffer.readBlockPos();
		this.pool = buffer.readUtf();
		this.isStart = buffer.readBoolean();
	}

	public static void buffer(DungeonJigsawConfigureMessage message, FriendlyByteBuf buffer) {
		buffer.writeBlockPos(message.pos);
		buffer.writeUtf(message.pool);
		buffer.writeBoolean(message.isStart);
	}

	public static void handler(DungeonJigsawConfigureMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(Component.literal(DungeonManager.poolNameError(message.pool)));
				return;
			}

			if (!(dm.level().getBlockEntity(message.pos) instanceof JigsawBlockEntity jigsaw)) return;

			DungeonManager.configureJigsaw(jigsaw, message.pool, message.isStart);
			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.jigsaw_set", message.pool, (message.isStart ? " (pieza de inicio)." : ".")));
		});
	}
}
