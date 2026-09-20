package net.hawthorn.dndsheets.dungeon.network;
import net.hawthorn.dndsheets.network.NetworkUtil;

import net.hawthorn.dndsheets.dungeon.DndsheetsDungeonMod;
import net.hawthorn.dndsheets.dungeon.DungeonManager;
import net.hawthorn.dndsheets.dungeon.DungeonPiecePlacer;
import net.hawthorn.dndsheets.dungeon.DungeonPieceRegistry;
import net.hawthorn.dndsheets.dungeon.GridToStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

//Client (the DM) -> server: capture a new piece DRAWN in DungeonTraceScreen, instead of built by hand
//and scanned with a structure block (see DungeonPieceCaptureMessage for that other path). Plants the
//blocks and scans them the same way a structure block does (DungeonPiecePlacer), then delegates
//registration to the same DungeonManager.capturePiece the manual flow uses.
public class DungeonTraceCaptureMessage {
	private static final int MAX_SIDE = 64;

	String id, structureId, pool, tags;
	int weight, height;
	String[] rows;
	//Objects with a block chosen by the DM (from any installed mod): sparse positions instead of a dense
	//grid parallel to "rows" — most OBJECT cells don't change the default block.
	int[] objX, objZ;
	String[] objBlockId;
	//Which way each object faces ("" = not rotated); getSerializedName()/byName, never the ordinal — a
	//Direction crossing the wire by index is exactly the mistake the mod's invariant 2 already documents.
	String[] objFacing;
	//Doors/starts with a destination pool and/or manual direction chosen by the DM — just as sparse as
	//the objects, most DOOR/START cells keep the default behavior (the piece's pool, automatic direction).
	int[] doorX, doorZ;
	String[] doorPool, doorFacing;
	//Wall/floor material for the WHOLE piece, not per cell (see DungeonPiecePlacer.Materials); "" = the
	//DM didn't choose one, use the default block.
	String wallBlockId, floorBlockId;
	//Horizontal offset from the DM's position — the origin is still "where the DM is standing," this
	//just shifts it a few blocks without forcing them to walk to the exact spot.
	int originDx, originDz;

	public DungeonTraceCaptureMessage(String id, String structureId, String pool, int weight, String tags,
			int height, String[] rows, int[] objX, int[] objZ, String[] objBlockId, String[] objFacing,
			int[] doorX, int[] doorZ, String[] doorPool, String[] doorFacing,
			String wallBlockId, String floorBlockId, int originDx, int originDz) {
		this.id = id;
		this.structureId = structureId;
		this.pool = pool;
		this.weight = weight;
		this.tags = tags;
		this.height = height;
		this.rows = rows;
		this.objX = objX;
		this.objZ = objZ;
		this.objBlockId = objBlockId;
		this.objFacing = objFacing;
		this.doorX = doorX;
		this.doorZ = doorZ;
		this.doorPool = doorPool;
		this.doorFacing = doorFacing;
		this.wallBlockId = wallBlockId;
		this.floorBlockId = floorBlockId;
		this.originDx = originDx;
		this.originDz = originDz;
	}

	public DungeonTraceCaptureMessage(FriendlyByteBuf buffer) {
		this.id = buffer.readUtf();
		this.structureId = buffer.readUtf();
		this.pool = buffer.readUtf();
		this.weight = buffer.readVarInt();
		this.tags = buffer.readUtf();
		this.height = buffer.readVarInt();
		this.wallBlockId = buffer.readUtf();
		this.floorBlockId = buffer.readUtf();
		this.originDx = buffer.readVarInt();
		this.originDz = buffer.readVarInt();

		int rowCount = buffer.readVarInt();
		this.rows = new String[rowCount];
		for (int i = 0; i < rowCount; i++) rows[i] = buffer.readUtf();

		int objectCount = buffer.readVarInt();
		this.objX = new int[objectCount];
		this.objZ = new int[objectCount];
		this.objBlockId = new String[objectCount];
		this.objFacing = new String[objectCount];
		for (int i = 0; i < objectCount; i++) {
			objX[i] = buffer.readVarInt();
			objZ[i] = buffer.readVarInt();
			objBlockId[i] = buffer.readUtf();
			objFacing[i] = buffer.readUtf();
		}

		int doorCount = buffer.readVarInt();
		this.doorX = new int[doorCount];
		this.doorZ = new int[doorCount];
		this.doorPool = new String[doorCount];
		this.doorFacing = new String[doorCount];
		for (int i = 0; i < doorCount; i++) {
			doorX[i] = buffer.readVarInt();
			doorZ[i] = buffer.readVarInt();
			doorPool[i] = buffer.readUtf();
			doorFacing[i] = buffer.readUtf();
		}
	}

