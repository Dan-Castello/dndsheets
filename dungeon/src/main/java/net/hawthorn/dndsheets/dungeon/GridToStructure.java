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
		EMPTY, FLOOR, WALL, DOOR, START, OBJECT, STAIRS;

		static Cell fromChar(char c) {
			return switch (c) {
				case '#' -> WALL;
				case ',' -> FLOOR;
				case 'D' -> DOOR;
				case 'S' -> START;
				case 'o' -> OBJECT;
				case 'H' -> STAIRS;
				default -> EMPTY;
			};
		}
	}

	//DOORWAY: the upper block of a closed doorway (the lower one is the CONNECTOR/START itself).
	//AIR: carved out (pasted into rock). LIGHT: blockId = the light, facing DOWN = hanging from the roof.
	//SIGN: a note, its text in targetPool.
	public enum Kind { FLOOR, WALL, CONNECTOR, START, OBJECT, STAIRS, DOORWAY, AIR, LIGHT, SIGN }

	/** A text pinned to a cell (a donjon room's contents, a trap...). */
	public record Note(int x, int z, String text) {}

	/**
	 * <p>What goes around the drawn cells, for whole dungeons rather than rooms: a ceiling over the top level,
	 * the inside carved out (so it can be pasted into rock), lights every {@code lightSpacing} cells (0 = none),
	 * and a sign on each note's cell.</p>
	 */
	public record Extras(boolean ceiling, boolean carve, ResourceLocation light, int lightSpacing, List<Note> notes) {
		public static final Extras NONE = new Extras(false, false, null, 0, List.of());
	}

	/**
	 * <p>Position relative to the piece's origin (x/z = grid column/row, y = height). {@code blockId}
	 * is the object's block for {@link Kind#OBJECT}, and the painted material (null = the piece's) for
	 * {@link Kind#WALL}/{@link Kind#FLOOR}. {@code targetPool} is only used with
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
	 *
	 * <p>{@code material} is the block a {@code WALL} column or a {@code FLOOR} cell is made of, painted cell
	 * by cell in the editor; null = the piece's wall/floor material. {@code holes} carves a {@code WALL}
	 * column: bit y-1 set = no block at height y (windows, gaps, wear). {@code blocks} overrides the material
	 * block by block, index y-1 (null array or entry = {@code material}): striped or patterned walls. Never
	 * mutated in place — {@link #withBlockAt} copies — so cells can share it after a copy.</p>
	 */
	public record CellOptions(ResourceLocation objectBlock, Direction objectFacing, String doorPool, Direction doorFacing,
			ResourceLocation material, int holes, ResourceLocation[] blocks) {
		public static final CellOptions EMPTY = new CellOptions(null, null, null, null, null, 0, null);

		public CellOptions(ResourceLocation objectBlock, Direction objectFacing, String doorPool, Direction doorFacing) {
			this(objectBlock, objectFacing, doorPool, doorFacing, null, 0, null);
		}

		/** The block at height y of a wall column: its own override, else the column's material (null = the piece's). */
		public ResourceLocation blockAt(int y) {
			return blocks != null && y >= 1 && y <= blocks.length && blocks[y - 1] != null ? blocks[y - 1] : material;
		}

		public CellOptions withBlockAt(int y, ResourceLocation block) {
			ResourceLocation[] copy = java.util.Arrays.copyOf(blocks != null ? blocks : new ResourceLocation[0], Math.max(y, blocks != null ? blocks.length : 0));
			copy[y - 1] = block;
			return new CellOptions(objectBlock, objectFacing, doorPool, doorFacing, material, holes, copy);
		}

		public CellOptions withBlocks(ResourceLocation[] newBlocks) {
			return new CellOptions(objectBlock, objectFacing, doorPool, doorFacing, material, holes, newBlocks);
		}

		public static CellOptions painted(ResourceLocation material) {
			return EMPTY.withMaterial(material);
		}

		public CellOptions withMaterial(ResourceLocation newMaterial) {
			return new CellOptions(objectBlock, objectFacing, doorPool, doorFacing, newMaterial, holes, blocks);
		}

		public CellOptions withHoles(int newHoles) {
			return new CellOptions(objectBlock, objectFacing, doorPool, doorFacing, material, newHoles, blocks);
		}

		/** Whether the wall column has a gap at height y (1 = the first block above the floor). */
		public boolean isHole(int y) {
			return y >= 1 && y <= 31 && ((holes >> (y - 1)) & 1) != 0;
		}
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
		return renderLayers(java.util.Collections.singletonList(grid), new int[]{height}, java.util.Collections.singletonList(options));
	}

	/**
	 * <p>Stacked levels: level i's floor sits right on top of level i-1's walls (y = the heights below plus
	 * one floor per level), so a second storey is just a second grid. A {@code STAIRS} cell is a climbable
	 * column from its level's floor up through the next level's floor, which is left open above it.</p>
	 *
	 * @param heights wall height per level
	 * @param options per level; null (or a null level, or a null cell) = unadjusted
	 */
	public static List<PlacedBlock> renderLayers(List<Cell[][]> layers, int[] heights, List<CellOptions[][]> options) {
		return renderLayers(layers, heights, options, Extras.NONE);
	}

	public static List<PlacedBlock> renderLayers(List<Cell[][]> layers, int[] heights, List<CellOptions[][]> options, Extras extras) {
		List<PlacedBlock> blocks = renderCells(layers, heights, options);
		if (extras.equals(Extras.NONE) || layers.isEmpty()) return blocks;
		java.util.Set<Long> taken = new java.util.HashSet<>();
		for (PlacedBlock block : blocks) taken.add(key(block.x(), block.y(), block.z()));
		List<PlacedBlock> added = new ArrayList<>();
		int base = 0;
		for (int i = 0; i < layers.size(); i++) {
			Cell[][] grid = layers.get(i);
			int height = heights[i];
			boolean roof = i + 1 < layers.size() || extras.ceiling();
			int top = base + height + 1;
			for (int z = 0; z < grid.length; z++) {
				for (int x = 0; x < grid[z].length; x++) {
					Cell cell = grid[z][x];
					if (cell == Cell.EMPTY) continue;
					if (i == layers.size() - 1 && extras.ceiling() && taken.add(key(x, top, z))) {
						added.add(new PlacedBlock(x, top, z, Kind.FLOOR, null, null, null));
					}
					if (cell != Cell.FLOOR) continue;
					int sp = extras.lightSpacing();
					if (extras.light() != null && sp > 0 && x % sp == sp / 2 && z % sp == sp / 2) {
						//Torches and candles need the floor under them; the rest hang from the roof when there is one.
						String path = extras.light().getPath();
						boolean hang = roof && !path.contains("torch") && !path.contains("candle");
						int y = hang ? base + height : base + 1;
						if (taken.add(key(x, y, z))) added.add(new PlacedBlock(x, y, z, Kind.LIGHT, hang ? Direction.DOWN : Direction.UP, extras.light(), null));
					}
				}
			}
			if (i == 0) {
				for (Note note : extras.notes()) {
					int x = note.x(), z = note.z();
					if (z < 0 || z >= grid.length || x < 0 || x >= grid[z].length || grid[z][x] != Cell.FLOOR) continue;
					if (taken.add(key(x, base + 1, z))) added.add(new PlacedBlock(x, base + 1, z, Kind.SIGN, null, null, note.text()));
				}
			}
			if (extras.carve()) {
				for (int z = 0; z < grid.length; z++) {
					for (int x = 0; x < grid[z].length; x++) {
						if (grid[z][x] == Cell.EMPTY || grid[z][x] == Cell.WALL) continue;
						for (int y = base + 1; y <= base + height; y++) {
							if (taken.add(key(x, y, z))) added.add(new PlacedBlock(x, y, z, Kind.AIR, null, null, null));
						}
					}
				}
			}
			base += height + 1;
		}
		//Air first (a paste spread over several ticks hollows the rock before building in it), then the room,
		//then what stands on it (lights, signs), so nothing waits in mid-air for its support.
		List<PlacedBlock> ordered = new ArrayList<>(added.size() + blocks.size());
		for (PlacedBlock block : added) if (block.kind() == Kind.AIR) ordered.add(block);
		ordered.addAll(blocks);
		for (PlacedBlock block : added) if (block.kind() != Kind.AIR) ordered.add(block);
		return ordered;
	}

	//A sign holds 4 short lines: the note's words wrapped at ~15 characters, the rest cut.
	public static List<String> signLines(String text) {
		List<String> lines = new java.util.ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : (text == null ? "" : text).trim().split("\\s+")) {
			if (line.length() > 0 && line.length() + 1 + word.length() > 15) {
				lines.add(line.toString());
				line.setLength(0);
				if (lines.size() == 4) return lines;
			}
			if (line.length() > 0) line.append(' ');
			line.append(word.length() > 15 ? word.substring(0, 15) : word);
		}
		if (line.length() > 0 && lines.size() < 4) lines.add(line.toString());
		return lines;
	}

	private static long key(int x, int y, int z) {
		return ((long) x & 0x1FFFFF) | (((long) z & 0x1FFFFF) << 21) | (((long) y & 0x1FFFFF) << 42);
	}

	private static List<PlacedBlock> renderCells(List<Cell[][]> layers, int[] heights, List<CellOptions[][]> options) {
		List<PlacedBlock> blocks = new ArrayList<>();
		int base = 0;
		for (int i = 0; i < layers.size(); i++) {
			Cell[][] grid = layers.get(i);
			Cell[][] below = i > 0 ? layers.get(i - 1) : null;
			CellOptions[][] levelOptions = options != null ? options.get(i) : null;
			CellOptions[][] belowOptions = options != null && i > 0 ? options.get(i - 1) : null;
			int height = heights[i];
			boolean levelAbove = i + 1 < layers.size();
			for (int z = 0; z < grid.length; z++) {
				for (int x = 0; x < grid[z].length; x++) {
					Cell cell = grid[z][x];
					if (cell == Cell.EMPTY) continue;
					CellOptions opt = optionsAt(levelOptions, x, z);
					//Open above a staircase from the level below: the stairs come up through here.
					//Same for a ceiling hatch from the level below (its jigsaw takes this floor's place).
					boolean stairsBelow = below != null && z < below.length && x < below[z].length && (below[z][x] == Cell.STAIRS
						|| (below[z][x] == Cell.DOOR || below[z][x] == Cell.START) && optionsAt(belowOptions, x, z).doorFacing() == Direction.UP);
					//A floor hatch: its jigsaw IS this floor block.
					boolean floorHatch = (cell == Cell.DOOR || cell == Cell.START) && opt.doorFacing() == Direction.DOWN;
					if (!stairsBelow && !floorHatch) blocks.add(new PlacedBlock(x, base, z, Kind.FLOOR, null, cell == Cell.FLOOR ? opt.material() : null, null));
					switch (cell) {
						case WALL -> {
							for (int y = 1; y <= height; y++) {
								if (!opt.isHole(y)) blocks.add(new PlacedBlock(x, base + y, z, Kind.WALL, null, opt.blockAt(y), null));
							}
						}
						case OBJECT -> blocks.add(new PlacedBlock(x, base + 1, z, Kind.OBJECT, opt.objectFacing(), opt.objectBlock(), null));
						case DOOR, START -> {
							Kind kind = cell == Cell.START ? Kind.START : Kind.CONNECTOR;
							Direction facing = opt.doorFacing() != null ? opt.doorFacing() : outwardFacing(grid, x, z);
							//Hatches, to join storeys of different pieces: Down = the jigsaw in the floor (the stairs of the
							//piece below arrive through it); Up = in the ceiling, with scaffolding to climb up to it.
							if (facing == Direction.DOWN) {
								blocks.add(new PlacedBlock(x, base, z, kind, facing, opt.objectBlock(), opt.doorPool()));
								break;
							}
							if (facing == Direction.UP) {
								for (int y = 1; y <= height; y++) blocks.add(new PlacedBlock(x, base + y, z, Kind.STAIRS, null, null, null));
								blocks.add(new PlacedBlock(x, base + height + 1, z, kind, facing, opt.objectBlock(), opt.doorPool()));
								break;
							}
							//objectBlock on a door = what closes the doorway (a door, or the wall's block for a
							//hidden room). Closed, the doorway is 2 tall with wall above it, like a lintel.
							blocks.add(new PlacedBlock(x, base + 1, z, kind, facing, opt.objectBlock(), opt.doorPool()));
							if (opt.objectBlock() != null) {
								if (height >= 2) blocks.add(new PlacedBlock(x, base + 2, z, Kind.DOORWAY, facing, opt.objectBlock(), null));
								for (int y = 3; y <= height; y++) blocks.add(new PlacedBlock(x, base + y, z, Kind.WALL, null, null, null));
							}
						}
						case STAIRS -> {
							//One block further when there's a level above, so it pokes through that level's floor.
							int top = height + (levelAbove ? 1 : 0);
							Direction up = opt.objectFacing();
							if (up == null || !up.getAxis().isHorizontal()) {
								for (int y = 1; y <= top; y++) blocks.add(new PlacedBlock(x, base + y, z, Kind.STAIRS, null, null, null));
							} else {
								//Real steps climbing towards "up": one block higher than the stairs cell behind (against the climb).
								int step = 1, bx = x - up.getStepX(), bz = z - up.getStepZ();
								while (step < top && bz >= 0 && bz < grid.length && bx >= 0 && bx < grid[bz].length && grid[bz][bx] == Cell.STAIRS) {
									step++;
									bx -= up.getStepX();
									bz -= up.getStepZ();
								}
								blocks.add(new PlacedBlock(x, base + step, z, Kind.STAIRS, up, opt.objectBlock(), null));
							}
						}
						default -> {}
					}
				}
			}
			base += height + 1;
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
			case STAIRS -> 'H';
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
	public static Direction outwardFacing(Cell[][] grid, int x, int z) {
		if (isOutside(grid, x, z - 1)) return Direction.NORTH;
		if (isOutside(grid, x + 1, z)) return Direction.EAST;
		if (isOutside(grid, x, z + 1)) return Direction.SOUTH;
		if (isOutside(grid, x - 1, z)) return Direction.WEST;
		return Direction.NORTH;
	}

	/** Whether a door at (x, z) has an empty neighbour to face; without one it can only point inward. */
	public static boolean hasOutside(Cell[][] grid, int x, int z) {
		return isOutside(grid, x, z - 1) || isOutside(grid, x + 1, z) || isOutside(grid, x, z + 1) || isOutside(grid, x - 1, z);
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
