package net.hawthorn.dndsheets.network;

import net.hawthorn.dndsheets.DndsheetsMod;
import net.hawthorn.dndsheets.DungeonManager;
import net.hawthorn.dndsheets.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.function.Supplier;

//Cliente (el DM) -> servidor: edita pool/peso/tags de una pieza ya capturada, desde DungeonPieceEditScreen
//— structureId no se toca (eso solo se fija al capturar, ver DungeonPieceCaptureMessage).
public class DungeonPieceUpdateMessage {
	String id, pool, tags;
	int weight;

	public DungeonPieceUpdateMessage(String id, String pool, int weight, String tags) {
		this.id = id;
		this.pool = pool;
		this.weight = weight;
		this.tags = tags;
	}

	public DungeonPieceUpdateMessage(FriendlyByteBuf buffer) {
		this.id = buffer.readUtf();
		this.pool = buffer.readUtf();
		this.weight = buffer.readVarInt();
		this.tags = buffer.readUtf();
	}

	public static void buffer(DungeonPieceUpdateMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.id);
		buffer.writeUtf(message.pool);
		buffer.writeVarInt(message.weight);
		buffer.writeUtf(message.tags);
	}

	public static void handler(DungeonPieceUpdateMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServer(context, () -> {
			ServerPlayer dm = context.getSender();
			if (dm == null || !dm.hasPermissions(2)) return;
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(Component.literal(DungeonManager.poolNameError(message.pool)));
				return;
			}

			DungeonPieceRegistry.DungeonPiece existing = DungeonPieceRegistry.get(message.id);
			if (existing == null) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.no_such_piece", message.id));
				return;
			}

			int weight = Math.max(1, Math.min(150, message.weight));
			DungeonPieceRegistry.register(new DungeonPieceRegistry.DungeonPiece(existing.id(), existing.structureId(), message.pool, weight, message.tags));
			DungeonPieceRegistry.save(dm.getServer());

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.piece_updated", message.id));
			DndsheetsMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
