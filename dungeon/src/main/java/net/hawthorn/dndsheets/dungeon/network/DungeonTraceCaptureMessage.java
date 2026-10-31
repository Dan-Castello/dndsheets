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
	//Imported dungeons are this big (see DungeonMapImporter); drawn rooms are 24 at most.
	private static final int MAX_SIDE = 256;
	private static final int MAX_LEVELS = 4;

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
	//Painted wall/floor cells (block per cell, see GridToStructure.CellOptions.material): sparse like the objects.
	int[] matX, matZ;
	String[] matBlockId;
	//Horizontal offset from the DM's position — the origin is still "where the DM is standing," this
	//just shifts it a few blocks without forcing them to walk to the exact spot.
	int originDx, originDz;
	//Absolute corner chosen in the world preview (see DungeonTracePreview); null = the DM's position.
	BlockPos anchor;
	//Wall height per level (storey). The levels' rows travel one after the other in "rows", and the sparse
	//z coordinates count across all of them (level * side + z). Empty = one level of "height".
	int[] layerHeights;
	//Build it in the world and stop there: no structure saved, no piece registered, doors left as openings.
	boolean pasteOnly;
	//Carved wall columns: sparse (x, z, bitmask of heights left open), z counted across levels like the rest.
	int[] holeX, holeZ, holeMask;
	//Vertical offset (the drawing was started above or below where the room should go).
	int originDy;
	//Block closing each door's doorway, parallel to doorPool ("" = left open).
	String[] doorBlockId;
	//Single wall blocks with their own block (stripes, patterns): sparse (x, z, height, block id).
	int[] wallBlockX, wallBlockZ, wallBlockY;
	String[] wallBlockIds;
	//Whole-dungeon extras (GridToStructure.Extras): a ceiling, the inside carved out, a light every
	//lightSpacing cells (0 = none, "" = the default light), and a sign per note (sparse; z = level 0 rows).
	boolean ceiling, carve;
	String lightBlockId;
	int lightSpacing;
	int[] noteX, noteZ;
	String[] noteText;
	//How far from the DM an anchored piece may be built: the preview is walked around, not teleported to.
	private static final int MAX_ANCHOR_DISTANCE = 64;

	public DungeonTraceCaptureMessage(String id, String structureId, String pool, int weight, String tags,
			int height, String[] rows, int[] objX, int[] objZ, String[] objBlockId, String[] objFacing,
			int[] doorX, int[] doorZ, String[] doorPool, String[] doorFacing,
			String wallBlockId, String floorBlockId, int originDx, int originDz, int[] matX, int[] matZ, String[] matBlockId, BlockPos anchor,
			int[] layerHeights, boolean pasteOnly, int[] holeX, int[] holeZ, int[] holeMask,
			int originDy, String[] doorBlockId, int[] wallBlockX, int[] wallBlockZ, int[] wallBlockY, String[] wallBlockIds,
			boolean ceiling, boolean carve, String lightBlockId, int lightSpacing, int[] noteX, int[] noteZ, String[] noteText) {
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
		this.matX = matX;
		this.matZ = matZ;
		this.matBlockId = matBlockId;
		this.anchor = anchor;
		this.layerHeights = layerHeights;
		this.pasteOnly = pasteOnly;
		this.holeX = holeX;
		this.holeZ = holeZ;
		this.holeMask = holeMask;
		this.originDy = originDy;
		this.doorBlockId = doorBlockId;
		this.wallBlockX = wallBlockX;
		this.wallBlockZ = wallBlockZ;
		this.wallBlockY = wallBlockY;
		this.wallBlockIds = wallBlockIds;
		this.ceiling = ceiling;
		this.carve = carve;
		this.lightBlockId = lightBlockId;
		this.lightSpacing = lightSpacing;
		this.noteX = noteX;
		this.noteZ = noteZ;
		this.noteText = noteText;
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

		//Last in the payload: new fields only ever go at the end (invariant 2).
		int materialCount = buffer.readVarInt();
		this.matX = new int[materialCount];
		this.matZ = new int[materialCount];
		this.matBlockId = new String[materialCount];
		for (int i = 0; i < materialCount; i++) {
			matX[i] = buffer.readVarInt();
			matZ[i] = buffer.readVarInt();
			matBlockId[i] = buffer.readUtf();
		}
		this.anchor = buffer.readBoolean() ? buffer.readBlockPos() : null;
		this.layerHeights = buffer.readVarIntArray(MAX_LEVELS);
		this.pasteOnly = buffer.readBoolean();
		int maxCells = MAX_SIDE * MAX_SIDE * MAX_LEVELS;
		this.holeX = buffer.readVarIntArray(maxCells);
		this.holeZ = buffer.readVarIntArray(maxCells);
		this.holeMask = buffer.readVarIntArray(maxCells);
		this.originDy = buffer.readVarInt();
		this.doorBlockId = buffer.readList(FriendlyByteBuf::readUtf).toArray(new String[0]);
		this.wallBlockX = buffer.readVarIntArray(maxCells * 10);
		this.wallBlockZ = buffer.readVarIntArray(maxCells * 10);
		this.wallBlockY = buffer.readVarIntArray(maxCells * 10);
		this.wallBlockIds = buffer.readList(FriendlyByteBuf::readUtf).toArray(new String[0]);
		this.ceiling = buffer.readBoolean();
		this.carve = buffer.readBoolean();
		this.lightBlockId = buffer.readUtf();
		this.lightSpacing = buffer.readVarInt();
		this.noteX = buffer.readVarIntArray(maxCells);
		this.noteZ = buffer.readVarIntArray(maxCells);
		this.noteText = buffer.readList(FriendlyByteBuf::readUtf).toArray(new String[0]);
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

		buffer.writeVarInt(message.matBlockId.length);
		for (int i = 0; i < message.matBlockId.length; i++) {
			buffer.writeVarInt(message.matX[i]);
			buffer.writeVarInt(message.matZ[i]);
			buffer.writeUtf(message.matBlockId[i]);
		}
		buffer.writeBoolean(message.anchor != null);
		if (message.anchor != null) buffer.writeBlockPos(message.anchor);
		buffer.writeVarIntArray(message.layerHeights);
		buffer.writeBoolean(message.pasteOnly);
		buffer.writeVarIntArray(message.holeX);
		buffer.writeVarIntArray(message.holeZ);
		buffer.writeVarIntArray(message.holeMask);
		buffer.writeVarInt(message.originDy);
		buffer.writeCollection(java.util.Arrays.asList(message.doorBlockId), FriendlyByteBuf::writeUtf);
		buffer.writeVarIntArray(message.wallBlockX);
		buffer.writeVarIntArray(message.wallBlockZ);
		buffer.writeVarIntArray(message.wallBlockY);
		buffer.writeCollection(java.util.Arrays.asList(message.wallBlockIds), FriendlyByteBuf::writeUtf);
		buffer.writeBoolean(message.ceiling);
		buffer.writeBoolean(message.carve);
		buffer.writeUtf(message.lightBlockId);
		buffer.writeVarInt(message.lightSpacing);
		buffer.writeVarIntArray(message.noteX);
		buffer.writeVarIntArray(message.noteZ);
		buffer.writeCollection(java.util.Arrays.asList(message.noteText), FriendlyByteBuf::writeUtf);
	}

	public static void handler(DungeonTraceCaptureMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
		NetworkEvent.Context context = contextSupplier.get();
		NetworkUtil.handleOnServerAsDm(context, dm -> handle(dm, message));
	}

	/** The capture itself; also reached through {@link DungeonTraceChunkMessage}, which is how the editor sends it now. */
	static void handle(net.minecraft.server.level.ServerPlayer dm, DungeonTraceCaptureMessage message) {
		{
			//A pasted room isn't a piece: it has no pool to validate.
			if (!message.pasteOnly && !DungeonManager.isValidPoolName(message.pool)) {
				dm.sendSystemMessage(DungeonManager.poolNameError(message.pool));
				return;
			}
			int levelCount = Math.max(1, message.layerHeights.length);
			if (message.rows.length == 0 || message.rows.length % levelCount != 0
					|| message.rows.length / levelCount > MAX_SIDE || message.rows[0].length() > MAX_SIDE) {
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
				String doorBlock = i < message.doorBlockId.length ? message.doorBlockId[i] : "";
				options[z][x] = new GridToStructure.CellOptions(doorBlock.isEmpty() ? null : ResourceLocation.tryParse(doorBlock), null,
					targetPool, Direction.byName(message.doorFacing[i]));
			}

			for (int i = 0; i < message.matBlockId.length; i++) {
				int x = message.matX[i], z = message.matZ[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue;
				options[z][x] = GridToStructure.CellOptions.painted(ResourceLocation.tryParse(message.matBlockId[i]));
			}

			for (int i = 0; i < Math.min(message.holeMask.length, Math.min(message.holeX.length, message.holeZ.length)); i++) {
				int x = message.holeX[i], z = message.holeZ[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue;
				GridToStructure.CellOptions old = options[z][x] != null ? options[z][x] : GridToStructure.CellOptions.EMPTY;
				options[z][x] = old.withHoles(message.holeMask[i]);
			}

			int wallBlockCount = Math.min(Math.min(message.wallBlockX.length, message.wallBlockZ.length), Math.min(message.wallBlockY.length, message.wallBlockIds.length));
			for (int i = 0; i < wallBlockCount; i++) {
				int x = message.wallBlockX[i], z = message.wallBlockZ[i], y = message.wallBlockY[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length || y < 1 || y > 10) continue;
				GridToStructure.CellOptions old = options[z][x] != null ? options[z][x] : GridToStructure.CellOptions.EMPTY;
				options[z][x] = old.withBlockAt(y, ResourceLocation.tryParse(message.wallBlockIds[i]));
			}

			//Split the stacked rows back into levels.
			int side = grid.length / levelCount;
			List<GridToStructure.Cell[][]> levels = new java.util.ArrayList<>();
			List<GridToStructure.CellOptions[][]> levelOptions = new java.util.ArrayList<>();
			int[] heights = new int[levelCount];
			for (int i = 0; i < levelCount; i++) {
				levels.add(java.util.Arrays.copyOfRange(grid, i * side, (i + 1) * side));
				levelOptions.add(java.util.Arrays.copyOfRange(options, i * side, (i + 1) * side));
				heights[i] = Math.max(1, Math.min(10, message.layerHeights.length > 0 ? message.layerHeights[i] : message.height));
			}
			int weight = Math.max(1, Math.min(150, message.weight));
			List<GridToStructure.Note> notes = new java.util.ArrayList<>();
			for (int i = 0; i < Math.min(message.noteText.length, Math.min(message.noteX.length, message.noteZ.length)); i++) {
				notes.add(new GridToStructure.Note(message.noteX[i], message.noteZ[i], message.noteText[i]));
			}
			GridToStructure.Extras extras = new GridToStructure.Extras(message.ceiling, message.carve,
				message.lightBlockId.isEmpty() ? null : ResourceLocation.tryParse(message.lightBlockId),
				Math.max(0, Math.min(32, message.lightSpacing)), notes);
			List<GridToStructure.PlacedBlock> blocks = GridToStructure.renderLayers(levels, heights, levelOptions, extras);

			DungeonPieceRegistry.DungeonPiece piece = new DungeonPieceRegistry.DungeonPiece(message.id, message.structureId, message.pool, weight, message.tags);
			DungeonPiecePlacer.Materials materials = new DungeonPiecePlacer.Materials(
				ResourceLocation.tryParse(message.wallBlockId), ResourceLocation.tryParse(message.floorBlockId));
			int dx = Math.max(-16, Math.min(16, message.originDx));
			int dz = Math.max(-16, Math.min(16, message.originDz));
			int dy = Math.max(-16, Math.min(16, message.originDy));
			BlockPos origin = (message.anchor != null ? message.anchor : BlockPos.containing(dm.position())).offset(dx, dy, dz);
			//A big dungeon's corner can sit far from a DM standing in its middle.
			if (!origin.closerToCenterThan(dm.position(), MAX_ANCHOR_DISTANCE + side)) {
				dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.trace_anchor_far", MAX_ANCHOR_DISTANCE));
				return;
			}
			if (message.pasteOnly) {
				if (net.hawthorn.dndsheets.dungeon.PasteQueue.busy(dm)) {
					dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.paste_busy"));
					return;
				}
				//Clickable [Undo] right in the confirmation, for when it landed in the wrong place.
				net.hawthorn.dndsheets.dungeon.PasteQueue.paste(dm, dm.serverLevel(), origin, blocks, materials,
					pastedMessage(Component.translatable("chat.dndsheets.dungeon.trace_pasted", origin.getX(), origin.getY(), origin.getZ())));
				return;
			}
			Optional<String> error = DungeonPiecePlacer.placeAndCapture(dm.serverLevel(), origin, piece, blocks, materials);

			if (error.isPresent()) {
				dm.sendSystemMessage(Component.literal(error.get()));
				return;
			}

			dm.sendSystemMessage(Component.translatable("chat.dndsheets.dungeon.piece_captured", message.id, message.pool));
			DndsheetsDungeonMod.PACKET_HANDLER.send(PacketDistributor.PLAYER.with(() -> dm), DungeonPieceListMessage.of(dm.serverLevel(), DungeonPieceRegistry.all()));
		}
	}

	/** A paste's confirmation with its clickable [Undo]. */
	public static Component pastedMessage(net.minecraft.network.chat.MutableComponent text) {
		return text.append(" ").append(Component.translatable("chat.dndsheets.dungeon.trace_paste_undo_link").withStyle(style -> style
			.withColor(net.minecraft.ChatFormatting.AQUA)
			.withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/dnddungeon undopaste"))));
	}
}
