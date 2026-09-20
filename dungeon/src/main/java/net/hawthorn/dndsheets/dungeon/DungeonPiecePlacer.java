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
 * <p>The impure layer of the trace editor: plants into the world what {@link GridToStructure}
 * translated, and scans that region exactly the way a structure block in SAVE mode would — so
 * {@link DungeonManager#capturePiece} doesn't need to know the piece wasn't built by a person.</p>
 *
 * <p>The block for an {@code OBJECT} cell is looked up in {@link ForgeRegistries#BLOCKS}, the same
 * global registry where any block from any installed mod lives (the same way the rest of the mod
 * already resolves foreign items, see {@code Config.resolveItem}) — no special per-mod compatibility
 * needed, a piece of furniture from another mod is just one more {@link ResourceLocation}. If it
 * doesn't resolve (the mod isn't installed, or the DM didn't pick anything) it falls back to the
 * default block.</p>
 */
public final class DungeonPiecePlacer {

	private DungeonPiecePlacer() {}

	//Public: the in-world preview renderer (DungeonTracePreviewRenderer, client-side) needs to resolve
	//EXACTLY the same blocks the server will plant — two copies of this table would drift apart the
	//first time someone changes one without remembering the other.
	public static final BlockState DEFAULT_FLOOR = Blocks.POLISHED_ANDESITE.defaultBlockState();
	public static final BlockState DEFAULT_WALL = Blocks.STONE_BRICKS.defaultBlockState();
	public static final BlockState DEFAULT_OBJECT = Blocks.BARREL.defaultBlockState();

	/**
	 * <p>Wall and floor material for the WHOLE piece — unlike {@code OBJECT}, which picks a block cell
	 * by cell, wall and floor are a single decision per piece (that's how a DM draws: "this room is
	 * made of stone," not block by block). Either one can be null: falls back to the default block.</p>
	 */
	public record Materials(ResourceLocation wallBlockId, ResourceLocation floorBlockId) {
		public static final Materials DEFAULTS = new Materials(null, null);
	}

	/** No materials chosen by the DM — see {@link #placeAndCapture(ServerLevel, BlockPos, DungeonPieceRegistry.DungeonPiece, List, Materials)}. */
	public static Optional<String> placeAndCapture(ServerLevel level, BlockPos origin,
			DungeonPieceRegistry.DungeonPiece piece, List<GridToStructure.PlacedBlock> blocks) {
		return placeAndCapture(level, origin, piece, blocks, Materials.DEFAULTS);
	}

	/**
	 * <p>Plants the blocks at {@code origin}, scans the region with the same mechanism the structure
	 * block uses when saving, and delegates registering the piece to
	 * {@link DungeonManager#capturePiece} without duplicating that logic.</p>
	 */
	public static Optional<String> placeAndCapture(ServerLevel level, BlockPos origin,
			DungeonPieceRegistry.DungeonPiece piece, List<GridToStructure.PlacedBlock> blocks, Materials materials) {
		ResourceLocation structureId = ResourceLocation.tryParse(piece.structureId());
		if (structureId == null) {
			return Optional.of("\"" + piece.structureId() + "\" is not a valid id (use the namespace:path format).");
		}
		if (blocks.isEmpty()) return Optional.of("the grid is empty, there is nothing to plant.");

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
			return Optional.of("could not save the scanned structure as " + structureId + ".");
		}

		return DungeonManager.capturePiece(level.getServer(), piece);
	}

	public static BlockState resolveOrDefault(ResourceLocation blockId, BlockState fallback) {
		if (blockId == null) return fallback;
		net.minecraft.world.level.block.Block block = ForgeRegistries.BLOCKS.getValue(blockId);
		return block != null ? block.defaultBlockState() : fallback;
	}

	//Rotates the object if its block uses the standard horizontal-facing property (most single-space
	//furniture has it, regardless of which mod it's from) — no list of known blocks needed, so no
	//special per-mod compatibility is required. Furniture spanning MORE than one cell (bed, door) won't
	//come out complete: each grid cell is a single BlockPos, and that's already a ceiling of the
	//editor, not of this function.
	public static BlockState objectStateFor(ResourceLocation blockId, Direction facing) {
		BlockState state = resolveOrDefault(blockId, DEFAULT_OBJECT);
		if (facing != null && facing.getAxis().isHorizontal()
				&& state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
			state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, facing);
		}
		return state;
	}

	//The "outward" direction GridToStructure computed is already the horizontal Direction; the jigsaw
	//also needs an "up" for its full orientation (FrontAndTop) — DOWN/UP don't apply to openings on the
	//grid's plane, so it's always UP.
	private static void placeJigsaw(ServerLevel level, BlockPos at, Direction facing, String pool, boolean isStart) {
		BlockState state = Blocks.JIGSAW.defaultBlockState()
			.setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(facing, Direction.UP));
		level.setBlock(at, state, 3);
		BlockEntity entity = level.getBlockEntity(at);
		if (entity instanceof JigsawBlockEntity jigsaw) DungeonManager.configureJigsaw(jigsaw, pool, isStart);
	}
}
