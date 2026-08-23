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

//Cliente (el DM) -> servidor: capturar una pieza nueva DIBUJADA en DungeonTraceScreen, en vez de
//construida a mano y escaneada con un bloque de estructura (ver DungeonPieceCaptureMessage para ese
//otro camino). Planta los bloques y escanea igual que un bloque de estructura (DungeonPiecePlacer),
//luego delega el registro en el mismo DungeonManager.capturePiece que usa el flujo manual.
public class DungeonTraceCaptureMessage {
	private static final int MAX_SIDE = 64;

	String id, structureId, pool, tags;
	int weight, height;
	String[] rows;
	//Objetos con bloque elegido por el DM (de cualquier mod instalado): posición dispersa en vez de una
	//grilla densa paralela a "rows" — la mayoría de las celdas OBJECT no cambian el bloque por defecto.
	int[] objX, objZ;
	String[] objBlockId;
	//Hacia dónde mira cada objeto ("" = sin girar); getSerializedName()/byName, nunca el ordinal — un
	//Direction cruzando el cable por índice es justo el error que ya documenta la invariante 2 del mod.
	String[] objFacing;
	//Puertas/entradas con pool destino y/o dirección manual elegidos por el DM — igual de disperso que
	//los objetos, la mayoría de las celdas DOOR/START se quedan con el comportamiento por defecto
	//(pool de la pieza, dirección automática).
	int[] doorX, doorZ;
	String[] doorPool, doorFacing;
	//Material de pared/piso de TODA la pieza, no por celda (ver DungeonPiecePlacer.Materials); "" = el DM
	//no eligió, usar el bloque por defecto.
	String wallBlockId, floorBlockId;
	//Desplazamiento horizontal desde la posición del DM — el origen sigue siendo "donde está parado el
	//DM", esto solo lo corre unos bloques sin obligarlo a caminar hasta el punto exacto.
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
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue; //fila manipulada a mano, se ignora en vez de reventar
				options[z][x] = new GridToStructure.CellOptions(
					ResourceLocation.tryParse(message.objBlockId[i]), Direction.byName(message.objFacing[i]), null, null);
			}
			for (int i = 0; i < message.doorPool.length; i++) {
				int x = message.doorX[i], z = message.doorZ[i];
				if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) continue;
				//Un pool destino inválido no revienta la captura entera: se ignora esa sola puerta y cae
				//al pool de la pieza, igual que si el DM nunca hubiera escrito nada ahí.
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
