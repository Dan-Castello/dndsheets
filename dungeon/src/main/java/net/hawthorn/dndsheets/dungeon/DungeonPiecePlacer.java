package net.hawthorn.dndsheets.dungeon;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JigsawBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JigsawBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;
import java.util.Optional;

/**
 * <p>Capa impura del editor de trazado: planta en el mundo lo que {@link GridToStructure} tradujo, y
 * escanea esa región exactamente como lo haría un bloque de estructura en modo SAVE — así
 * {@link DungeonManager#capturePiece} no necesita saber que la pieza no la construyó una persona.</p>
 *
 * <p>El bloque de una celda {@code OBJECT} se busca en {@link ForgeRegistries#BLOCKS}, el mismo
 * registro global donde vive cualquier bloque de cualquier mod instalado (así es como el resto del
 * mod ya resuelve ítems ajenos, ver {@code Config.resolveItem}) — no hace falta compatibilidad
 * especial por mod, un mueble de otro mod es solo un {@link ResourceLocation} más. Si no resuelve
 * (el mod no está instalado, o el DM no eligió nada) cae al bloque por defecto.</p>
 */
public final class DungeonPiecePlacer {

	private DungeonPiecePlacer() {}

	//Públicos: el renderer de previsualización en el mundo (DungeonTracePreviewRenderer, cliente) necesita
	//resolver EXACTAMENTE los mismos bloques que va a plantar el servidor — dos copias de esta tabla se
	//desincronizan la primera vez que alguien cambie una sin acordarse de la otra.
	public static final BlockState DEFAULT_FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
	public static final BlockState DEFAULT_WALL = Blocks.STONE_BRICKS.defaultBlockState();
	public static final BlockState DEFAULT_OBJECT = Blocks.BARREL.defaultBlockState();

	/**
	 * <p>Material de pared y de piso para TODA la pieza — a diferencia de {@code OBJECT}, que elige
	 * bloque celda a celda, pared y piso son una sola decisión por pieza (así dibuja un DM: "esta sala
	 * es de piedra", no bloque por bloque). Cualquiera de los dos puede ser null: cae al bloque por
	 * defecto.</p>
	 */
	public record Materials(ResourceLocation wallBlockId, ResourceLocation floorBlockId) {
		public static final Materials DEFAULTS = new Materials(null, null);
	}

	/** Sin materiales elegidos por el DM — ver {@link #placeAndCapture(ServerLevel, BlockPos, DungeonPieceRegistry.DungeonPiece, List, Materials)}. */
	public static Optional<String> placeAndCapture(ServerLevel level, BlockPos origin,
			DungeonPieceRegistry.DungeonPiece piece, List<GridToStructure.PlacedBlock> blocks) {
		return placeAndCapture(level, origin, piece, blocks, Materials.DEFAULTS);
	}

	/**
	 * <p>Planta los bloques en {@code origin}, escanea la región con el mismo mecanismo que usa el
	 * bloque de estructura al guardar, y delega el registro de la pieza en
	 * {@link DungeonManager#capturePiece} sin duplicar esa lógica.</p>
	 */
	public static Optional<String> placeAndCapture(ServerLevel level, BlockPos origin,
			DungeonPieceRegistry.DungeonPiece piece, List<GridToStructure.PlacedBlock> blocks, Materials materials) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) {
			return Optional.of("\"" + piece.structureId() + "\" no es un id válido (usa el formato espacioDeNombres:ruta).");
		}
		if (blocks.isEmpty()) return Optional.of("la grilla está vacía, no hay nada que plantar.");

		BlockState floorState = resolveOrDefault(materials.floorBlockId(), DEFAULT_FLOOR);
		BlockState wallState = resolveOrDefault(materials.wallBlockId(), DEFAULT_WALL);

		int width = 0, tallest = 0, depth = 0;
		for (GridToStructure.PlacedBlock block : blocks) {
			width = Math.max(width, block.x() + 1);
			tallest = Math.max(tallest, block.y() + 1);
			depth = Math.max(depth, block.z() + 1);
		}

		for (GridToStructure.PlacedBlock block : blocks) {
			BlockPos at = origin.offset(block.x(), block.y(), block.z());
			switch (block.kind()) {
				case FLOOR -> level.setBlock(at, floorState, 3);
				case WALL -> level.setBlock(at, wallState, 3);
				case OBJECT -> level.setBlock(at, objectStateFor(block.blockId(), block.facing()), 3);
				case CONNECTOR, START -> placeJigsaw(level, at, block.facing(),
					block.targetPool() != null ? block.targetPool() : piece.pool(), block.kind() == GridToStructure.Kind.START);
			}
		}

		StructureTemplateManager manager = level.getStructureManager();
		StructureTemplate template = manager.getOrCreate(structureId);
		template.fillFromWorld(level, origin, new Vec3i(width, tallest, depth), false, Blocks.STRUCTURE_VOID);
		if (!manager.save(structureId)) {
			return Optional.of("no pude guardar la estructura escaneada como " + structureId + ".");
		}

		return DungeonManager.capturePiece(level.getServer(), piece);
	}

	public static BlockState resolveOrDefault(ResourceLocation blockId, BlockState fallback) {
		if (blockId == null) return fallback;
		net.minecraft.world.level.block.Block block = ForgeRegistries.BLOCKS.getValue(blockId);
		return block != null ? block.defaultBlockState() : fallback;
	}

	//Gira el objeto si su bloque usa la propiedad de orientación horizontal estándar (la mayoría de
	//muebles de un solo espacio la tienen, sea del mod que sea) — sin lista de bloques conocidos, así
	//que no hace falta compatibilidad especial por mod. Un mueble de MÁS de una celda (cama, puerta) no
	//sale completo: cada celda de la grilla es un solo BlockPos, y eso ya es un techo del editor, no de
	//esta función.
	public static BlockState objectStateFor(ResourceLocation blockId, Direction facing) {
		BlockState state = resolveOrDefault(blockId, DEFAULT_OBJECT);
		if (facing != null && facing.getAxis().isHorizontal()
				&& state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
			state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, facing);
		}
		return state;
	}

	//El "afuera" que calculó GridToStructure ya es la Direction horizontal; el jigsaw también necesita
	//un "arriba" para su orientación completa (FrontAndTop) — DOWN/UP no aplican a puertas en el plano
	//de la grilla, así que siempre es UP.
	private static void placeJigsaw(ServerLevel level, BlockPos at, Direction facing, String pool, boolean isStart) {
		BlockState state = Blocks.JIGSAW.defaultBlockState()
			.setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(facing, Direction.UP));
		level.setBlock(at, state, 3);
		BlockEntity entity = level.getBlockEntity(at);
		if (entity instanceof JigsawBlockEntity jigsaw) DungeonManager.configureJigsaw(jigsaw, pool, isStart);
	}
}
