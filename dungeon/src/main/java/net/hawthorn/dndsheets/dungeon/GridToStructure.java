package net.hawthorn.dndsheets.dungeon;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>Translates a 2D grid drawn by the DM (trace editor) into a flat list of blocks relative to the
 * piece's origin. Pure: doesn't touch {@code Level} or {@code ServerPlayer}, so it's testable without
 * booting Forge, just like {@link CharacterRules}. Whatever uses it in the real world (placing the
 * blocks, or dumping them into a {@code StructureTemplate}) is a separate layer that does need the
 * game running.</p>
 *
 * <p>A wall doesn't have to come out as a geometric circle: the DM draws it cell by cell, so it comes
 * out as recognizable as a circle hand-built with blocks in-game — stair-stepped edges, not a perfect
 * arc. This class doesn't try to smooth it out, it just translates what's on the grid.</p>
 *
 * <p>{@code OBJECT} cells don't carry a fixed block: they hold the {@link ResourceLocation} the DM
 * picked in the editor (see {@code BlockPickerScreen}), which can be from any installed mod —
 * furniture included. {@link Kind#WALL}/{@link Kind#FLOOR} still use a default block; the layer that
 * plants the blocks decides what to do if an object's {@code ResourceLocation} doesn't resolve to
 * anything (mod uninstalled).</p>
 */
public final class GridToStructure {

	private GridToStructure() {}

	public enum Cell {
		EMPTY, FLOOR, WALL, DOOR, START, OBJECT;

		static Cell fromChar(char c) {
			return switch (c) {
				case '#' -> WALL;
				case ',' -> FLOOR;
				case 'D' -> DOOR;
				case 'S' -> START;
				case 'o' -> OBJECT;
				default -> EMPTY;
			};
		}
	}

	public enum Kind { FLOOR, WALL, CONNECTOR, START, OBJECT }

	/**
	 * <p>Position relative to the piece's origin (x/z = grid column/row, y = height). {@code blockId}
	 * is only used with {@link Kind#OBJECT}. {@code targetPool} is only used with
	 * {@link Kind#CONNECTOR}/{@link Kind#START}: which pool this jigsaw connects to — null means "this
	 * same piece's pool" (default behavior, whatever plants the block decides what to fill it with).</p>
	 */
	public record PlacedBlock(int x, int y, int z, Kind kind, Direction facing, ResourceLocation blockId, String targetPool) {}

	/**
	 * <p>Everything the DM can adjust on a cell beyond its type — which block an {@code OBJECT} uses
	 * and which way it faces, or which pool a {@code DOOR}/{@code START} connects to and which way it
	 * points. The four fields are independent of each other (an object never uses {@code doorPool}, a
	 * door never uses {@code objectBlock}) because a cell is one thing or the other, never both. Any
	 * field left null means "use the default behavior" — the same behavior the editor had before this
	 * was adjustable.</p>
	 */
	public record CellOptions(ResourceLocation objectBlock, Direction objectFacing, String doorPool, Direction doorFacing) {
		public static final CellOptions EMPTY = new CellOptions(null, null, null, null);
	}

	/** One text row per grid row, all the same length. See {@link Cell#fromChar}. */
	public static Cell[][] parse(String[] rows) {
		if (rows.length == 0) return new Cell[0][0];
		int width = rows[0].length();
		Cell[][] grid = new Cell[rows.length][width];
		for (int z = 0; z < rows.length; z++) {
			if (rows[z].length() != width) {
				throw new IllegalArgumentException("all rows must be " + width + " cells wide, row " + z + " is " + rows[z].length());
			}
			for (int x = 0; x < width; x++) grid[z][x] = Cell.fromChar(rows[z].charAt(x));
		}
		return grid;
	}

	/** No per-cell adjustments — see {@link #render(Cell[][], int, CellOptions[][])}. */
	public static List<PlacedBlock> render(Cell[][] grid, int height) {
		return render(grid, height, null);
	}

	/**
	 * @param height  height of the wall columns, in blocks, above the floor.
	 * @param options per-cell adjustments (see {@link CellOptions}), same dimensions as {@code grid};
	 *                null (the whole grid, or a single cell) = unadjusted, uses the default behavior
	 *                for that cell type.
	 */
	public static List<PlacedBlock> render(Cell[][] grid, int height, CellOptions[][] options) {
		List<PlacedBlock> blocks = new ArrayList<>();
		for (int z = 0; z < grid.length; z++) {
			for (int x = 0; x < grid[z].length; x++) {
				switch (grid[z][x]) {
					case WALL -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						for (int y = 1; y <= height; y++) blocks.add(new PlacedBlock(x, y, z, Kind.WALL, null, null, null));
					}
					case FLOOR -> blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
					case OBJECT -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						CellOptions opt = optionsAt(options, x, z);
						blocks.add(new PlacedBlock(x, 1, z, Kind.OBJECT, opt.objectFacing(), opt.objectBlock(), null));
					}
					case DOOR, START -> {
						blocks.add(new PlacedBlock(x, 0, z, Kind.FLOOR, null, null, null));
						Kind kind = grid[z][x] == Cell.START ? Kind.START : Kind.CONNECTOR;
						CellOptions opt = optionsAt(options, x, z);
						Direction facing = opt.doorFacing() != null ? opt.doorFacing() : outwardFacing(grid, x, z);
						blocks.add(new PlacedBlock(x, 1, z, kind, facing, null, opt.doorPool()));
					}
					case EMPTY -> {}
				}
			}
		}
		return blocks;
	}

	private static CellOptions optionsAt(CellOptions[][] options, int x, int z) {
		if (options == null) return CellOptions.EMPTY;
		CellOptions opt = options[z][x];
		return opt != null ? opt : CellOptions.EMPTY;
	}

	/** Inverse of {@link Cell#fromChar}, so the editor can send the grid as text over the network. */
	public static char toChar(Cell cell) {
		return switch (cell) {
			case WALL -> '#';
			case FLOOR -> ',';
			case DOOR -> 'D';
			case START -> 'S';
			case OBJECT -> 'o';
			case EMPTY -> '.';
		};
	}

	public static List<PlacedBlock> translate(String[] rows, int height) {
		return render(parse(rows), height);
	}

	//ponytail: simple heuristic — the door points to the first empty neighbor found, in N/E/S/W order.
	//An interior door with no empty neighbor at all has no natural "outside" and defaults to pointing
	//north. If routing interior doors is ever needed, the editor will have to let the DM mark the
	//direction by hand.
	private static Direction outwardFacing(Cell[][] grid, int x, int z) {
		if (isOutside(grid, x, z - 1)) return Direction.NORTH;
		if (isOutside(grid, x + 1, z)) return Direction.EAST;
		if (isOutside(grid, x, z + 1)) return Direction.SOUTH;
		if (isOutside(grid, x - 1, z)) return Direction.WEST;
		return Direction.NORTH;
	}

	private static boolean isOutside(Cell[][] grid, int x, int z) {
		if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length) return true;
		return grid[z][x] == Cell.EMPTY;
	}

	/**
	 * <p>Stamps a circle onto an already-parsed grid: useful so the editor can offer a "circular room"
	 * brush instead of forcing the DM to mark cell by cell. Compares distance to the center, so the
	 * result comes out stair-stepped — recognizable as a circle, not geometrically exact.</p>
	 */
	public static void stampCircle(Cell[][] grid, int centerX, int centerZ, int radius, int wallThickness) {
		for (int z = 0; z < grid.length; z++) {
			for (int x = 0; x < grid[z].length; x++) {
				double dist = Math.hypot(x - centerX, z - centerZ);
				if (dist <= radius - wallThickness) grid[z][x] = Cell.FLOOR;
				else if (dist <= radius) grid[z][x] = Cell.WALL;
			}
		}
	}
}
