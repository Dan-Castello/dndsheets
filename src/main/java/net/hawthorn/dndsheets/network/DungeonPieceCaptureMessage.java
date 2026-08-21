package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;
import java.util.function.Supplier;

//Cliente (el DM) -> servidor: capturar una pieza nueva desde DungeonPieceAddScreen — copia el .nbt ya
//escaneado con el bloque de estructura al datapack de la partida y la registra (ver DungeonManager.capturePiece).
public class DungeonPieceCaptureMessage {
	String id, structureId, pool, tags;
	int weight;

	public DungeonPieceCaptureMessage(String id, String structureId, String pool, int weight, String tags) {
		this.id = id;
		this.structureId = structureId;
		this.pool = pool;
		this.weight = weight;
		this.tags = tags;
	}

	public DungeonPieceCaptureMessage(FriendlyByteBuf buffer) {
		this.id = buffer.readUtf();
		this.structureId = buffer.readUtf();
		this.pool = buffer.readUtf();
		this.weight = buffer.readVarInt();
		this.tags = buffer.readUtf();
	}

	public static void buffer(DungeonPieceCaptureMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.id);
		buffer.writeUtf(message.structureId);
		buffer.writeUtf(message.pool);
		buffer.writeVarInt(message.weight);
		buffer.writeUtf(message.tags);
	}

	public static void handler(DungeonPieceCaptureMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(Component.literal(DungeonManager.poolNameError(message.pool)));
				return;
			}

			int weight = Math.max(1, Math.min(150, message.weight));
			DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(message.id, message.structureId, message.pool, weight, message.tags);
			Optional<String> error = DungeonManager.capturePiece(dm.getServer(), piece);

			if (error.isPresent()) {
				dm.sendSystemMessage(Component.literal(error.get()));
				return;
			}

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.piece_captured", message.id, message.pool));
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