	public static void buffer(DungeonTraceCaptureMessage message, FriendlyByteBuf buffer) {
		buffer.writeUtf(message.id);
		buffer.writeUtf(message.structureId);
		buffer.writeUtf(message.pool);
		buffer.writeVarInt(message.weight);
		buffer.writeUtf(message.tags);
		buffer.writeVarInt(message.height);
		buffer.writeUtf(message.wallBlockId);
		buffer.writeUtf(message.floorBlockId);
		buffer.writeVarInt(message.originDx);
		buffer.writeVarInt(message.originDz);

		buffer.writeVarInt(message.rows.length);
		for (String row : message.rows) buffer.writeUtf(row);

		buffer.writeVarInt(message.objBlockId.length);
		for (int i = 0; i < message.objBlockId.length; i++) {
			buffer.writeVarInt(message.objX[i]);
			buffer.writeVarInt(message.objZ[i]);
			buffer.writeUtf(message.objBlockId[i]);
			buffer.writeUtf(message.objFacing[i]);
		}

		buffer.writeVarInt(message.doorPool.length);
		for (int i = 0; i < message.doorPool.length; i++) {
			buffer.writeVarInt(message.doorX[i]);
			buffer.writeVarInt(message.doorZ[i]);
			buffer.writeUtf(message.doorPool[i]);
			buffer.writeUtf(message.doorFacing[i]);
		}
	}

	public static void handler(DungeonTraceCaptureMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> {
			if (!DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(DungeonManager.poolNameError(message.pool));
				return;
			}
			if (message.rows.length == 0 || message.rows.length > MAX_SIDE || message.rows[0].length() > MAX_SIDE) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.trace_grid_too_big", MAX_SIDE, MAX_SIDE));
				return;
			}

			GridToStructure.Cell[][] grid;
			try {
				grid = GridToStructure.parse(message.rows);
			} catch (IllegalArgumentException e) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.trace_grid_invalid", e.getMessage()));
				return;
			}

			GridToStructure.CellOptions[][] options = new GridToStructure.CellOptions[grid.length][grid[0].length];
			for (int i = 0; i < message.objBlockId.length; i++) {
				int x = message.objX[i], z = message.objZ[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue; //hand-tampered row, ignored instead of crashing
				options[z][x] = new GridToStructure.CellOptions(
					ResourceLocation.tryParse(message.objBlockId[i]), Direction.byName(message.objFacing[i]), null, null);
			}
			for (int i = 0; i < message.doorPool.length; i++) {
				int x = message.doorX[i], z = message.doorZ[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue;
				//An invalid destination pool doesn't blow up the whole capture: just that one door is
				//ignored and falls back to the piece's pool, same as if the DM had never typed anything there.
				String targetPool = message.doorPool[i].isEmpty() || !DungeonManager.isValidPoolName(message.doorPool[i])
					? null : message.doorPool[i];
				options[z][x] = new GridToStructure.CellOptions(null, null, targetPool, Direction.byName(message.doorFacing[i]));
			}

			int height = Math.max(1, Math.min(10, message.height));
			int weight = Math.max(1, Math.min(150, message.weight));
			List<GridToStructure.PlacedBlock> blocks = GridToStructure.render(grid, height, options);

			DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(message.id, message.structureId, message.pool, weight, message.tags);
			DungeonPiecePlacer.Materials materials = new DungeonPiecePlacer.Materials(
				ResourceLocation.tryParse(message.wallBlockId), ResourceLocation.tryParse(message.floorBlockId));
			int dx = Math.max(-16, Math.min(16, message.originDx));
			int dz = Math.max(-16, Math.min(16, message.originDz));
			BlockPos origin = BlockPos.containing(dm.position()).offset(dx, 0, dz);
			Optional<String> error = DungeonPiecePlacer.placeAndCapture(dm.serverLevel(), origin, piece, blocks, materials);

			if (error.isPresent()) {
				dm.sendSystemMessage(Component.literal(error.get()));
				return;
			}

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.piece_captured", message.id, message.pool));
			DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		});
	}
}
