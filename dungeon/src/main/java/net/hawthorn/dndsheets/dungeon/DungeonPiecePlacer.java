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
	//Scaffolding: climbable on its own, no wall to hang on and no facing (a ladder needs both). Distance 0 =
	//standing on the block below, so the column doesn't collapse on its first tick.
	public static final BlockState DEFAULT_STAIRS = Blocks.SCAFFOLDING.defaultBlockState().setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE, 0);
	//The steps of a staircase with a direction, when the DM picks no block: matches the default wall.
	public static final BlockState DEFAULT_STEP = Blocks.STONE_BRICK_STAIRS.defaultBlockState();

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

		int width = 0, tallest = 0, depth = 0;
		for (GridToStructure.PlacedBlock block : blocks) {
			width = Math.max(width, block.x() + 1);
			tallest = Math.max(tallest, block.y() + 1);
			depth = Math.max(depth, block.z() + 1);
		}

		place(level, origin, blocks, materials, piece.pool(), true);

		StructureTemplateManager manager = level.getStructureManager();
		StructureTemplate template = manager.getOrCreate(structureId);
		template.fillFromWorld(level, origin, new Vec3i(width, tallest, depth), false, Blocks.STRUCTURE_VOID);
		if (!manager.save(structureId)) {
			return Optional.of("could not save the scanned structure as " + structureId + ".");
		}

		return DungeonManager.capturePiece(level.getServer(), piece);
	}

	/**
	 * <p>Plants the blocks and nothing else. {@code connectors} false is a room pasted straight into the world
	 * (not a dungeon piece): doors and entrances are left as open doorways instead of jigsaw blocks, which
	 * would only mean something to the dungeon generator.</p>
	 */
	public static void place(ServerLevel level, BlockPos origin, List<GridToStructure.PlacedBlock> blocks, Materials materials,
			String pool, boolean connectors) {
		BlockState floorState = resolveOrDefault(materials.floorBlockId(), DEFAULT_FLOOR);
		BlockState wallState = resolveOrDefault(materials.wallBlockId(), DEFAULT_WALL);
		for (GridToStructure.PlacedBlock block : blocks) {
			BlockPos at = origin.offset(block.x(), block.y(), block.z());
			switch (block.kind()) {
				//A painted cell carries its own block; the rest use the piece's material.
				case FLOOR -> level.setBlock(at, resolveOrDefault(block.blockId(), floorState), 3);
				case WALL -> level.setBlock(at, resolveOrDefault(block.blockId(), wallState), 3);
				case OBJECT -> level.setBlock(at, objectStateFor(block.blockId(), block.facing()), 3);
				case STAIRS -> level.setBlock(at, stairsState(block.blockId(), block.facing()), 3);
				case CONNECTOR, START -> {
					//A ceiling hatch with nothing chosen closes as scaffolding: the climb goes on into the piece above.
					BlockState closed = block.blockId() != null ? doorwayState(block.blockId(), block.facing(), false)
						: block.facing() == Direction.UP ? DEFAULT_STAIRS : null;
					if (connectors) placeJigsaw(level, at, block.facing(),
						block.targetPool() != null ? block.targetPool() : pool, block.kind() == GridToStructure.Kind.START, closed);
					else level.setBlock(at, closed != null ? closed : Blocks.AIR.defaultBlockState(), 2);
				}
				//Flag 2 (no neighbour updates): a door's lower half alone would pop off before its upper half lands.
				case DOORWAY -> level.setBlock(at, doorwayState(block.blockId(), block.facing(), true), 2);
				case AIR -> level.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
				case LIGHT -> level.setBlock(at, lightState(block.blockId(), block.facing()), 3);
				case SIGN -> {
					level.setBlock(at, Blocks.OAK_SIGN.defaultBlockState(), 3);
					if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
						net.minecraft.world.level.block.entity.SignText text = new net.minecraft.world.level.block.entity.SignText();
						List<String> lines = GridToStructure.signLines(block.targetPool() != null ? block.targetPool().split("\n")[0] : "");
						for (int i = 0; i < lines.size(); i++) text = text.setMessage(i, net.minecraft.network.chat.Component.literal(lines.get(i)));
						sign.setText(text, true);
						sign.setChanged();
					}
				}
			}
		}
	}

	public static final BlockState DEFAULT_LIGHT = Blocks.LANTERN.defaultBlockState();

	/** A light from the "lights every N" option: hanging (facing DOWN) when the block can hang. */
	public static BlockState lightState(ResourceLocation blockId, Direction facing) {
		BlockState state = resolveOrDefault(blockId, DEFAULT_LIGHT);
		var hanging = net.minecraft.world.level.block.state.properties.BlockStateProperties.HANGING;
		if (state.hasProperty(hanging)) state = state.setValue(hanging, facing == Direction.DOWN);
		return state;
	}


	public static BlockState resolveOrDefault(ResourceLocation blockId, BlockState fallback) {
		//containsKey, not just a null check: the block registry answers AIR for an id it doesn't know, so
		//an empty material ("" parses as "minecraft:") pasted walls and floors made of air.
		if (blockId == null || !ForgeRegistries.BLOCKS.containsKey(blockId)) return fallback;
		net.minecraft.world.level.block.Block block = ForgeRegistries.BLOCKS.getValue(blockId);
		return block != null ? block.defaultBlockState() : fallback;
	}

	//Rotates the object if its block uses the standard horizontal-facing property (most single-space
	//furniture has it, regardless of which mod it's from) — no list of known blocks needed, so no
	//special per-mod compatibility is required. Furniture spanning MORE than one cell (bed, door) won't
	//come out complete: each grid cell is a single BlockPos, and that's already a ceiling of the
	//editor, not of this function.
	public static BlockState objectStateFor(ResourceLocation blockId, Direction facing) {
		return orient(resolveOrDefault(blockId, DEFAULT_OBJECT), facing);
	}

	/** Scaffolding with no direction; with one, a step of the chosen stairs block climbing that way. */
	public static BlockState stairsState(ResourceLocation blockId, Direction facing) {
		return facing == null ? DEFAULT_STAIRS : orient(resolveOrDefault(blockId, DEFAULT_STEP), facing);
	}

	//Whichever orientation property the block has, from any mod: horizontal facing (stairs, furniture),
	//full facing (dispensers, rods), axis (logs, pillars). Down on stairs/slabs/trapdoors = upside down.
	private static BlockState orient(BlockState state, Direction facing) {
		if (facing == null) return state;
		if (facing.getAxis().isHorizontal() && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING)) {
			return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, facing);
		}
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
			return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING, facing);
		}
		if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)) {
			return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS, facing.getAxis());
		}
		if (facing == Direction.DOWN && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF)) {
			return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HALF, net.minecraft.world.level.block.state.properties.Half.TOP);
		}
		if (facing == Direction.DOWN && state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE)) {
			return state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.SLAB_TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP);
		}
		return state;
	}

	//The "outward" direction GridToStructure computed is already the horizontal Direction; the jigsaw
	//also needs an "up" for its full orientation (FrontAndTop) — DOWN/UP don't apply to openings on the
	//grid's plane, so it's always UP.
	/**
	 * <p>The block closing a doorway, lower or upper part: a door gets the matching half and faces along the
	 * opening; any other block (the wall's, for a hidden room) is just itself.</p>
	 */
	public static BlockState doorwayState(ResourceLocation blockId, Direction facing, boolean upper) {
		BlockState state = resolveOrDefault(blockId, Blocks.AIR.defaultBlockState());
		var half = net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
		if (state.hasProperty(half)) state = state.setValue(half, upper
			? net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
			: net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
		var horizontal = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
		if (facing != null && facing.getAxis().isHorizontal() && state.hasProperty(horizontal)) state = state.setValue(horizontal, facing);
		return state;
	}

	//closed != null: what the jigsaw turns into once the dungeon is generated (its "final state"), so
	//the connection point ends up as a door or a hidden wall instead of an open gap.
	private static void placeJigsaw(ServerLevel level, BlockPos at, Direction facing, String pool, boolean isStart, BlockState closed) {
		BlockState state = Blocks.JIGSAW.defaultBlockState()
			.setValue(JigsawBlock.ORIENTATION, FrontAndTop.fromFrontAndTop(facing, facing.getAxis().isVertical() ? Direction.NORTH : Direction.UP));
		level.setBlock(at, state, 3);
		BlockEntity entity = level.getBlockEntity(at);
		if (entity instanceof JigsawBlockEntity jigsaw) {
			DungeonManager.configureJigsaw(jigsaw, pool, isStart);
			//Floor/ceiling hatches: rollable, so the piece on the other side may join at any rotation.
			if (facing.getAxis().isVertical()) jigsaw.setJoint(JigsawBlockEntity.JointType.ROLLABLE);
			if (closed != null) {
				jigsaw.setFinalState(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(closed));
				jigsaw.setChanged();
			}
		}
	}
}
