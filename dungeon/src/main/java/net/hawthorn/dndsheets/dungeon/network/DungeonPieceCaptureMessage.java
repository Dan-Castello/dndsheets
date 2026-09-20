package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.Optional;
import java.util.function.Supplier;

//Client (the DM) -> server: capture a new piece from DungeonPieceAddScreen — copies the .nbt already
//scanned with the structure block into the game's datapack and registers it (see DungeonManager.capturePiece).
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
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage((DungeonManager.poolNameError(message.pool)));
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
			DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
