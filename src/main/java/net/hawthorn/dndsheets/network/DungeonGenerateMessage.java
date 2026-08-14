package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DungeonManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: publica los pools y genera la mazmorra en la posición pedida, desde
//DungeonGenerateScreen (equivalente en GUI a /dnddungeon generate).
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
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(Component.literal("\"" + message.pool + "\" no es un nombre de pool válido."));
				return;
			}

			int maxDepth = Math.max(1, Math.min(7, message.maxDepth));
			boolean success = DungeonManager.generate(dm, message.pool, maxDepth, message.pos);
			//DungeonManager.generate ya le manda al DM el motivo del fallo — aquí solo falta confirmar el éxito.
			if (success) dm.sendSystemMessage(Component.literal("Mazmorra generada en " + message.pos.toShortString() + "."));
		});
	}
}
